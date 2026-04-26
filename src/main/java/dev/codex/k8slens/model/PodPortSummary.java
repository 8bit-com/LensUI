package dev.codex.k8slens.model;

public class PodPortSummary {

    private final String name;
    private final String containerName;
    private final int containerPort;
    private final String protocol;

    public PodPortSummary(String name, String containerName, int containerPort, String protocol) {
        this.name = name;
        this.containerName = containerName;
        this.containerPort = containerPort;
        this.protocol = protocol;
    }

    public String getName() {
        return name;
    }

    public String getContainerName() {
        return containerName;
    }

    public int getContainerPort() {
        return containerPort;
    }

    public String getProtocol() {
        return protocol;
    }
}
