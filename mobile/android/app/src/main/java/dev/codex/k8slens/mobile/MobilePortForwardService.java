package dev.codex.k8slens.mobile;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

class MobilePortForwardService {
    private static final long KUBECTL_START_TIMEOUT_MILLIS = 30_000;

    private final Context context;
    private final MobileKubernetesClient client;
    private final MobileKubectl kubectl;
    private final String externalHost;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ConcurrentMap<String, RunningForward> forwards = new ConcurrentHashMap<>();

    MobilePortForwardService(Context context, MobileKubernetesClient client) {
        this.context = context.getApplicationContext();
        this.client = client;
        this.kubectl = new MobileKubectl(this.context, client);
        this.externalHost = detectExternalHost();
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

            if (kubectl.isAvailable()) {
                KubectlProxyForward forward = new KubectlProxyForward(id, namespace, podName, remotePort, localPort, https);
                forwards.put(id, forward);
                startKeepAlive();
                executor.execute(forward::acceptLoop);
                return forward.session();
            }

            JavaForward forward = new JavaForward(id, namespace, podName, remotePort, localPort, https);
            forwards.put(id, forward);
            startKeepAlive();
            executor.execute(forward::acceptLoop);
            return forward.session();
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot start local port forward: " + ex.getMessage(), ex);
        }
    }

    List<PortForwardSession> start(String namespace, String podName, List<PortMapping> mappings, boolean https) {
        if (mappings == null || mappings.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one port");
        }

        validateRequestedLocalPorts(mappings);

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
                .map(RunningForward::session)
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
        RunningForward forward = forwards.remove(id);
        if (forward == null) {
            pruneStoppedForwards();
            return false;
        }
        forward.close();
        stopKeepAliveIfIdle();
        return true;
    }

    void shutdown() {
        stopAll();
        stopKeepAlive();
        executor.shutdownNow();
    }

    private void stopByLocalPort(int localPort) {
        if (localPort <= 0) {
            return;
        }
        forwards.forEach((id, forward) -> {
            if (forward.session().getLocalPort() == localPort) {
                stop(id);
            }
        });
    }

    private void pruneStoppedForwards() {
        forwards.forEach((id, forward) -> {
            if (forward.isClosed()) {
                forwards.remove(id, forward);
            }
        });
        stopKeepAliveIfIdle();
    }

    private void startKeepAlive() {
        try {
            Intent intent = new Intent(context, PortForwardKeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void stopKeepAliveIfIdle() {
        if (forwards.isEmpty()) {
            stopKeepAlive();
        }
    }

    private void stopKeepAlive() {
        try {
            context.stopService(new Intent(context, PortForwardKeepAliveService.class));
        } catch (RuntimeException ignored) {
        }
    }

    private void validatePort(int port, String label) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(label + " must be between 1 and 65535");
        }
    }

    private int randomLocalPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot allocate random local port: " + ex.getMessage(), ex);
        }
    }

    private String detectExternalHost() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }

                String name = networkInterface.getName() == null
                        ? ""
                        : networkInterface.getName().toLowerCase(java.util.Locale.ROOT);
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        String host = address.getHostAddress();
                        if (name.startsWith("wlan")
                                || name.startsWith("eth")
                                || name.startsWith("rndis")
                                || name.startsWith("ap")) {
                            return host;
                        }
                        if (fallback == null || address.isSiteLocalAddress()) {
                            fallback = host;
                        }
                    }
                }
            }
        } catch (SocketException ignored) {
        }
        return fallback == null ? "127.0.0.1" : fallback;
    }

    private void validateRequestedLocalPorts(List<PortMapping> mappings) {
        Set<Integer> localPorts = new HashSet<>();
        for (PortMapping mapping : mappings) {
            if (mapping == null) {
                throw new IllegalArgumentException("Port mapping cannot be empty");
            }
            if (mapping.localPort == null) {
                continue;
            }

            validatePort(mapping.localPort, "Local port");
            if (!localPorts.add(mapping.localPort)) {
                throw new IllegalArgumentException("Local port " + mapping.localPort + " is used more than once");
            }
        }
    }

    private void sleepQuietly(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for Android kubectl port-forward", ex);
        }
    }

    private void destroyProcess(Process target) {
        if (target == null || !target.isAlive()) {
            return;
        }

        target.destroy();
        try {
            if (!target.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                target.destroyForcibly();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            target.destroyForcibly();
        }
    }

    private interface RunningForward extends Closeable {
        PortForwardSession session();

        boolean isClosed();

        @Override
        void close();
    }

    private class KubectlForward implements RunningForward {
        private final String id;
        private final String namespace;
        private final String podName;
        private final int remotePort;
        private final int localPort;
        private final PortForwardSession session;
        private final StringBuilder output = new StringBuilder();
        private volatile Process process;
        private volatile boolean closed;

        KubectlForward(String id, String namespace, String podName, int remotePort, int localPort, boolean https) throws IOException {
            this.id = id;
            this.namespace = namespace;
            this.podName = podName;
            this.remotePort = remotePort;
            this.localPort = localPort;
            this.session = new PortForwardSession(id, namespace, podName, localPort, remotePort, https, externalHost);
            startProcess();
            ensureStarted();
        }

        @Override
        public PortForwardSession session() {
            return session;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public void close() {
            closed = true;
            forwards.remove(id, this);
            destroyProcess(process);
        }

        private synchronized void startProcess() throws IOException {
            if (closed) {
                return;
            }

            synchronized (output) {
                output.setLength(0);
            }
            Process startedProcess = kubectl.startPortForward(namespace, podName, localPort, remotePort);
            process = startedProcess;
            executor.execute(() -> captureOutput(startedProcess));
        }

        private void ensureStarted() throws IOException {
            long deadline = System.currentTimeMillis() + KUBECTL_START_TIMEOUT_MILLIS;
            IOException lastError = null;
            while (System.currentTimeMillis() < deadline) {
                Process currentProcess = process;
                if (currentProcess == null || !currentProcess.isAlive()) {
                    String message = capturedOutput();
                    throw new IOException(message.isEmpty()
                            ? "Android kubectl port-forward exited immediately"
                            : message);
                }

                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", localPort), 500);
                    return;
                } catch (IOException ex) {
                    lastError = ex;
                    sleepQuietly(100);
                }
            }

            IOException failure = new IOException("Android kubectl port-forward did not open local port " + localPort);
            if (lastError != null) {
                failure.addSuppressed(lastError);
            }
            String text = capturedOutput();
            if (!text.isEmpty()) {
                failure.addSuppressed(new IOException("kubectl output:\n" + text));
            }
            close();
            throw failure;
        }

        private String capturedOutput() {
            synchronized (output) {
                return output.toString().trim();
            }
        }

        private void captureOutput(Process watchedProcess) {
            byte[] buffer = new byte[4096];
            int read;
            try (InputStream input = watchedProcess.getInputStream()) {
                while ((read = input.read(buffer)) != -1) {
                    String chunk = new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8);
                    synchronized (output) {
                        output.append(chunk);
                        if (output.length() > 16 * 1024) {
                            output.delete(0, output.length() - 16 * 1024);
                        }
                    }
                }
            } catch (IOException ignored) {
            } finally {
                if (!closed && process == watchedProcess) {
                    closed = true;
                    forwards.remove(id, this);
                }
            }
        }
    }

    private class KubectlProxyForward implements RunningForward {
        private final String id;
        private final String namespace;
        private final String podName;
        private final int remotePort;
        private final ServerSocket serverSocket;
        private final PortForwardSession session;
        private final List<KubectlProxyConnection> connections = new CopyOnWriteArrayList<>();
        private final int upstreamPort;
        private final StringBuilder output = new StringBuilder();
        private volatile Process process;
        private volatile boolean closed;

        KubectlProxyForward(String id, String namespace, String podName, int remotePort, int requestedLocalPort, boolean https) throws IOException {
            this.id = id;
            this.namespace = namespace;
            this.podName = podName;
            this.remotePort = remotePort;
            this.serverSocket = new ServerSocket();
            this.serverSocket.setReuseAddress(true);
            this.serverSocket.bind(new InetSocketAddress(requestedLocalPort), 50);
            this.upstreamPort = randomLocalPort();
            this.session = new PortForwardSession(
                    id,
                    namespace,
                    podName,
                    serverSocket.getLocalPort(),
                    remotePort,
                    https,
                    externalHost);
            startProcess();
            ensureStarted();
        }

        @Override
        public PortForwardSession session() {
            return session;
        }

        @Override
        public boolean isClosed() {
            return closed || process == null || !process.isAlive();
        }

        void acceptLoop() {
            while (!closed) {
                try {
                    Socket socket = serverSocket.accept();
                    KubectlProxyConnection connection = new KubectlProxyConnection(this, socket);
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
            for (KubectlProxyConnection connection : connections) {
                connection.close();
            }
            destroyProcess(process);
            forwards.remove(id, this);
        }

        KubectlUpstreamSession openUpstreamSession() throws IOException {
            ensureProcessRunning();

            IOException lastError = null;
            long deadline = System.currentTimeMillis() + 3_000;
            while (System.currentTimeMillis() < deadline) {
                Process currentProcess = process;
                if (currentProcess == null || !currentProcess.isAlive()) {
                    ensureProcessRunning();
                    currentProcess = process;
                }

                try {
                    Socket upstream = new Socket();
                    upstream.connect(new InetSocketAddress("127.0.0.1", upstreamPort), 500);
                    return new KubectlUpstreamSession(upstream, output);
                } catch (IOException ex) {
                    lastError = ex;
                    sleepQuietly(100);
                }
            }

            IOException failure = new IOException("Android app could not connect to active kubectl port-forward listener on internal local port " + upstreamPort);
            if (lastError != null) {
                failure.addSuppressed(lastError);
            }
            String text = capturedOutput(output);
            if (!text.isEmpty()) {
                failure.addSuppressed(new IOException("kubectl output:\n" + text));
            }
            throw failure;
        }

        private synchronized void startProcess() throws IOException {
            if (closed) {
                return;
            }

            synchronized (output) {
                output.setLength(0);
            }
            Process startedProcess = kubectl.startPortForward(namespace, podName, upstreamPort, remotePort);
            process = startedProcess;
            executor.execute(() -> captureOutput(startedProcess, output));
        }

        private synchronized void ensureProcessRunning() throws IOException {
            Process currentProcess = process;
            if (currentProcess != null && currentProcess.isAlive()) {
                return;
            }

            startProcess();
            ensureStarted();
        }

        private void ensureStarted() throws IOException {
            IOException lastError = null;
            long deadline = System.currentTimeMillis() + KUBECTL_START_TIMEOUT_MILLIS;
            while (System.currentTimeMillis() < deadline) {
                Process currentProcess = process;
                if (currentProcess == null || !currentProcess.isAlive()) {
                    throw new IOException(startupFailureMessage(output));
                }

                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", upstreamPort), 500);
                    return;
                } catch (IOException ex) {
                    lastError = ex;
                    sleepQuietly(100);
                }
            }

            IOException failure = new IOException("Android kubectl port-forward did not open internal local port " + upstreamPort);
            if (lastError != null) {
                failure.addSuppressed(lastError);
            }
            String text = capturedOutput(output);
            if (!text.isEmpty()) {
                failure.addSuppressed(new IOException("kubectl output:\n" + text));
            }
            close();
            throw failure;
        }

        private String startupFailureMessage(StringBuilder output) {
            String message = capturedOutput(output);
            return message.isEmpty()
                    ? "Android kubectl port-forward exited immediately"
                    : message;
        }

        String latestOutput() {
            return capturedOutput(output);
        }

        private String capturedOutput(StringBuilder output) {
            synchronized (output) {
                return output.toString().trim();
            }
        }

        private void captureOutput(Process watchedProcess, StringBuilder output) {
            byte[] buffer = new byte[4096];
            int read;
            try (InputStream input = watchedProcess.getInputStream()) {
                while ((read = input.read(buffer)) != -1) {
                    String chunk = new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8);
                    synchronized (output) {
                        output.append(chunk);
                        if (output.length() > 16 * 1024) {
                            output.delete(0, output.length() - 16 * 1024);
                        }
                    }
                }
            } catch (IOException ignored) {
            } finally {
                if (!closed && process == watchedProcess) {
                    process = null;
                }
            }
        }

    }

    private class KubectlUpstreamSession implements Closeable {
        private final Socket socket;
        private final StringBuilder output;

        KubectlUpstreamSession(Socket socket, StringBuilder output) {
            this.socket = socket;
            this.output = output;
        }

        Socket socket() {
            return socket;
        }

        String output() {
            synchronized (output) {
                return output.toString().trim();
            }
        }

        @Override
        public void close() {
            closeQuietly(socket);
        }
    }

    private class KubectlProxyConnection implements Closeable {
        private final KubectlProxyForward forward;
        private final Socket socket;
        private volatile KubectlUpstreamSession upstream;

        KubectlProxyConnection(KubectlProxyForward forward, Socket socket) {
            this.forward = forward;
            this.socket = socket;
        }

        void run() {
            AtomicBoolean responseStarted = new AtomicBoolean(false);
            try {
                upstream = forward.openUpstreamSession();
                Socket upstreamSocket = upstream.socket();
                InputStream socketInput = socket.getInputStream();
                OutputStream upstreamOutput = upstreamSocket.getOutputStream();
                InputStream upstreamInput = upstreamSocket.getInputStream();
                OutputStream socketOutput = markOnWrite(socket.getOutputStream(), responseStarted);
                executor.execute(() -> pipe(socketInput, upstreamOutput, true));
                long responseBytes = pipe(upstreamInput, socketOutput, false);
                if (responseBytes == 0 && !responseStarted.get()) {
                    String message = "Android kubectl port-forward closed before returning data.";
                    String output = upstream.output();
                    if (!output.isEmpty()) {
                        message += "\n\nkubectl output:\n" + output;
                    }
                    sendHttpError(responseStarted, readablePortForwardFailure(
                            new IOException(message),
                            forward.namespace,
                            forward.podName,
                            forward.remotePort));
                }
            } catch (IOException ex) {
                sendHttpError(responseStarted, readablePortForwardFailure(ex, forward.namespace, forward.podName, forward.remotePort));
            } catch (RuntimeException ex) {
                sendHttpError(responseStarted, readablePortForwardFailure(ex, forward.namespace, forward.podName, forward.remotePort));
            } finally {
                close();
                forward.connections.remove(this);
            }
        }

        @Override
        public void close() {
            closeQuietly(socket);
            closeQuietly(upstream);
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

    private class JavaForward implements RunningForward {
        private final String id;
        private final String namespace;
        private final String podName;
        private final int remotePort;
        private final boolean https;
        private final ServerSocket serverSocket;
        private final PortForwardSession session;
        private final List<ForwardConnection> connections = new CopyOnWriteArrayList<>();
        private volatile boolean closed;

        JavaForward(String id, String namespace, String podName, int remotePort, int requestedLocalPort, boolean https) throws IOException {
            this.id = id;
            this.namespace = namespace;
            this.podName = podName;
            this.remotePort = remotePort;
            this.https = https;
            this.serverSocket = new ServerSocket();
            this.serverSocket.setReuseAddress(true);
            this.serverSocket.bind(new InetSocketAddress(requestedLocalPort), 50);
            this.session = new PortForwardSession(
                    id,
                    namespace,
                    podName,
                    serverSocket.getLocalPort(),
                    remotePort,
                    https,
                    externalHost);
        }

        @Override
        public PortForwardSession session() {
            return session;
        }

        @Override
        public boolean isClosed() {
            return closed;
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
        private final JavaForward forward;
        private final Socket socket;
        private volatile LegacySpdyPortForward.Connection portForward;
        private volatile PortForwardStreams streams;

        ForwardConnection(JavaForward forward, Socket socket) {
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
                CapturingInputStream socketInput = new CapturingInputStream(socket.getInputStream());
                OutputStream socketOutput = markOnWrite(socket.getOutputStream(), responseStarted);

                executor.execute(() -> {
                    pipe(socketInput, remoteOutput, true);
                });
                executor.execute(() -> pipePortForwardErrors(remoteError, responseStarted));
                long responseBytes = pipe(remoteInput, socketOutput, false);
                if (responseBytes == 0 && !responseStarted.get()) {
                    sendHttpError(responseStarted, emptyResponseMessage(socketInput));
                }
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
            try {
                streams = new WebSocketPortForwardStreams(WebSocketPortForward.open(
                        client.apiClient(),
                        forward.namespace,
                        forward.podName,
                        forward.remotePort));
                return;
            } catch (IOException webSocketEx) {
                openLegacySpdyPortForwardStreams(webSocketEx);
            }
        }

        private void openLegacySpdyPortForwardStreams(IOException webSocketEx) throws IOException {
            LegacySpdyPortForward.Connection legacyConnection = null;
            try {
                legacyConnection = LegacySpdyPortForward.open(client.apiClient(), forward.namespace, forward.podName);
                LegacySpdyPortForward.ForwardStreams legacyStreams = legacyConnection.openStreams(forward.remotePort);
                portForward = legacyConnection;
                streams = new LegacyPortForwardStreams(legacyStreams);
            } catch (IOException legacyEx) {
                closeQuietly(legacyConnection);
                throw combinedPortForwardFailure(webSocketEx, legacyEx);
            }
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

        private String emptyResponseMessage(CapturingInputStream socketInput) {
            StringBuilder message = new StringBuilder()
                    .append("Port-forward connected and the browser request reached pod ")
                    .append(forward.namespace)
                    .append("/")
                    .append(forward.podName)
                    .append(" on port ")
                    .append(forward.remotePort)
                    .append(", but the pod closed the stream without returning any data.");

            if (forward.https) {
                message.append("\n\nThe browser used HTTPS for this request. If this pod port is plain HTTP, start the port-forward without HTTPS.");
            } else {
                message.append("\n\nThe browser used plain HTTP for this request. If this pod port serves TLS, start the port-forward with HTTPS enabled. If this port is not an HTTP server, it cannot be opened directly in a browser.");
            }

            message.append(socketInput.requestPreview());
            return message.toString();
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

    private static class CapturingInputStream extends FilterInputStream {
        private static final int MAX_CAPTURE_BYTES = 1024;
        private final java.io.ByteArrayOutputStream capture = new java.io.ByteArrayOutputStream(MAX_CAPTURE_BYTES);

        private CapturingInputStream(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                capture(value);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                capture(buffer, offset, read);
            }
            return read;
        }

        String requestPreview() {
            byte[] bytes = capture.toByteArray();
            if (bytes.length == 0) {
                return "";
            }

            String text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1)
                    .replace("\r", "");
            StringBuilder preview = new StringBuilder("\n\nBrowser request sent to pod:\n");
            String[] lines = text.split("\n");
            int count = 0;
            for (String line : lines) {
                if (line.trim().isEmpty()) {
                    break;
                }
                String lower = line.toLowerCase(java.util.Locale.ROOT);
                if (lower.startsWith("cookie:") || lower.startsWith("authorization:")) {
                    line = line.substring(0, line.indexOf(':') + 1) + " <hidden>";
                }
                preview.append(line).append('\n');
                count++;
                if (count >= 8) {
                    break;
                }
            }
            return preview.toString();
        }

        private void capture(int value) {
            if (capture.size() < MAX_CAPTURE_BYTES) {
                capture.write(value);
            }
        }

        private void capture(byte[] buffer, int offset, int length) {
            int remaining = MAX_CAPTURE_BYTES - capture.size();
            if (remaining <= 0) {
                return;
            }
            capture.write(buffer, offset, Math.min(length, remaining));
        }
    }

    private long pipe(InputStream input, OutputStream output, boolean closeOutput) {
        byte[] buffer = new byte[16 * 1024];
        int read;
        long total = 0;
        try {
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                output.flush();
                total += read;
            }
        } catch (IOException ignored) {
        } finally {
            if (closeOutput) {
                closeQuietly(output);
            }
        }
        return total;
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
            return "Kubernetes API denied port-forward for pod "
                    + namespace + "/" + podName + " on port " + remotePort + ".\n\n"
                    + "The kubeconfig user likely lacks create access to pods/portforward, "
                    + "or the API gateway blocks WebSocket/SPDY upgrades.\n\n"
                    + "Check from a machine with kubectl:\n"
                    + "kubectl auth can-i create pods/portforward -n " + namespace + "\n\n"
                    + "Raw Kubernetes error:\n" + message;
        }

        if (isShutdownFailure(message)) {
            return "Kubernetes API closed the port-forward connection before the data stream was created for pod "
                    + namespace + "/" + podName + " on port " + remotePort + ".\n\n"
                    + "The mobile app retried with a fresh connection. If this still appears, "
                    + "the Kubernetes API endpoint or an API gateway/proxy in front of it is likely closing port-forward upgrades.";
        }

        if (isInternalKubectlConnectFailure(message)) {
            return "Android internal kubectl port-forward did not open its local listener for pod "
                    + namespace + "/" + podName + " on port " + remotePort + ".\n\n"
                    + "The phone reached this Android app, but the app could not connect to the temporary localhost port "
                    + "created for the bundled kubectl process.\n\n"
                    + "Raw internal error:\n" + message;
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
            for (Throwable suppressed : cursor.getSuppressed()) {
                appendMessage(builder, suppressed.getMessage());
            }
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

    private boolean isInternalKubectlConnectFailure(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("android kubectl port-forward")
                || lower.contains("internal local port")
                || lower.contains("kubectl output:");
    }

    private IOException combinedPortForwardFailure(IOException webSocketEx, IOException legacyEx) {
        String webSocketMessage = throwableMessages(webSocketEx);
        String legacyMessage = throwableMessages(legacyEx);
        IOException combined = new IOException(
                "Kubernetes port-forward failed with WebSocket and legacy SPDY.\n\n"
                        + "WebSocket error:\n" + webSocketMessage + "\n\n"
                        + "Legacy SPDY error:\n" + legacyMessage,
                legacyEx);
        combined.addSuppressed(webSocketEx);
        return combined;
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

    private interface PortForwardStreams extends Closeable {
        InputStream input();

        OutputStream output();

        InputStream error();
    }

    private static class WebSocketPortForwardStreams implements PortForwardStreams {
        private final WebSocketPortForward.Streams streams;

        private WebSocketPortForwardStreams(WebSocketPortForward.Streams streams) {
            this.streams = streams;
        }

        @Override
        public InputStream input() {
            return streams.input();
        }

        @Override
        public OutputStream output() {
            return streams.output();
        }

        @Override
        public InputStream error() {
            return streams.error();
        }

        @Override
        public void close() {
            streams.close();
        }
    }

    private static class LegacyPortForwardStreams implements PortForwardStreams {
        private final LegacySpdyPortForward.ForwardStreams streams;

        private LegacyPortForwardStreams(LegacySpdyPortForward.ForwardStreams streams) {
            this.streams = streams;
        }

        @Override
        public InputStream input() {
            return streams.input();
        }

        @Override
        public OutputStream output() {
            return streams.output();
        }

        @Override
        public InputStream error() {
            return streams.error();
        }

        @Override
        public void close() throws IOException {
            streams.close();
        }
    }

}
