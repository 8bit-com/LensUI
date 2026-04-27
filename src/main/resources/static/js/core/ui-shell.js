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
