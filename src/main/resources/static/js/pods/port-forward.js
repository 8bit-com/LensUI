function openPortForwardDialog(namespace, podName, remotePort) {
    const selectedRemotePort = Number(remotePort);
    state.pendingPortForward = {
        namespace,
        podName,
        remotePort: selectedRemotePort,
        ports: portForwardCandidates(namespace, podName, selectedRemotePort)
    };

    els.portForwardPodName.textContent = podName;
    els.portForwardHttps.checked = false;
    els.portForwardOpenBrowser.checked = true;
    renderPortForwardRows();
    els.portForwardModal.classList.remove("hidden");

    const firstLocalPort = els.portForwardRows.querySelector(".port-forward-local-input");
    if (firstLocalPort) {
        firstLocalPort.focus();
    }
}

function closePortForwardDialog() {
    state.pendingPortForward = null;
    if (els.portForwardModal) {
        els.portForwardModal.classList.add("hidden");
    }
}

async function openPortForwardsDialog() {
    if (els.portForwardsModal) {
        els.portForwardsModal.classList.remove("hidden");
    }
    await loadPortForwards();
}

function closePortForwardsDialog() {
    if (els.portForwardsModal) {
        els.portForwardsModal.classList.add("hidden");
    }
}

function portForwardCandidates(namespace, podName, selectedRemotePort) {
    const pod = state.detailsPod &&
        state.detailsPod.namespace === namespace &&
        state.detailsPod.name === podName
        ? state.detailsPod
        : null;
    const seen = new Set();
    const candidates = [];

    (pod?.ports || []).forEach(port => {
        const remotePort = Number(port.containerPort);
        if (!Number.isInteger(remotePort) || remotePort < 1 || seen.has(remotePort)) {
            return;
        }

        seen.add(remotePort);
        candidates.push({
            remotePort,
            name: port.name || "",
            protocol: port.protocol || "TCP",
            containerName: port.containerName || ""
        });
    });

    if (Number.isInteger(selectedRemotePort) && selectedRemotePort > 0 && !seen.has(selectedRemotePort)) {
        candidates.unshift({
            remotePort: selectedRemotePort,
            name: "",
            protocol: "TCP",
            containerName: ""
        });
    }

    return candidates;
}

function renderPortForwardRows() {
    const pending = state.pendingPortForward;
    if (!pending || !els.portForwardRows) {
        return;
    }

    els.portForwardRows.innerHTML = pending.ports.map(port => {
        const checked = port.remotePort === pending.remotePort || pending.ports.length === 1;
        const label = `${port.name ? `${port.name} ` : ""}${port.remotePort}/${port.protocol || "TCP"}`;
        const containerName = port.containerName
            ? `<span class="port-forward-container">${escapeHtml(port.containerName)}</span>`
            : "";

        return `<div class="port-forward-row">
            <label class="port-forward-select">
                <input class="port-forward-port-check" type="checkbox" data-remote-port="${escapeHtml(port.remotePort)}" ${checked ? "checked" : ""}>
                <span class="port-forward-remote">${escapeHtml(label)}</span>
                ${containerName}
            </label>
            <input class="port-forward-local-input" type="number" min="1" max="65535" placeholder="Random" data-remote-port="${escapeHtml(port.remotePort)}" aria-label="Local port for ${escapeHtml(label)}">
        </div>`;
    }).join("");
}

async function startPortForward() {
    const pending = state.pendingPortForward;
    if (!pending) {
        return;
    }

    let mappings;
    try {
        mappings = selectedPortForwardMappings();
    } catch (error) {
        setStatus("error", error.message);
        return;
    }

    const openBrowser = els.portForwardOpenBrowser.checked;
    const https = els.portForwardHttps.checked;

    closePortForwardDialog();
    setStatus("loading", mappings.length === 1 ? "Starting port forward" : "Starting port forwards");
    const response = await api(`/api/pods/${encodeURIComponent(pending.namespace)}/${encodeURIComponent(pending.podName)}/port-forwards`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ ports: mappings, https })
    });
    const sessions = await response.json();
    await loadPortForwards({ silent: true });

    setStatus("ok", portForwardStatusMessage(sessions));
    if (openBrowser) {
        sessions.forEach(session => {
            window.open(session.url, "_blank", "noopener");
        });
    }
}

function selectedPortForwardMappings() {
    const mappings = [];
    const localPorts = new Set();

    Array.from(els.portForwardRows.querySelectorAll(".port-forward-row")).forEach(row => {
        const checkbox = row.querySelector(".port-forward-port-check");
        if (!checkbox || !checkbox.checked) {
            return;
        }

        const remotePort = Number(checkbox.dataset.remotePort);
        const input = row.querySelector(".port-forward-local-input");
        const localValue = input ? input.value.trim() : "";
        const localPort = localValue ? Number(localValue) : null;

        validatePortNumber(remotePort, "Remote port");
        if (localPort !== null) {
            validatePortNumber(localPort, "Local port");
            if (localPorts.has(localPort)) {
                throw new Error(`Local port ${localPort} is used more than once`);
            }
            localPorts.add(localPort);
        }

        mappings.push({ remotePort, localPort });
    });

    if (mappings.length === 0) {
        throw new Error("Choose at least one port");
    }

    return mappings;
}

function validatePortNumber(port, label) {
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
        throw new Error(`${label} must be between 1 and 65535`);
    }
}

function portForwardStatusMessage(sessions) {
    if (!Array.isArray(sessions) || sessions.length === 0) {
        return "No port forwards started";
    }

    if (sessions.length === 1) {
        const session = sessions[0];
        return `Forward ${session.localPort}:${session.remotePort}`;
    }

    return `Forwarded ${sessions.length} ports`;
}

async function loadPortForwards(options = {}) {
    const silent = Boolean(options.silent);
    if (state.portForwardsLoading) {
        return;
    }

    state.portForwardsLoading = true;
    if (!silent && isPortForwardsDialogOpen()) {
        setStatus("loading", "Loading port forwards");
    }

    try {
        const response = await api("/api/port-forwards");
        state.portForwards = await response.json();
        renderPortForwards();

        if (!silent && isPortForwardsDialogOpen()) {
            setStatus("ok", "Ready");
        }
    } catch (error) {
        if (silent) {
            console.error(error);
            return;
        }
        throw error;
    } finally {
        state.portForwardsLoading = false;
    }
}

function renderPortForwards() {
    if (!els.portForwardsBody) {
        return;
    }

    const sessions = Array.isArray(state.portForwards) ? state.portForwards : [];
    const count = sessions.length;

    els.portForwardsCount.textContent = String(count);
    els.portForwardsButton.classList.toggle("active", count > 0);
    els.portForwardManagementCount.textContent = `${count} active`;
    els.emptyPortForwards.classList.toggle("hidden", count > 0);
    els.stopAllPortForwardsButton.disabled = count === 0;

    els.portForwardsBody.innerHTML = sessions.map(session => `
        <tr>
            <td>
                <button class="port-forward-link" type="button" data-open-port-forward-url="${escapeHtml(session.url)}">
                    ${escapeHtml(session.localPort)}
                </button>
            </td>
            <td>${escapeHtml(session.remotePort)}</td>
            <td><span class="namespace-link">${escapeHtml(session.namespace)}</span></td>
            <td title="${escapeHtml(session.podName)}">${escapeHtml(session.podName)}</td>
            <td>
                <button class="port-forward-url" type="button" data-open-port-forward-url="${escapeHtml(session.url)}">
                    ${escapeHtml(session.url)}
                </button>
            </td>
            <td class="actions-col">
                <button class="port-forward-table-button" type="button" data-stop-port-forward-id="${escapeHtml(session.id)}">Stop</button>
            </td>
        </tr>
    `).join("");
}

async function stopPortForward(id) {
    setStatus("loading", "Stopping port forward");
    const response = await api(`/api/port-forwards/${encodeURIComponent(id)}`, {
        method: "DELETE"
    });
    state.portForwards = await response.json();
    renderPortForwards();
    setStatus("ok", "Ready");
}

async function stopAllPortForwards() {
    if (!state.portForwards.length) {
        return;
    }

    setStatus("loading", "Stopping port forwards");
    const response = await api("/api/port-forwards", {
        method: "DELETE"
    });
    state.portForwards = await response.json();
    renderPortForwards();
    setStatus("ok", "Ready");
}

function startPortForwardsAutoRefresh() {
    stopPortForwardsAutoRefresh();
    state.portForwardsRefreshTimer = window.setInterval(() => {
        loadPortForwards({ silent: true }).catch(console.error);
    }, 5000);
}

function stopPortForwardsAutoRefresh() {
    if (state.portForwardsRefreshTimer) {
        clearInterval(state.portForwardsRefreshTimer);
        state.portForwardsRefreshTimer = null;
    }
}

function isPortForwardsDialogOpen() {
    return els.portForwardsModal && !els.portForwardsModal.classList.contains("hidden");
}
