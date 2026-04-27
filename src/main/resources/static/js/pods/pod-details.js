async function showPodDetails(namespace, name) {
    setStatus("loading", "Loading pod details");
    const response = await api(`/api/pods/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}`);
    state.detailsPod = await response.json();
    renderPodDetails();
    renderPods();
    setStatus("ok", "Ready");
}

function renderPodDetails() {
    const pod = state.detailsPod;
    if (!pod || !els.podDetailsPanel) {
        closePodDetails();
        return;
    }

    els.lens.classList.add("details-open");
    els.podDetailsPanel.classList.remove("hidden");
    els.detailsTitle.textContent = `Pod: ${pod.name}`;
    const containers = pod.containerDetails && pod.containerDetails.length > 0 ? pod.containerDetails : pod.containers || [];
    const primaryContainer = containers[0] || {};
    const primaryPorts = (pod.ports || []).filter(port => port.containerName === primaryContainer.name);

    els.detailsProperties.innerHTML = [
        detailRow("Status", `${String(pod.phase || "").toLowerCase()}, ${primaryContainer.ready ? "ready" : "not ready"}`, "status-running"),
        detailRow("Last Status", primaryContainer.lastState || ""),
        detailRowHtml("Image", codeValue(primaryContainer.image || "")),
        detailRow("ImagePullPolicy", primaryContainer.imagePullPolicy || ""),
        detailRowHtml("Ports", portsCell(pod, primaryPorts)),
        detailRow("Environment", listSummary(primaryContainer.environment, "Environmental Variables")),
        detailRowHtml("Mounts", detailsList(primaryContainer.mounts || [])),
        detailRow("Requests", primaryContainer.requests || ""),
        detailRow("Limits", primaryContainer.limits || "")
    ].join("");

    els.detailsContainers.innerHTML = [
        detailRow("Created", pod.createdAt || pod.age),
        detailRow("Name", pod.name),
        detailRow("Namespace", pod.namespace),
        detailRow("Labels", `${Object.keys(pod.labels || {}).length} Labels`),
        detailRow("Annotations", `${Object.keys(pod.annotations || {}).length} Annotations`),
        detailRow("Controlled By", pod.controlledBy || ""),
        detailRow("Status", pod.phase, statusClass(pod)),
        detailRow("Node", pod.nodeName),
        detailRow("Pod IP", pod.podIp),
        detailRow("Pod IPs", (pod.podIps || []).join(", ") || pod.podIp),
        detailRow("Host IP", pod.hostIp || ""),
        detailRow("Service Account", pod.serviceAccount || "default"),
        detailRow("QoS Class", pod.qosClass || ""),
        detailRow("Ready", pod.ready),
        detailRow("Restarts", pod.restarts)
    ].join("");

    els.detailsConditions.innerHTML = listRows(pod.conditions || []);
    els.detailsLabels.innerHTML = keyValueRows(pod.labels || {});
    els.detailsAnnotations.innerHTML = keyValueRows(pod.annotations || {});
}

function closePodDetails() {
    state.detailsPod = null;
    els.lens.classList.remove("details-open");
    if (els.podDetailsPanel) {
        els.podDetailsPanel.classList.add("hidden");
    }
    renderPods();
}

function detailRow(label, value, valueClass = "") {
    return `<div class="details-row">
        <span>${escapeHtml(label)}</span>
        <span class="${escapeHtml(valueClass)}">${escapeHtml(value ?? "").replace(/\n/g, "<br>")}</span>
    </div>`;
}

function detailRowHtml(label, html, valueClass = "") {
    return `<div class="details-row">
        <span>${escapeHtml(label)}</span>
        <span class="${escapeHtml(valueClass)}">${html}</span>
    </div>`;
}

function codeValue(value) {
    return value ? `<span class="details-code">${escapeHtml(value)}</span>` : "";
}

function listSummary(values, noun) {
    const count = (values || []).length;
    if (count === 0) {
        return "";
    }
    return `${count} ${noun}`;
}

function listRows(values) {
    if (!values || values.length === 0) {
        return `<div class="details-row"><span></span><span class="details-muted">No items</span></div>`;
    }
    return values.map((value, index) => detailRow(String(index + 1), value)).join("");
}

function keyValueRows(values) {
    const entries = Object.entries(values || {});
    if (entries.length === 0) {
        return `<div class="details-row"><span></span><span class="details-muted">No items</span></div>`;
    }
    return entries.map(([key, value]) => detailRow(key, value)).join("");
}

function portsCell(pod, ports) {
    if (!ports || ports.length === 0) {
        return `<span class="details-muted">No ports</span>`;
    }
    return `<div class="port-forward-list">${ports.map(port => portRow(pod, port)).join("")}</div>`;
}

function detailsList(values) {
    if (!values || values.length === 0) {
        return "";
    }
    return values.map(value => `<span class="details-code">${escapeHtml(value)}</span>`).join("<br>");
}

function portRow(pod, port) {
    const label = `${port.name ? `${port.name} ` : ""}${port.containerPort}/${port.protocol || "TCP"}`;
    return `<div class="port-forward-item">
        <button class="port-forward-link" type="button" data-namespace="${escapeHtml(pod.namespace)}" data-pod="${escapeHtml(pod.name)}" data-port="${escapeHtml(port.containerPort)}">
            ${escapeHtml(label)}
        </button>
        <button class="port-forward-button" type="button" data-namespace="${escapeHtml(pod.namespace)}" data-pod="${escapeHtml(pod.name)}" data-port="${escapeHtml(port.containerPort)}">
            Forward...
        </button>
    </div>`;
}

async function openDetailsLogs() {
    if (!state.detailsPod) {
        return;
    }

    const existing = state.logTabs.find(tab => tab.pod &&
        tab.pod.namespace === state.detailsPod.namespace &&
        tab.pod.name === state.detailsPod.name);
    if (existing) {
        switchLogTab(existing.id);
    } else {
        const current = activeLogTab();
        if (current && current.pod) {
            createLogTab({ silent: true });
        }
        await selectPod(state.detailsPod.namespace, state.detailsPod.name);
    }
    setLogsCollapsed(false);
}
