package dev.codex.k8slens.desktop;

import dev.codex.k8slens.KubernetesLensUiApplication;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.HashMap;
import java.util.Map;

public class KubernetesLensDesktopApplication extends Application {

    private ConfigurableApplicationContext springContext;
    private int serverPort;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void init() {
        System.setProperty("java.awt.headless", "false");

        Map<String, Object> defaults = new HashMap<>();
        defaults.put("server.port", "0");
        defaults.put("spring.main.banner-mode", "off");

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

        BorderPane root = new BorderPane(webView);
        StackPane overlay = new StackPane(status);
        overlay.setMouseTransparent(true);
        root.setTop(overlay);

        engine.getLoadWorker().stateProperty().addListener((observable, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                root.setTop(null);
            }

            if (newState == Worker.State.FAILED) {
                Throwable error = engine.getLoadWorker().getException();
                status.setText(error == null ? "Failed to load UI" : error.getMessage());
            }
        });

        stage.setTitle("Kubernetes Lens UI");
        stage.setScene(new Scene(root, 1280, 820));
        stage.setMinWidth(1024);
        stage.setMinHeight(640);
        stage.setOnCloseRequest(event -> Platform.exit());
        stage.show();

        engine.load("http://127.0.0.1:" + serverPort + "/");
    }

    @Override
    public void stop() {
        if (springContext != null) {
            springContext.close();
        }
    }
}
