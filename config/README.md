# Kubernetes configs

Put your kubeconfig file here if you do not want to use the default `%USERPROFILE%\.kube\config`.

Recommended path:

```text
config/kubeconfig.yaml
```

Then set this in `src/main/resources/application.properties`:

```properties
kubernetes.kube-config-path=config/kubeconfig.yaml
```

The kubeconfig can contain multiple clusters and contexts, just like the config used by Lens or `kubectl`.
