initLogsResizer();

restoreUiState();

loadKubeConfigs()
    .then(loadNamespaces)
    .then(loadPods)
    .then(restoreSavedLogTabs)
    .catch(handleError);
