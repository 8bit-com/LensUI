package dev.codex.k8slens.service;

import dev.codex.k8slens.config.KubernetesLensProperties;
import dev.codex.k8slens.model.PodDetails;
import dev.codex.k8slens.model.PodSummary;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1NamespaceList;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class KubernetesLensService {

    private final KubernetesClientProvider clientProvider;
    private final KubernetesLensProperties properties;
    private final KubernetesPodMapper podMapper;

    public KubernetesLensService(
            KubernetesClientProvider clientProvider,
            KubernetesLensProperties properties,
            KubernetesPodMapper podMapper) {
        this.clientProvider = clientProvider;
        this.properties = properties;
        this.podMapper = podMapper;
    }

    public List<String> listNamespaces() {
        List<String> configuredNamespaces = configuredNamespaces();
        if (!configuredNamespaces.isEmpty()) {
            return configuredNamespaces;
        }

        try {
            V1NamespaceList namespaces = api().listNamespace(
                    null, null, null, null, null, null, null, null, null, null, null);
            return Optional.ofNullable(namespaces.getItems()).orElse(Collections.emptyList()).stream()
                    .map(V1Namespace::getMetadata)
                    .filter(Objects::nonNull)
                    .map(metadata -> metadata.getName())
                    .filter(StringUtils::hasText)
                    .sorted()
                    .collect(Collectors.toList());
        } catch (ApiException ex) {
            if (ex.getCode() == 403) {
                return clientProvider.activeNamespace()
                        .map(Collections::singletonList)
                        .orElseGet(Collections::emptyList);
            }
            throw new KubernetesApiRuntimeException(ex);
        }
    }

    public List<PodSummary> listPods(String namespace) {
        try {
            if (StringUtils.hasText(namespace)) {
                return podMapper.summarize(api().listNamespacedPod(
                        namespace, null, null, null, null, null, null, null, null, null, null, null)
                        .getItems());
            }

            List<String> configuredNamespaces = configuredNamespaces();
            if (configuredNamespaces.isEmpty()) {
                try {
                    V1PodList pods = api().listPodForAllNamespaces(
                            null, null, null, null, null, null, null, null, null, null, null);
                    return podMapper.summarize(pods.getItems());
                } catch (ApiException ex) {
                    if (ex.getCode() == 403 && clientProvider.activeNamespace().isPresent()) {
                        return podMapper.summarize(api().listNamespacedPod(
                                clientProvider.activeNamespace().get(),
                                null, null, null, null, null, null, null, null, null, null, null)
                                .getItems());
                    }
                    throw ex;
                }
            }

            List<PodSummary> pods = new ArrayList<>();
            for (String configuredNamespace : configuredNamespaces) {
                pods.addAll(podMapper.summarize(api().listNamespacedPod(
                        configuredNamespace, null, null, null, null, null, null, null, null, null, null, null)
                        .getItems()));
            }
            return pods.stream()
                    .sorted(Comparator.comparing(PodSummary::getNamespace).thenComparing(PodSummary::getName))
                    .collect(Collectors.toList());
        } catch (ApiException ex) {
            throw new KubernetesApiRuntimeException(ex);
        }
    }

    public PodDetails getPod(String namespace, String name) {
        try {
            V1Pod pod = api().readNamespacedPod(name, namespace, null);
            return podMapper.toDetails(pod);
        } catch (ApiException ex) {
            throw new KubernetesApiRuntimeException(ex);
        }
    }

    public String readLogs(String namespace, String name, String container, Integer tailLines, boolean previous) {
        Integer effectiveTailLines = tailLines != null ? tailLines : properties.getLogs().getDefaultTailLines();

        try {
            return api().readNamespacedPodLog(
                    name,
                    namespace,
                    container,
                    false,
                    null,
                    null,
                    null,
                    previous,
                    null,
                    effectiveTailLines,
                    null);
        } catch (ApiException ex) {
            throw new KubernetesApiRuntimeException(ex);
        }
    }

    private CoreV1Api api() {
        return clientProvider.coreV1Api();
    }

    private List<String> configuredNamespaces() {
        return Optional.ofNullable(properties.getNamespaces()).orElse(Collections.emptyList()).stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .sorted()
                .collect(Collectors.toList());
    }

    public static class KubernetesApiRuntimeException extends RuntimeException {
        private final ApiException apiException;

        public KubernetesApiRuntimeException(ApiException apiException) {
            super(apiException);
            this.apiException = apiException;
        }

        public ApiException getApiException() {
            return apiException;
        }
    }
}
