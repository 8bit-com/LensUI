initLogsResizer();

restoreUiState();

loadKubeConfigs()
    .then(loadNamespaces)
    .then(loadPods)
    .then(() => loadPortForwards({ silent: true }))
    .then(restoreSavedLogTabs)
    .then(startPodsAutoRefresh)
    .then(startPortForwardsAutoRefresh)
    .catch(error => {
        handleError(error);
        startPodsAutoRefresh();
        startPortForwardsAutoRefresh();
    });
