package dev.codex.k8slens.model;

public class ContainerResourceMetrics {

    private final String name;
    private final String cpu;
    private final double cpuMillicores;
    private final String memory;
    private final long memoryBytes;

    public ContainerResourceMetrics(
            String name,
            String cpu,
            double cpuMillicores,
            String memory,
            long memoryBytes) {
        this.name = name;
        this.cpu = cpu;
        this.cpuMillicores = cpuMillicores;
        this.memory = memory;
        this.memoryBytes = memoryBytes;
    }

    public String getName() {
        return name;
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
}
