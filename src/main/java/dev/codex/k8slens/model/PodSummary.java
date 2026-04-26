package dev.codex.k8slens.model;

import java.util.List;

public class PodSummary {

    private final String namespace;
    private final String name;
    private final String phase;
    private final String ready;
    private final int restarts;
    private final String nodeName;
    private final String podIp;
    private final String age;
    private final List<ContainerSummary> containers;

    public PodSummary(
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

    public String getNamespace() {
        return namespace;
    }

    public String getName() {
        return name;
    }

    public String getPhase() {
        return phase;
    }

    public String getReady() {
        return ready;
    }

    public int getRestarts() {
        return restarts;
    }

    public String getNodeName() {
        return nodeName;
    }

    public String getPodIp() {
        return podIp;
    }

    public String getAge() {
        return age;
    }

    public List<ContainerSummary> getContainers() {
        return containers;
    }
}
