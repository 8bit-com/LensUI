package dev.codex.k8slens.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "kubernetes")
public class KubernetesLensProperties {

    private List<String> namespaces = new ArrayList<>();
    private String kubeConfigPath;
    private String kubeConfigDir;
    private Logs logs = new Logs();

    public List<String> getNamespaces() {
        return namespaces;
    }

    public void setNamespaces(List<String> namespaces) {
        this.namespaces = namespaces;
    }

    public String getKubeConfigPath() {
        return kubeConfigPath;
    }

    public void setKubeConfigPath(String kubeConfigPath) {
        this.kubeConfigPath = kubeConfigPath;
    }

    public String getKubeConfigDir() {
        return kubeConfigDir;
    }

    public void setKubeConfigDir(String kubeConfigDir) {
        this.kubeConfigDir = kubeConfigDir;
    }

    public Logs getLogs() {
        return logs;
    }

    public void setLogs(Logs logs) {
        this.logs = logs;
    }

    public static class Logs {
        private int defaultTailLines = 300;

        public int getDefaultTailLines() {
            return defaultTailLines;
        }

        public void setDefaultTailLines(int defaultTailLines) {
            this.defaultTailLines = defaultTailLines;
        }
    }
}
