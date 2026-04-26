package dev.codex.k8slens.service;

import dev.codex.k8slens.model.PortForwardSession;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class PortForwardService {

    private final KubernetesClientProvider clientProvider;
    private final ConcurrentMap<String, Process> processes = new ConcurrentHashMap<>();

    public PortForwardService(KubernetesClientProvider clientProvider) {
        this.clientProvider = clientProvider;
    }

    public PortForwardSession start(String namespace, String podName, int remotePort, Integer requestedLocalPort) {
        int localPort = requestedLocalPort == null ? randomLocalPort() : requestedLocalPort;
        String id = UUID.randomUUID().toString();
        List<String> command = new ArrayList<>();
        command.add("kubectl");
        clientProvider.activeKubeConfigFile()
                .map(Path::toString)
                .ifPresent(path -> {
                    command.add("--kubeconfig");
                    command.add(path);
                });
        command.add("-n");
        command.add(namespace);
        command.add("port-forward");
        command.add("pod/" + podName);
        command.add(localPort + ":" + remotePort);

        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            processes.put(id, process);
            return new PortForwardSession(id, namespace, podName, localPort, remotePort);
        } catch (IOException ex) {
            throw new PortForwardException("Cannot start kubectl port-forward: " + ex.getMessage(), ex);
        }
    }

    private int randomLocalPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new PortForwardException("Cannot allocate random local port: " + ex.getMessage(), ex);
        }
    }

    public static class PortForwardException extends RuntimeException {
        public PortForwardException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
