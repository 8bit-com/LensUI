els.refreshButton.addEventListener("click", () => {
    loadPods().catch(handleError);
});

els.kubeConfigSelect.addEventListener("change", () => {
    if (!els.kubeConfigSelect.value) {
        return;
    }

    activateKubeConfig(els.kubeConfigSelect.value).catch(handleError);
});

els.clusterTiles.addEventListener("click", event => {
    if (state.suppressClusterClick) {
        state.suppressClusterClick = false;
        return;
    }

    const tile = event.target.closest(".cluster-tile[data-config]");
    if (!tile) {
        return;
    }

    const config = tile.dataset.config;

    if (!config || config === state.activeConfig) {
        return;
    }

    if (Array.from(els.kubeConfigSelect.options).some(option => option.value === config)) {
        els.kubeConfigSelect.value = config;
    }

    activateKubeConfig(config).catch(handleError);
});

els.clusterTiles.addEventListener("dragstart", event => {
    const tile = event.target.closest(".cluster-tile[data-config]");
    if (!tile) {
        return;
    }

    state.draggedConfig = tile.dataset.config;
    state.suppressClusterClick = true;
    tile.classList.add("dragging");
    event.dataTransfer.effectAllowed = "move";
    event.dataTransfer.setData("text/plain", state.draggedConfig);
});

els.clusterTiles.addEventListener("mousedown", event => {
    if (event.button !== 0) {
        return;
    }

    const tile = event.target.closest(".cluster-tile[data-config]");
    if (!tile) {
        return;
    }

    state.mouseDraggedConfig = tile.dataset.config;
});

els.clusterTiles.addEventListener("mouseover", event => {
    const tile = event.target.closest(".cluster-tile[data-config]");
    if (!tile || !state.mouseDraggedConfig || tile.dataset.config === state.mouseDraggedConfig) {
        clearClusterDropMarkers();
        return;
    }

    if (!state.draggedConfig) {
        state.draggedConfig = state.mouseDraggedConfig;
        state.suppressClusterClick = true;
        const sourceTile = els.clusterTiles.querySelector(`.cluster-tile[data-config="${CSS.escape(state.draggedConfig)}"]`);
        if (sourceTile) {
            sourceTile.classList.add("dragging");
        }
    }

    clearClusterDropMarkers();
    tile.classList.add("drop-after");
});

document.addEventListener("mouseup", event => {
    if (!state.draggedConfig) {
        state.mouseDraggedConfig = "";
        return;
    }

    const tile = event.target.closest ? event.target.closest(".cluster-tile[data-config]") : null;
    if (tile && tile.dataset.config && tile.dataset.config !== state.draggedConfig) {
        moveKubeConfig(state.draggedConfig, tile.dataset.config, tile.classList.contains("drop-after"));
    }

    clearClusterDragState();
    state.mouseDraggedConfig = "";
    window.setTimeout(() => {
        state.suppressClusterClick = false;
    }, 200);
});

els.clusterTiles.addEventListener("dragover", event => {
    const tile = event.target.closest(".cluster-tile[data-config]");
    if (!tile || !state.draggedConfig || tile.dataset.config === state.draggedConfig) {
        clearClusterDropMarkers();
        return;
    }

    event.preventDefault();
    event.dataTransfer.dropEffect = "move";

    const rect = tile.getBoundingClientRect();
    const insertAfterTarget = event.clientY > rect.top + rect.height / 2;
    clearClusterDropMarkers();
    tile.classList.add(insertAfterTarget ? "drop-after" : "drop-before");
});

els.clusterTiles.addEventListener("drop", event => {
    const tile = event.target.closest(".cluster-tile[data-config]");
    if (!tile || !state.draggedConfig) {
        clearClusterDragState();
        return;
    }

    event.preventDefault();
    moveKubeConfig(
        event.dataTransfer.getData("text/plain") || state.draggedConfig,
        tile.dataset.config,
        tile.classList.contains("drop-after")
    );
    clearClusterDragState();
});

els.clusterTiles.addEventListener("dragend", () => {
    clearClusterDragState();
    window.setTimeout(() => {
        state.suppressClusterClick = false;
    }, 200);
});

els.addConfigFolderButton.addEventListener("click", openKubeConfigFolderDialog);

els.chooseKubeConfigFolderButton.addEventListener("click", () => {
    els.kubeConfigFolderInput.click();
});

els.kubeConfigFolderInput.addEventListener("change", () => {
    state.pendingKubeConfigFiles = Array.from(els.kubeConfigFolderInput.files || []);
    els.kubeConfigFolderChoice.textContent = selectedFolderName(state.pendingKubeConfigFiles);
    if (state.pendingKubeConfigFiles.length > 0) {
        els.kubeConfigDirInput.value = "";
    }
});

els.cancelKubeConfigFolderButton.addEventListener("click", closeKubeConfigFolderDialog);

els.saveKubeConfigFolderButton.addEventListener("click", () => {
    loadKubeConfigFolder().catch(handleError);
});

els.kubeConfigFolderModal.addEventListener("click", event => {
    if (event.target === els.kubeConfigFolderModal) {
        closeKubeConfigFolderDialog();
    }
});

els.namespaceSelect.addEventListener("change", () => {
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

document.addEventListener("visibilitychange", () => {
    if (document.hidden) {
        return;
    }

    refreshPodsSilently().catch(error => {
        setStatus("error", "Pods refresh error");
        console.error(error);
    });
});
