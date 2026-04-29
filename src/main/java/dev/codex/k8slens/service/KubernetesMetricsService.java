package dev.codex.k8slens.service;

import dev.codex.k8slens.model.ContainerResourceMetrics;
import dev.codex.k8slens.model.PodResourceMetrics;
import io.kubernetes.client.openapi.ApiException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class KubernetesMetricsService {

    private static final String GROUP = "metrics.k8s.io";
    private static final String VERSION = "v1beta1";
    private static final String PODS = "pods";

    private final KubernetesClientProvider clientProvider;

    public KubernetesMetricsService(KubernetesClientProvider clientProvider) {
        this.clientProvider = clientProvider;
    }

    public PodResourceMetrics podMetrics(String namespace, String podName) {
        try {
            Object response = clientProvider.customObjectsApi()
                    .getNamespacedCustomObject(GROUP, VERSION, namespace, PODS, podName);
            return toPodMetrics(response, namespace, podName);
        } catch (ApiException ex) {
            throw new KubernetesLensService.KubernetesApiRuntimeException(ex);
        }
    }

    private PodResourceMetrics toPodMetrics(Object response, String fallbackNamespace, String fallbackPodName) {
        Map<String, Object> root = asMap(response);
        Map<String, Object> metadata = asMap(root.get("metadata"));
        List<ContainerResourceMetrics> containers = asList(root.get("containers")).stream()
                .map(this::toContainerMetrics)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        double cpuMillicores = containers.stream()
                .mapToDouble(ContainerResourceMetrics::getCpuMillicores)
                .sum();
        long memoryBytes = containers.stream()
                .mapToLong(ContainerResourceMetrics::getMemoryBytes)
                .sum();

        return new PodResourceMetrics(
                stringValue(metadata.get("namespace"), fallbackNamespace),
                stringValue(metadata.get("name"), fallbackPodName),
                stringValue(root.get("timestamp"), ""),
                stringValue(root.get("window"), ""),
                formatCpu(cpuMillicores),
                round(cpuMillicores),
                formatMemory(memoryBytes),
                memoryBytes,
                containers);
    }

    private ContainerResourceMetrics toContainerMetrics(Object value) {
        Map<String, Object> container = asMap(value);
        if (container.isEmpty()) {
            return null;
        }

        Map<String, Object> usage = asMap(container.get("usage"));
        String cpu = stringValue(usage.get("cpu"), "0");
        String memory = stringValue(usage.get("memory"), "0");
        double cpuMillicores = parseCpuMillicores(cpu);
        long memoryBytes = parseMemoryBytes(memory);

        return new ContainerResourceMetrics(
                stringValue(container.get("name"), ""),
                formatCpu(cpuMillicores),
                round(cpuMillicores),
                formatMemory(memoryBytes),
                memoryBytes);
    }

    private double parseCpuMillicores(String value) {
        String cleanValue = clean(value);
        if (cleanValue.endsWith("n")) {
            return decimal(cleanValue.substring(0, cleanValue.length() - 1))
                    .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP)
                    .doubleValue();
        }
        if (cleanValue.endsWith("u")) {
            return decimal(cleanValue.substring(0, cleanValue.length() - 1))
                    .divide(BigDecimal.valueOf(1_000), 6, RoundingMode.HALF_UP)
                    .doubleValue();
        }
        if (cleanValue.endsWith("m")) {
            return decimal(cleanValue.substring(0, cleanValue.length() - 1)).doubleValue();
        }
        return decimal(cleanValue).multiply(BigDecimal.valueOf(1000)).doubleValue();
    }

    private long parseMemoryBytes(String value) {
        String cleanValue = clean(value);
        String suffix = suffix(cleanValue);
        BigDecimal number = decimal(cleanValue.substring(0, cleanValue.length() - suffix.length()));
        return number.multiply(BigDecimal.valueOf(memoryMultiplier(suffix))).longValue();
    }

    private String suffix(String value) {
        int index = value.length();
        while (index > 0 && Character.isLetter(value.charAt(index - 1))) {
            index--;
        }
        return value.substring(index);
    }

    private long memoryMultiplier(String suffix) {
        switch (suffix) {
            case "Ki":
                return 1024L;
            case "Mi":
                return 1024L * 1024L;
            case "Gi":
                return 1024L * 1024L * 1024L;
            case "Ti":
                return 1024L * 1024L * 1024L * 1024L;
            case "K":
                return 1000L;
            case "M":
                return 1000L * 1000L;
            case "G":
                return 1000L * 1000L * 1000L;
            case "":
                return 1L;
            default:
                return 1L;
        }
    }

    private String formatCpu(double millicores) {
        if (millicores >= 1000) {
            return trim(millicores / 1000) + " cores";
        }
        return trim(millicores) + " mCPU";
    }

    private String formatMemory(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return trim(bytes / 1024.0 / 1024.0 / 1024.0) + " GiB";
        }
        if (bytes >= 1024L * 1024L) {
            return trim(bytes / 1024.0 / 1024.0) + " MiB";
        }
        if (bytes >= 1024L) {
            return trim(bytes / 1024.0) + " KiB";
        }
        return bytes + " B";
    }

    private String trim(double value) {
        if (Math.abs(value) >= 100) {
            return String.format(Locale.US, "%.0f", value);
        }
        if (Math.abs(value) >= 10) {
            return String.format(Locale.US, "%.1f", value);
        }
        return String.format(Locale.US, "%.2f", value);
    }

    private double round(double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }

    private String clean(String value) {
        String cleanValue = Optional.ofNullable(value).orElse("0").trim();
        return cleanValue.isEmpty() ? "0" : cleanValue;
    }

    private BigDecimal decimal(String value) {
        String cleanValue = value == null || value.isBlank() ? "0" : value;
        return new BigDecimal(cleanValue);
    }

    private String stringValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private List<Object> asList(Object value) {
        if (value instanceof List) {
            return (List<Object>) value;
        }
        return Collections.emptyList();
    }
}
