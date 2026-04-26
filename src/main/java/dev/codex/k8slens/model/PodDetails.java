package dev.codex.k8slens.model;

import java.util.List;
import java.util.Map;

public class PodDetails extends PodSummary {

    private final Map<String, String> labels;
    private final Map<String, String> annotations;
    private final List<String> conditions;
    private final String createdAt;
    private final String serviceAccount;
    private final String hostIp;
    private final String qosClass;
    private final String controlledBy;
    private final List<String> podIps;
    private final List<PodPortSummary> ports;
    private final List<PodContainerDetails> containerDetails;

    public PodDetails(
            PodSummary summary,
            Map<String, String> labels,
            Map<String, String> annotations,
            List<String> conditions,
            String createdAt,
            String serviceAccount,
            String hostIp,
            String qosClass,
            String controlledBy,
            List<String> podIps,
            List<PodPortSummary> ports,
            List<PodContainerDetails> containerDetails) {
        super(
                summary.getNamespace(),
                summary.getName(),
                summary.getPhase(),
                summary.getReady(),
                summary.getRestarts(),
                summary.getNodeName(),
                summary.getPodIp(),
                summary.getAge(),
                summary.getContainers());
        this.labels = labels;
        this.annotations = annotations;
        this.conditions = conditions;
        this.createdAt = createdAt;
        this.serviceAccount = serviceAccount;
        this.hostIp = hostIp;
        this.qosClass = qosClass;
        this.controlledBy = controlledBy;
        this.podIps = podIps;
        this.ports = ports;
        this.containerDetails = containerDetails;
    }

    public Map<String, String> getLabels() {
        return labels;
    }

    public Map<String, String> getAnnotations() {
        return annotations;
    }

    public List<String> getConditions() {
        return conditions;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getServiceAccount() {
        return serviceAccount;
    }

    public String getHostIp() {
        return hostIp;
    }

    public String getQosClass() {
        return qosClass;
    }

    public String getControlledBy() {
        return controlledBy;
    }

    public List<String> getPodIps() {
        return podIps;
    }

    public List<PodPortSummary> getPorts() {
        return ports;
    }

    public List<PodContainerDetails> getContainerDetails() {
        return containerDetails;
    }
}
