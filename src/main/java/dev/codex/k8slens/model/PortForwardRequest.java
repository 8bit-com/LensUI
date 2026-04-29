package dev.codex.k8slens.model;

import javax.validation.constraints.Min;
import java.util.ArrayList;
import java.util.List;

public class PortForwardRequest {

    @Min(1)
    private int remotePort;

    @Min(1)
    private Integer localPort;

    private boolean https;

    private List<PortMapping> ports = new ArrayList<>();

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

    public boolean isHttps() {
        return https;
    }

    public void setHttps(boolean https) {
        this.https = https;
    }

    public List<PortMapping> getPorts() {
        return ports;
    }

    public void setPorts(List<PortMapping> ports) {
        this.ports = ports == null ? new ArrayList<>() : ports;
    }

    public List<PortMapping> mappings() {
        if (ports != null && !ports.isEmpty()) {
            return ports;
        }

        PortMapping mapping = new PortMapping();
        mapping.setRemotePort(remotePort);
        mapping.setLocalPort(localPort);
        return List.of(mapping);
    }

    public static class PortMapping {

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
}
