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
