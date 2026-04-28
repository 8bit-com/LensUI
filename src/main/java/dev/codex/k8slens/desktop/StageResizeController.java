package dev.codex.k8slens.desktop;

import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

final class StageResizeController {

    private final int margin;

    StageResizeController(int margin) {
        this.margin = margin;
    }

    void install(Stage stage, Scene scene) {
        ResizeState resizeState = new ResizeState();

        scene.addEventFilter(MouseEvent.MOUSE_MOVED, event -> {
            if (!resizeState.active) {
                scene.setCursor(stage.isMaximized() ? Cursor.DEFAULT : resizeCursor(scene, event.getSceneX(), event.getSceneY()));
            }
        });

        scene.addEventFilter(MouseEvent.MOUSE_EXITED, event -> {
            if (!resizeState.active) {
                scene.setCursor(Cursor.DEFAULT);
            }
        });

        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            ResizeEdges edges = resizeEdges(scene, event.getSceneX(), event.getSceneY());

            if (stage.isMaximized() || !edges.any()) {
                return;
            }

            resizeState.start(stage, event, edges);
            event.consume();
        });

        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (!resizeState.active) {
                return;
            }

            resizeState.apply(stage, event);
            event.consume();
        });

        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (resizeState.active) {
                resizeState.active = false;
                scene.setCursor(Cursor.DEFAULT);
            }
        });
    }

    private Cursor resizeCursor(Scene scene, double x, double y) {
        ResizeEdges edges = resizeEdges(scene, x, y);

        if ((edges.top && edges.left) || (edges.bottom && edges.right)) {
            return Cursor.NW_RESIZE;
        }
        if ((edges.top && edges.right) || (edges.bottom && edges.left)) {
            return Cursor.NE_RESIZE;
        }
        if (edges.left || edges.right) {
            return Cursor.E_RESIZE;
        }
        if (edges.top || edges.bottom) {
            return Cursor.N_RESIZE;
        }
        return Cursor.DEFAULT;
    }

    private ResizeEdges resizeEdges(Scene scene, double x, double y) {
        return new ResizeEdges(
                x <= margin,
                x >= scene.getWidth() - margin,
                y <= margin,
                y >= scene.getHeight() - margin);
    }

    private static class ResizeEdges {
        private final boolean left;
        private final boolean right;
        private final boolean top;
        private final boolean bottom;

        private ResizeEdges(boolean left, boolean right, boolean top, boolean bottom) {
            this.left = left;
            this.right = right;
            this.top = top;
            this.bottom = bottom;
        }

        private boolean any() {
            return left || right || top || bottom;
        }
    }

    private static class ResizeState {
        private boolean active;
        private ResizeEdges edges;
        private double screenX;
        private double screenY;
        private double stageX;
        private double stageY;
        private double stageWidth;
        private double stageHeight;

        private void start(Stage stage, MouseEvent event, ResizeEdges edges) {
            this.active = true;
            this.edges = edges;
            this.screenX = event.getScreenX();
            this.screenY = event.getScreenY();
            this.stageX = stage.getX();
            this.stageY = stage.getY();
            this.stageWidth = stage.getWidth();
            this.stageHeight = stage.getHeight();
        }

        private void apply(Stage stage, MouseEvent event) {
            double deltaX = event.getScreenX() - screenX;
            double deltaY = event.getScreenY() - screenY;

            if (edges.right) {
                stage.setWidth(Math.max(stage.getMinWidth(), stageWidth + deltaX));
            }

            if (edges.bottom) {
                stage.setHeight(Math.max(stage.getMinHeight(), stageHeight + deltaY));
            }

            if (edges.left) {
                double nextWidth = Math.max(stage.getMinWidth(), stageWidth - deltaX);
                stage.setX(stageX + stageWidth - nextWidth);
                stage.setWidth(nextWidth);
            }

            if (edges.top) {
                double nextHeight = Math.max(stage.getMinHeight(), stageHeight - deltaY);
                stage.setY(stageY + stageHeight - nextHeight);
                stage.setHeight(nextHeight);
            }
        }
    }
}
