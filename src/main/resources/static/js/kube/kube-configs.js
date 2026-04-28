async function loadKubeConfigs() {
    const response = await api("/api/kubeconfigs");
    applyKubeConfigs(await response.json());
}

function applyKubeConfigs(configs) {
    state.kubeConfigs = orderKubeConfigs(Array.isArray(configs) ? configs : []);
    if (state.kubeConfigs.length === 0) {
        els.kubeConfigSelect.innerHTML = `<option value="">Default kubeconfig</option>`;
        els.kubeConfigSelect.disabled = true;
        state.activeConfig = "DEV";
        renderClusterTiles();
        renderConfigLabels();
        return;
    }

    const active = state.kubeConfigs.find(config => config.active) || state.kubeConfigs[0];
    state.activeConfig = active.name;
    els.kubeConfigSelect.disabled = false;
    els.kubeConfigSelect.innerHTML = state.kubeConfigs.map(config =>
        `<option value="${escapeHtml(config.name)}" ${config.active ? "selected" : ""}>${escapeHtml(config.name)}</option>`
    ).join("");
    renderClusterTiles();
    renderConfigLabels();
}

function orderKubeConfigs(configs) {
    const savedOrder = loadUiState().kubeConfigOrder;
    if (!Array.isArray(savedOrder) || savedOrder.length === 0) {
        return configs;
    }

    const positionByName = new Map(savedOrder.map((name, index) => [name, index]));
    return configs
        .map((config, originalIndex) => ({ config, originalIndex }))
        .sort((left, right) => {
            const leftPosition = positionByName.has(left.config.name)
                ? positionByName.get(left.config.name)
                : Number.MAX_SAFE_INTEGER;
            const rightPosition = positionByName.has(right.config.name)
                ? positionByName.get(right.config.name)
                : Number.MAX_SAFE_INTEGER;

            return leftPosition - rightPosition || left.originalIndex - right.originalIndex;
        })
        .map(item => item.config);
}

async function activateKubeConfig(name) {
    saveActiveLogTab();
    saveLogTabsState();
    stopPodsAutoRefresh();

    try {
        setStatus("loading", "Switching kubeconfig");

        const response = await api(`/api/kubeconfigs/${encodeURIComponent(name)}/activate`, {
            method: "POST"
        });

        applyKubeConfigs(await response.json());

        await loadNamespaces();
        await loadPods();

        renderLogTabs();
        restoreLogTab(activeLogTab());
    } finally {
        startPodsAutoRefresh();
    }
}

function openKubeConfigFolderDialog() {
    state.pendingKubeConfigFiles = [];
    els.kubeConfigFolderInput.value = "";
    els.kubeConfigFolderChoice.textContent = "No folder selected";
    els.kubeConfigFolderError.textContent = "";
    els.kubeConfigFolderModal.classList.remove("hidden");
    els.kubeConfigDirInput.focus();
}

function closeKubeConfigFolderDialog() {
    els.kubeConfigFolderModal.classList.add("hidden");
}

function selectedFolderName(files) {
    const first = files[0];
    if (!first) {
        return "No folder selected";
    }

    const relativePath = first.webkitRelativePath || first.name;
    return relativePath.split("/")[0] || first.name;
}

async function loadKubeConfigFolder() {
    const directory = els.kubeConfigDirInput.value.trim();
    let response;

    els.kubeConfigFolderError.textContent = "";
    els.saveKubeConfigFolderButton.disabled = true;
    stopPodsAutoRefresh();

    try {
        if (directory) {
            response = await api("/api/kubeconfigs/directory", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ path: directory })
            });
        } else if (state.pendingKubeConfigFiles.length > 0) {
            const body = new FormData();
            state.pendingKubeConfigFiles.forEach(file => {
                body.append("files", file, file.webkitRelativePath || file.name);
            });
            response = await api("/api/kubeconfigs/import-folder", {
                method: "POST",
                body
            });
        } else {
            throw new Error("Choose a folder or enter a folder path");
        }

        applyKubeConfigs(await response.json());
        closeKubeConfigFolderDialog();
        resetLogTabs();
        await loadNamespaces();
        await loadPods();
    } catch (error) {
        els.kubeConfigFolderError.textContent = error.message;
    } finally {
        els.saveKubeConfigFolderButton.disabled = false;
        startPodsAutoRefresh();
    }
}


function renderConfigLabels() {
    const name = state.activeConfig || "DEV";

    els.pageTitle.textContent = `Pods - ${name}`;
    els.navigatorClusterName.textContent = name;

    els.clusterTiles.querySelectorAll(".cluster-tile[data-config]").forEach(tile => {
        tile.classList.toggle("active", tile.dataset.config === name);
    });
}

function renderClusterTiles() {
    const colors = ["#c64113", "#df0074", "#d8d000", "#07985b", "#3a98dd", "#7c6ee6"];

    els.clusterTiles.innerHTML = state.kubeConfigs.map((config, index) => `
        <button class="cluster-tile" style="--tile-color: ${colors[index % colors.length]}" data-config="${escapeHtml(config.name)}" type="button" draggable="true" title="${escapeHtml(config.path || config.name)}">
            <svg viewBox="0 0 48 48" aria-hidden="true"><use href="#clusterIcon"></use></svg><span>${escapeHtml(shortConfigName(config.name))}</span>
        </button>
    `).join("");
}

function moveKubeConfig(sourceName, targetName, insertAfterTarget = false) {
    if (!sourceName || !targetName || sourceName === targetName) {
        return;
    }

    const sourceIndex = state.kubeConfigs.findIndex(config => config.name === sourceName);
    if (sourceIndex < 0) {
        return;
    }

    const [movedConfig] = state.kubeConfigs.splice(sourceIndex, 1);
    const targetIndex = state.kubeConfigs.findIndex(config => config.name === targetName);
    if (targetIndex < 0) {
        state.kubeConfigs.splice(sourceIndex, 0, movedConfig);
        return;
    }

    state.kubeConfigs.splice(insertAfterTarget ? targetIndex + 1 : targetIndex, 0, movedConfig);
    saveKubeConfigOrder();
    renderClusterTiles();
    renderConfigLabels();
}

function saveKubeConfigOrder() {
    saveUiState({
        kubeConfigOrder: state.kubeConfigs.map(config => config.name)
    });
}

function clearClusterDropMarkers() {
    els.clusterTiles.querySelectorAll(".cluster-tile").forEach(tile => {
        tile.classList.remove("drop-before", "drop-after");
    });
}

function clearClusterDragState() {
    state.draggedConfig = "";
    clearClusterDropMarkers();
    els.clusterTiles.querySelectorAll(".cluster-tile").forEach(tile => {
        tile.classList.remove("dragging");
    });
}

function shortConfigName(name) {
    const cleanName = String(name || "KC").replace(/\.[^.]+$/, "");
    const parts = cleanName.split(/[^a-zA-ZА-Яа-я0-9]+/).filter(Boolean);
    const label = parts.length > 1
        ? parts.map(part => part[0]).join("")
        : cleanName.replace(/[^a-zA-ZА-Яа-я0-9]/g, "");

    return (label.slice(0, 4) || "KC").toUpperCase();
}
