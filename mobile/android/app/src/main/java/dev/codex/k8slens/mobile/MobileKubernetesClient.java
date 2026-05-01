package dev.codex.k8slens.mobile;

import android.content.Context;
import android.content.SharedPreferences;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.util.Config;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

class MobileKubernetesClient {
    private static final String PREFS = "kubernetes_lens_mobile";
    private static final String PREF_ACTIVE_CONFIG = "activeKubeConfig";
    private static final Set<String> KUBECONFIG_KEYS = new HashSet<>(
            Arrays.asList("apiVersion", "clusters", "contexts", "users"));

    private final File kubeConfigDir;
    private final File normalizedKubeConfigDir;
    private final SharedPreferences preferences;

    private ApiClient apiClient;
    private CoreV1Api coreV1Api;
    private CustomObjectsApi customObjectsApi;

    MobileKubernetesClient(Context context) {
        this.kubeConfigDir = new File(context.getFilesDir(), "kubeconfigs");
        this.normalizedKubeConfigDir = new File(context.getFilesDir(), "normalized-kubeconfigs");
        this.preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        //noinspection ResultOfMethodCallIgnored
        this.kubeConfigDir.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        this.normalizedKubeConfigDir.mkdirs();
    }

    synchronized CoreV1Api coreV1Api() {
        if (coreV1Api == null) {
            coreV1Api = new CoreV1Api(apiClient());
        }
        return coreV1Api;
    }

    synchronized CustomObjectsApi customObjectsApi() {
        if (customObjectsApi == null) {
            customObjectsApi = new CustomObjectsApi(apiClient());
        }
        return customObjectsApi;
    }

    synchronized List<KubeConfigSummary> listKubeConfigs() {
        List<File> files = kubeConfigFiles();
        String active = activeName(files);
        List<KubeConfigSummary> summaries = new ArrayList<>();
        for (File file : files) {
            summaries.add(new KubeConfigSummary(file.getName(), file.getAbsolutePath(), file.getName().equals(active)));
        }
        return summaries;
    }

    synchronized List<KubeConfigSummary> importKubeConfigs(List<ImportedKubeConfig> imports) throws IOException {
        if (imports.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one kubeconfig file");
        }

        String firstImported = null;
        for (ImportedKubeConfig imported : imports) {
            String name = safeFileName(imported.name);
            if (!hasText(name)) {
                name = "kubeconfig-" + System.currentTimeMillis() + ".yaml";
            }
            File target = uniqueTarget(name);
            Files.copy(imported.file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (firstImported == null && looksLikeKubeConfig(target)) {
                firstImported = target.getName();
            }
        }

        if (firstImported == null) {
            throw new IllegalArgumentException("No kubeconfig files found in selected files");
        }

        if (firstImported != null) {
            preferences.edit().putString(PREF_ACTIVE_CONFIG, firstImported).apply();
        }
        resetApis();
        return listKubeConfigs();
    }

    synchronized List<KubeConfigSummary> activateKubeConfig(String name) {
        boolean exists = false;
        for (File file : kubeConfigFiles()) {
            if (file.getName().equals(name)) {
                exists = true;
                break;
            }
        }
        if (!exists) {
            throw new IllegalArgumentException("Kubeconfig not found: " + name);
        }
        preferences.edit().putString(PREF_ACTIVE_CONFIG, name).apply();
        resetApis();
        return listKubeConfigs();
    }

    synchronized Optional<String> activeNamespace() {
        File file = activeKubeConfigFile();
        if (file == null) {
            return Optional.empty();
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("namespace:")) {
                    String namespace = trimmed.substring("namespace:".length()).trim();
                    if (hasText(namespace)) {
                        return Optional.of(namespace);
                    }
                }
            }
        } catch (IOException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    synchronized ApiClient apiClient() {
        if (apiClient == null) {
            File file = activeKubeConfigFile();
            if (file == null) {
                throw new IllegalStateException("Import a kubeconfig file on the phone first");
            }

            try {
                File normalized = normalizedKubeConfigFile(file);
                validateSupportedAuth(normalized);
                apiClient = Config.fromConfig(normalized.getAbsolutePath());
                apiClient.setConnectTimeout(30_000);
                apiClient.setReadTimeout(60_000);
                io.kubernetes.client.openapi.Configuration.setDefaultApiClient(apiClient);
            } catch (IOException ex) {
                throw new IllegalStateException("Cannot initialize Kubernetes client: " + ex.getMessage(), ex);
            }
        }
        return apiClient;
    }

    synchronized File kubectlKubeConfigFile() throws IOException {
        File file = activeKubeConfigFile();
        if (file == null) {
            throw new IllegalStateException("Import a kubeconfig file on the phone first");
        }

        File normalized = normalizedKubeConfigFile(file);
        validateSupportedAuth(normalized);
        return normalized;
    }

    private File activeKubeConfigFile() {
        List<File> files = kubeConfigFiles();
        if (files.isEmpty()) {
            return null;
        }

        String active = activeName(files);
        for (File file : files) {
            if (file.getName().equals(active)) {
                return file;
            }
        }
        return files.get(0);
    }

    private String activeName(List<File> files) {
        String active = preferences.getString(PREF_ACTIVE_CONFIG, "");
        if (hasText(active)) {
            for (File file : files) {
                if (file.getName().equals(active)) {
                    return active;
                }
            }
        }

        if (files.isEmpty()) {
            return "";
        }
        String first = files.get(0).getName();
        preferences.edit().putString(PREF_ACTIVE_CONFIG, first).apply();
        return first;
    }

    private List<File> kubeConfigFiles() {
        File[] files = kubeConfigDir.listFiles(file -> file.isFile()
                && !file.getName().startsWith(".")
                && looksLikeKubeConfig(file));
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }
        List<File> result = new ArrayList<>(Arrays.asList(files));
        result.sort(Comparator.comparing(file -> file.getName().toLowerCase(Locale.ROOT)));
        return result;
    }

    @SuppressWarnings("unchecked")
    private File normalizedKubeConfigFile(File source) throws IOException {
        Map<String, Object> config = readYaml(source);
        if (config.isEmpty()) {
            return source;
        }

        boolean changed = ensureCurrentContext(config);
        changed = rewriteFileReferenceLists(config, "clusters", "cluster",
                Collections.singletonList("certificate-authority")) || changed;
        changed = rewriteFileReferenceLists(config, "users", "user",
                Arrays.asList("client-certificate", "client-key", "tokenFile", "auth-provider.config.cmd-path")) || changed;

        if (!changed) {
            return source;
        }

        File target = new File(normalizedKubeConfigDir, source.getName() + ".yaml");
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        new Yaml(options).dump(config, Files.newBufferedWriter(target.toPath()));
        return target;
    }

    @SuppressWarnings("unchecked")
    private boolean ensureCurrentContext(Map<String, Object> config) {
        Object contextsValue = config.get("contexts");
        if (!(contextsValue instanceof List)) {
            return false;
        }

        List<Object> contexts = (List<Object>) contextsValue;
        Object currentContextValue = config.get("current-context");
        boolean hasCurrentContext = currentContextValue != null && hasText(String.valueOf(currentContextValue));
        boolean currentContextExists = hasCurrentContext && contexts.stream()
                .filter(Map.class::isInstance)
                .map(context -> ((Map<String, Object>) context).get("name"))
                .anyMatch(name -> currentContextValue.equals(name));

        if (currentContextExists || contexts.size() != 1 || !(contexts.get(0) instanceof Map)) {
            return false;
        }

        Object onlyContextName = ((Map<String, Object>) contexts.get(0)).get("name");
        if (!hasText(String.valueOf(onlyContextName))) {
            return false;
        }

        config.put("current-context", onlyContextName);
        return true;
    }

    @SuppressWarnings("unchecked")
    private boolean rewriteFileReferenceLists(
            Map<String, Object> config,
            String listKey,
            String nestedKey,
            List<String> referencePaths) {
        Object listValue = config.get(listKey);
        if (!(listValue instanceof List)) {
            return false;
        }

        boolean changed = false;
        for (Object item : (List<Object>) listValue) {
            if (!(item instanceof Map)) {
                continue;
            }
            Object nestedValue = ((Map<String, Object>) item).get(nestedKey);
            if (!(nestedValue instanceof Map)) {
                continue;
            }
            Map<String, Object> nested = (Map<String, Object>) nestedValue;
            for (String referencePath : referencePaths) {
                changed = rewriteFileReference(nested, referencePath) || changed;
            }
        }
        return changed;
    }

    @SuppressWarnings("unchecked")
    private boolean rewriteFileReference(Map<String, Object> root, String dottedPath) {
        String[] parts = dottedPath.split("\\.");
        Map<String, Object> current = root;
        for (int index = 0; index < parts.length - 1; index++) {
            Object next = current.get(parts[index]);
            if (!(next instanceof Map)) {
                return false;
            }
            current = (Map<String, Object>) next;
        }

        String key = parts[parts.length - 1];
        Object value = current.get(key);
        if (!hasText(String.valueOf(value))) {
            return false;
        }

        String path = String.valueOf(value);
        File original = new File(path);
        if (original.isAbsolute() && original.exists()) {
            return false;
        }

        File sibling = new File(kubeConfigDir, new File(path.replace('\\', '/')).getName());
        if (!sibling.isFile()) {
            return false;
        }

        current.put(key, sibling.getAbsolutePath());
        return true;
    }

    @SuppressWarnings("unchecked")
    private void validateSupportedAuth(File source) throws IOException {
        Map<String, Object> config = readYaml(source);
        Object usersValue = config.get("users");
        if (!(usersValue instanceof List)) {
            return;
        }

        for (Object item : (List<Object>) usersValue) {
            if (!(item instanceof Map)) {
                continue;
            }

            Object userValue = ((Map<String, Object>) item).get("user");
            if (!(userValue instanceof Map)) {
                continue;
            }

            Map<String, Object> user = (Map<String, Object>) userValue;
            if (user.containsKey("exec")) {
                throw new IllegalStateException("This kubeconfig uses exec auth. Android cannot run desktop auth commands; use a token/certificate kubeconfig instead.");
            }
        }
    }

    private boolean looksLikeKubeConfig(File file) {
        try {
            Map<String, Object> yaml = readYaml(file);
            return KUBECONFIG_KEYS.stream().anyMatch(yaml::containsKey);
        } catch (Exception ignored) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYaml(File file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file.toPath())) {
            Object value = new Yaml().load(reader);
            if (value instanceof Map) {
                return (Map<String, Object>) value;
            }
            return Collections.emptyMap();
        }
    }

    private File uniqueTarget(String name) {
        File target = new File(kubeConfigDir, name);
        if (!target.exists()) {
            return target;
        }

        String base = name;
        String extension = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            extension = name.substring(dot);
        }

        int index = 2;
        do {
            target = new File(kubeConfigDir, base + "-" + index + extension);
            index++;
        } while (target.exists());
        return target;
    }

    private String safeFileName(String name) {
        String clean = Optional.ofNullable(name).orElse("").replace('\\', '/');
        int slash = clean.lastIndexOf('/');
        if (slash >= 0) {
            clean = clean.substring(slash + 1);
        }
        clean = clean.replaceAll("[^A-Za-z0-9._-]", "_");
        while (clean.startsWith(".")) {
            clean = clean.substring(1);
        }
        return clean;
    }

    private void resetApis() {
        apiClient = null;
        coreV1Api = null;
        customObjectsApi = null;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
