package dev.codex.k8slens.desktop;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

final class DesktopWindowChrome {

    private static final int RESIZE_MARGIN = 6;

    private final String title;

    DesktopWindowChrome(String title) {
        this.title = title;
    }

    Scene createScene(Stage stage, Node content, double width, double height) {
        stage.initStyle(StageStyle.UNDECORATED);

        BorderPane appRoot = new BorderPane(content);
        appRoot.setTop(new DesktopTitleBar(title).create(stage));
        appRoot.setStyle("-fx-background-color: #15191c;");

        StackPane root = new StackPane(appRoot);
        root.setPadding(new Insets(RESIZE_MARGIN));
        root.setStyle("-fx-background-color: #111518;");
        stage.maximizedProperty().addListener((observable, oldValue, maximized) ->
                root.setPadding(maximized ? Insets.EMPTY : new Insets(RESIZE_MARGIN)));

        Scene scene = new Scene(root, width, height);
        scene.setFill(Color.web("#15191c"));
        new StageResizeController(RESIZE_MARGIN).install(stage, scene);
        return scene;
    }
}
