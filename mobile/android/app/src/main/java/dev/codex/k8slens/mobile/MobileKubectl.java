package dev.codex.k8slens.mobile;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class MobileKubectl {
    private final MobileKubernetesClient client;
    private final File executable;

    MobileKubectl(Context context, MobileKubernetesClient client) {
        this.client = client;
        this.executable = findExecutable(context);
    }

    boolean isAvailable() {
        return executable != null && executable.isFile() && executable.canExecute();
    }

    String executablePath() {
        return executable == null ? "" : executable.getAbsolutePath();
    }

    Process startPortForward(String namespace, String podName, int localPort, int remotePort) throws IOException {
        if (!isAvailable()) {
            throw new IOException("Bundled Android kubectl binary was not found");
        }

        File kubeConfig = client.kubectlKubeConfigFile();
        List<String> command = new ArrayList<>();
        command.add(executable.getAbsolutePath());
        command.add("--kubeconfig");
        command.add(kubeConfig.getAbsolutePath());
        command.add("-n");
        command.add(namespace);
        command.add("port-forward");
        command.add("--address");
        command.add("127.0.0.1");
        command.add("pod/" + podName);
        command.add(localPort + ":" + remotePort);

        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("HOME", kubeConfig.getParentFile().getAbsolutePath());
        env.put("TMPDIR", kubeConfig.getParentFile().getAbsolutePath());
        env.put("KUBECONFIG", kubeConfig.getAbsolutePath());
        return builder.start();
    }

    private File findExecutable(Context context) {
        String nativeLibraryDir = context.getApplicationInfo().nativeLibraryDir;
        if (nativeLibraryDir == null || nativeLibraryDir.trim().isEmpty()) {
            return null;
        }

        File libDir = new File(nativeLibraryDir);
        File kubectl = new File(libDir, "libkubectl.so");
        if (kubectl.isFile() && kubectl.setExecutable(true, false)) {
            return kubectl;
        }

        File plain = new File(libDir, "kubectl");
        if (plain.isFile() && plain.setExecutable(true, false)) {
            return plain;
        }

        return kubectl.isFile() ? kubectl : null;
    }
}
