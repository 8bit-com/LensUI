const state = {
    kubeConfigs: [],
    namespaces: [],
    pods: [],
    filteredPods: [],
    detailsPod: null,
    selectedPod: null,
    selectedContainer: "",
    activeConfig: "DEV",
    logsAutoRefresh: true,
    logsRefreshTimer: null,
    logsLoading: false,
    rawLogs: "",
    logMatches: [],
    activeLogMatchIndex: -1,
    currentTailLines: 300,
    logTailStep: 300,
    maxTailLines: 5000,
    logsExhausted: false,
    compactJsonLogs: false,
    pendingPortForward: null,
    previousPodLogsOrigin: null,
    logTabs: [],
    activeLogTabId: "",
    nextLogTabId: 1
};

const els = {
    lens: document.querySelector(".lens"),
    clusterTiles: document.querySelectorAll(".cluster-tile[data-config]"),
    kubeConfigSelect: document.querySelector("#kubeConfigSelect"),
    namespaceSelect: document.querySelector("#namespaceSelect"),
    searchInput: document.querySelector("#searchInput"),
    logSearchInput: document.querySelector("#logSearchInput"),
    refreshButton: document.querySelector("#refreshButton"),
    podsBody: document.querySelector("#podsBody"),
    podDetailsPanel: document.querySelector("#podDetailsPanel"),
    detailsTitle: document.querySelector("#detailsTitle"),
    detailsProperties: document.querySelector("#detailsProperties"),
    detailsContainers: document.querySelector("#detailsContainers"),
    detailsConditions: document.querySelector("#detailsConditions"),
    detailsLabels: document.querySelector("#detailsLabels"),
    detailsAnnotations: document.querySelector("#detailsAnnotations"),
    openLogsButton: document.querySelector("#openLogsButton"),
    closeDetailsButton: document.querySelector("#closeDetailsButton"),
    portForwardModal: document.querySelector("#portForwardModal"),
    portForwardPodName: document.querySelector("#portForwardPodName"),
    portForwardLocalPort: document.querySelector("#portForwardLocalPort"),
    portForwardHttps: document.querySelector("#portForwardHttps"),
    portForwardOpenBrowser: document.querySelector("#portForwardOpenBrowser"),
    cancelPortForwardButton: document.querySelector("#cancelPortForwardButton"),
    startPortForwardButton: document.querySelector("#startPortForwardButton"),
    emptyPods: document.querySelector("#emptyPods"),
    podCount: document.querySelector("#podCount"),
    clusterStatus: document.querySelector("#clusterStatus"),
    clusterText: document.querySelector("#clusterText"),
    logsTabs: document.querySelector("#logsTabs"),
    addLogTabButton: document.querySelector("#addLogTabButton"),
    selectedMeta: document.querySelector("#selectedMeta"),
    containerSelect: document.querySelector("#containerSelect"),
    tailInput: document.querySelector("#tailInput"),
    previousPodLogsButton: document.querySelector("#previousPodLogsButton"),
    compactJsonButton: document.querySelector("#compactJsonButton"),
    autoRefreshButton: document.querySelector("#autoRefreshButton"),
    logRefreshInterval: document.querySelector("#logRefreshInterval"),
    logMatchCounter: document.querySelector("#logMatchCounter"),
    prevLogMatchButton: document.querySelector("#prevLogMatchButton"),
    nextLogMatchButton: document.querySelector("#nextLogMatchButton"),
    expandLogsButton: document.querySelector("#expandLogsButton"),
    collapseLogsButton: document.querySelector("#collapseLogsButton"),
    logsView: document.querySelector("#logsView"),
    pageTitle: document.querySelector("#pageTitle"),
    navigatorClusterName: document.querySelector("#navigatorClusterName"),
    activeConfigBadge: document.querySelector("#activeConfigBadge"),
    statusCluster: document.querySelector("#statusCluster")
};

const UI_STATE_KEY = "k8sLensUiState";

function loadUiState() {
    try {
        return JSON.parse(localStorage.getItem(UI_STATE_KEY)) || {};
    } catch {
        return {};
    }
}

function saveUiState(updates = {}) {
    const current = loadUiState();
    localStorage.setItem(UI_STATE_KEY, JSON.stringify({
        ...current,
        ...updates
    }));
}

function saveLogTabsState() {
    saveActiveLogTab();

    saveUiState({
        logTabs: state.logTabs.map(tab => ({
            id: tab.id,
            pod: tab.pod,
            selectedContainer: tab.selectedContainer,
            currentTailLines: tab.currentTailLines,
            logTailStep: tab.logTailStep,
            logsExhausted: tab.logsExhausted,
            previousPodLogsOrigin: tab.previousPodLogsOrigin
        })),
        activeLogTabId: state.activeLogTabId,
        nextLogTabId: state.nextLogTabId
    });
}

function restoreUiState() {
    const saved = loadUiState();

    if (typeof saved.logsAutoRefresh === "boolean") {
        state.logsAutoRefresh = saved.logsAutoRefresh;
        els.autoRefreshButton.classList.toggle("active", state.logsAutoRefresh);
        els.autoRefreshButton.textContent = state.logsAutoRefresh ? "Live" : "Paused";
    }

    if (typeof saved.compactJsonLogs === "boolean") {
        state.compactJsonLogs = saved.compactJsonLogs;
        els.compactJsonButton.classList.toggle("active", state.compactJsonLogs);
    }

    if (saved.logRefreshInterval) {
        els.logRefreshInterval.value = saved.logRefreshInterval;
    }

    if (saved.tailLines) {
        els.tailInput.value = saved.tailLines;
        state.currentTailLines = Number(saved.tailLines);
        state.logTailStep = Number(saved.tailLines);
    }

    if (saved.logsExpanded) {
        els.lens.classList.add("logs-expanded");
        els.expandLogsButton.textContent = "🗗";
        els.expandLogsButton.title = "Restore logs panel";
    }

    if (saved.logsCollapsed) {
        els.lens.classList.add("logs-collapsed");
        els.collapseLogsButton.textContent = "˄";
        els.collapseLogsButton.title = "Restore logs panel";
    }
}

async function restoreSavedLogTabs() {
    const saved = loadUiState();

    if (!Array.isArray(saved.logTabs) || saved.logTabs.length === 0) {
        createLogTab({ silent: true });
        return;
    }

    state.logTabs = saved.logTabs.map(tab => ({
        id: tab.id,
        pod: tab.pod || null,
        selectedContainer: tab.selectedContainer || "",
        rawLogs: "",
        logMatches: [],
        activeLogMatchIndex: -1,
        currentTailLines: tab.currentTailLines || Math.max(1, Number(els.tailInput.value || 300)),
        logTailStep: tab.logTailStep || Math.max(1, Number(els.tailInput.value || 300)),
        logsExhausted: Boolean(tab.logsExhausted),
        previousPodLogsOrigin: tab.previousPodLogsOrigin || null
    }));

    state.activeLogTabId = saved.activeLogTabId || state.logTabs[0].id;
    state.nextLogTabId = saved.nextLogTabId || state.logTabs.length + 1;

    if (!state.logTabs.some(tab => tab.id === state.activeLogTabId)) {
        state.activeLogTabId = state.logTabs[0].id;
    }

    renderLogTabs();

    const active = activeLogTab();

    if (!active || !active.pod) {
        restoreLogTab(active || state.logTabs[0]);
        return;
    }

    try {
        setStatus("loading", "Loading pod");

        const response = await api(`/api/pods/${encodeURIComponent(active.pod.namespace)}/${encodeURIComponent(active.pod.name)}`);
        const fullPod = await response.json();

        active.pod = fullPod;

        state.selectedPod = fullPod;
        state.selectedContainer = active.selectedContainer || fullPod.containers?.[0]?.name || "";
        state.rawLogs = "";
        state.logMatches = [];
        state.activeLogMatchIndex = -1;
        state.currentTailLines = active.currentTailLines || Math.max(1, Number(els.tailInput.value || 300));
        state.logTailStep = active.logTailStep || state.currentTailLines;
        state.logsExhausted = Boolean(active.logsExhausted);
        state.previousPodLogsOrigin = active.previousPodLogsOrigin || null;

        renderSelectedPod();
        renderPods();

        els.logsView.textContent = "Loading logs...";

        const logs = await fetchCurrentLogs();
        renderLogs(logs);

        updateActiveLogTab({
            pod: fullPod,
            selectedContainer: state.selectedContainer
        });

        restartLogsAutoRefresh();
        setStatus("ok", "Ready");
    } catch (error) {
        els.logsView.textContent = error.message || "Logs loading error";
        setStatus("error", "Logs error");
        console.error(error);
    } finally {
        state.logsLoading = false;
    }
}

initLogsResizer();

async function api(path, options = {}) {
    const response = await fetch(path, options);
    if (!response.ok) {
        const text = await response.text();
        let message = text;
        try {
            message = JSON.parse(text).message || text;
        } catch {
            message = text;
        }
        throw new Error(message || `HTTP ${response.status}`);
    }
    return response;
}

async function loadKubeConfigs() {
    const response = await api("/api/kubeconfigs");
    state.kubeConfigs = await response.json();

    if (state.kubeConfigs.length === 0) {
        els.kubeConfigSelect.innerHTML = `<option value="">Default kubeconfig</option>`;
        els.kubeConfigSelect.disabled = true;
        state.activeConfig = "DEV";
        renderConfigLabels();
        return;
    }

    const active = state.kubeConfigs.find(config => config.active) || state.kubeConfigs[0];
    state.activeConfig = active.name;
    els.kubeConfigSelect.disabled = false;
    els.kubeConfigSelect.innerHTML = state.kubeConfigs.map(config =>
        `<option value="${escapeHtml(config.name)}" ${config.active ? "selected" : ""}>${escapeHtml(config.name)}</option>`
    ).join("");
    renderConfigLabels();
}

async function activateKubeConfig(name) {
    setStatus("loading", "Switching kubeconfig");
    const response = await api(`/api/kubeconfigs/${encodeURIComponent(name)}/activate`, { method: "POST" });
    state.kubeConfigs = await response.json();
    const active = state.kubeConfigs.find(config => config.active);
    state.activeConfig = active ? active.name : name;
    resetLogTabs();
    renderConfigLabels();
    await loadNamespaces();
    await loadPods();
}

async function loadNamespaces() {
    const response = await api("/api/namespaces");
    state.namespaces = await response.json();
    els.namespaceSelect.innerHTML = `<option value="">All namespaces</option>` +
        state.namespaces.map(namespace => `<option value="${escapeHtml(namespace)}">${escapeHtml(namespace)}</option>`).join("");
    if (state.namespaces.length === 1) {
        els.namespaceSelect.value = state.namespaces[0];
    }
}

async function loadPods() {
    setStatus("loading", "Loading pods");
    const namespace = els.namespaceSelect.value;
    const query = namespace ? `?namespace=${encodeURIComponent(namespace)}` : "";
    const response = await api(`/api/pods${query}`);
    state.pods = await response.json();
    filterPods();
    setStatus("ok", "Ready");
}

function filterPods() {
    const needle = els.searchInput.value.trim().toLowerCase();
    state.filteredPods = state.pods.filter(pod => {
        if (!needle) {
            return true;
        }
        return [pod.name, pod.namespace, pod.phase, pod.nodeName, pod.podIp]
            .some(value => String(value || "").toLowerCase().includes(needle));
    });
    renderPods();
}

function renderPods() {
    els.podCount.textContent = `${state.filteredPods.length} item${state.filteredPods.length === 1 ? "" : "s"}`;
    els.emptyPods.classList.toggle("hidden", state.filteredPods.length > 0);
    els.podsBody.innerHTML = state.filteredPods.map(pod => {
        const selected = state.detailsPod &&
            state.detailsPod.namespace === pod.namespace &&
            state.detailsPod.name === pod.name;
        return `<tr class="${selected ? "selected" : ""}" data-namespace="${escapeHtml(pod.namespace)}" data-name="${escapeHtml(pod.name)}">
            <td class="check-col"><input type="checkbox"></td>
            <td title="${escapeHtml(pod.name)}">${escapeHtml(pod.name)}</td>
            <td class="warn-col">${pod.restarts > 0 ? "▲" : ""}</td>
            <td><span class="namespace-link">${escapeHtml(pod.namespace)}</span></td>
            <td>${containerBlocks(pod)}</td>
            <td>${pod.restarts}</td>
            <td><span class="node-link">${escapeHtml(pod.nodeName)}</span></td>
            <td>${escapeHtml(formatAge(pod.age))}</td>
            <td><span class="${statusClass(pod)}">${escapeHtml(pod.phase)}</span></td>
            <td class="menu-col">⋮</td>
        </tr>`;
    }).join("");
    updatePreviousPodLogsButton();
}

function createLogTab(options = {}) {
    saveActiveLogTab();

    const tab = {
        id: `logs-tab-${state.nextLogTabId++}`,
        pod: null,
        selectedContainer: "",
        rawLogs: "",
        logMatches: [],
        activeLogMatchIndex: -1,
        currentTailLines: Math.max(1, Number(els.tailInput.value || 300)),
        logTailStep: Math.max(1, Number(els.tailInput.value || 300)),
        logsExhausted: false,
        previousPodLogsOrigin: null
    };

    state.logTabs.push(tab);
    state.activeLogTabId = tab.id;
    restoreLogTab(tab);
    renderLogTabs();
    saveLogTabsState();

    if (!options.silent) {
        setStatus("ok", "Select a pod");
    }
}

function renderLogTabs() {
    if (!els.logsTabs) {
        return;
    }

    els.logsTabs.innerHTML = state.logTabs.map(tab => {
        const active = tab.id === state.activeLogTabId;
        const label = tab.pod ? `Pod ${tab.pod.name}` : "Select a pod";
        return `<button class="logs-tab ${active ? "active" : ""}" type="button" data-tab-id="${escapeHtml(tab.id)}" title="${escapeHtml(label)}">
            <span class="logs-tab-icon">≡</span>
            <span>${escapeHtml(label)}</span>
            <span class="logs-tab-close" data-close-tab-id="${escapeHtml(tab.id)}">×</span>
        </button>`;
    }).join("");
}

function activeLogTab() {
    return state.logTabs.find(tab => tab.id === state.activeLogTabId) || null;
}

function saveActiveLogTab() {
    const tab = activeLogTab();
    if (!tab) {
        return;
    }

    tab.pod = state.selectedPod;
    tab.selectedContainer = state.selectedContainer;
    tab.rawLogs = state.rawLogs;
    tab.logMatches = state.logMatches.slice();
    tab.activeLogMatchIndex = state.activeLogMatchIndex;
    tab.currentTailLines = state.currentTailLines;
    tab.logTailStep = state.logTailStep;
    tab.logsExhausted = state.logsExhausted;
    tab.previousPodLogsOrigin = state.previousPodLogsOrigin;
}

function restoreLogTab(tab) {
    if (!tab) {
        return;
    }

    state.selectedPod = tab.pod;
    state.selectedContainer = tab.selectedContainer || "";
    state.rawLogs = tab.rawLogs || "";
    state.logMatches = (tab.logMatches || []).slice();
    state.activeLogMatchIndex = tab.activeLogMatchIndex ?? -1;
    state.currentTailLines = tab.currentTailLines || Math.max(1, Number(els.tailInput.value || 300));
    state.logTailStep = tab.logTailStep || state.currentTailLines;
    state.logsExhausted = Boolean(tab.logsExhausted);
    state.previousPodLogsOrigin = tab.previousPodLogsOrigin || null;

    renderSelectedPod();
    renderHighlightedLogs();
    renderPods();
    restartLogsAutoRefresh();
}

function updateActiveLogTab(updates = {}) {
    const tab = activeLogTab();
    if (!tab) {
        return;
    }

    Object.assign(tab, updates);
    saveActiveLogTab();
    renderLogTabs();
    saveLogTabsState();
}

function switchLogTab(tabId) {
    if (tabId === state.activeLogTabId) {
        return;
    }

    const tab = state.logTabs.find(item => item.id === tabId);
    if (!tab) {
        return;
    }

    saveActiveLogTab();
    state.activeLogTabId = tab.id;
    restoreLogTab(tab);
    renderLogTabs();
    saveLogTabsState();

    if (tab.pod) {
        refreshLogsSilently().catch(handleError);
    }
}

function closeLogTab(tabId) {
    if (state.logTabs.length <= 1) {
        const tab = activeLogTab();

        if (tab) {
            Object.assign(tab, {
                pod: null,
                selectedContainer: "",
                rawLogs: "",
                logMatches: [],
                activeLogMatchIndex: -1,
                logsExhausted: false,
                previousPodLogsOrigin: null
            });

            restoreLogTab(tab);
            renderLogTabs();
            saveLogTabsState();
        }

        setLogsCollapsed(true);
        return;
    }

    const index = state.logTabs.findIndex(tab => tab.id === tabId);

    if (index === -1) {
        return;
    }

    const closingActive = tabId === state.activeLogTabId;
    state.logTabs.splice(index, 1);

    if (closingActive) {
        const nextTab = state.logTabs[Math.min(index, state.logTabs.length - 1)];
        state.activeLogTabId = nextTab.id;
        restoreLogTab(nextTab);
    } else {
        renderLogTabs();
    }

    saveLogTabsState();
}

function resetLogTabs() {
    closePodDetails();
    state.logTabs = [];
    state.activeLogTabId = "";
    createLogTab({ silent: true });
    saveLogTabsState();
}

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

async function selectPod(namespace, name, options = {}) {
    if (!options.keepPreviousPodOrigin) {
        state.previousPodLogsOrigin = null;
    }

    setStatus("loading", "Loading pod");
    const response = await api(`/api/pods/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}`);
    state.selectedPod = await response.json();
    state.selectedContainer = state.selectedPod.containers[0]?.name || "";
    resetLogTailLimit();
    renderSelectedPod();
    renderPods();
    await loadLogs();
    updateActiveLogTab({ pod: state.selectedPod });
    saveLogTabsState();
    setStatus("ok", "Ready");
}

function renderSelectedPod() {
    const pod = state.selectedPod;
    if (!pod) {
        clearSelectedPod();
        return;
    }

    els.selectedMeta.innerHTML = `Displaying logs from Namespace: <span class="namespace-link">${escapeHtml(pod.namespace)}</span> for Pod: <span class="pod-link">${escapeHtml(pod.name)}</span>.`;

    const containers = pod.containers || [];
    const containerOptions = [`<option value="">All Containers</option>`].concat(
        containers.map(container =>
            `<option value="${escapeHtml(container.name)}">${escapeHtml(container.name)}</option>`)
    );

    els.containerSelect.innerHTML = containerOptions.join("");
    els.containerSelect.value = state.selectedContainer;
}

function clearSelectedPod() {
    if (state.logsRefreshTimer) {
        clearInterval(state.logsRefreshTimer);
        state.logsRefreshTimer = null;
    }

    els.selectedMeta.textContent = "Select a pod to display logs";
    els.containerSelect.innerHTML = `<option value="">All Containers</option>`;
    state.rawLogs = "";
    state.logMatches = [];
    state.activeLogMatchIndex = -1;
    state.logsExhausted = false;
    state.previousPodLogsOrigin = null;
    els.logsView.textContent = "";
    updateLogMatchCounter();
    updatePreviousPodLogsButton();

    updateActiveLogTab({
        pod: null,
        selectedContainer: "",
        rawLogs: "",
        logMatches: [],
        activeLogMatchIndex: -1,
        logsExhausted: false,
        previousPodLogsOrigin: null
    });
}

async function loadLogs() {
    if (!state.selectedPod || state.logsLoading) {
        return;
    }

    state.logsLoading = true;
    els.logsView.textContent = "Loading logs...";

    try {
        resetLogTailLimit();
        const logs = await fetchCurrentLogs();
        renderLogs(logs);
        restartLogsAutoRefresh();
    } finally {
        state.logsLoading = false;
    }
}

async function refreshLogsSilently() {
    if (!state.selectedPod || state.logsLoading) {
        return;
    }

    state.logsLoading = true;

    try {
        const logs = await fetchCurrentLogs();
        renderLogs(logs);
    } catch (error) {
        setStatus("error", "Logs error");
        console.error(error);
    } finally {
        state.logsLoading = false;
    }
}

async function fetchCurrentLogs(options = {}) {
    const tailLines = state.currentTailLines || Number(els.tailInput.value || 300);
    const params = new URLSearchParams();

    if (state.selectedContainer) {
        params.set("container", state.selectedContainer);
    }

    params.set("tailLines", String(Math.max(1, tailLines)));

    if (options.previous || state.previousPodLogsOrigin?.previous) {
        params.set("previous", "true");
    }

    const pod = state.selectedPod;
    const response = await api(`/api/pods/${encodeURIComponent(pod.namespace)}/${encodeURIComponent(pod.name)}/logs?${params}`);
    return response.text();
}

async function loadOlderLogs() {
    if (!state.selectedPod || state.logsLoading || state.logsExhausted) {
        return;
    }

    const previousTail = state.currentTailLines;
    const nextTail = Math.min(state.maxTailLines, previousTail + state.logTailStep);

    if (nextTail <= previousTail) {
        state.logsExhausted = true;
        saveLogTabsState();
        return;
    }

    state.logsLoading = true;
    const previousScrollHeight = els.logsView.scrollHeight;
    const previousScrollTop = els.logsView.scrollTop;
    setStatus("loading", `Loading ${nextTail} log lines`);

    try {
        state.currentTailLines = nextTail;
        const logs = await fetchCurrentLogs();

        if (logs === state.rawLogs) {
            state.logsExhausted = true;
            saveLogTabsState();
            setStatus("ok", "Ready");
            return;
        }

        renderLogs(logs, { keepViewportAfterPrepend: true, previousScrollHeight, previousScrollTop });
        saveLogTabsState();
        setStatus("ok", "Ready");
    } catch (error) {
        state.currentTailLines = previousTail;
        setStatus("error", "Logs error");
        console.error(error);
    } finally {
        state.logsLoading = false;
    }
}

function renderLogs(logs, options = {}) {
    const wasNearBottom = isLogsNearBottom();
    const nextText = logs || "No logs returned";

    if (state.rawLogs === nextText && state.logMatches.length === countLogMatches(nextText, els.logSearchInput.value.trim())) {
        return;
    }

    const selection = captureLogsSelection();
    state.rawLogs = nextText;
    renderHighlightedLogs();
    restoreLogsSelection(selection);

    if (options.keepViewportAfterPrepend) {
        const diff = els.logsView.scrollHeight - options.previousScrollHeight;
        els.logsView.scrollTop = Math.max(0, options.previousScrollTop + diff);
    } else if (wasNearBottom) {
        els.logsView.scrollTop = els.logsView.scrollHeight;
    }

    saveActiveLogTab();
    saveLogTabsState();
}

function resetLogTailLimit() {
    const baseTail = Math.max(1, Number(els.tailInput.value || 300));
    state.logTailStep = baseTail;
    state.currentTailLines = baseTail;
    state.logsExhausted = false;
}

function renderHighlightedLogs() {
    const query = els.logSearchInput.value.trim();
    const displayLogs = currentDisplayedLogs();
    state.logMatches = [];

    if (!query) {
        state.activeLogMatchIndex = -1;
        renderLogHtmlWithoutSearch(displayLogs);
        updateLogMatchCounter();
        saveActiveLogTab();
        return;
    }

    const lowerText = displayLogs.toLowerCase();
    const lowerQuery = query.toLowerCase();
    let cursor = 0;
    let html = "";
    let matchIndex = 0;

    while (true) {
        const found = lowerText.indexOf(lowerQuery, cursor);

        if (found === -1) {
            html += escapeHtml(displayLogs.slice(cursor));
            break;
        }

        html += renderLogSegment(displayLogs.slice(cursor, found));

        const currentClass = matchIndex === state.activeLogMatchIndex ? " current" : "";
        html += `<mark class="log-hit${currentClass}" data-match-index="${matchIndex}">${escapeHtml(displayLogs.slice(found, found + query.length))}</mark>`;

        state.logMatches.push({ start: found, end: found + query.length });
        cursor = found + query.length;
        matchIndex += 1;
    }

    if (state.logMatches.length === 0) {
        state.activeLogMatchIndex = -1;
    } else if (state.activeLogMatchIndex < 0 || state.activeLogMatchIndex >= state.logMatches.length) {
        state.activeLogMatchIndex = 0;
        html = html.replace('class="log-hit"', 'class="log-hit current"');
    }

    els.logsView.innerHTML = html;
    updateLogMatchCounter();
    scrollToActiveLogMatch();
    saveActiveLogTab();
}

function renderLogHtmlWithoutSearch(displayLogs) {
    els.logsView.innerHTML = colorizeLevelsOnly(displayLogs);
}

function renderLogSegment(segment) {
    return colorizeLevelsOnly(segment, false);
}

function captureLogsSelection() {
    const selection = window.getSelection();

    if (!selection || selection.rangeCount === 0 || !els.logsView.contains(selection.anchorNode) || !els.logsView.contains(selection.focusNode)) {
        return null;
    }

    return {
        anchor: nodeOffsetToTextOffset(selection.anchorNode, selection.anchorOffset),
        focus: nodeOffsetToTextOffset(selection.focusNode, selection.focusOffset),
        backward: isSelectionBackward(selection)
    };
}

function restoreLogsSelection(saved) {
    if (!saved || !els.logsView.firstChild || els.logSearchInput.value.trim() || els.logsView.firstChild.nodeType !== Node.TEXT_NODE) {
        return;
    }

    const textNode = els.logsView.firstChild;
    const length = textNode.textContent.length;
    const anchor = Math.min(saved.anchor, length);
    const focus = Math.min(saved.focus, length);
    const selection = window.getSelection();
    const range = document.createRange();

    if (saved.backward && selection.extend) {
        range.setStart(textNode, focus);
        range.collapse(true);
        selection.removeAllRanges();
        selection.addRange(range);
        selection.extend(textNode, anchor);
        return;
    }

    range.setStart(textNode, Math.min(anchor, focus));
    range.setEnd(textNode, Math.max(anchor, focus));
    selection.removeAllRanges();
    selection.addRange(range);
}

function nodeOffsetToTextOffset(node, offset) {
    if (node === els.logsView) {
        return offset === 0 ? 0 : els.logsView.textContent.length;
    }

    const range = document.createRange();
    range.setStart(els.logsView, 0);
    range.setEnd(node, offset);
    return range.toString().length;
}

function isSelectionBackward(selection) {
    if (selection.anchorNode === selection.focusNode) {
        return selection.anchorOffset > selection.focusOffset;
    }

    const position = selection.anchorNode.compareDocumentPosition(selection.focusNode);
    return Boolean(position & Node.DOCUMENT_POSITION_PRECEDING);
}

function isLogsNearBottom() {
    return els.logsView.scrollHeight - els.logsView.scrollTop - els.logsView.clientHeight < 80;
}

function updateLogSearch(direction = 0) {
    if (!state.rawLogs) {
        updateLogMatchCounter();
        return;
    }

    const total = countLogMatches(currentDisplayedLogs(), els.logSearchInput.value.trim());

    if (total === 0) {
        state.activeLogMatchIndex = -1;
    } else if (direction !== 0) {
        state.activeLogMatchIndex = (state.activeLogMatchIndex + direction + total) % total;
    } else {
        state.activeLogMatchIndex = 0;
    }

    renderHighlightedLogs();
}

function countLogMatches(text, query) {
    if (!query) {
        return 0;
    }

    let count = 0;
    let cursor = 0;
    const lowerText = text.toLowerCase();
    const lowerQuery = query.toLowerCase();

    while (true) {
        const found = lowerText.indexOf(lowerQuery, cursor);

        if (found === -1) {
            return count;
        }

        count += 1;
        cursor = found + lowerQuery.length;
    }
}

function currentDisplayedLogs() {
    if (!state.compactJsonLogs) {
        return state.rawLogs;
    }

    return compactJsonLogLines(state.rawLogs);
}

function compactJsonLogLines(logs) {
    return logs.split("\n").map(line => {
        const trimmed = line.trim();

        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return line;
        }

        try {
            const entry = JSON.parse(trimmed);

            if (!Object.prototype.hasOwnProperty.call(entry, "timestamp") || !Object.prototype.hasOwnProperty.call(entry, "message")) {
                return line;
            }

            return `${entry.timestamp} ${entry.level || ""} ${entry.message}`.trim();
        } catch {
            return compactJsonLineByPattern(line);
        }
    }).join("\n");
}

function compactJsonLineByPattern(line) {
    const timestampMatch = line.match(/"timestamp"\s*:\s*"([^"]*)"/);
    const levelMatch = line.match(/"level"\s*:\s*"(TRACE|DEBUG|INFO|WARN|ERROR)"/i);
    const messageMatch = line.match(/"message"\s*:\s*("(?:\\.|[^"\\])*")/);

    if (!timestampMatch || !messageMatch) {
        return line;
    }

    let message = messageMatch[1];

    try {
        message = JSON.parse(messageMatch[1]);
    } catch {
        message = messageMatch[1].slice(1, -1);
    }

    return `${timestampMatch[1]} ${levelMatch ? levelMatch[1].toUpperCase() : ""} ${message}`.trim();
}

function colorizeLevelsOnly(logs, wrapLines = true) {
    const lines = logs.split("\n");

    return lines.map(line => {
        const content = colorizeLevelInLine(line);
        return wrapLines ? `<span class="log-line">${content}</span>` : content;
    }).join("\n");
}

function colorizeLevelInLine(line) {
    const escaped = escapeHtml(line);
    const jsonLevelPattern = /(&quot;level&quot;\s*:\s*&quot;)(TRACE|DEBUG|INFO|WARN|ERROR)(&quot;)/i;

    if (jsonLevelPattern.test(escaped)) {
        return escaped.replace(jsonLevelPattern, (_, prefix, level, suffix) =>
            `${prefix}<span class="${levelClass(level)}">${level}</span>${suffix}`);
    }

    return escaped.replace(/\b(TRACE|DEBUG|INFO|WARN|ERROR)\b/i, level =>
        `<span class="${levelClass(level)}">${level}</span>`);
}

function colorizeLogLine(line, wrapLine) {
    const content = colorizeLogLineContent(line);
    return wrapLine ? `<span class="log-line">${content}</span>` : content;
}

function colorizeLogLineContent(line) {
    const trimmed = line.trim();

    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
        return colorizeJsonLogLine(line);
    }

    return colorizePlainLogLine(line);
}

function colorizeJsonLogLine(line) {
    if (!state.compactJsonLogs) {
        return colorizeRawJsonLine(line);
    }

    try {
        const entry = JSON.parse(line.trim());

        if (entry && typeof entry === "object") {
            const timestamp = entry.timestamp ? `<span class="log-ts">${escapeHtml(entry.timestamp)}</span>` : "";
            const level = entry.level ? `<span class="${levelClass(entry.level)}">${escapeHtml(entry.level)}</span>` : "";
            const message = entry.message ? `<span class="log-message">${escapeHtml(entry.message)}</span>` : "";
            return [timestamp, level, message].filter(Boolean).join(" ");
        }
    } catch {
        return colorizePlainLogLine(line);
    }

    return escapeHtml(line);
}

function colorizeRawJsonLine(line) {
    return escapeHtml(line)
        .replace(/(&quot;timestamp&quot;\s*:\s*&quot;)(.*?)(?=&quot;)/g, '$1<span class="log-ts">$2</span>')
        .replace(/(&quot;level&quot;\s*:\s*&quot;)(TRACE|DEBUG|INFO|WARN|ERROR)(?=&quot;)/g, (_, a, level) => `${a}<span class="${levelClass(level)}">${level}</span>`)
        .replace(/(&quot;message&quot;\s*:\s*&quot;)(.*?)(?=&quot;[,}])/g, '$1<span class="log-message">$2</span>');
}

function colorizePlainLogLine(line) {
    const escaped = escapeHtml(line);

    return escaped
        .replace(/^(\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:[.,]\d+)?)/, '<span class="log-ts">$1</span>')
        .replace(/\b(TRACE|DEBUG|INFO|WARN|ERROR)\b/g, level => `<span class="${levelClass(level)}">${level}</span>`)
        .replace(/\b(\d{3,7})\b(?=\s+---)/, '<span class="log-pid">$1</span>')
        .replace(/(\[[^\]]+])/g, '<span class="log-thread">$1</span>')
        .replace(/\b([a-zA-Z_]\w*(?:\.[a-zA-Z_]\w*){2,})\b/g, '<span class="log-module">$1</span>');
}

function detectLogLevel(line) {
    const trimmed = line.trim();

    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
        try {
            const entry = JSON.parse(trimmed);
            return normalizeLogLevel(entry.level);
        } catch {
            const match = line.match(/"level"\s*:\s*"(TRACE|DEBUG|INFO|WARN|ERROR)"/i);
            return match ? normalizeLogLevel(match[1]) : null;
        }
    }

    const match = line.match(/\b(TRACE|DEBUG|INFO|WARN|ERROR)\b/i);
    return match ? normalizeLogLevel(match[1]) : null;
}

function normalizeLogLevel(level) {
    const normalized = String(level || "").toUpperCase();
    return ["TRACE", "DEBUG", "INFO", "WARN", "ERROR"].includes(normalized) ? normalized : null;
}

function levelClass(level) {
    return `log-level-${String(level || "").toLowerCase()}`;
}

function updateLogMatchCounter() {
    const total = state.logMatches.length;
    const current = total === 0 ? 0 : state.activeLogMatchIndex + 1;

    els.logMatchCounter.textContent = `${current} / ${total}`;
    els.prevLogMatchButton.disabled = total === 0;
    els.nextLogMatchButton.disabled = total === 0;
}

function updatePreviousPodLogsButton() {
    if (!els.previousPodLogsButton) {
        return;
    }

    const active = Boolean(state.previousPodLogsOrigin);

    els.previousPodLogsButton.disabled = !state.selectedPod;
    els.previousPodLogsButton.classList.toggle("active", active);
    els.previousPodLogsButton.textContent = active ? "Current pod" : "Prev pod";
    els.previousPodLogsButton.title = active
        ? "Return to current pod logs"
        : "Load previous container logs";
}

function selectedPodIndex() {
    if (!state.selectedPod) {
        return -1;
    }

    return state.filteredPods.findIndex(pod =>
        pod.namespace === state.selectedPod.namespace &&
        pod.name === state.selectedPod.name
    );
}

async function selectPreviousPodLogs() {
    if (!state.selectedPod || state.logsLoading) {
        return;
    }

    state.logsLoading = true;

    try {
        if (state.previousPodLogsOrigin) {
            state.previousPodLogsOrigin = null;

            const logs = await fetchCurrentLogs();
            renderLogs(logs);

            updateActiveLogTab({
                previousPodLogsOrigin: null
            });

            saveLogTabsState();
            return;
        }

        state.previousPodLogsOrigin = {
            namespace: state.selectedPod.namespace,
            name: state.selectedPod.name,
            previous: true
        };

        const logs = await fetchCurrentLogs({ previous: true });
        renderLogs(logs);

        updateActiveLogTab({
            previousPodLogsOrigin: state.previousPodLogsOrigin
        });

        saveLogTabsState();
    } catch (error) {
        const message = String(error.message || "");

        if (
            message.includes("previous terminated container") &&
            message.includes("not found")
        ) {
            state.previousPodLogsOrigin = null;
            els.logsView.textContent = "Под не перезагружался";
            setStatus("ok", "Ready");

            updateActiveLogTab({
                previousPodLogsOrigin: null
            });

            saveLogTabsState();
            return;
        }

        setStatus("error", "Previous logs error");
        els.logsView.textContent = message;
        console.error(error);
    } finally {
        state.logsLoading = false;
        updatePreviousPodLogsButton();
    }
}

function scrollToActiveLogMatch() {
    if (state.activeLogMatchIndex < 0) {
        return;
    }

    const current = els.logsView.querySelector(`mark[data-match-index="${state.activeLogMatchIndex}"]`);

    if (current) {
        current.scrollIntoView({ block: "center", inline: "nearest" });
    }
}

function containerBlocks(pod) {
    const containers = pod.containers || [];

    if (containers.length === 0) {
        return "";
    }

    return `<span class="container-blocks">${containers.map(container =>
        `<span class="container-block ${container.ready ? "ready" : ""}" title="${escapeHtml(container.name)}"></span>`
    ).join("")}</span>`;
}

function statusClass(pod) {
    const unhealthy = (pod.containers || []).some(container => !container.ready);
    const phase = String(pod.phase || "").toLowerCase();

    if (phase === "failed" || phase === "unknown") {
        return "status-bad";
    }

    if (phase === "pending") {
        return "status-warning";
    }

    if (phase === "succeeded") {
        return "status-running";
    }

    return unhealthy ? "status-warning" : "status-running";
}

function formatAge(age) {
    if (!age) {
        return "";
    }

    return age.replace("h", "h").replace("d", "d");
}

function renderConfigLabels() {
    const name = state.activeConfig || "DEV";

    els.pageTitle.textContent = `Pods - ${name}`;
    els.navigatorClusterName.textContent = name;
    els.activeConfigBadge.textContent = shortConfigName(name);
    els.statusCluster.textContent = `⬢ ${name} (v1.22.12)`;

    els.clusterTiles.forEach(tile => {
        tile.classList.toggle("active", tile.dataset.config === name);
    });
}

function shortConfigName(name) {
    const letters = String(name || "DE").replace(/[^a-zA-ZА-Яа-я0-9]/g, "");
    return (letters.slice(0, 2) || "DE").toUpperCase();
}

function setStatus(kind, text) {
    els.clusterStatus.className = "status-dot";

    if (kind === "ok") {
        els.clusterStatus.classList.add("ok");
    }

    if (kind === "error") {
        els.clusterStatus.classList.add("error");
    }

    els.clusterText.textContent = text;
}

function escapeHtml(value) {
    return String(value ?? "")
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#039;");
}

els.refreshButton.addEventListener("click", () => {
    loadPods().catch(handleError);
});

els.kubeConfigSelect.addEventListener("change", () => {
    if (!els.kubeConfigSelect.value) {
        return;
    }

    activateKubeConfig(els.kubeConfigSelect.value).catch(handleError);
});

els.clusterTiles.forEach(tile => {
    tile.addEventListener("click", () => {
        const config = tile.dataset.config;

        if (!config || config === state.activeConfig) {
            return;
        }

        if (Array.from(els.kubeConfigSelect.options).some(option => option.value === config)) {
            els.kubeConfigSelect.value = config;
        }

        activateKubeConfig(config).catch(handleError);
    });
});

els.namespaceSelect.addEventListener("change", () => {
    resetLogTabs();
    loadPods().catch(handleError);
});

els.searchInput.addEventListener("input", filterPods);

els.logSearchInput.addEventListener("input", () => {
    updateLogSearch();
});

els.logSearchInput.addEventListener("keydown", event => {
    if (event.key !== "Enter") {
        return;
    }

    event.preventDefault();
    updateLogSearch(event.shiftKey ? -1 : 1);
});

els.prevLogMatchButton.addEventListener("click", () => updateLogSearch(-1));
els.nextLogMatchButton.addEventListener("click", () => updateLogSearch(1));

els.podsBody.addEventListener("click", event => {
    if (event.target.matches("input[type='checkbox']")) {
        return;
    }

    const row = event.target.closest("tr[data-namespace][data-name]");

    if (!row) {
        return;
    }

    showPodDetails(row.dataset.namespace, row.dataset.name).catch(handleError);
});

if (els.openLogsButton) {
    els.openLogsButton.addEventListener("click", () => {
        openDetailsLogs().catch(handleError);
    });
}

if (els.closeDetailsButton) {
    els.closeDetailsButton.addEventListener("click", closePodDetails);
}

if (els.podDetailsPanel) {
    els.podDetailsPanel.addEventListener("click", event => {
        const button = event.target.closest("[data-port][data-namespace][data-pod]");
        if (!button) {
            return;
        }
        openPortForwardDialog(button.dataset.namespace, button.dataset.pod, button.dataset.port);
    });
}

if (els.cancelPortForwardButton) {
    els.cancelPortForwardButton.addEventListener("click", closePortForwardDialog);
}

if (els.startPortForwardButton) {
    els.startPortForwardButton.addEventListener("click", () => {
        startPortForward().catch(handleError);
    });
}

if (els.portForwardModal) {
    els.portForwardModal.addEventListener("click", event => {
        if (event.target === els.portForwardModal) {
            closePortForwardDialog();
        }
    });
}

if (els.addLogTabButton) {
    els.addLogTabButton.addEventListener("click", () => {
        createLogTab();
    });
}

if (els.logsTabs) {
    els.logsTabs.addEventListener("click", event => {
        const closeButton = event.target.closest("[data-close-tab-id]");

        if (closeButton) {
            event.stopPropagation();
            closeLogTab(closeButton.dataset.closeTabId);
            return;
        }

        const tabButton = event.target.closest("[data-tab-id]");

        if (tabButton) {
            switchLogTab(tabButton.dataset.tabId);
        }
    });
}

els.containerSelect.addEventListener("change", () => {
    state.selectedContainer = els.containerSelect.value;
    updateActiveLogTab({ selectedContainer: state.selectedContainer });
    loadLogs().catch(handleError);
});

els.tailInput.addEventListener("change", () => {
    saveUiState({ tailLines: els.tailInput.value });

    resetLogTailLimit();

    updateActiveLogTab({
        currentTailLines: state.currentTailLines,
        logTailStep: state.logTailStep,
        logsExhausted: state.logsExhausted
    });

    loadLogs().catch(handleError);
});

if (els.previousPodLogsButton) {
    els.previousPodLogsButton.addEventListener("click", () => {
        selectPreviousPodLogs().catch(handleError);
    });
}

els.compactJsonButton.addEventListener("click", () => {
    state.compactJsonLogs = !state.compactJsonLogs;
    els.compactJsonButton.classList.toggle("active", state.compactJsonLogs);
    saveUiState({ compactJsonLogs: state.compactJsonLogs });
    renderHighlightedLogs();
});

els.logsView.addEventListener("scroll", () => {
    if (els.logsView.scrollTop <= 24) {
        loadOlderLogs();
    }
});

els.autoRefreshButton.addEventListener("click", () => {
    state.logsAutoRefresh = !state.logsAutoRefresh;
    els.autoRefreshButton.classList.toggle("active", state.logsAutoRefresh);
    els.autoRefreshButton.textContent = state.logsAutoRefresh ? "Live" : "Paused";
    saveUiState({ logsAutoRefresh: state.logsAutoRefresh });
    restartLogsAutoRefresh();
});

els.logRefreshInterval.addEventListener("change", () => {
    saveUiState({ logRefreshInterval: els.logRefreshInterval.value });
    restartLogsAutoRefresh();
});

els.expandLogsButton.addEventListener("click", () => {
    setLogsExpanded(!els.lens.classList.contains("logs-expanded"));
});

if (els.collapseLogsButton) {
    els.collapseLogsButton.addEventListener("click", () => {
        setLogsCollapsed(!els.lens.classList.contains("logs-collapsed"));
    });
}

document.addEventListener("keydown", event => {
    if (event.key === "Escape" && els.lens.classList.contains("logs-expanded")) {
        setLogsExpanded(false);
    }
});

function handleError(error) {
    setStatus("error", "Error");
    els.logsView.textContent = error.message;
    console.error(error);
}

function restartLogsAutoRefresh() {
    if (state.logsRefreshTimer) {
        clearInterval(state.logsRefreshTimer);
        state.logsRefreshTimer = null;
    }

    if (!state.logsAutoRefresh || !state.selectedPod) {
        return;
    }

    state.logsRefreshTimer = setInterval(refreshLogsSilently, Number(els.logRefreshInterval.value || 5000));
}

function initLogsResizer() {
    const handle = document.querySelector("#logsResizeHandle");

    if (!handle || !els.lens) {
        return;
    }

    const savedHeight = Number(localStorage.getItem("logsPanelHeight"));

    if (savedHeight) {
        setLogsHeight(savedHeight);
    }

    let startY = 0;
    let startHeight = 0;

    handle.addEventListener("mousedown", event => {
        event.preventDefault();

        startY = event.clientY;
        startHeight = currentLogsHeight();

        els.lens.classList.add("resizing-logs");

        document.addEventListener("mousemove", resizeLogs);
        document.addEventListener("mouseup", stopResizeLogs);
    });

    function resizeLogs(event) {
        const delta = startY - event.clientY;
        setLogsHeight(startHeight + delta);
    }

    function stopResizeLogs() {
        els.lens.classList.remove("resizing-logs");

        document.removeEventListener("mousemove", resizeLogs);
        document.removeEventListener("mouseup", stopResizeLogs);

        localStorage.setItem("logsPanelHeight", String(currentLogsHeight()));
    }
}

function currentLogsHeight() {
    const value = getComputedStyle(els.lens).getPropertyValue("--logs-height").trim();
    return Number(value.replace("px", "")) || 300;
}

function setLogsHeight(height) {
    const viewportHeight = window.innerHeight || 800;
    const min = 160;
    const max = Math.max(220, viewportHeight - 180);
    const clamped = Math.min(max, Math.max(min, Math.round(height)));

    els.lens.style.setProperty("--logs-height", `${clamped}px`);
}

function setLogsExpanded(expanded) {
    const wasNearBottom = isLogsNearBottom();

    if (expanded) {
        els.lens.classList.remove("logs-collapsed");
        els.collapseLogsButton.textContent = "˅";
        els.collapseLogsButton.title = "Collapse logs";
        saveUiState({ logsCollapsed: false });
    }

    els.lens.classList.toggle("logs-expanded", expanded);
    els.expandLogsButton.textContent = expanded ? "🗗" : "⛶";
    els.expandLogsButton.title = expanded ? "Restore logs panel" : "Expand logs";

    saveUiState({ logsExpanded: expanded });

    requestAnimationFrame(() => {
        if (wasNearBottom) {
            els.logsView.scrollTop = els.logsView.scrollHeight;
        }
    });
}

function setLogsCollapsed(collapsed) {
    if (collapsed) {
        els.lens.classList.remove("logs-expanded");
        els.expandLogsButton.textContent = "⛶";
        els.expandLogsButton.title = "Expand logs";
        saveUiState({ logsExpanded: false });
    }

    els.lens.classList.toggle("logs-collapsed", collapsed);
    els.collapseLogsButton.textContent = collapsed ? "˄" : "˅";
    els.collapseLogsButton.title = collapsed ? "Restore logs panel" : "Collapse logs";

    saveUiState({ logsCollapsed: collapsed });
}

restoreUiState();

loadKubeConfigs()
    .then(loadNamespaces)
    .then(loadPods)
    .then(restoreSavedLogTabs)
    .catch(handleError);
