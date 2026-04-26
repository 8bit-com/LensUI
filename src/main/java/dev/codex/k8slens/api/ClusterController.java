package dev.codex.k8slens.api;

import dev.codex.k8slens.model.KubeConfigSummary;
import dev.codex.k8slens.model.PodDetails;
import dev.codex.k8slens.model.PodSummary;
import dev.codex.k8slens.model.PortForwardRequest;
import dev.codex.k8slens.model.PortForwardSession;
import dev.codex.k8slens.service.KubernetesClientProvider;
import dev.codex.k8slens.service.KubernetesLensService;
import dev.codex.k8slens.service.PortForwardService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.constraints.Min;
import java.util.List;

@RestController
@RequestMapping("/api")
public class ClusterController {

    private final KubernetesLensService service;
    private final KubernetesClientProvider clientProvider;
    private final PortForwardService portForwardService;

    public ClusterController(
            KubernetesLensService service,
            KubernetesClientProvider clientProvider,
            PortForwardService portForwardService) {
        this.service = service;
        this.clientProvider = clientProvider;
        this.portForwardService = portForwardService;
    }

    @GetMapping("/kubeconfigs")
    public List<KubeConfigSummary> kubeConfigs() {
        return clientProvider.listKubeConfigs();
    }

    @PostMapping("/kubeconfigs/{name}/activate")
    public List<KubeConfigSummary> activateKubeConfig(@PathVariable String name) {
        clientProvider.activateKubeConfig(name);
        return clientProvider.listKubeConfigs();
    }

    @GetMapping("/namespaces")
    public List<String> namespaces() {
        return service.listNamespaces();
    }

    @GetMapping("/pods")
    public List<PodSummary> pods(@RequestParam(required = false) String namespace) {
        return service.listPods(namespace);
    }

    @GetMapping("/pods/{namespace}/{name}")
    public PodDetails pod(@PathVariable String namespace, @PathVariable String name) {
        return service.getPod(namespace, name);
    }

    @GetMapping(value = "/pods/{namespace}/{name}/logs", produces = MediaType.TEXT_PLAIN_VALUE)
    public String logs(
            @PathVariable String namespace,
            @PathVariable String name,
            @RequestParam(required = false) String container,
            @RequestParam(required = false) @Min(1) Integer tailLines,
            @RequestParam(defaultValue = "false") boolean previous) {
        return service.readLogs(namespace, name, container, tailLines, previous);
    }

    @PostMapping("/pods/{namespace}/{name}/port-forward")
    public PortForwardSession portForward(
            @PathVariable String namespace,
            @PathVariable String name,
            @RequestBody PortForwardRequest request) {
        return portForwardService.start(namespace, name, request.getRemotePort(), request.getLocalPort());
    }
}
