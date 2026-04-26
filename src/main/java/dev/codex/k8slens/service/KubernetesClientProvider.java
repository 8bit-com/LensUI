package dev.codex.k8slens.service;

import dev.codex.k8slens.config.KubernetesLensProperties;
import dev.codex.k8slens.model.KubeConfigSummary;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.util.Config;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class KubernetesClientProvider {

    private final KubernetesLensProperties properties;
    private CoreV1Api coreV1Api;
    private Path activeKubeConfigPath;

    public KubernetesClientProvider(KubernetesLensProperties properties) {
        this.properties = properties;
    }

    public synchronized CoreV1Api coreV1Api() {
        if (coreV1Api == null) {
            try {
                ApiClient client = createClient();
                io.kubernetes.client.openapi.Configuration.setDefaultApiClient(client);
                coreV1Api = new CoreV1Api(client);
            } catch (IOException ex) {
                throw new KubernetesClientInitializationException("Cannot initialize Kubernetes client: " + ex.getMessage(), ex);
            }
        }
        return coreV1Api;
    }

    public synchronized List<KubeConfigSummary> listKubeConfigs() {
        List<Path> files = configuredKubeConfigFiles();
        Path active = activeKubeConfigPath();
        return files.stream()
                .map(path -> new KubeConfigSummary(
                        path.getFileName().toString(),
                        path.toString(),
                        active != null && path.equals(active)))
                .collect(Collectors.toList());
    }

    public synchronized void activateKubeConfig(String name) {
        Path selected = configuredKubeConfigFiles().stream()
                .filter(path -> path.getFileName().toString().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Kubeconfig not found in configured directory: " + name));
        activeKubeConfigPath = selected;
        coreV1Api = null;
    }

    public synchronized Optional<String> activeNamespace() {
        Path path = activeKubeConfigPath();
        if (path == null || !Files.isRegularFile(path)) {
            return Optional.empty();
        }

        try (Stream<String> lines = Files.lines(path)) {
            return lines
                    .map(String::trim)
                    .filter(line -> line.startsWith("namespace:"))
                    .map(line -> line.substring("namespace:".length()).trim())
                    .filter(StringUtils::hasText)
                    .findFirst();
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    public synchronized Optional<Path> activeKubeConfigFile() {
        Path path = activeKubeConfigPath();
        if (path == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(normalizedKubeConfigPath(path));
        } catch (IOException ex) {
            throw new KubernetesClientInitializationException("Cannot normalize kubeconfig: " + ex.getMessage(), ex);
        }
    }

    private ApiClient createClient() throws IOException {
        Path selectedPath = activeKubeConfigPath();
        if (selectedPath != null) {
            return Config.fromConfig(normalizedKubeConfigPath(selectedPath).toString());
        }
        return Config.defaultClient();
    }

    @SuppressWarnings("unchecked")
    private Path normalizedKubeConfigPath(Path source) throws IOException {
        Yaml yaml = new Yaml();
        Map<String, Object> config;
        try (Reader reader = Files.newBufferedReader(source)) {
            config = yaml.load(reader);
        }

        if (config == null) {
            return source;
        }

        Object contextsValue = config.get("contexts");
        Object currentContextValue = config.get("current-context");
        if (!(contextsValue instanceof List) || currentContextValue == null) {
            return source;
        }

        List<Object> contexts = (List<Object>) contextsValue;
        boolean currentContextExists = contexts.stream()
                .filter(Map.class::isInstance)
                .map(context -> ((Map<String, Object>) context).get("name"))
                .anyMatch(name -> currentContextValue.equals(name));

        if (currentContextExists || contexts.size() != 1 || !(contexts.get(0) instanceof Map)) {
            return source;
        }

        Object onlyContextName = ((Map<String, Object>) contexts.get(0)).get("name");
        if (!StringUtils.hasText(String.valueOf(onlyContextName))) {
            return source;
        }

        config.put("current-context", onlyContextName);

        Path normalizedDir = Path.of("target", "normalized-kubeconfigs").toAbsolutePath().normalize();
        Files.createDirectories(normalizedDir);
        Path normalized = normalizedDir.resolve(source.getFileName().toString() + ".yaml");

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        Yaml outputYaml = new Yaml(options);
        Files.writeString(normalized, outputYaml.dump(config));
        return normalized;
    }

    private Path activeKubeConfigPath() {
        if (activeKubeConfigPath != null) {
            return activeKubeConfigPath;
        }

        String kubeConfigPath = properties.getKubeConfigPath();
        if (StringUtils.hasText(kubeConfigPath)) {
            activeKubeConfigPath = Path.of(kubeConfigPath.trim()).toAbsolutePath().normalize();
            return activeKubeConfigPath;
        }

        activeKubeConfigPath = configuredKubeConfigFiles().stream().findFirst().orElse(null);
        return activeKubeConfigPath;
    }

    private List<Path> configuredKubeConfigFiles() {
        String kubeConfigDir = properties.getKubeConfigDir();
        if (!StringUtils.hasText(kubeConfigDir)) {
            return List.of();
        }

        Path dir = Path.of(kubeConfigDir.trim()).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }

        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()))
                    .collect(Collectors.toList());
        } catch (IOException ex) {
            throw new KubernetesClientInitializationException("Cannot read kubeconfig directory: " + ex.getMessage(), ex);
        }
    }

    public static class KubernetesClientInitializationException extends RuntimeException {
        public KubernetesClientInitializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
