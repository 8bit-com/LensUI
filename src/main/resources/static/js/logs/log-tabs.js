function createLogTab(options = {}) {
    saveActiveLogTab();
    invalidateLogRequests();

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

function podIdentity(pod) {
    return pod ? `${pod.namespace}/${pod.name}` : "";
}

function invalidateLogRequests() {
    state.logsRequestVersion += 1;
    state.logsLoading = false;
}

function beginLogsRequest() {
    if (!state.selectedPod || state.logsLoading) {
        return null;
    }

    const pod = state.selectedPod;
    state.logsLoading = true;
    return {
        version: ++state.logsRequestVersion,
        tabId: state.activeLogTabId,
        pod: podIdentity(pod),
        namespace: pod.namespace,
        podName: pod.name,
        container: state.selectedContainer,
        tailLines: state.currentTailLines || Number(els.tailInput.value || 300),
        previous: Boolean(state.previousPodLogsOrigin?.previous)
    };
}

function isCurrentLogsRequest(request) {
    return Boolean(
        request &&
        request.version === state.logsRequestVersion &&
        request.tabId === state.activeLogTabId &&
        request.pod === podIdentity(state.selectedPod)
    );
}

function finishLogsRequest(request) {
    if (request && request.version === state.logsRequestVersion) {
        state.logsLoading = false;
    }
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
    invalidateLogRequests();
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
            invalidateLogRequests();
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
        invalidateLogRequests();
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
    invalidateLogRequests();
    state.logTabs = [];
    state.activeLogTabId = "";
    createLogTab({ silent: true });
    saveLogTabsState();
}
