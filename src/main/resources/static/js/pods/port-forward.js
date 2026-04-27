function openPortForwardDialog(namespace, podName, remotePort) {
    state.pendingPortForward = {
        namespace,
        podName,
        remotePort: Number(remotePort)
    };
    els.portForwardPodName.textContent = podName;
    els.portForwardLocalPort.value = "";
    els.portForwardHttps.checked = false;
    els.portForwardOpenBrowser.checked = true;
    els.portForwardModal.classList.remove("hidden");
    els.portForwardLocalPort.focus();
}

function closePortForwardDialog() {
    state.pendingPortForward = null;
    if (els.portForwardModal) {
        els.portForwardModal.classList.add("hidden");
    }
}

async function startPortForward() {
    const pending = state.pendingPortForward;
    if (!pending) {
        return;
    }
    const localValue = els.portForwardLocalPort.value.trim();
    const localPort = localValue ? Number(localValue) : null;
    if (localPort !== null && (!Number.isInteger(localPort) || localPort < 1)) {
        setStatus("error", "Invalid local port");
        return;
    }

    closePortForwardDialog();
    setStatus("loading", "Starting port forward");
    const response = await api(`/api/pods/${encodeURIComponent(pending.namespace)}/${encodeURIComponent(pending.podName)}/port-forward`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ remotePort: pending.remotePort, localPort })
    });
    const session = await response.json();
    setStatus("ok", `Forward ${session.localPort}:${session.remotePort}`);
    if (els.portForwardOpenBrowser.checked) {
        const scheme = els.portForwardHttps.checked ? "https" : "http";
        window.open(`${scheme}://localhost:${session.localPort}`, "_blank", "noopener");
    }
}
