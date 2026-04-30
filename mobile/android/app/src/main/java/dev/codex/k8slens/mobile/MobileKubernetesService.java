package dev.codex.k8slens.mobile;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1NamespaceList;
import io.kubernetes.client.openapi.models.V1PodList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

class MobileKubernetesService {
    private final MobileKubernetesClient client;
    private final MobilePodMapper mapper = new MobilePodMapper();

    MobileKubernetesService(MobileKubernetesClient client) {
        this.client = client;
    }

    List<String> listNamespaces() throws ApiException {
        try {
            V1NamespaceList namespaces = api().listNamespace(
                    null, null, null, null, null, null, null, null, null, null, null);
            return Optional.ofNullable(namespaces.getItems()).orElse(Collections.emptyList()).stream()
                    .map(V1Namespace::getMetadata)
                    .filter(Objects::nonNull)
                    .map(metadata -> metadata.getName())
                    .filter(this::hasText)
                    .sorted()
                    .collect(Collectors.toList());
        } catch (ApiException ex) {
            if (ex.getCode() == 403) {
                return client.activeNamespace()
                        .map(Collections::singletonList)
                        .orElseGet(Collections::emptyList);
            }
            throw ex;
        }
    }

    List<PodSummary> listPods(String namespace) throws ApiException {
        if (hasText(namespace)) {
            return mapper.summarize(api().listNamespacedPod(
                    namespace, null, null, null, null, null, null, null, null, null, null, null)
                    .getItems());
        }

        try {
            V1PodList pods = api().listPodForAllNamespaces(
                    null, null, null, null, null, null, null, null, null, null, null);
            return mapper.summarize(pods.getItems());
        } catch (ApiException ex) {
            if (ex.getCode() == 403 && client.activeNamespace().isPresent()) {
                return mapper.summarize(api().listNamespacedPod(
                        client.activeNamespace().get(),
                        null, null, null, null, null, null, null, null, null, null, null)
                        .getItems());
            }
            throw ex;
        }
    }

    PodDetails getPod(String namespace, String name) throws ApiException {
        return mapper.toDetails(api().readNamespacedPod(name, namespace, null));
    }

    String readLogs(String namespace, String name, String container, Integer tailLines, boolean previous) throws ApiException {
        int effectiveTailLines = tailLines == null ? 300 : tailLines;
        return api().readNamespacedPodLog(
                name,
                namespace,
                hasText(container) ? container : null,
                false,
                null,
                null,
                null,
                previous,
                null,
                effectiveTailLines,
                null);
    }

    private CoreV1Api api() {
        return client.coreV1Api();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
