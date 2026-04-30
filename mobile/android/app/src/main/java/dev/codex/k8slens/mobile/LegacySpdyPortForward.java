package dev.codex.k8slens.mobile;

import com.squareup.okhttp.Protocol;
import com.squareup.okhttp.internal.framed.ErrorCode;
import com.squareup.okhttp.internal.framed.FramedConnection;
import com.squareup.okhttp.internal.framed.FramedStream;
import com.squareup.okhttp.internal.framed.Header;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Pair;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import okhttp3.HttpUrl;
import okhttp3.Request;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;

final class LegacySpdyPortForward {
    private static final String METHOD = "POST";
    private static final String UPGRADE = "SPDY/3.1";
    private static final String STREAM_PROTOCOL_HEADER = "X-Stream-Protocol-Version";
    private static final String PORT_FORWARD_PROTOCOL = "portforward.k8s.io";
    private static final int MAX_LINE_BYTES = 64 * 1024;
    private static final int MAX_ERROR_BODY_BYTES = 64 * 1024;

    private LegacySpdyPortForward() {
    }

    static Connection open(ApiClient apiClient, String namespace, String podName) throws IOException {
        Request request = buildUpgradeRequest(apiClient, namespace, podName);
        HttpUrl url = request.url();
        Socket socket = openSocket(apiClient, url);
        boolean success = false;
        try {
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();
            writeUpgradeRequest(output, request);
            verifyUpgradeResponse(input);
            socket.setSoTimeout(0);

            FramedConnection framedConnection = new FramedConnection.Builder(true)
                    .socket(socket, url.host(), Okio.buffer(Okio.source(input)), Okio.buffer(Okio.sink(output)))
                    .protocol(Protocol.SPDY_3)
                    .build();
            framedConnection.sendConnectionPreface();
            success = true;
            return new Connection(socket, framedConnection);
        } finally {
            if (!success) {
                closeQuietly(socket);
            }
        }
    }

    private static Request buildUpgradeRequest(ApiClient apiClient, String namespace, String podName) throws IOException {
        String path = "/api/v1/namespaces/"
                + apiClient.escapeString(namespace)
                + "/pods/"
                + apiClient.escapeString(podName)
                + "/portforward";

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Connection", "Upgrade");
        headers.put("Upgrade", UPGRADE);
        headers.put(STREAM_PROTOCOL_HEADER, PORT_FORWARD_PROTOCOL);
        headers.put("Accept", "*/*");

        try {
            return apiClient.buildRequest(
                    path,
                    METHOD,
                    Collections.<Pair>emptyList(),
                    Collections.<Pair>emptyList(),
                    null,
                    headers,
                    Collections.<String, String>emptyMap(),
                    Collections.<String, Object>emptyMap(),
                    new String[] {"BearerToken"},
                    null);
        } catch (ApiException ex) {
            throw new IOException("Cannot build Kubernetes port-forward request: " + ex.getMessage(), ex);
        }
    }

    private static Socket openSocket(ApiClient apiClient, HttpUrl url) throws IOException {
        okhttp3.OkHttpClient httpClient = apiClient.getHttpClient();
        Socket socket = new Socket();
        int connectTimeout = Math.max(1, httpClient.connectTimeoutMillis());
        socket.connect(new InetSocketAddress(url.host(), url.port()), connectTimeout);
        socket.setSoTimeout(Math.max(1, httpClient.readTimeoutMillis()));

        if (!"https".equalsIgnoreCase(url.scheme())) {
            return socket;
        }

        SSLSocketFactory sslSocketFactory = httpClient.sslSocketFactory();
        SSLSocket sslSocket = (SSLSocket) sslSocketFactory.createSocket(
                socket,
                url.host(),
                url.port(),
                true);
        sslSocket.startHandshake();

        HostnameVerifier verifier = httpClient.hostnameVerifier();
        SSLSession session = sslSocket.getSession();
        if (verifier != null && !verifier.verify(url.host(), session)) {
            closeQuietly(sslSocket);
            throw new IOException("Kubernetes API TLS hostname verification failed for " + url.host());
        }
        return sslSocket;
    }

    private static void writeUpgradeRequest(OutputStream output, Request request) throws IOException {
        HttpUrl url = request.url();
        StringBuilder builder = new StringBuilder();
        builder.append(METHOD)
                .append(' ')
                .append(requestTarget(url))
                .append(" HTTP/1.1\r\n");
        builder.append("Host: ").append(hostHeader(url)).append("\r\n");

        for (int index = 0; index < request.headers().size(); index++) {
            String name = request.headers().name(index);
            if ("Host".equalsIgnoreCase(name) || "Content-Length".equalsIgnoreCase(name)) {
                continue;
            }
            builder.append(name)
                    .append(": ")
                    .append(request.headers().value(index))
                    .append("\r\n");
        }

        builder.append("Content-Length: 0\r\n");
        builder.append("\r\n");
        output.write(builder.toString().getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
    }

    private static void verifyUpgradeResponse(InputStream input) throws IOException {
        String statusLine = readHttpLine(input);
        if (statusLine.isEmpty()) {
            throw new IOException("Kubernetes port-forward upgrade failed: empty HTTP response");
        }

        String[] statusParts = statusLine.split(" ", 3);
        int statusCode = statusParts.length > 1 ? parseStatusCode(statusParts[1]) : -1;
        String reason = statusParts.length > 2 ? statusParts[2] : "";
        Map<String, List<String>> headers = readHeaders(input);

        if (statusCode != 101) {
            String body = readErrorBody(input, headers);
            String message = "Kubernetes port-forward upgrade failed: HTTP " + statusCode;
            if (!reason.isEmpty()) {
                message += " " + reason;
            }
            if (!body.trim().isEmpty()) {
                message += "\n" + body.trim();
            }
            throw new IOException(message);
        }

        String protocol = firstHeader(headers, STREAM_PROTOCOL_HEADER);
        if (!PORT_FORWARD_PROTOCOL.equals(protocol)) {
            throw new IOException("Kubernetes port-forward protocol negotiation failed: server returned "
                    + (protocol == null || protocol.isEmpty() ? "<none>" : protocol));
        }
    }

    private static Map<String, List<String>> readHeaders(InputStream input) throws IOException {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        while (true) {
            String line = readHttpLine(input);
            if (line.isEmpty()) {
                return headers;
            }

            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }

            String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
    }

    private static String readHttpLine(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            int next = input.read();
            if (next == -1) {
                break;
            }
            if (next == '\n') {
                break;
            }
            if (next != '\r') {
                buffer.write(next);
            }
            if (buffer.size() > MAX_LINE_BYTES) {
                throw new IOException("Kubernetes port-forward HTTP header line is too large");
            }
        }
        return buffer.toString(StandardCharsets.ISO_8859_1.name());
    }

    private static String readErrorBody(InputStream input, Map<String, List<String>> headers) {
        int contentLength = parsePositiveInt(firstHeader(headers, "content-length"));
        if (contentLength <= 0) {
            return "";
        }

        int length = Math.min(contentLength, MAX_ERROR_BODY_BYTES);
        byte[] body = new byte[length];
        int offset = 0;
        try {
            while (offset < length) {
                int read = input.read(body, offset, length - offset);
                if (read == -1) {
                    break;
                }
                offset += read;
            }
        } catch (IOException ignored) {
            return "";
        }
        return new String(body, 0, offset, StandardCharsets.UTF_8);
    }

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.get(0);
    }

    private static int parseStatusCode(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static int parsePositiveInt(String value) {
        if (value == null || value.trim().isEmpty()) {
            return -1;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : -1;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static String requestTarget(HttpUrl url) {
        String target = url.encodedPath();
        if (url.encodedQuery() != null) {
            target += "?" + url.encodedQuery();
        }
        return target;
    }

    private static String hostHeader(HttpUrl url) {
        String host = url.host();
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        if (url.port() == defaultPort(url.scheme())) {
            return host;
        }
        return host + ":" + url.port();
    }

    private static int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    static final class Connection implements Closeable {
        private final Socket socket;
        private final FramedConnection connection;
        private final AtomicInteger requestIds = new AtomicInteger();

        private Connection(Socket socket, FramedConnection connection) {
            this.socket = socket;
            this.connection = connection;
        }

        ForwardStreams openStreams(int remotePort) throws IOException {
            int requestId = requestIds.getAndIncrement();
            List<Header> errorHeaders = streamHeaders("error", remotePort, requestId);
            FramedStream errorStream = connection.newStream(errorHeaders, false, true);

            List<Header> dataHeaders = streamHeaders("data", remotePort, requestId);
            FramedStream dataStream = connection.newStream(dataHeaders, true, true);

            return new ForwardStreams(dataStream, errorStream);
        }

        @Override
        public void close() throws IOException {
            IOException first = null;
            try {
                connection.close();
            } catch (IOException ex) {
                first = ex;
            }
            try {
                socket.close();
            } catch (IOException ex) {
                if (first == null) {
                    first = ex;
                }
            }
            if (first != null) {
                throw first;
            }
        }

        private List<Header> streamHeaders(String streamType, int remotePort, int requestId) {
            List<Header> headers = new ArrayList<>();
            headers.add(new Header("streamType", streamType));
            headers.add(new Header("port", String.valueOf(remotePort)));
            headers.add(new Header("requestID", String.valueOf(requestId)));
            return headers;
        }
    }

    static final class ForwardStreams implements Closeable {
        private final FramedStream dataStream;
        private final FramedStream errorStream;
        private final BufferedSource dataSource;
        private final BufferedSink dataSink;
        private final BufferedSource errorSource;

        private ForwardStreams(FramedStream dataStream, FramedStream errorStream) {
            this.dataStream = dataStream;
            this.errorStream = errorStream;
            this.dataSource = Okio.buffer(dataStream.getSource());
            this.dataSink = Okio.buffer(dataStream.getSink());
            this.errorSource = Okio.buffer(errorStream.getSource());
        }

        InputStream input() {
            return dataSource.inputStream();
        }

        OutputStream output() {
            return dataSink.outputStream();
        }

        InputStream error() {
            return errorSource.inputStream();
        }

        @Override
        public void close() throws IOException {
            closeQuietly(dataSource);
            closeQuietly(dataSink);
            closeQuietly(errorSource);
            closeStream(dataStream);
            closeStream(errorStream);
        }

        private void closeStream(FramedStream stream) {
            try {
                stream.close(ErrorCode.CANCEL);
            } catch (IOException ignored) {
            }
        }
    }
}
