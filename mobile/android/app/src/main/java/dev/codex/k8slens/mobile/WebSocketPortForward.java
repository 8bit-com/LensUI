package dev.codex.k8slens.mobile;

import io.kubernetes.client.PortForward;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.util.WebSocketStreamHandler;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.Collections;

final class WebSocketPortForward {
    private WebSocketPortForward() {
    }

    static Streams open(ApiClient apiClient, String namespace, String podName, int remotePort) throws IOException {
        try {
            PortForward.PortForwardResult result = new PortForward(apiClient)
                    .forward(namespace, podName, Collections.singletonList(remotePort));
            OutputStream output = result.getOutboundStream(remotePort);
            InputStream input = result.getInputStream(remotePort);
            InputStream error = result.getErrorStream(remotePort);

            if (output == null || input == null || error == null) {
                closeResult(result);
                throw new IOException("Kubernetes WebSocket port-forward did not open streams for port " + remotePort);
            }

            return new Streams(result, input, error, output);
        } catch (ApiException ex) {
            throw new IOException("Cannot start Kubernetes WebSocket port-forward: " + apiExceptionMessage(ex), ex);
        }
    }

    private static String apiExceptionMessage(ApiException ex) {
        if (hasText(ex.getResponseBody())) {
            return ex.getResponseBody().trim();
        }
        if (hasText(ex.getMessage())) {
            return ex.getMessage().trim();
        }
        return "HTTP " + ex.getCode();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static void closeResult(PortForward.PortForwardResult result) {
        if (result == null) {
            return;
        }

        try {
            Field handlerField = result.getClass().getDeclaredField("handler");
            handlerField.setAccessible(true);
            Object handler = handlerField.get(result);
            if (handler instanceof WebSocketStreamHandler) {
                ((WebSocketStreamHandler) handler).close();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }

    static final class Streams implements Closeable {
        private final PortForward.PortForwardResult result;
        private final InputStream input;
        private final InputStream error;
        private final OutputStream output;

        private Streams(
                PortForward.PortForwardResult result,
                InputStream input,
                InputStream error,
                OutputStream output) {
            this.result = result;
            this.input = input;
            this.error = error;
            this.output = output;
        }

        InputStream input() {
            return input;
        }

        InputStream error() {
            return error;
        }

        OutputStream output() {
            return output;
        }

        @Override
        public void close() {
            closeResult(result);
        }
    }
}
