package dev.codex.k8slens.desktop;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

final class DesktopTitleBar {

    private static final String WINDOW_CONTROL_CLASS = "window-control";

    private final String title;
    private double dragOffsetX;
    private double dragOffsetY;

    DesktopTitleBar(String title) {
        this.title = title;
    }

    HBox create(Stage stage) {
        HBox titleBar = new HBox();
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setMinHeight(28);
        titleBar.setPrefHeight(28);
        titleBar.setMaxHeight(28);
        titleBar.setStyle("-fx-background-color: #111518; -fx-border-color: #2d3338; -fx-border-width: 0 0 1 0;");

        Label appIcon = new Label("K8S");
        appIcon.setStyle("-fx-padding: 0 7 0 9; -fx-text-fill: #c03b12; -fx-font-size: 11px; -fx-font-weight: 800;");

        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-text-fill: #d8dde2; -fx-font-size: 12px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button minimize = windowButton(windowIcon("minimize"), "#2a2f34");
        minimize.setOnAction(event -> stage.setIconified(true));

        Button maximize = windowButton(windowIcon("maximize"), "#2a2f34");
        maximize.setOnAction(event -> toggleMaximized(stage));

        Button close = windowButton(windowIcon("close"), "#c43b32");
        close.setOnAction(event -> Platform.exit());

        titleBar.getChildren().addAll(appIcon, titleLabel, spacer, minimize, maximize, close);
        installDragHandlers(stage, titleBar);
        return titleBar;
    }

    private void installDragHandlers(Stage stage, HBox titleBar) {
        titleBar.setOnMousePressed(event -> {
            if (isWindowButtonEvent(event) || stage.isMaximized()) {
                return;
            }

            dragOffsetX = event.getScreenX() - stage.getX();
            dragOffsetY = event.getScreenY() - stage.getY();
        });

        titleBar.setOnMouseDragged(event -> {
            if (isWindowButtonEvent(event) || stage.isMaximized()) {
                return;
            }

            stage.setX(event.getScreenX() - dragOffsetX);
            stage.setY(event.getScreenY() - dragOffsetY);
        });

        titleBar.setOnMouseClicked(event -> {
            if (!isWindowButtonEvent(event) && event.getClickCount() == 2) {
                toggleMaximized(stage);
            }
        });
    }

    private Button windowButton(Node icon, String hoverColor) {
        Button button = new Button();
        button.getStyleClass().add(WINDOW_CONTROL_CLASS);
        button.setMinSize(46, 28);
        button.setPrefSize(46, 28);
        button.setMaxSize(46, 28);
        button.setGraphic(icon);
        button.setStyle(windowButtonStyle());
        button.setOnMouseEntered(event -> button.setStyle(windowButtonStyle(hoverColor)));
        button.setOnMouseExited(event -> button.setStyle(windowButtonStyle()));
        return button;
    }

    private String windowButtonStyle() {
        return windowButtonStyle("transparent");
    }

    private String windowButtonStyle(String backgroundColor) {
        return "-fx-background-color: transparent;"
                + "-fx-background-radius: 0;"
                + "-fx-border-width: 0;"
                + "-fx-background-color: " + backgroundColor + ";"
                + "-fx-padding: 0;";
    }

    private Node windowIcon(String type) {
        if ("minimize".equals(type)) {
            Region line = new Region();
            line.setMinSize(12, 1);
            line.setPrefSize(12, 1);
            line.setMaxSize(12, 1);
            line.setStyle("-fx-background-color: #d8dde2;");
            return line;
        }

        if ("maximize".equals(type)) {
            Region square = new Region();
            square.setMinSize(10, 10);
            square.setPrefSize(10, 10);
            square.setMaxSize(10, 10);
            square.setStyle("-fx-border-color: #d8dde2; -fx-border-width: 1.2; -fx-background-color: transparent;");
            return square;
        }

        Region first = closeLine(45);
        Region second = closeLine(-45);
        return new StackPane(first, second);
    }

    private Region closeLine(double rotate) {
        Region line = new Region();
        line.setMinSize(13, 1.5);
        line.setPrefSize(13, 1.5);
        line.setMaxSize(13, 1.5);
        line.setRotate(rotate);
        line.setStyle("-fx-background-color: #d8dde2;");
        return line;
    }

    private boolean isWindowButtonEvent(MouseEvent event) {
        Node node = event.getPickResult().getIntersectedNode();

        while (node != null) {
            if (node.getStyleClass().contains(WINDOW_CONTROL_CLASS)) {
                return true;
            }
            node = node.getParent();
        }

        return false;
    }

    private void toggleMaximized(Stage stage) {
        stage.setMaximized(!stage.isMaximized());
    }
}
