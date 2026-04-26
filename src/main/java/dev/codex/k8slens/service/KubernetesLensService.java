package dev.codex.k8slens.service;

import dev.codex.k8slens.config.KubernetesLensProperties;
import dev.codex.k8slens.model.ContainerSummary;
import dev.codex.k8slens.model.PodDetails;
import dev.codex.k8slens.model.PodSummary;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1ContainerState;
import io.kubernetes.client.openapi.models.V1ContainerStatus;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1NamespaceList;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodCondition;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1PodStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class KubernetesLensService {

    private final KubernetesClientProvider clientProvider;
    private final KubernetesLensProperties properties;

    public KubernetesLensService(KubernetesClientProvider clientProvider, KubernetesLensProperties properties) {
        this.clientProvider = clientProvider;
        this.properties = properties;
    }

    public List<String> listNamespaces() {
        List<String> configuredNamespaces = configuredNamespaces();
        if (!configuredNamespaces.isEmpty()) {
            return configuredNamespaces;
        }

        try {
            V1NamespaceList namespaces = api().listNamespace(
                    null, null, null, null, null, null, null, null, null, null, null);
            return Optional.ofNullable(namespaces.getItems()).orElse(Collections.emptyList()).stream()
                    .map(V1Namespace::getMetadata)
                    .filter(Objects::nonNull)
                    .map(metadata -> metadata.getName())
                    .filter(StringUtils::hasText)
                    .sorted()
                    .collect(Collectors.toList());
        } catch (ApiException ex) {
            if (ex.getCode() == 403) {
                return clientProvider.activeNamespace()
                        .map(Collections::singletonList)
                        .orElseGet(Collections::emptyList);
            }
            throw new KubernetesApiRuntimeException(ex);
        }
    }

    public List<PodSummary> listPods(String namespace) {
        try {
            if (StringUtils.hasText(namespace)) {
                return summarize(api().listNamespacedPod(
                        namespace, null, null, null, null, null, null, null, null, null, null, null)
                        .getItems());
            }

            List<String> configuredNamespaces = configuredNamespaces();
            if (configuredNamespaces.isEmpty()) {
                try {
                    V1PodList pods = api().listPodForAllNamespaces(
                            null, null, null, null, null, null, null, null, null, null, null);
                    return summarize(pods.getItems());
                } catch (ApiException ex) {
                    if (ex.getCode() == 403 && clientProvider.activeNamespace().isPresent()) {
                        return summarize(api().listNamespacedPod(
                                clientProvider.activeNamespace().get(),
                                null, null, null, null, null, null, null, null, null, null, null)
                                .getItems());
                    }
                    throw ex;
                }
            }

            List<PodSummary> pods = new ArrayList<>();
            for (String configuredNamespace : configuredNamespaces) {
                pods.addAll(summarize(api().listNamespacedPod(
                        configuredNamespace, null, null, null, null, null, null, null, null, null, null, null)
                        .getItems()));
            }
            return pods.stream()
                    .sorted(Comparator.comparing(PodSummary::getNamespace).thenComparing(PodSummary::getName))
                    .collect(Collectors.toList());
        } catch (ApiException ex) {
            throw new KubernetesApiRuntimeException(ex);
        }
    }

    public PodDetails getPod(String namespace, String name) {
        try {
            V1Pod pod = api().readNamespacedPod(name, namespace, null);
            return toDetails(pod);
        } catch (ApiException ex) {
            throw new KubernetesApiRuntimeException(ex);
        }
    }

    public String readLogs(String namespace, String name, String container, Integer tailLines) {
        Integer effectiveTailLines = tailLines != null ? tailLines : properties.getLogs().getDefaultTailLines();
        try {
            return api().readNamespacedPodLog(
                    name,
                    namespace,
                    container,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    effectiveTailLines,
                    null);
        } catch (ApiException ex) {
            throw new KubernetesApiRuntimeException(ex);
        }
    }

    private CoreV1Api api() {
        return clientProvider.coreV1Api();
    }

    private List<PodSummary> summarize(List<V1Pod> pods) {
        return Optional.ofNullable(pods).orElse(Collections.emptyList()).stream()
                .map(this::toSummary)
                .sorted(Comparator.comparing(PodSummary::getNamespace).thenComparing(PodSummary::getName))
                .collect(Collectors.toList());
    }

    private PodDetails toDetails(V1Pod pod) {
        PodSummary summary = toSummary(pod);
        List<String> conditions = Optional.ofNullable(pod.getStatus())
                .map(V1PodStatus::getConditions)
                .orElse(Collections.emptyList())
                .stream()
                .map(this::formatCondition)
                .collect(Collectors.toList());

        return new PodDetails(
                summary,
                nullToEmpty(pod.getMetadata() == null ? null : pod.getMetadata().getLabels()),
                nullToEmpty(pod.getMetadata() == null ? null : pod.getMetadata().getAnnotations()),
                conditions);
    }

    private PodSummary toSummary(V1Pod pod) {
        V1PodSpec spec = pod.getSpec();
        V1PodStatus status = pod.getStatus();
        Map<String, V1Container> containersByName = Optional.ofNullable(spec)
                .map(V1PodSpec::getContainers)
                .orElse(Collections.emptyList())
                .stream()
                .collect(Collectors.toMap(V1Container::getName, Function.identity(), (left, right) -> left));

        List<V1ContainerStatus> statuses = Optional.ofNullable(status)
                .map(V1PodStatus::getContainerStatuses)
                .orElse(Collections.emptyList());
        Set<String> statusNames = statuses.stream()
                .map(V1ContainerStatus::getName)
                .collect(Collectors.toSet());

        List<ContainerSummary> containers = statuses.stream()
                .map(containerStatus -> toContainerSummary(containerStatus, containersByName.get(containerStatus.getName())))
                .collect(Collectors.toCollection(ArrayList::new));

        containersByName.values().stream()
                .filter(container -> !statusNames.contains(container.getName()))
                .map(container -> new ContainerSummary(container.getName(), container.getImage(), false, 0, "Waiting"))
                .forEach(containers::add);

        int readyContainers = (int) containers.stream().filter(ContainerSummary::isReady).count();
        int restarts = containers.stream().mapToInt(ContainerSummary::getRestartCount).sum();

        return new PodSummary(
                pod.getMetadata() == null ? "" : pod.getMetadata().getNamespace(),
                pod.getMetadata() == null ? "" : pod.getMetadata().getName(),
                status == null || status.getPhase() == null ? "Unknown" : status.getPhase(),
                readyContainers + "/" + containers.size(),
                restarts,
                spec == null ? "" : nullToBlank(spec.getNodeName()),
                status == null ? "" : nullToBlank(status.getPodIP()),
                pod.getMetadata() == null ? "" : age(pod.getMetadata().getCreationTimestamp()),
                containers);
    }

    private ContainerSummary toContainerSummary(V1ContainerStatus status, V1Container container) {
        String image = container != null ? container.getImage() : status.getImage();
        return new ContainerSummary(
                status.getName(),
                image,
                Boolean.TRUE.equals(status.getReady()),
                status.getRestartCount() == null ? 0 : status.getRestartCount(),
                state(status.getState()));
    }

    private String state(V1ContainerState state) {
        if (state == null) {
            return "Unknown";
        }
        if (state.getRunning() != null) {
            return "Running";
        }
        if (state.getWaiting() != null) {
            return "Waiting: " + nullToBlank(state.getWaiting().getReason());
        }
        if (state.getTerminated() != null) {
            return "Terminated: " + nullToBlank(state.getTerminated().getReason());
        }
        return "Unknown";
    }

    private String formatCondition(V1PodCondition condition) {
        return condition.getType() + "=" + condition.getStatus()
                + (StringUtils.hasText(condition.getReason()) ? " (" + condition.getReason() + ")" : "");
    }

    private String age(OffsetDateTime creationTimestamp) {
        if (creationTimestamp == null) {
            return "";
        }
        Duration duration = Duration.between(creationTimestamp, OffsetDateTime.now());
        long days = duration.toDays();
        if (days > 0) {
            return days + "d";
        }
        long hours = duration.toHours();
        if (hours > 0) {
            return hours + "h";
        }
        long minutes = duration.toMinutes();
        if (minutes > 0) {
            return minutes + "m";
        }
        return Math.max(0, duration.getSeconds()) + "s";
    }

    private List<String> configuredNamespaces() {
        return Optional.ofNullable(properties.getNamespaces()).orElse(Collections.emptyList()).stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .sorted()
                .collect(Collectors.toList());
    }

    private Map<String, String> nullToEmpty(Map<String, String> source) {
        if (source == null) {
            return Collections.emptyMap();
        }
        return source.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (left, right) -> left, LinkedHashMap::new));
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    public static class KubernetesApiRuntimeException extends RuntimeException {
        private final ApiException apiException;

        public KubernetesApiRuntimeException(ApiException apiException) {
            super(apiException);
            this.apiException = apiException;
        }

        public ApiException getApiException() {
            return apiException;
        }
    }
}
