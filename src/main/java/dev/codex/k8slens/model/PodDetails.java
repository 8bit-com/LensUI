package dev.codex.k8slens.model;

import java.util.List;
import java.util.Map;

public class PodDetails extends PodSummary {

    private final Map<String, String> labels;
    private final Map<String, String> annotations;
    private final List<String> conditions;

    public PodDetails(
            PodSummary summary,
            Map<String, String> labels,
            Map<String, String> annotations,
            List<String> conditions) {
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
}
