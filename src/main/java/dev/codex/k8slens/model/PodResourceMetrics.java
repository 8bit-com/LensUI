package dev.codex.k8slens.model;

import java.util.List;

public class PodResourceMetrics {

    private final String namespace;
    private final String podName;
    private final String timestamp;
    private final String window;
    private final String cpu;
    private final double cpuMillicores;
    private final String memory;
    private final long memoryBytes;
    private final List<ContainerResourceMetrics> containers;

    public PodResourceMetrics(
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

    public String getNamespace() {
        return namespace;
    }

    public String getPodName() {
        return podName;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public String getWindow() {
        return window;
    }

    public String getCpu() {
        return cpu;
    }

    public double getCpuMillicores() {
        return cpuMillicores;
    }

    public String getMemory() {
        return memory;
    }

    public long getMemoryBytes() {
        return memoryBytes;
    }

    public List<ContainerResourceMetrics> getContainers() {
        return containers;
    }
}
