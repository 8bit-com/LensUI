async function loadLogs() {
    const request = beginLogsRequest();
    if (!request) {
        return;
    }

    els.logsView.textContent = "Loading logs...";

    try {
        resetLogTailLimit();
        const logs = await fetchCurrentLogs({ request });
        if (!isCurrentLogsRequest(request)) {
            return;
        }

        renderLogs(logs);
        restartLogsAutoRefresh();
    } finally {
        finishLogsRequest(request);
    }
}

async function refreshLogsSilently() {
    const request = beginLogsRequest();
    if (!request) {
        return;
    }

    try {
        const logs = await fetchCurrentLogs({ request });
        if (!isCurrentLogsRequest(request)) {
            return;
        }

        renderLogs(logs);
    } catch (error) {
        setStatus("error", "Logs error");
        console.error(error);
    } finally {
        finishLogsRequest(request);
    }
}

async function fetchCurrentLogs(options = {}) {
    const request = options.request || null;
    const tailLines = request?.tailLines || state.currentTailLines || Number(els.tailInput.value || 300);
    const params = new URLSearchParams();
    const container = request?.container ?? state.selectedContainer;

    if (container) {
        params.set("container", container);
    }

    params.set("tailLines", String(Math.max(1, tailLines)));

    if (options.previous || request?.previous || state.previousPodLogsOrigin?.previous) {
        params.set("previous", "true");
    }

    const pod = state.selectedPod;
    const namespace = request?.namespace || pod.namespace;
    const podName = request?.podName || pod.name;
    const response = await api(`/api/pods/${encodeURIComponent(namespace)}/${encodeURIComponent(podName)}/logs?${params}`);
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

    const request = beginLogsRequest();
    if (!request) {
        return;
    }

    const previousScrollHeight = els.logsView.scrollHeight;
    const previousScrollTop = els.logsView.scrollTop;
    setStatus("loading", `Loading ${nextTail} log lines`);

    try {
        state.currentTailLines = nextTail;
        request.tailLines = nextTail;
        const logs = await fetchCurrentLogs({ request });
        if (!isCurrentLogsRequest(request)) {
            return;
        }

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
        finishLogsRequest(request);
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
    const originalPreviousPodLogsOrigin = state.previousPodLogsOrigin;
    const nextPreviousPodLogsOrigin = originalPreviousPodLogsOrigin
        ? null
        : {
            namespace: state.selectedPod?.namespace,
            name: state.selectedPod?.name,
            previous: true
        };

    state.previousPodLogsOrigin = nextPreviousPodLogsOrigin;

    const request = beginLogsRequest();
    if (!request) {
        state.previousPodLogsOrigin = originalPreviousPodLogsOrigin;
        return;
    }

    try {
        if (!nextPreviousPodLogsOrigin) {
            const logs = await fetchCurrentLogs({ request });
            if (!isCurrentLogsRequest(request)) {
                return;
            }

            renderLogs(logs);

            updateActiveLogTab({
                previousPodLogsOrigin: null
            });

            saveLogTabsState();
            return;
        }

        const logs = await fetchCurrentLogs({ request });
        if (!isCurrentLogsRequest(request)) {
            return;
        }

        renderLogs(logs);

        updateActiveLogTab({
            previousPodLogsOrigin: state.previousPodLogsOrigin
        });

        saveLogTabsState();
    } catch (error) {
        if (!isCurrentLogsRequest(request)) {
            return;
        }

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
        finishLogsRequest(request);
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
        els.collapseLogsButton.textContent = "v";
        els.collapseLogsButton.title = "Collapse logs";
        saveUiState({ logsCollapsed: false });
    }

    els.lens.classList.toggle("logs-expanded", expanded);
    els.expandLogsButton.textContent = expanded ? "><" : "[]";
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
        els.expandLogsButton.textContent = "[]";
        els.expandLogsButton.title = "Expand logs";
        saveUiState({ logsExpanded: false });
    }

    els.lens.classList.toggle("logs-collapsed", collapsed);
    els.collapseLogsButton.textContent = collapsed ? "^" : "v";
    els.collapseLogsButton.title = collapsed ? "Restore logs panel" : "Collapse logs";

    saveUiState({ logsCollapsed: collapsed });
}
