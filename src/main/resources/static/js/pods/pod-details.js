async function showPodDetails(namespace, name) {
    setStatus("loading", "Loading pod details");
    const response = await api(`/api/pods/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}`);
    state.detailsPod = await response.json();
    renderPodDetails();
    loadPodMetrics(namespace, name).catch(console.error);
    renderPods();
    if (isMobileLayout()) {
        setMobileView("details");
    }
    setStatus("ok", "Ready");
}

function renderPodDetails(options = {}) {
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

    if (!options.keepMetrics) {
        renderPodMetricsLoading();
    }

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
    state.metricsRequestVersion++;
    els.lens.classList.remove("details-open");
    if (els.podDetailsPanel) {
        els.podDetailsPanel.classList.add("hidden");
    }
    if (isMobileLayout() && state.mobileView === "details") {
        setMobileView("pods");
    } else {
        setMobileView(state.mobileView);
    }
    renderPods();
}

async function loadPodMetrics(namespace, name, options = {}) {
    const requestVersion = ++state.metricsRequestVersion;
    if (!options.silent) {
        renderPodMetricsLoading();
    }

    try {
        const response = await api(`/api/pods/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}/metrics`);
        const metrics = await response.json();

        if (requestVersion !== state.metricsRequestVersion || !isCurrentDetailsPod(namespace, name)) {
            return;
        }

        renderPodMetrics(metrics);
    } catch (error) {
        if (requestVersion !== state.metricsRequestVersion || !isCurrentDetailsPod(namespace, name)) {
            return;
        }

        renderPodMetricsError(error.message);
    }
}

function renderPodMetricsLoading() {
    if (!els.metricsPanel) {
        return;
    }

    els.metricsPanel.classList.remove("metrics-ready", "metrics-error");
    els.metricsPanel.innerHTML = `<div class="metrics-spinner">( )</div>`;
}

function renderPodMetrics(metrics) {
    if (!els.metricsPanel) {
        return;
    }

    const containers = metrics.containers || [];
    els.metricsPanel.classList.add("metrics-ready");
    els.metricsPanel.classList.remove("metrics-error");
    els.metricsPanel.innerHTML = `
        <div class="metrics-summary">
            <div class="metric-card">
                <span>CPU</span>
                <strong>${escapeHtml(metrics.cpu || "0 mCPU")}</strong>
            </div>
            <div class="metric-card">
                <span>Memory</span>
                <strong>${escapeHtml(metrics.memory || "0 B")}</strong>
            </div>
        </div>
        <div class="metrics-table">
            <div class="metrics-row metrics-head">
                <span>Container</span>
                <span>CPU</span>
                <span>Memory</span>
            </div>
            ${containers.length ? containers.map(container => `
                <div class="metrics-row">
                    <span title="${escapeHtml(container.name)}">${escapeHtml(container.name || "container")}</span>
                    <span>${escapeHtml(container.cpu || "0 mCPU")}</span>
                    <span>${escapeHtml(container.memory || "0 B")}</span>
                </div>
            `).join("") : `<div class="metrics-empty">No metrics found</div>`}
        </div>
        <div class="metrics-updated">${escapeHtml(metrics.timestamp || "")}</div>
    `;
}

function renderPodMetricsError(message) {
    if (!els.metricsPanel) {
        return;
    }

    const metricsError = readableMetricsError(message);
    els.metricsPanel.classList.add("metrics-error");
    els.metricsPanel.classList.remove("metrics-ready");
    els.metricsPanel.innerHTML = `
        <div class="metrics-error-message">
            <strong>${escapeHtml(metricsError.title)}</strong>
            <span>${escapeHtml(metricsError.description)}</span>
            ${metricsError.hint ? `<span class="metrics-error-hint">${escapeHtml(metricsError.hint)}</span>` : ""}
        </div>
    `;
}

function readableMetricsError(message) {
    const text = kubernetesStatusMessage(message);

    if (/metrics\.k8s\.io/i.test(text) && /forbidden|cannot get resource/i.test(text)) {
        const user = quotedMatch(text, /User "([^"]+)"/);
        const namespace = quotedMatch(text, /namespace "([^"]+)"/);
        const subject = user || "Current user";
        const scope = namespace ? ` in namespace ${namespace}` : "";

        return {
            title: "Metrics access denied",
            description: `${subject} cannot read pod metrics${scope}.`,
            hint: "Grant get/list on pods.metrics.k8s.io or switch to a kubeconfig with that permission."
        };
    }

    if (/metrics\.k8s\.io/i.test(text) && /not found|could not find|404/i.test(text)) {
        return {
            title: "Metrics API unavailable",
            description: "The cluster did not return pod metrics.",
            hint: "Install or enable metrics-server, then retry."
        };
    }

    return {
        title: "Metrics unavailable",
        description: text || "Metrics API is not available.",
        hint: ""
    };
}

function kubernetesStatusMessage(message) {
    const text = String(message || "").trim();
    if (!text) {
        return "";
    }

    try {
        const status = JSON.parse(text);
        return status.message || text;
    } catch {
        return text;
    }
}

function quotedMatch(text, pattern) {
    const match = text.match(pattern);
    return match ? match[1] : "";
}

function isCurrentDetailsPod(namespace, name) {
    return state.detailsPod &&
        state.detailsPod.namespace === namespace &&
        state.detailsPod.name === name;
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
    if (isMobileLayout()) {
        setMobileView("logs");
    }
}
