package dev.codex.k8slens.service;

import dev.codex.k8slens.model.ContainerSummary;
import dev.codex.k8slens.model.PodContainerDetails;
import dev.codex.k8slens.model.PodDetails;
import dev.codex.k8slens.model.PodPortSummary;
import dev.codex.k8slens.model.PodSummary;
import io.kubernetes.client.custom.Quantity;
import io.kubernetes.client.openapi.models.V1Container;
import io.kubernetes.client.openapi.models.V1ContainerPort;
import io.kubernetes.client.openapi.models.V1ContainerState;
import io.kubernetes.client.openapi.models.V1ContainerStatus;
import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodCondition;
import io.kubernetes.client.openapi.models.V1PodIP;
import io.kubernetes.client.openapi.models.V1PodSpec;
import io.kubernetes.client.openapi.models.V1PodStatus;
import io.kubernetes.client.openapi.models.V1ResourceRequirements;
import io.kubernetes.client.openapi.models.V1VolumeMount;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class KubernetesPodMapper {

    public List<PodSummary> summarize(List<V1Pod> pods) {
        return Optional.ofNullable(pods).orElse(Collections.emptyList()).stream()
                .map(this::toSummary)
                .sorted(Comparator.comparing(PodSummary::getNamespace).thenComparing(PodSummary::getName))
                .collect(Collectors.toList());
    }

    public PodDetails toDetails(V1Pod pod) {
        PodSummary summary = toSummary(pod);
        V1PodStatus status = pod.getStatus();
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
                conditions,
                pod.getMetadata() == null || pod.getMetadata().getCreationTimestamp() == null
                        ? ""
                        : pod.getMetadata().getCreationTimestamp().toString(),
                pod.getSpec() == null ? "" : nullToBlank(pod.getSpec().getServiceAccountName()),
                status == null ? "" : nullToBlank(status.getHostIP()),
                status == null ? "" : nullToBlank(status.getQosClass()),
                controlledBy(pod),
                podIps(pod),
                ports(pod),
                containerDetails(pod));
    }

    private String controlledBy(V1Pod pod) {
        return Optional.ofNullable(pod.getMetadata())
                .map(metadata -> metadata.getOwnerReferences())
                .orElse(Collections.emptyList())
                .stream()
                .findFirst()
                .map(owner -> nullToBlank(owner.getKind()) + " " + nullToBlank(owner.getName()))
                .orElse("");
    }

    private List<String> podIps(V1Pod pod) {
        V1PodStatus status = pod.getStatus();
        List<String> ips = Optional.ofNullable(status)
                .map(V1PodStatus::getPodIPs)
                .orElse(Collections.emptyList())
                .stream()
                .map(V1PodIP::getIp)
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
        if (ips.isEmpty() && status != null && StringUtils.hasText(status.getPodIP())) {
            return List.of(status.getPodIP());
        }
        return ips;
    }

    private List<PodPortSummary> ports(V1Pod pod) {
        return Optional.ofNullable(pod.getSpec())
                .map(V1PodSpec::getContainers)
                .orElse(Collections.emptyList())
                .stream()
                .flatMap(container -> Optional.ofNullable(container.getPorts()).orElse(Collections.emptyList()).stream()
                        .filter(port -> port.getContainerPort() != null)
                        .map(port -> toPortSummary(container.getName(), port)))
                .collect(Collectors.toList());
    }

    private PodPortSummary toPortSummary(String containerName, V1ContainerPort port) {
        return new PodPortSummary(
                nullToBlank(port.getName()),
                nullToBlank(containerName),
                port.getContainerPort(),
                nullToBlank(port.getProtocol()));
    }

    private List<PodContainerDetails> containerDetails(V1Pod pod) {
        V1PodSpec spec = pod.getSpec();
        Map<String, V1ContainerStatus> statusesByName = Optional.ofNullable(pod.getStatus())
                .map(V1PodStatus::getContainerStatuses)
                .orElse(Collections.emptyList())
                .stream()
                .collect(Collectors.toMap(V1ContainerStatus::getName, Function.identity(), (left, right) -> left));

        return Optional.ofNullable(spec)
                .map(V1PodSpec::getContainers)
                .orElse(Collections.emptyList())
                .stream()
                .map(container -> toContainerDetails(container, statusesByName.get(container.getName())))
                .collect(Collectors.toList());
    }

    private PodContainerDetails toContainerDetails(V1Container container, V1ContainerStatus status) {
        V1ResourceRequirements resources = container.getResources();
        return new PodContainerDetails(
                nullToBlank(container.getName()),
                nullToBlank(container.getImage()),
                nullToBlank(container.getImagePullPolicy()),
                status != null && Boolean.TRUE.equals(status.getReady()),
                status == null || status.getRestartCount() == null ? 0 : status.getRestartCount(),
                status == null ? "Unknown" : state(status.getState()),
                status == null ? "" : lastState(status.getLastState()),
                resources == null ? "" : quantityMap(resources.getRequests()),
                resources == null ? "" : quantityMap(resources.getLimits()),
                Optional.ofNullable(container.getEnv()).orElse(Collections.emptyList()).stream()
                        .map(V1EnvVar::getName)
                        .filter(StringUtils::hasText)
                        .sorted()
                        .collect(Collectors.toList()),
                Optional.ofNullable(container.getVolumeMounts()).orElse(Collections.emptyList()).stream()
                        .map(this::formatMount)
                        .collect(Collectors.toList()));
    }

    private String lastState(V1ContainerState state) {
        if (state == null) {
            return "";
        }
        if (state.getTerminated() != null) {
            List<String> lines = new ArrayList<>();
            lines.add("terminated");
            String reason = nullToBlank(state.getTerminated().getReason());
            Integer exitCode = state.getTerminated().getExitCode();
            if (StringUtils.hasText(reason) || exitCode != null) {
                lines.add("Reason: " + reason + (exitCode == null ? "" : " - exit code: " + exitCode));
            }
            if (state.getTerminated().getStartedAt() != null) {
                lines.add("Started at: " + state.getTerminated().getStartedAt());
            }
            if (state.getTerminated().getFinishedAt() != null) {
                lines.add("Finished at: " + state.getTerminated().getFinishedAt());
            }
            return String.join("\n", lines);
        }
        if (state.getWaiting() != null) {
            return "waiting " + nullToBlank(state.getWaiting().getReason());
        }
        if (state.getRunning() != null) {
            return "running";
        }
        return "";
    }

    private String formatMount(V1VolumeMount mount) {
        String source = nullToBlank(mount.getName());
        String path = nullToBlank(mount.getMountPath());
        String mode = Boolean.TRUE.equals(mount.getReadOnly()) ? " (ro)" : "";
        return path + (StringUtils.hasText(source) ? " from " + source : "") + mode;
    }

    private String quantityMap(Map<String, ?> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> resourceLabel(entry.getKey()) + ": " + quantityValue(entry.getValue()))
                .collect(Collectors.joining(", "));
    }

    private String quantityValue(Object value) {
        if (value instanceof Quantity) {
            return ((Quantity) value).toSuffixedString();
        }
        return String.valueOf(value);
    }

    private String resourceLabel(String name) {
        if ("cpu".equalsIgnoreCase(name)) {
            return "CPU";
        }
        if ("memory".equalsIgnoreCase(name)) {
            return "Memory";
        }
        if ("ephemeral-storage".equalsIgnoreCase(name)) {
            return "Ephemeral Storage";
        }
        return name;
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
}
