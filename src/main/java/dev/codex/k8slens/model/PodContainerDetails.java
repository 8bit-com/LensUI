package dev.codex.k8slens.model;

import java.util.List;

public class PodContainerDetails {

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

    public PodContainerDetails(
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

    public String getName() {
        return name;
    }

    public String getImage() {
        return image;
    }

    public String getImagePullPolicy() {
        return imagePullPolicy;
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

    public String getLastState() {
        return lastState;
    }

    public String getRequests() {
        return requests;
    }

    public String getLimits() {
        return limits;
    }

    public List<String> getEnvironment() {
        return environment;
    }

    public List<String> getMounts() {
        return mounts;
    }
}
