package dev.codex.k8slens.desktop;

import dev.codex.k8slens.KubernetesLensUiApplication;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class KubernetesLensDesktopApplication extends Application {

    private ConfigurableApplicationContext springContext;
    private int serverPort;
    private String desktopAccessToken;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void init() {
        System.setProperty("java.awt.headless", "false");

        Map<String, Object> defaults = new HashMap<>();
        defaults.put("server.port", "0");
        defaults.put("server.address", "127.0.0.1");
        defaults.put("spring.main.banner-mode", "off");
        desktopAccessToken = generateDesktopAccessToken();
        defaults.put("kubernetes.desktop.access-token", desktopAccessToken);

        springContext = new SpringApplicationBuilder(KubernetesLensUiApplication.class)
            .headless(false)
            .properties(defaults)
            .run(getParameters().getRaw().toArray(new String[0]));

        WebServerApplicationContext webContext = (WebServerApplicationContext) springContext;
        serverPort = webContext.getWebServer().getPort();
    }

    @Override
    public void start(Stage stage) {
        WebView webView = new WebView();
        WebEngine engine = webView.getEngine();

        Label status = new Label("Loading Kubernetes Lens UI...");
        status.setPadding(new Insets(16));
        status.setStyle("-fx-text-fill: #d2d7dc; -fx-background-color: #15191c;");

        StackPane overlay = new StackPane(status);
        overlay.setMouseTransparent(true);
        StackPane content = new StackPane(webView, overlay);

        engine.getLoadWorker().stateProperty().addListener((observable, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                overlay.setVisible(false);
            }

            if (newState == Worker.State.FAILED) {
                Throwable error = engine.getLoadWorker().getException();
                overlay.setVisible(true);
                status.setText(error == null ? "Failed to load UI" : error.getMessage());
            }
        });

        stage.setTitle("Kubernetes Lens UI");
        stage.setMinWidth(1024);
        stage.setMinHeight(640);
        stage.setScene(new DesktopWindowChrome("Kubernetes Lens UI").createScene(stage, content, 1280, 820));
        stage.setOnCloseRequest(event -> Platform.exit());
        stage.show();

        String encodedToken = URLEncoder.encode(desktopAccessToken, StandardCharsets.UTF_8);
        engine.load("http://127.0.0.1:" + serverPort + "/?desktopAccessToken=" + encodedToken);
    }

    @Override
    public void stop() {
        if (springContext != null) {
            springContext.close();
        }
    }

    private String generateDesktopAccessToken() {
        byte[] randomBytes = new byte[32];
        new SecureRandom().nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
}
