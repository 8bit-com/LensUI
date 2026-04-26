package dev.codex.k8slens.api;

import io.kubernetes.client.openapi.ApiException;
import dev.codex.k8slens.service.KubernetesLensService.KubernetesApiRuntimeException;
import dev.codex.k8slens.service.KubernetesClientProvider.KubernetesClientInitializationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import javax.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApiException(ApiException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getCode());
        if (status == null) {
            status = HttpStatus.BAD_GATEWAY;
        }
        return ResponseEntity.status(status).body(error(status, readableKubernetesError(ex)));
    }

    @ExceptionHandler(KubernetesApiRuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleKubernetesRuntimeException(KubernetesApiRuntimeException ex) {
        return handleApiException(ex.getApiException());
    }

    @ExceptionHandler(KubernetesClientInitializationException.class)
    public ResponseEntity<Map<String, Object>> handleKubernetesClientInitialization(KubernetesClientInitializationException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(error(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage()));
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            ConstraintViolationException.class,
            MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception ex) {
        return ResponseEntity.badRequest().body(error(HttpStatus.BAD_REQUEST, ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage()));
    }

    private Map<String, Object> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return body;
    }

    private String readableKubernetesError(ApiException ex) {
        if (ex.getResponseBody() != null && !ex.getResponseBody().isBlank()) {
            return ex.getResponseBody();
        }
        if (ex.getMessage() != null && !ex.getMessage().isBlank()) {
            return ex.getMessage();
        }
        return "Kubernetes API returned status " + ex.getCode();
    }
}
