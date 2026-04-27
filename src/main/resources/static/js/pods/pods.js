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


async function selectPod(namespace, name, options = {}) {
    if (!options.keepPreviousPodOrigin) {
        state.previousPodLogsOrigin = null;
    }

    setStatus("loading", "Loading pod");
    const response = await api(`/api/pods/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}`);
    invalidateLogRequests();
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
