package dev.codex.k8slens.model;

import javax.validation.constraints.Min;

public class PortForwardRequest {

    @Min(1)
    private int remotePort;

    @Min(1)
    private Integer localPort;

    public int getRemotePort() {
        return remotePort;
    }

    public void setRemotePort(int remotePort) {
        this.remotePort = remotePort;
    }

    public Integer getLocalPort() {
        return localPort;
    }

    public void setLocalPort(Integer localPort) {
        this.localPort = localPort;
    }
}
