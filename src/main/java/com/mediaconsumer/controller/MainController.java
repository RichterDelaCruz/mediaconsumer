package com.mediaconsumer.controller;

import com.mediaconsumer.model.VideoFile;
import com.mediaconsumer.model.VideoQueue;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.BlockingQueue;

public class MainController {
    @FXML private ScrollPane scrollPane;
    @FXML private TilePane videoTilePane;
    @FXML private Label queueStatusLabel;

    private VideoQueue videoQueue;
    private MediaPlayer hoverPlayer;

    public void setVideoQueue(VideoQueue videoQueue) {
        this.videoQueue = videoQueue;
        startQueueMonitor();
        updateQueueStatus();
    }

    private void startQueueMonitor() {
        new Thread(() -> {
            while (true) {
                try {
                    VideoFile video = videoQueue.take();
                    Platform.runLater(() -> addVideoToUI(video));
                    updateQueueStatus();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }).start();
    }

    private void addVideoToUI(VideoFile video) {
        try {
            // Debug: Verify file exists
            System.out.println("Adding video: " + video.getFile().getAbsolutePath());
            System.out.println("File exists: " + video.getFile().exists());

            // Initialize media object if null
            if (video.getMedia() == null) {
                video.setMedia(new Media(video.getFile().toURI().toString()));
            }

            // Create thumbnail container
            ImageView thumbnailView = new ImageView();
            thumbnailView.setFitWidth(300);
            thumbnailView.setFitHeight(200);
            thumbnailView.setPreserveRatio(true);

            // Set placeholder first
            Image placeholder = new Image(getClass().getResourceAsStream("/placeholder.png"));
            thumbnailView.setImage(placeholder);

            // Load actual thumbnail in background
            new Thread(() -> {
                try {
                    Image thumbnail = new Image("file:" + video.getFile().getPath(), 300, 200, true, true);
                    Platform.runLater(() -> {
                        thumbnailView.setImage(thumbnail);
                        video.setThumbnail(thumbnail);
                    });
                } catch (Exception e) {
                    System.err.println("Failed to load thumbnail for " + video.getFile().getName());
                    e.printStackTrace();
                }
            }).start();

            // Create video info label
            Label titleLabel = new Label(video.getFile().getName());

            // Create container for the video tile
            javafx.scene.layout.VBox tileBox = new javafx.scene.layout.VBox(5, thumbnailView, titleLabel);
            tileBox.setStyle("-fx-padding: 10; -fx-background-color: #f0f0f0; -fx-border-color: #ccc; -fx-border-width: 1;");

            // Hover effect - preview first 10 seconds
            tileBox.setOnMouseEntered(event -> {
                try {
                    if (hoverPlayer != null) {
                        hoverPlayer.stop();
                    }

                    MediaPlayer newPlayer = new MediaPlayer(video.getMedia());
                    MediaView mediaView = new MediaView(newPlayer);
                    mediaView.setFitWidth(300);
                    mediaView.setFitHeight(200);

                    tileBox.getChildren().set(0, mediaView);
                    newPlayer.setMute(true);
                    newPlayer.play();
                    newPlayer.setStopTime(Duration.seconds(10));

                    hoverPlayer = newPlayer;
                    newPlayer.setOnStopped(() -> {
                        if (tileBox.getChildren().size() > 0) {
                            tileBox.getChildren().set(0, thumbnailView);
                        }
                    });
                } catch (Exception e) {
                    System.err.println("Error during hover preview:");
                    e.printStackTrace();
                }
            });

            // Mouse exit - stop preview
            tileBox.setOnMouseExited(event -> {
                if (hoverPlayer != null) {
                    hoverPlayer.stop();
                    if (tileBox.getChildren().size() > 0) {
                        tileBox.getChildren().set(0, thumbnailView);
                    }
                }
            });

            // Click - open full player
            tileBox.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2) {
                    openFullPlayer(video);
                }
            });

            videoTilePane.getChildren().add(tileBox);
        } catch (Exception e) {
            e.printStackTrace();
            showAlert("Error", "Failed to display video: " + e.getMessage());
        }
    }

    private void updateQueueStatus() {
        Platform.runLater(() -> {
            queueStatusLabel.setText(String.format("Queue: %d/%d",
                    videoQueue.size(), videoQueue.getQueue().remainingCapacity() + videoQueue.size()));
        });
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void openFullPlayer(VideoFile video) {
        try {
            // Verify media first
            Media media = video.getMedia();
            if (media == null) {
                throw new IllegalStateException("Media not initialized for: " + video.getFile());
            }

            // Load the player FXML
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/mediaconsumer/view/player.fxml"));
            VBox playerRoot = loader.load();

            // Setup media player with error handling
            MediaPlayer player = new MediaPlayer(media);
            player.setOnError(() -> {
                System.err.println("Media error: " + player.getError());
                showAlert("Playback Error", player.getError().getMessage());
            });

            MediaView mediaView = (MediaView) playerRoot.lookup("#mediaView");
            mediaView.setMediaPlayer(player);

            // Setup controls
            Button playButton = (Button) playerRoot.lookup("#playButton");
            playButton.setOnAction(e -> player.play());

            Button pauseButton = (Button) playerRoot.lookup("#pauseButton");
            pauseButton.setOnAction(e -> player.pause());

            Button stopButton = (Button) playerRoot.lookup("#stopButton");
            stopButton.setOnAction(e -> {
                player.stop();
                ((Stage) playerRoot.getScene().getWindow()).close();
            });

            // Create and show stage
            Stage stage = new Stage();
            stage.setTitle(video.getFile().getName());
            stage.setScene(new Scene(playerRoot, 800, 600));
            stage.setOnCloseRequest(e -> {
                player.stop();
                player.dispose();
            });
            stage.show();

            // Start playback
            player.play();
        } catch (Exception e) {
            System.err.println("Failed to open player:");
            e.printStackTrace();
            showAlert("Playback Error", "Could not play video: " + e.getMessage());
        }
    }
}