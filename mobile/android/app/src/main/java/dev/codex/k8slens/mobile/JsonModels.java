package dev.codex.k8slens.mobile;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

class KubeConfigSummary {
    private final String name;
    private final String path;
    private final boolean active;

    KubeConfigSummary(String name, String path, boolean active) {
        this.name = name;
        this.path = path;
        this.active = active;
    }

    String getName() {
        return name;
    }
}

class ContainerSummary {
    private final String name;
    private final String image;
    private final boolean ready;
    private final int restartCount;
    private final String state;

    ContainerSummary(String name, String image, boolean ready, int restartCount, String state) {
        this.name = name;
        this.image = image;
        this.ready = ready;
        this.restartCount = restartCount;
        this.state = state;
    }

    boolean isReady() {
        return ready;
    }

    int getRestartCount() {
        return restartCount;
    }
}

class PodSummary {
    private final String namespace;
    private final String name;
    private final String phase;
    private final String ready;
    private final int restarts;
    private final String nodeName;
    private final String podIp;
    private final String age;
    private final List<ContainerSummary> containers;

    PodSummary(
            String namespace,
            String name,
            String phase,
            String ready,
            int restarts,
            String nodeName,
            String podIp,
            String age,
            List<ContainerSummary> containers) {
        this.namespace = namespace;
        this.name = name;
        this.phase = phase;
        this.ready = ready;
        this.restarts = restarts;
        this.nodeName = nodeName;
        this.podIp = podIp;
        this.age = age;
        this.containers = containers;
    }

    String getNamespace() {
        return namespace;
    }

    String getName() {
        return name;
    }

    String getPhase() {
        return phase;
    }

    String getReady() {
        return ready;
    }

    int getRestarts() {
        return restarts;
    }

    String getNodeName() {
        return nodeName;
    }

    String getPodIp() {
        return podIp;
    }

    String getAge() {
        return age;
    }

    List<ContainerSummary> getContainers() {
        return containers;
    }
}

class PodDetails extends PodSummary {
    private final Map<String, String> labels;
    private final Map<String, String> annotations;
    private final List<String> conditions;
    private final String createdAt;
    private final String serviceAccount;
    private final String hostIp;
    private final String qosClass;
    private final String controlledBy;
    private final List<String> podIps;
    private final List<PodPortSummary> ports;
    private final List<PodContainerDetails> containerDetails;

    PodDetails(
            PodSummary summary,
            Map<String, String> labels,
            Map<String, String> annotations,
            List<String> conditions,
            String createdAt,
            String serviceAccount,
            String hostIp,
            String qosClass,
            String controlledBy,
            List<String> podIps,
            List<PodPortSummary> ports,
            List<PodContainerDetails> containerDetails) {
        super(
                summary.getNamespace(),
                summary.getName(),
                summary.getPhase(),
                summary.getReady(),
                summary.getRestarts(),
                summary.getNodeName(),
                summary.getPodIp(),
                summary.getAge(),
                summary.getContainers());
        this.labels = labels;
        this.annotations = annotations;
        this.conditions = conditions;
        this.createdAt = createdAt;
        this.serviceAccount = serviceAccount;
        this.hostIp = hostIp;
        this.qosClass = qosClass;
        this.controlledBy = controlledBy;
        this.podIps = podIps;
        this.ports = ports;
        this.containerDetails = containerDetails;
    }
}

class PodPortSummary {
    private final String name;
    private final String containerName;
    private final int containerPort;
    private final String protocol;

    PodPortSummary(String name, String containerName, int containerPort, String protocol) {
        this.name = name;
        this.containerName = containerName;
        this.containerPort = containerPort;
        this.protocol = protocol;
    }
}

class PodContainerDetails {
    private final String name;
    private final String image;
    private final String imagePullPolicy;
    private final boolean ready;
    private final int restartCount;
    private final String state;
    private final String lastState;
    private final String requests;
    private final String limits;
    private final List<String> environment;
    private final List<String> mounts;

    PodContainerDetails(
            String name,
            String image,
            String imagePullPolicy,
            boolean ready,
            int restartCount,
            String state,
            String lastState,
            String requests,
            String limits,
            List<String> environment,
            List<String> mounts) {
        this.name = name;
        this.image = image;
        this.imagePullPolicy = imagePullPolicy;
        this.ready = ready;
        this.restartCount = restartCount;
        this.state = state;
        this.lastState = lastState;
        this.requests = requests;
        this.limits = limits;
        this.environment = environment;
        this.mounts = mounts;
    }
}

class PodResourceMetrics {
    private final String namespace;
    private final String podName;
    private final String timestamp;
    private final String window;
    private final String cpu;
    private final double cpuMillicores;
    private final String memory;
    private final long memoryBytes;
    private final List<ContainerResourceMetrics> containers;

    PodResourceMetrics(
            String namespace,
            String podName,
            String timestamp,
            String window,
            String cpu,
            double cpuMillicores,
            String memory,
            long memoryBytes,
            List<ContainerResourceMetrics> containers) {
        this.namespace = namespace;
        this.podName = podName;
        this.timestamp = timestamp;
        this.window = window;
        this.cpu = cpu;
        this.cpuMillicores = cpuMillicores;
        this.memory = memory;
        this.memoryBytes = memoryBytes;
        this.containers = containers;
    }
}

class ContainerResourceMetrics {
    private final String name;
    private final String cpu;
    private final double cpuMillicores;
    private final String memory;
    private final long memoryBytes;

    ContainerResourceMetrics(String name, String cpu, double cpuMillicores, String memory, long memoryBytes) {
        this.name = name;
        this.cpu = cpu;
        this.cpuMillicores = cpuMillicores;
        this.memory = memory;
        this.memoryBytes = memoryBytes;
    }

    double getCpuMillicores() {
        return cpuMillicores;
    }

    long getMemoryBytes() {
        return memoryBytes;
    }
}

class PortForwardSession {
    private final String id;
    private final String namespace;
    private final String podName;
    private final int localPort;
    private final int remotePort;
    private final String scheme;
    private final String url;

    PortForwardSession(String id, String namespace, String podName, int localPort, int remotePort, boolean https) {
        this.id = id;
        this.namespace = namespace;
        this.podName = podName;
        this.localPort = localPort;
        this.remotePort = remotePort;
        this.scheme = https ? "https" : "http";
        this.url = scheme + "://127.0.0.1:" + localPort;
    }

    String getId() {
        return id;
    }

    String getNamespace() {
        return namespace;
    }

    String getPodName() {
        return podName;
    }

    int getLocalPort() {
        return localPort;
    }

    int getRemotePort() {
        return remotePort;
    }
}

class PortForwardRequest {
    int remotePort;
    Integer localPort;
    boolean https;
    List<PortMapping> ports = new ArrayList<>();

    List<PortMapping> mappings() {
        if (ports != null && !ports.isEmpty()) {
            return ports;
        }

        PortMapping mapping = new PortMapping();
        mapping.remotePort = remotePort;
        mapping.localPort = localPort;
        List<PortMapping> result = new ArrayList<>();
        result.add(mapping);
        return result;
    }
}

class PortMapping {
    int remotePort;
    Integer localPort;
}

class ErrorBody {
    private final String timestamp;
    private final int status;
    private final String error;
    private final String message;

    ErrorBody(String timestamp, int status, String error, String message) {
        this.timestamp = timestamp;
        this.status = status;
        this.error = error;
        this.message = message;
    }
}

class ImportedKubeConfig {
    final String name;
    final java.io.File file;

    ImportedKubeConfig(String name, java.io.File file) {
        this.name = name;
        this.file = file;
    }
}
