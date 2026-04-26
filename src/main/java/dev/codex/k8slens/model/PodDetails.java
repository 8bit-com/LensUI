package dev.codex.k8slens.model;

import java.util.List;
import java.util.Map;

public class PodDetails extends PodSummary {

    private final Map<String, String> labels;
    private final Map<String, String> annotations;
    private final List<String> conditions;
    private final String createdAt;
    private final String serviceAccount;
    private final List<PodPortSummary> ports;

    public PodDetails(
            PodSummary summary,
            Map<String, String> labels,
            Map<String, String> annotations,
            List<String> conditions,
            String createdAt,
            String serviceAccount,
            List<PodPortSummary> ports) {
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
        this.ports = ports;
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

    public List<PodPortSummary> getPorts() {
        return ports;
    }
}
