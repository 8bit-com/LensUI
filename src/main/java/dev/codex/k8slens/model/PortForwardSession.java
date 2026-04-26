package dev.codex.k8slens.model;

public class PortForwardSession {

    private final String id;
    private final String namespace;
    private final String podName;
    private final int localPort;
    private final int remotePort;
    private final String url;

    public PortForwardSession(String id, String namespace, String podName, int localPort, int remotePort) {
        this.id = id;
        this.namespace = namespace;
        this.podName = podName;
        this.localPort = localPort;
        this.remotePort = remotePort;
        this.url = "http://localhost:" + localPort;
    }

    public String getId() {
        return id;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getPodName() {
        return podName;
    }

    public int getLocalPort() {
        return localPort;
    }

    public int getRemotePort() {
        return remotePort;
    }

    public String getUrl() {
        return url;
    }
}
