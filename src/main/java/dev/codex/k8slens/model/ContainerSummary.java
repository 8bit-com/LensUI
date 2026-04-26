package dev.codex.k8slens.model;

public class ContainerSummary {

    private final String name;
    private final String image;
    private final boolean ready;
    private final int restartCount;
    private final String state;

    public ContainerSummary(String name, String image, boolean ready, int restartCount, String state) {
        this.name = name;
        this.image = image;
        this.ready = ready;
        this.restartCount = restartCount;
        this.state = state;
    }

    public String getName() {
        return name;
    }

    public String getImage() {
        return image;
    }

    public boolean isReady() {
        return ready;
    }

    public int getRestartCount() {
        return restartCount;
    }

    public String getState() {
        return state;
    }
}
