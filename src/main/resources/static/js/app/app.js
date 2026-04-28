initLogsResizer();

restoreUiState();

loadKubeConfigs()
    .then(loadNamespaces)
    .then(loadPods)
    .then(restoreSavedLogTabs)
    .then(startPodsAutoRefresh)
    .catch(error => {
        handleError(error);
        startPodsAutoRefresh();
    });
