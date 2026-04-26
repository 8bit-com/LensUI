# Kubernetes Lens UI

Small Spring Boot web UI for viewing Kubernetes pods and pod logs.

## Run

```powershell
mvn spring-boot:run
```

Open:

```text
http://localhost:8080
```

The app uses the current Kubernetes context from the default kubeconfig, the same way `kubectl` does. It can also run inside a cluster with an in-cluster service account.

If you want to keep the kubeconfig inside this project, put it here:

```text
config/kubeconfig.yaml
```

Then set:

```properties
kubernetes.kube-config-path=config/kubeconfig.yaml
```

For Lens-style folders with several kubeconfig files, point the app to the directory:

```properties
kubernetes.kube-config-dir=C:/Users/Владимир/OneDrive/Документы/config_lens
```

The UI will show a kubeconfig selector.

## Configuration

Edit `src/main/resources/application.properties`:

```properties
server.port=8080
kubernetes.kube-config-path=config/kubeconfig.yaml
kubernetes.kube-config-dir=C:/Users/Владимир/OneDrive/Документы/config_lens
kubernetes.namespaces=
kubernetes.logs.default-tail-lines=300
```

Set `kubernetes.namespaces=default,kube-system` to limit the UI to specific namespaces. Leave it blank to show all namespaces allowed by the current Kubernetes credentials.

## Features

- Namespace filter
- Pod list with phase, readiness, restarts, node, IP, age
- Pod search
- Container selector
- Tail logs viewer
- Pod details with labels, annotations, and conditions
