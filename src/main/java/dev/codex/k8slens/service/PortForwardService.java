package dev.codex.k8slens.service;

import dev.codex.k8slens.model.PortForwardSession;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

@Service
public class PortForwardService {

    private final KubernetesClientProvider clientProvider;
    private final ConcurrentMap<String, ForwardProcess> processes = new ConcurrentHashMap<>();

    public PortForwardService(KubernetesClientProvider clientProvider) {
        this.clientProvider = clientProvider;
    }

    public PortForwardSession start(String namespace, String podName, int remotePort, Integer requestedLocalPort) {
        int localPort = requestedLocalPort == null ? randomLocalPort() : requestedLocalPort;
        stopByLocalPort(localPort);

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
            ensureStarted(process);
            processes.put(id, new ForwardProcess(process, localPort));
            return new PortForwardSession(id, namespace, podName, localPort, remotePort);
        } catch (IOException ex) {
            throw new PortForwardException("Cannot start kubectl port-forward: " + ex.getMessage(), ex);
        }
    }

    public void stopAll() {
        processes.forEach((id, forward) -> stopProcess(id, forward));
    }

    private void stopByLocalPort(int localPort) {
        processes.forEach((id, forward) -> {
            if (forward.localPort == localPort || !forward.process.isAlive()) {
                stopProcess(id, forward);
            }
        });
    }

    private void stopProcess(String id, ForwardProcess forward) {
        processes.remove(id, forward);
        if (!forward.process.isAlive()) {
            return;
        }

        forward.process.destroy();
        try {
            if (!forward.process.waitFor(2, TimeUnit.SECONDS)) {
                forward.process.destroyForcibly();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            forward.process.destroyForcibly();
        }
    }

    private void ensureStarted(Process process) throws IOException {
        try {
            Thread.sleep(500);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new PortForwardException("Interrupted while starting port-forward", ex);
        }

        if (!process.isAlive()) {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            throw new PortForwardException(
                    output.isEmpty() ? "kubectl port-forward exited immediately" : output,
                    null);
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

    private static class ForwardProcess {
        private final Process process;
        private final int localPort;

        private ForwardProcess(Process process, int localPort) {
            this.process = process;
            this.localPort = localPort;
        }
    }
}
