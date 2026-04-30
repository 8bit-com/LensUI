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

## Build Windows desktop exe

The project targets Java 17 because the desktop shell and Windows packaging use JavaFX and `jpackage`.

Install JDK 17/21, then run:

```powershell
.\scripts\build-exe.ps1
```

The script checks `PATH` first and then looks in the JDKs installed by IntelliJ IDEA under `%USERPROFILE%\.jdks`.

The desktop app image will be created at:

```text
dist\KubernetesLensUIDesktop\KubernetesLensUIDesktop.exe
```

The desktop launcher starts the Spring Boot backend inside the same process and opens the UI in a JavaFX window. By default it uses a random local port to avoid conflicts.

To build a Windows installer `.exe`, install WiX Toolset and run:

```powershell
.\scripts\build-exe.ps1 -Installer
```

You can also override the embedded server port:

```powershell
.\scripts\build-exe.ps1 -Port 8082
```

To build the old browser-based server launcher instead of the desktop window:

```powershell
.\scripts\build-exe.ps1 -Web -AppName KubernetesLensUI
```

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

## Mobile app (Android)

Сделано standalone Android-приложение в `mobile/android`: APK содержит Lens UI, локальный HTTP-сервер и Android-адаптер к Kubernetes API.

Spring Boot backend на компьютере для APK не нужен. Для подключения к кластеру импортируйте kubeconfig прямо на телефоне через кнопку добавления kubeconfig в приложении.

Телефон должен иметь сетевой доступ к Kubernetes API server. Если `clusters[].cluster.server` в kubeconfig указывает на приватный адрес вроде `10.x.x.x`, `172.16-31.x.x` или `192.168.x.x`, телефон должен быть в той же сети/VPN, иначе port-forward не сможет подключиться к API server. Лучше использовать kubeconfig с встроенными `*-data` сертификатами/ключами или bearer token; kubeconfig, который ссылается на файлы на компьютере или запускает desktop-команды через `exec`, на телефоне без этих файлов/команд не сработает.

### Как собрать APK

Соберите debug APK:

```powershell
.\scripts\build-android-apk.ps1
```

Готовый APK:

```text
mobile/android/app/build/outputs/apk/debug/app-debug.apk
```

Для ручной сборки из `mobile/android`:

```powershell
.\gradlew.bat assembleDebug
```

Port-forward в standalone APK работает через тот же kubectl-style POST/SPDY-путь, что и desktop-версия через `kubectl`. Для kubeconfig-пользователя нужны права на subresource `pods/portforward`: минимум `create`.

Проверка прав:

```powershell
kubectl auth can-i create pods/portforward -n <namespace>
```
