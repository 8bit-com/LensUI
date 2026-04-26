package dev.codex.k8slens.model;

public class KubeConfigSummary {

    private final String name;
    private final String path;
    private final boolean active;

    public KubeConfigSummary(String name, String path, boolean active) {
        this.name = name;
        this.path = path;
        this.active = active;
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }

    public boolean isActive() {
        return active;
    }
}
