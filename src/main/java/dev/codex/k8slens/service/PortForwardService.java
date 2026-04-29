package dev.codex.k8slens.service;

import dev.codex.k8slens.model.PortForwardRequest.PortMapping;
import dev.codex.k8slens.model.PortForwardSession;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class PortForwardService {

    private final KubernetesClientProvider clientProvider;
    private final ConcurrentMap<String, ForwardProcess> processes = new ConcurrentHashMap<>();

    public PortForwardService(KubernetesClientProvider clientProvider) {
        this.clientProvider = clientProvider;
    }

    public PortForwardSession start(String namespace, String podName, int remotePort, Integer requestedLocalPort) {
        return start(namespace, podName, remotePort, requestedLocalPort, false);
    }

    public PortForwardSession start(
            String namespace,
            String podName,
            int remotePort,
            Integer requestedLocalPort,
            boolean https) {
        validatePort(remotePort, "Remote port");
        if (requestedLocalPort != null) {
            validatePort(requestedLocalPort, "Local port");
        }

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
            PortForwardSession session = new PortForwardSession(id, namespace, podName, localPort, remotePort, https);
            processes.put(id, new ForwardProcess(process, session));
            return session;
        } catch (IOException ex) {
            throw new PortForwardException("Cannot start kubectl port-forward: " + ex.getMessage(), ex);
        }
    }

    public List<PortForwardSession> start(
            String namespace,
            String podName,
            List<PortMapping> mappings,
            boolean https) {
        if (mappings == null || mappings.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one port");
        }

        validateRequestedLocalPorts(mappings);

        List<PortForwardSession> sessions = new ArrayList<>();
        try {
            for (PortMapping mapping : mappings) {
                if (mapping == null) {
                    throw new IllegalArgumentException("Port mapping cannot be empty");
                }
                sessions.add(start(namespace, podName, mapping.getRemotePort(), mapping.getLocalPort(), https));
            }
            return sessions;
        } catch (RuntimeException ex) {
            sessions.forEach(session -> stop(session.getId()));
            throw ex;
        }
    }

    public List<PortForwardSession> list() {
        pruneStoppedProcesses();
        return processes.values().stream()
                .map(forward -> forward.session)
                .sorted(Comparator
                        .comparing(PortForwardSession::getNamespace)
                        .thenComparing(PortForwardSession::getPodName)
                        .thenComparingInt(PortForwardSession::getLocalPort))
                .collect(Collectors.toList());
    }

    public void stopAll() {
        processes.forEach((id, forward) -> stopProcess(id, forward));
    }

    public boolean stop(String id) {
        ForwardProcess forward = processes.get(id);
        if (forward == null) {
            pruneStoppedProcesses();
            return false;
        }

        stopProcess(id, forward);
        return true;
    }

    private void stopByLocalPort(int localPort) {
        processes.forEach((id, forward) -> {
            if (forward.session.getLocalPort() == localPort || !forward.process.isAlive()) {
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

    private void validatePort(int port, String label) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(label + " must be between 1 and 65535");
        }
    }

    private void validateRequestedLocalPorts(
            List<PortMapping> mappings) {
        Set<Integer> localPorts = new HashSet<>();
        for (PortMapping mapping : mappings) {
            if (mapping == null) {
                throw new IllegalArgumentException("Port mapping cannot be empty");
            }
            if (mapping.getLocalPort() == null) {
                continue;
            }

            validatePort(mapping.getLocalPort(), "Local port");
            if (!localPorts.add(mapping.getLocalPort())) {
                throw new IllegalArgumentException("Local port " + mapping.getLocalPort() + " is used more than once");
            }
        }
    }

    private void pruneStoppedProcesses() {
        processes.forEach((id, forward) -> {
            if (!forward.process.isAlive()) {
                processes.remove(id, forward);
            }
        });
    }

    public static class PortForwardException extends RuntimeException {
        public PortForwardException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static class ForwardProcess {
        private final Process process;
        private final PortForwardSession session;

        private ForwardProcess(Process process, PortForwardSession session) {
            this.process = process;
            this.session = session;
        }
    }
}
