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

function handleError(error) {
    setStatus("error", "Error");
    els.logsView.textContent = error.message;
    console.error(error);
}

function isMobileLayout() {
    return window.matchMedia("(max-width: 700px), (max-width: 900px) and (max-height: 520px)").matches;
}

function setMobileView(view) {
    const allowed = ["pods", "details", "logs"];
    const nextView = allowed.includes(view) ? view : "pods";

    if (nextView === "details" && !state.detailsPod) {
        return setMobileView("pods");
    }

    state.mobileView = nextView;
    els.lens.classList.remove("mobile-view-pods", "mobile-view-details", "mobile-view-logs");
    els.lens.classList.add(`mobile-view-${nextView}`);

    els.mobileNavButtons.forEach(button => {
        button.classList.toggle("active", button.dataset.mobileView === nextView);
        button.disabled = button.dataset.mobileView === "details" && !state.detailsPod;
    });

    saveUiState({ mobileView: nextView });

    if (nextView === "logs") {
        els.lens.classList.remove("logs-collapsed", "logs-expanded");
        requestAnimationFrame(() => {
            if (els.logsView) {
                els.logsView.scrollTop = els.logsView.scrollHeight;
            }
        });
    }
}

function syncMobileShell() {
    els.lens.classList.toggle("mobile-shell", isMobileLayout());
    setMobileView(state.mobileView || "pods");
}
