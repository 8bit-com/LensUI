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
        els.expandLogsButton.dataset.icon = "restore";
        els.expandLogsButton.title = "Restore logs panel";
    }

    if (saved.logsCollapsed) {
        els.lens.classList.add("logs-collapsed");
        els.collapseLogsButton.dataset.icon = "expand";
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

    let request = null;

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

        request = beginLogsRequest();
        if (!request) {
            return;
        }

        renderSelectedPod();
        renderPods();

        els.logsView.textContent = "Loading logs...";

        const logs = await fetchCurrentLogs({ request });
        if (!isCurrentLogsRequest(request)) {
            return;
        }

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
        finishLogsRequest(request);
    }
}

