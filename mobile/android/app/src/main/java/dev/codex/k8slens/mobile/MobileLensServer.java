package dev.codex.k8slens.mobile;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import fi.iki.elonen.NanoHTTPD;
import io.kubernetes.client.openapi.ApiException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

class MobileLensServer extends NanoHTTPD {
    private final Context context;
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final MobileKubernetesClient kubernetesClient;
    private final MobileKubernetesService kubernetesService;
    private final MobileMetricsService metricsService;
    private final MobilePortForwardService portForwardService;

    MobileLensServer(Context context, int port) {
        super("127.0.0.1", port);
        this.context = context.getApplicationContext();
        this.kubernetesClient = new MobileKubernetesClient(this.context);
        this.kubernetesService = new MobileKubernetesService(kubernetesClient);
        this.metricsService = new MobileMetricsService(kubernetesClient);
        this.portForwardService = new MobilePortForwardService(kubernetesClient);
    }

    @Override
    public void stop() {
        portForwardService.shutdown();
        super.stop();
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            String uri = Optional.ofNullable(session.getUri()).orElse("/");
            if (uri.startsWith("/api/")) {
                return serveApi(session, uri);
            }
            return serveAsset(uri);
        } catch (ApiException ex) {
            return apiError(ex);
        } catch (IllegalArgumentException ex) {
            return error(Response.Status.BAD_REQUEST, ex.getMessage());
        } catch (IllegalStateException ex) {
            return error(Response.Status.SERVICE_UNAVAILABLE, ex.getMessage());
        } catch (Exception ex) {
            return error(Response.Status.INTERNAL_ERROR, ex.getMessage());
        }
    }

    private Response serveApi(IHTTPSession session, String uri) throws Exception {
        Method method = session.getMethod();
        List<String> segments = segments(uri.substring("/api/".length()));

        if (method == Method.GET && matches(segments, "kubeconfigs")) {
            return json(kubernetesClient.listKubeConfigs());
        }

        if (method == Method.POST && segments.size() == 3
                && "kubeconfigs".equals(segments.get(0))
                && "activate".equals(segments.get(2))) {
            portForwardService.stopAll();
            return json(kubernetesClient.activateKubeConfig(segments.get(1)));
        }

        if (method == Method.POST && matches(segments, "kubeconfigs", "order")) {
            return json(kubernetesClient.listKubeConfigs());
        }

        if (method == Method.POST && matches(segments, "kubeconfigs", "directory")) {
            throw new IllegalArgumentException("On Android, choose kubeconfig files instead of a desktop folder path");
        }

        if (method == Method.POST && matches(segments, "kubeconfigs", "import-folder")) {
            portForwardService.stopAll();
            return json(kubernetesClient.importKubeConfigs(readUploadedKubeConfigs(session)));
        }

        if (method == Method.GET && matches(segments, "namespaces")) {
            return json(kubernetesService.listNamespaces());
        }

        if (method == Method.GET && matches(segments, "pods")) {
            return json(kubernetesService.listPods(session.getParms().get("namespace")));
        }

        if (method == Method.GET && segments.size() == 3 && "pods".equals(segments.get(0))) {
            return json(kubernetesService.getPod(segments.get(1), segments.get(2)));
        }

        if (method == Method.GET && segments.size() == 4
                && "pods".equals(segments.get(0))
                && "metrics".equals(segments.get(3))) {
            return json(metricsService.podMetrics(segments.get(1), segments.get(2)));
        }

        if (method == Method.GET && segments.size() == 4
                && "pods".equals(segments.get(0))
                && "logs".equals(segments.get(3))) {
            Map<String, String> params = session.getParms();
            return text(kubernetesService.readLogs(
                    segments.get(1),
                    segments.get(2),
                    params.get("container"),
                    parseInt(params.get("tailLines")),
                    Boolean.parseBoolean(params.getOrDefault("previous", "false"))));
        }

        if (method == Method.GET && matches(segments, "port-forwards")) {
            return json(portForwardService.list());
        }

        if (method == Method.DELETE && matches(segments, "port-forwards")) {
            portForwardService.stopAll();
            return json(portForwardService.list());
        }

        if (method == Method.DELETE && segments.size() == 2 && "port-forwards".equals(segments.get(0))) {
            portForwardService.stop(segments.get(1));
            return json(portForwardService.list());
        }

        if (method == Method.POST && segments.size() == 4
                && "pods".equals(segments.get(0))
                && "port-forward".equals(segments.get(3))) {
            PortForwardRequest request = readJsonBody(session, PortForwardRequest.class);
            return json(portForwardService.start(
                    segments.get(1),
                    segments.get(2),
                    request.remotePort,
                    request.localPort,
                    request.https));
        }

        if (method == Method.POST && segments.size() == 4
                && "pods".equals(segments.get(0))
                && "port-forwards".equals(segments.get(3))) {
            PortForwardRequest request = readJsonBody(session, PortForwardRequest.class);
            return json(portForwardService.start(
                    segments.get(1),
                    segments.get(2),
                    request.mappings(),
                    request.https));
        }

        return error(Response.Status.NOT_FOUND, "Not found: " + uri);
    }

    private Response serveAsset(String uri) throws IOException {
        String assetPath = "/".equals(uri) ? "index.html" : uri.substring(1);
        int queryIndex = assetPath.indexOf('?');
        if (queryIndex >= 0) {
            assetPath = assetPath.substring(0, queryIndex);
        }
        assetPath = URLDecoder.decode(assetPath, StandardCharsets.UTF_8.name());
        if (assetPath.contains("..") || assetPath.startsWith("/")) {
            return error(Response.Status.FORBIDDEN, "Forbidden");
        }

        try (InputStream input = context.getAssets().open(assetPath)) {
            byte[] bytes = readAll(input);
            Response response = newFixedLengthResponse(
                    Response.Status.OK,
                    mime(assetPath),
                    new ByteArrayInputStream(bytes),
                    bytes.length);
            response.addHeader("Cache-Control", "no-store");
            return response;
        } catch (IOException ex) {
            return error(Response.Status.NOT_FOUND, "Asset not found: " + assetPath);
        }
    }

    private List<ImportedKubeConfig> readUploadedKubeConfigs(IHTTPSession session) throws IOException, ResponseException {
        Map<String, String> tempFiles = new LinkedHashMap<>();
        session.parseBody(tempFiles);
        Map<String, List<String>> parameters = session.getParameters();
        boolean hasIndexedFields = tempFiles.keySet().stream().anyMatch(key -> key.startsWith("file_"));

        List<ImportedKubeConfig> imports = new ArrayList<>();
        for (Map.Entry<String, String> entry : tempFiles.entrySet()) {
            String field = entry.getKey();
            if (hasIndexedFields && !field.startsWith("file_")) {
                continue;
            }

            File tempFile = new File(entry.getValue());
            if (!tempFile.isFile()) {
                continue;
            }

            String originalName = first(parameters.get(field));
            if (!hasText(originalName)) {
                originalName = field + ".yaml";
            }
            imports.add(new ImportedKubeConfig(originalName, tempFile));
        }
        return imports;
    }

    private <T> T readJsonBody(IHTTPSession session, Class<T> type) throws IOException, ResponseException {
        Map<String, String> files = new LinkedHashMap<>();
        session.parseBody(files);
        String body = files.get("postData");
        if (!hasText(body)) {
            body = session.getQueryParameterString();
        }
        if (!hasText(body)) {
            throw new IllegalArgumentException("Request body is empty");
        }
        return gson.fromJson(body, type);
    }

    private Response json(Object value) {
        Response response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", gson.toJson(value));
        response.addHeader("Cache-Control", "no-store");
        return response;
    }

    private Response text(String value) {
        return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", value == null ? "" : value);
    }

    private Response apiError(ApiException ex) {
        int code = ex.getCode() > 0 ? ex.getCode() : 502;
        Response.Status status = status(code);
        String message = hasText(ex.getResponseBody()) ? ex.getResponseBody() : ex.getMessage();
        if (!hasText(message)) {
            message = "Kubernetes API returned status " + code;
        }
        return error(status, message);
    }

    private Response error(Response.Status status, String message) {
        ErrorBody body = new ErrorBody(
                Instant.now().toString(),
                status.getRequestStatus(),
                status.getDescription(),
                hasText(message) ? message : status.getDescription());
        Response response = newFixedLengthResponse(status, "application/json; charset=utf-8", gson.toJson(body));
        response.addHeader("Cache-Control", "no-store");
        return response;
    }

    private List<String> segments(String path) throws IOException {
        String clean = Optional.ofNullable(path).orElse("");
        int queryIndex = clean.indexOf('?');
        if (queryIndex >= 0) {
            clean = clean.substring(0, queryIndex);
        }
        if (clean.isEmpty()) {
            return Collections.emptyList();
        }

        String[] rawSegments = clean.split("/");
        List<String> result = new ArrayList<>();
        for (String raw : rawSegments) {
            if (!raw.isEmpty()) {
                result.add(URLDecoder.decode(raw, StandardCharsets.UTF_8.name()));
            }
        }
        return result;
    }

    private boolean matches(List<String> segments, String... expected) {
        if (segments.size() != expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (!expected[index].equals(segments.get(index))) {
                return false;
            }
        }
        return true;
    }

    private Integer parseInt(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Response.Status status(int code) {
        for (Response.Status status : Response.Status.values()) {
            if (status.getRequestStatus() == code) {
                return status;
            }
        }
        if (code >= 400 && code < 500) {
            return Response.Status.BAD_REQUEST;
        }
        return Response.Status.INTERNAL_ERROR;
    }

    private String mime(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (lower.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (lower.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".ico")) {
            return "image/x-icon";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }

    private byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private String first(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.get(0);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
