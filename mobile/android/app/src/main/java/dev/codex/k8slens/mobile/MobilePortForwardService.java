package dev.codex.k8slens.mobile;

import java.io.Closeable;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

class MobilePortForwardService {
    private final MobileKubernetesClient client;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentMap<String, LocalForward> forwards = new ConcurrentHashMap<>();

    MobilePortForwardService(MobileKubernetesClient client) {
        this.client = client;
    }

    PortForwardSession start(String namespace, String podName, int remotePort, Integer requestedLocalPort, boolean https) {
        validatePort(remotePort, "Remote port");
        if (requestedLocalPort != null) {
            validatePort(requestedLocalPort, "Local port");
        }

        try {
            int localPort = requestedLocalPort == null ? 0 : requestedLocalPort;
            stopByLocalPort(localPort);
            String id = UUID.randomUUID().toString();
            LocalForward forward = new LocalForward(id, namespace, podName, remotePort, localPort, https);
            forwards.put(id, forward);
            executor.execute(forward::acceptLoop);
            return forward.session;
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot start local port forward: " + ex.getMessage(), ex);
        }
    }

    List<PortForwardSession> start(String namespace, String podName, List<PortMapping> mappings, boolean https) {
        if (mappings == null || mappings.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one port");
        }

        List<PortForwardSession> sessions = new ArrayList<>();
        try {
            for (PortMapping mapping : mappings) {
                if (mapping == null) {
                    throw new IllegalArgumentException("Port mapping cannot be empty");
                }
                sessions.add(start(namespace, podName, mapping.remotePort, mapping.localPort, https));
            }
            return sessions;
        } catch (RuntimeException ex) {
            for (PortForwardSession session : sessions) {
                stop(session.getId());
            }
            throw ex;
        }
    }

    List<PortForwardSession> list() {
        pruneStoppedForwards();
        return forwards.values().stream()
                .map(forward -> forward.session)
                .sorted(Comparator
                        .comparing(PortForwardSession::getNamespace)
                        .thenComparing(PortForwardSession::getPodName)
                        .thenComparingInt(PortForwardSession::getLocalPort))
                .collect(Collectors.toList());
    }

    void stopAll() {
        for (String id : new ArrayList<>(forwards.keySet())) {
            stop(id);
        }
    }

    boolean stop(String id) {
        LocalForward forward = forwards.remove(id);
        if (forward == null) {
            pruneStoppedForwards();
            return false;
        }
        forward.close();
        return true;
    }

    void shutdown() {
        stopAll();
        executor.shutdownNow();
    }

    private void stopByLocalPort(int localPort) {
        if (localPort <= 0) {
            return;
        }
        forwards.forEach((id, forward) -> {
            if (forward.session.getLocalPort() == localPort) {
                stop(id);
            }
        });
    }

    private void pruneStoppedForwards() {
        forwards.forEach((id, forward) -> {
            if (forward.closed) {
                forwards.remove(id, forward);
            }
        });
    }

    private void validatePort(int port, String label) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(label + " must be between 1 and 65535");
        }
    }

    private class LocalForward implements Closeable {
        private final String id;
        private final String namespace;
        private final String podName;
        private final int remotePort;
        private final ServerSocket serverSocket;
        private final PortForwardSession session;
        private final List<ForwardConnection> connections = new CopyOnWriteArrayList<>();
        private volatile boolean closed;

        LocalForward(String id, String namespace, String podName, int remotePort, int requestedLocalPort, boolean https) throws IOException {
            this.id = id;
            this.namespace = namespace;
            this.podName = podName;
            this.remotePort = remotePort;
            this.serverSocket = new ServerSocket(
                    requestedLocalPort,
                    50,
                    InetAddress.getByName("127.0.0.1"));
            this.serverSocket.setReuseAddress(true);
            this.session = new PortForwardSession(
                    id,
                    namespace,
                    podName,
                    serverSocket.getLocalPort(),
                    remotePort,
                    https);
        }

        void acceptLoop() {
            while (!closed) {
                try {
                    Socket socket = serverSocket.accept();
                    ForwardConnection connection = new ForwardConnection(this, socket);
                    connections.add(connection);
                    executor.execute(connection::run);
                } catch (SocketException ex) {
                    if (!closed) {
                        close();
                    }
                } catch (IOException ex) {
                    if (!closed) {
                        close();
                    }
                }
            }
        }

        @Override
        public void close() {
            closed = true;
            closeQuietly(serverSocket);
            for (ForwardConnection connection : connections) {
                connection.close();
            }
            forwards.remove(id, this);
        }
    }

    private class ForwardConnection implements Closeable {
        private final LocalForward forward;
        private final Socket socket;
        private volatile LegacySpdyPortForward.Connection portForward;
        private volatile LegacySpdyPortForward.ForwardStreams streams;

        ForwardConnection(LocalForward forward, Socket socket) {
            this.forward = forward;
            this.socket = socket;
        }

        void run() {
            AtomicBoolean responseStarted = new AtomicBoolean(false);
            try {
                openPortForwardStreams();
                InputStream remoteInput = streams.input();
                InputStream remoteError = streams.error();
                OutputStream remoteOutput = streams.output();
                InputStream socketInput = socket.getInputStream();
                OutputStream socketOutput = markOnWrite(socket.getOutputStream(), responseStarted);

                executor.execute(() -> {
                    pipe(socketInput, remoteOutput);
                });
                executor.execute(() -> pipePortForwardErrors(remoteError, responseStarted));
                pipe(remoteInput, socketOutput);
            } catch (IOException ex) {
                sendHttpError(responseStarted, readablePortForwardFailure(ex, forward.namespace, forward.podName, forward.remotePort));
                close();
            } catch (RuntimeException ex) {
                sendHttpError(responseStarted, readablePortForwardFailure(ex, forward.namespace, forward.podName, forward.remotePort));
                close();
            } finally {
                close();
                forward.connections.remove(this);
            }
        }

        @Override
        public void close() {
            closeQuietly(socket);
            closeQuietly(streams);
            closeQuietly(portForward);
        }

        private void openPortForwardStreams() throws IOException {
            try {
                openFreshPortForwardStreams();
            } catch (IOException ex) {
                if (!isShutdownFailure(ex)) {
                    throw ex;
                }
                closeQuietly(portForward);
                portForward = null;
                openFreshPortForwardStreams();
            }
        }

        private void openFreshPortForwardStreams() throws IOException {
            portForward = LegacySpdyPortForward.open(client.apiClient(), forward.namespace, forward.podName);
            streams = portForward.openStreams(forward.remotePort);
        }

        private void pipePortForwardErrors(InputStream remoteError, AtomicBoolean responseStarted) {
            String error = readText(remoteError).trim();
            if (!error.isEmpty()) {
                sendHttpError(responseStarted, "Kubernetes port-forward stream error: " + error);
                close();
            }
        }

        private void sendHttpError(AtomicBoolean responseStarted, String message) {
            if (!responseStarted.compareAndSet(false, true)) {
                return;
            }

            try {
                writeHttpError(socket.getOutputStream(), message);
            } catch (IOException ignored) {
            }
        }
    }

    private OutputStream markOnWrite(OutputStream output, AtomicBoolean written) {
        return new FilterOutputStream(output) {
            @Override
            public void write(int value) throws IOException {
                written.set(true);
                super.write(value);
            }

            @Override
            public void write(byte[] buffer, int offset, int length) throws IOException {
                if (length > 0) {
                    written.set(true);
                }
                super.write(buffer, offset, length);
            }
        };
    }

    private void pipe(InputStream input, OutputStream output) {
        byte[] buffer = new byte[16 * 1024];
        int read;
        try {
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                output.flush();
            }
        } catch (IOException ignored) {
        } finally {
            closeQuietly(output);
        }
    }

    private void writeHttpError(OutputStream output, String message) throws IOException {
        String safeMessage = message == null || message.trim().isEmpty()
                ? "Port-forward failed"
                : message.trim();
        String escaped = safeMessage
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        String body = "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>Port-forward error</title>"
                + "<style>body{background:#15191c;color:#d2d7dc;font-family:sans-serif;padding:20px}"
                + "pre{white-space:pre-wrap;background:#1e2225;border:1px solid #343c44;padding:12px;border-radius:8px}"
                + "</style></head><body><h3>Port-forward error</h3><pre>"
                + escaped
                + "</pre></body></html>";
        byte[] bodyBytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 502 Bad Gateway\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.write(bodyBytes);
        output.flush();
    }

    private String readText(InputStream input) {
        if (input == null) {
            return "";
        }

        byte[] buffer = new byte[4096];
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        int read;
        try {
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        } catch (IOException ignored) {
            return "";
        }
        return output.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private String readablePortForwardFailure(Throwable throwable, String namespace, String podName, int remotePort) {
        String message = throwableMessages(throwable);
        if (message.contains("403") || message.toLowerCase(java.util.Locale.ROOT).contains("forbidden")) {
            return "Kubernetes API denied legacy kubectl-style port-forward for pod "
                    + namespace + "/" + podName + " on port " + remotePort + ".\n\n"
                    + "The mobile app now uses the same POST/SPDY authorization path as desktop kubectl. "
                    + "This means the kubeconfig user still lacks create access to pods/portforward, "
                    + "or the API gateway blocks SPDY upgrades.\n\n"
                    + "Check from a machine with kubectl:\n"
                    + "kubectl auth can-i create pods/portforward -n " + namespace + "\n\n"
                    + "Raw Kubernetes error:\n" + message;
        }

        if (isShutdownFailure(message)) {
            return "Kubernetes API closed the kubectl-style port-forward connection before the data stream was created for pod "
                    + namespace + "/" + podName + " on port " + remotePort + ".\n\n"
                    + "The mobile app retried with a fresh SPDY connection. If this still appears, "
                    + "the Kubernetes API endpoint or an API gateway/proxy in front of it is likely closing SPDY upgrades.";
        }

        if (isApiConnectTimeout(message)) {
            return "The phone cannot connect to the Kubernetes API endpoint required for port-forward.\n\n"
                    + "The local browser reached this Android app, but the app could not open "
                    + "the Kubernetes API connection from the phone network. Connect the phone to the same VPN/network "
                    + "that can reach the cluster, or import a kubeconfig whose cluster server URL is reachable from the phone.\n\n"
                    + "Raw network error:\n" + message;
        }

        if (message.trim().isEmpty()) {
            return "Kubernetes port-forward failed for pod "
                    + namespace + "/" + podName + " on port " + remotePort + ".";
        }

        return "Kubernetes port-forward failed for pod "
                + namespace + "/" + podName + " on port " + remotePort + ":\n"
                + message;
    }

    private String throwableMessages(Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        Throwable cursor = throwable;
        while (cursor != null) {
            appendMessage(builder, cursor.getMessage());
            cursor = cursor.getCause();
        }
        return builder.toString();
    }

    private void appendMessage(StringBuilder builder, String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(message.trim());
    }

    private boolean isShutdownFailure(Throwable throwable) {
        return isShutdownFailure(throwableMessages(throwable));
    }

    private boolean isShutdownFailure(String message) {
        return "shutdown".equals(message == null ? "" : message.trim());
    }

    private boolean isApiConnectTimeout(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("failed to connect")
                || lower.contains("connect timed out")
                || lower.contains("connection timed out")
                || lower.contains("no route to host")
                || lower.contains("network is unreachable");
    }

    private void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

}
