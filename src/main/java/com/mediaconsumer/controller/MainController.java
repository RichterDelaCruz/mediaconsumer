package com.mediaconsumer.controller;

import com.mediaconsumer.model.VideoFile;
import com.mediaconsumer.model.VideoQueue;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos; // Import Pos for alignment
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane; // Import StackPane
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaException; // Import MediaException
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.paint.Color; // Import Color
// import javafx.scene.shape.Rectangle; // Rectangle not used here
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;
// import java.util.concurrent.BlockingQueue; // Not directly used

public class MainController {
    private static final Logger logger = LoggerFactory.getLogger(MainController.class); // Use logger

    @FXML private ScrollPane scrollPane;
    @FXML private TilePane videoTilePane;
    @FXML private Label queueStatusLabel;

    private VideoQueue videoQueue;
    private MediaPlayer hoverPlayer; // Keep track of the single hover player

    // Placeholder image loaded once
    private Image placeholderImage;


    public void initialize() { // Use initialize for loading resources tied to FXML
        try {
            placeholderImage = new Image(Objects.requireNonNull(getClass().getResourceAsStream("/placeholder.png")));
            if (placeholderImage.isError()) {
                logger.error("Failed to load placeholder image!", placeholderImage.getException());
                // Maybe create a default colored rectangle?
            }
        } catch (Exception e) {
            logger.error("Error loading placeholder image", e);
            // Handle error appropriately, maybe disable UI elements
        }
    }

    public void setVideoQueue(VideoQueue videoQueue) {
        if (videoQueue == null) {
            logger.error("VideoQueue cannot be null!");
            // Handle this error - maybe disable UI?
            return;
        }
        this.videoQueue = videoQueue;
        startQueueMonitor();
        updateQueueStatus();
    }

    private void startQueueMonitor() {
        // Consider using JavaFX Service/Task for better lifecycle management
        Thread queueMonitorThread = new Thread(() -> {
            logger.info("Queue monitor thread started.");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    VideoFile video = videoQueue.take(); // Blocks until item available
                    logger.debug("Took video from queue: {}", video.getFile().getName());
                    // Update UI on the JavaFX Application Thread
                    Platform.runLater(() -> {
                        addVideoToUI(video);
                        updateQueueStatus(); // Update status after adding UI element
                    });
                } catch (InterruptedException e) {
                    logger.info("Queue monitor thread interrupted normally.");
                    Thread.currentThread().interrupt(); // Re-interrupt thread status
                    break; // Exit loop
                } catch (Exception e) {
                    // Log unexpected errors in the loop itself
                    logger.error("Unexpected error in queue monitor loop", e);
                    // Optional: Add a small delay to prevent tight loop on continuous error
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break;}
                }
            }
            logger.info("Queue monitor thread finished.");
        }, "VideoQueueMonitor");
        queueMonitorThread.setDaemon(true); // Allow application to exit even if thread is blocked
        queueMonitorThread.start();
    }

    private void addVideoToUI(VideoFile video) {
        if (video == null) {
            logger.warn("Attempted to add null video to UI. Skipping.");
            return;
        }
        logger.info("Adding video to UI: {}", video.getFile().getName());

        // --- Create UI Components ---
        ImageView thumbnailView = new ImageView(placeholderImage); // Start with placeholder
        thumbnailView.setFitWidth(300);
        thumbnailView.setFitHeight(200);
        thumbnailView.setPreserveRatio(true);

        Label titleLabel = new Label(video.getFile().getName());
        titleLabel.setWrapText(true); // Allow wrapping for long names
        titleLabel.setMaxWidth(280); // Prevent label from pushing container too wide

        // Use StackPane to overlay error messages/icons if needed
        StackPane thumbnailContainer = new StackPane(thumbnailView);
        thumbnailContainer.setPrefSize(300, 200); // Set preferred size for layout

        VBox tileBox = new VBox(5, thumbnailContainer, titleLabel);
        // Default style, will be overridden for error state
        tileBox.setStyle("-fx-padding: 10; -fx-border-color: #ccc; -fx-border-width: 1; -fx-background-color: #f0f0f0;");
        tileBox.setPrefWidth(320); // Set preferred width for TilePane layout


        // --- Check Media Status and Load Thumbnail/Setup Interaction ---
        VideoFile.MediaStatus status = video.getMediaStatus(); // This triggers media initialization if needed

        switch (status) {
            case ERROR:
                logger.warn("Video file '{}' marked as ERROR. Reason: {}", video.getFile().getName(), video.getMediaError());
                // Style the tile to indicate error
                tileBox.setStyle("-fx-padding: 10; -fx-border-color: #cc0000; -fx-border-width: 2; -fx-background-color: #ffeeee;");

                Label errorLabel = new Label("Incompatible\nFormat");
                errorLabel.setTextFill(Color.RED);
                errorLabel.setStyle("-fx-font-weight: bold; -fx-background-color: rgba(255,255,255,0.7); -fx-padding: 5;");
                StackPane.setAlignment(errorLabel, Pos.CENTER);
                thumbnailContainer.getChildren().add(errorLabel); // Add error label on top

                // Disable hover/click for incompatible files (by not adding listeners)
                break; // End ERROR case

            case READY:
                logger.debug("Video file '{}' status is READY.", video.getFile().getName());
                // Load actual thumbnail in background *only if media is ready*
                loadThumbnailAsync(video, thumbnailView);

                // Setup hover and click listeners *only if media is ready*
                setupHoverPreview(tileBox, video, thumbnailView, thumbnailContainer);
                setupClickToPlay(tileBox, video);
                break; // End READY case

            case UNKNOWN: // Should be rare here after getMediaStatus, but handle defensively
                logger.warn("Video file '{}' status is UNKNOWN after getMediaStatus() call.", video.getFile().getName());
                // Treat as error for now or display a "Processing..." state
                tileBox.setStyle("-fx-padding: 10; -fx-border-color: #ffcc00; -fx-border-width: 2; -fx-background-color: #fffacd;");
                Label processingLabel = new Label("Processing...");
                StackPane.setAlignment(processingLabel, Pos.CENTER);
                thumbnailContainer.getChildren().add(processingLabel);
                break; // End UNKNOWN case
        }

        // Add the prepared tile to the UI
        videoTilePane.getChildren().add(tileBox);
    }

    // Separate method for async thumbnail loading
    private void loadThumbnailAsync(VideoFile video, ImageView thumbnailView) {
        Thread thumbnailThread = new Thread(() -> {
            try {
                // Try creating an Image directly from the video file path
                // Note: This is often unreliable for various formats/codecs
                logger.debug("Attempting background thumbnail load for: {}", video.getFile().getName());
                // Using background loading = true
                Image thumbnail = new Image(video.getFile().toURI().toString(), 300, 200, true, true, true);

                // Wait for image loading or error on FX thread
                Platform.runLater(() -> {
                    if (thumbnail.isError()) {
                        logger.warn("Failed to load thumbnail image for {} (isError=true). Error: {}",
                                video.getFile().getName(), thumbnail.getException() != null ? thumbnail.getException().getMessage() : "Unknown image loading error");
                        // Keep placeholder, maybe add subtle visual indication?
                    } else {
                        thumbnailView.setImage(thumbnail);
                        video.setThumbnail(thumbnail); // Store for potential reuse (e.g., restoring after hover)
                        logger.debug("Thumbnail loaded successfully for: {}", video.getFile().getName());
                    }
                });
            } catch (Exception e) {
                // Log failure, keep placeholder. Don't mark VideoFile as ERROR just for thumbnail.
                logger.warn("Exception during thumbnail loading thread for [{}]: {}", video.getFile().getName(), e.getMessage(), e);
                // Keep placeholder
            }
        }, "ThumbnailLoader-" + video.getFile().getName());
        thumbnailThread.setDaemon(true);
        thumbnailThread.start();
    }

    // --- Refactored Hover Preview Setup ---
    private void setupHoverPreview(VBox tileBox, VideoFile video, ImageView thumbnailView, StackPane thumbnailContainer) {
        tileBox.setOnMouseEntered(event -> {
            // Check status again before trying hover
            if (video.getMediaStatus() != VideoFile.MediaStatus.READY) {
                logger.trace("Skipping hover for {}: Status is {}", video.getFile().getName(), video.getMediaStatus());
                return;
            }

            stopAndDisposeHoverPlayer(tileBox, thumbnailView, thumbnailContainer); // Clean up previous player first

            Media media = video.getMedia(); // Get potentially pre-loaded media
            if (media == null) { // getMedia() returns null if status became ERROR after initial check
                logger.warn("Skipping hover preview for {}: Media object is null (likely became incompatible).", video.getFile().getName());
                return; // Cannot play if media object itself is null or has error
            }

            try {
                logger.debug("Starting hover preview for: {}", video.getFile().getName());
                MediaPlayer newPlayer = new MediaPlayer(media);
                hoverPlayer = newPlayer; // Assign to instance variable BEFORE setting listeners

                MediaView mediaView = new MediaView(newPlayer);
                mediaView.setFitWidth(300);
                mediaView.setFitHeight(200);
                mediaView.setPreserveRatio(true);
                mediaView.setSmooth(true); // Optional: improve quality

                // Ensure MediaView replaces the thumbnailView inside the StackPane
                // RunLater is essential if other events are happening
                Platform.runLater(() -> {
                    if (!thumbnailContainer.getChildren().isEmpty() && thumbnailContainer.getChildren().get(0) instanceof ImageView) {
                        thumbnailContainer.getChildren().set(0, mediaView);
                    } else {
                        logger.warn("Thumbnail container state unexpected during hover for {}", video.getFile().getName());
                        // Attempt to add if empty? Or just log? For now, log.
                    }
                });

                newPlayer.setMute(true);
                newPlayer.setStopTime(Duration.seconds(10));

                newPlayer.setOnReady(() -> {
                    if (newPlayer == hoverPlayer) { // Check if still the active player
                        newPlayer.play();
                        logger.trace("Hover player playing for: {}", video.getFile().getName());
                    } else {
                        logger.trace("Hover player for {} became obsolete before playing, disposing.", video.getFile().getName());
                        disposePlayer(newPlayer); // Dispose if another hover started quickly
                    }
                });

                // Setup cleanup listeners
                newPlayer.setOnEndOfMedia(() -> {
                    logger.trace("Hover player reached endOfMedia for {}", video.getFile().getName());
                    stopAndDisposeHoverPlayer(tileBox, thumbnailView, thumbnailContainer);
                });
                newPlayer.setOnStopped(() -> {
                    logger.trace("Hover player stopped for {}", video.getFile().getName());
                    // Check if disposal is needed or already handled by stopAndDispose...
                    // Redundant call here might be okay, or rely on setOnEndOfMedia/MouseExit
                    restoreThumbnailAndDisposePlayer(newPlayer, tileBox, thumbnailView, thumbnailContainer);
                });
                newPlayer.setOnError(() -> {
                    MediaException error = newPlayer.getError();
                    logger.error("Hover player error for {}: Type={}, Message={}",
                            video.getFile().getName(),
                            error != null ? error.getType() : "N/A",
                            error != null ? error.getMessage() : "Unknown hover error");
                    restoreThumbnailAndDisposePlayer(newPlayer, tileBox, thumbnailView, thumbnailContainer);
                });

            } catch (Exception e) {
                logger.error("Error setting up hover preview player for {}: {}", video.getFile().getName(), e.getMessage(), e);
                restoreThumbnail(tileBox, thumbnailView, thumbnailContainer); // Ensure thumbnail is shown
                if (hoverPlayer != null) { // If player was created before error
                    disposePlayer(hoverPlayer);
                    hoverPlayer = null;
                }
            }
        });

        tileBox.setOnMouseExited(event -> {
            logger.trace("Mouse exited tile for {}", video.getFile().getName());
            stopAndDisposeHoverPlayer(tileBox, thumbnailView, thumbnailContainer);
        });
    }

    // --- Refactored Click Listener Setup ---
    private void setupClickToPlay(VBox tileBox, VideoFile video) {
        tileBox.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                logger.info("Double-click detected for: {}", video.getFile().getName());
                // Check status again just before trying to play
                if (video.getMediaStatus() == VideoFile.MediaStatus.READY) {
                    stopAndDisposeHoverPlayer(tileBox, video.getThumbnail() != null ? new ImageView(video.getThumbnail()) : new ImageView(placeholderImage), (StackPane)tileBox.getChildren().get(0)); // Stop hover if active
                    openFullPlayer(video);
                } else {
                    logger.warn("Cannot play {}: Status is {}", video.getFile().getName(), video.getMediaStatus());
                    showAlert("Playback Error", "Cannot play file: " +
                            (video.getMediaError() != null ? video.getMediaError() : "File is incompatible or processing failed."));
                }
            }
        });
    }


    // --- Hover Player Cleanup Helpers ---

    private void restoreThumbnail(VBox tileBox, ImageView thumbnailView, StackPane thumbnailContainer) {
        // Ensure this runs on FX thread
        Platform.runLater(() -> {
            // Check if the first child is not already the thumbnail image view
            if (!thumbnailContainer.getChildren().isEmpty() && !(thumbnailContainer.getChildren().get(0) instanceof ImageView)) {
                thumbnailContainer.getChildren().set(0, thumbnailView); // Put the thumbnail view back
                logger.trace("Restored thumbnail view for {}", ((Label)tileBox.getChildren().get(1)).getText());
            } else if (thumbnailContainer.getChildren().isEmpty()){
                // If somehow empty, add it back
                thumbnailContainer.getChildren().add(thumbnailView);
                logger.trace("Restored thumbnail view (container was empty) for {}", ((Label)tileBox.getChildren().get(1)).getText());
            }
        });
    }

    private void disposePlayer(MediaPlayer player) {
        if (player != null && player.getStatus() != MediaPlayer.Status.DISPOSED) {
            final String playerName = player.getMedia().getSource(); // Get source for logging before disposal
            logger.trace("Disposing player for: {}", playerName);
            // Ensure disposal happens on FX thread
            Platform.runLater(() -> {
                try {
                    player.dispose();
                    logger.trace("Player disposed successfully for: {}", playerName);
                } catch (Exception e) {
                    logger.warn("Exception during player disposal for {}: {}", playerName, e.getMessage());
                }
            });
        }
    }

    // Renamed for clarity - Stops the current hover player if it exists
    private void stopAndDisposeHoverPlayer(VBox tileBox, ImageView thumbnailView, StackPane thumbnailContainer) {
        if (hoverPlayer != null) {
            logger.trace("Stopping and initiating disposal of current hover player for tile: {}", ((Label)tileBox.getChildren().get(1)).getText());
            MediaPlayer playerToDispose = hoverPlayer;
            hoverPlayer = null; // Clear instance variable *immediately* to prevent race conditions

            // Stop the player. Its setOnStopped handler should manage thumbnail restoration and final disposal.
            try {
                playerToDispose.stop();
            } catch (Exception e) {
                logger.warn("Exception while stopping player for {}: {}", playerToDispose.getMedia().getSource(), e.getMessage());
                // Failsafe: If stop fails, manually restore and dispose
                restoreThumbnailAndDisposePlayer(playerToDispose, tileBox, thumbnailView, thumbnailContainer);
            }
        }
        // If no hoverPlayer was active, ensure thumbnail is visible (might have been left in bad state)
        // else { restoreThumbnail(tileBox, thumbnailView, thumbnailContainer); } // Careful: might cause flicker if called unnecessarily
    }

    // This is primarily called by the player's own event handlers (onStop, onError, onEnd)
    private void restoreThumbnailAndDisposePlayer(MediaPlayer player, VBox tileBox, ImageView thumbnailView, StackPane thumbnailContainer) {
        logger.trace("Restoring thumbnail and disposing player {} for tile {}", player, ((Label)tileBox.getChildren().get(1)).getText());
        restoreThumbnail(tileBox, thumbnailView, thumbnailContainer);
        disposePlayer(player);
        // Clear instance variable if this was the active player (should already be null if called via stopAndDisposeHoverPlayer)
        if (player == hoverPlayer) {
            logger.warn("Instance variable hoverPlayer still pointed to player during restore/dispose callback!");
            hoverPlayer = null;
        }
    }


    // --- Update Queue Status ---
    private void updateQueueStatus() {
        // Ensure queue object exists
        if (videoQueue == null || videoQueue.getQueue() == null) return;

        Platform.runLater(() -> {
            try {
                int currentSize = videoQueue.size();
                // Calculate capacity based on LinkedBlockingQueue which is fixed
                int capacity = videoQueue.getQueue().remainingCapacity() + currentSize;
                queueStatusLabel.setText(String.format("Queue: %d/%d", currentSize, capacity));
                logger.trace("Queue status updated: {}/{}", currentSize, capacity);
            } catch (Exception e) {
                logger.error("Error updating queue status label", e);
            }
        });
    }

    // --- Show Alert ---
    private void showAlert(String title, String message) {
        // Ensure alerts are shown on FX thread
        if (Platform.isFxApplicationThread()) {
            doShowAlert(title, message);
        } else {
            Platform.runLater(() -> doShowAlert(title, message));
        }
    }
    private void doShowAlert(String title, String message){
        try {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        } catch (Exception e) {
            logger.error("Failed to show alert dialog: Title='{}', Message='{}'", title, message, e);
        }
    }


    // --- Open Full Player (Check Status Before Opening) ---
    private void openFullPlayer(VideoFile video) {
        logger.info("Attempting to open full player for: {}", video.getFile().getName());

        // --- Crucial Check: Verify media status before proceeding ---
        VideoFile.MediaStatus status = video.getMediaStatus();
        if (status != VideoFile.MediaStatus.READY) {
            logger.error("Cannot open player for {}: Status is {}. Error: {}",
                    video.getFile().getName(), status, video.getMediaError());
            showAlert("Playback Error", "Cannot play file: " +
                    (video.getMediaError() != null ? video.getMediaError() : "File is incompatible or processing failed."));
            return; // Do not proceed
        }

        Media media = video.getMedia(); // Get the media object (should be ready and non-null)
        if (media == null) { // Should not happen if status is READY, but double-check
            logger.error("Cannot open player for {}: Media object is NULL despite READY status!", video.getFile().getName());
            showAlert("Playback Error", "Cannot play file: Internal error (Media object null).");
            return; // Do not proceed
        }
        // --- End Check ---


        Stage stage = new Stage(); // Create stage early for potential error dialog owner
        MediaPlayer player = null; // Declare player outside try for finally block

        try {
            // Load the player FXML
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/mediaconsumer/view/player.fxml"));
            VBox playerRoot = loader.load(); // Can throw IOException

            // --- Setup media player ---
            player = new MediaPlayer(media);
            final MediaPlayer finalPlayer = player; // Final variable for lambda

            // Setup error handling for the player itself (e.g., runtime playback errors)
            player.setOnError(() -> {
                MediaException error = finalPlayer.getError();
                logger.error("MediaPlayer Error during playback of {}: Type={}, Message={}",
                        video.getFile().getName(),
                        error != null ? error.getType() : "N/A",
                        error != null ? error.getMessage() : "Unknown playback error");
                // Show error relative to the player window if possible
                Stage currentStage = (Stage) playerRoot.getScene().getWindow();
                showAlert("Playback Error", "An error occurred during playback: \n" +
                        (error != null && error.getMessage() != null ? error.getMessage() : "Unknown error."));
                // Close the player window on error
                if (currentStage != null) {
                    currentStage.close(); // This will trigger onCloseRequest -> disposePlayer
                } else {
                    disposePlayer(finalPlayer); // Dispose manually if stage is gone
                }
            });

            MediaView mediaView = (MediaView) playerRoot.lookup("#mediaView");
            if (mediaView == null) throw new IOException("MediaView with fx:id 'mediaView' not found in player.fxml");
            mediaView.setMediaPlayer(player);

            // Setup controls (Ideally done in PlayerController)
            Button playButton = (Button) playerRoot.lookup("#playButton");
            Button pauseButton = (Button) playerRoot.lookup("#pauseButton");
            Button stopButton = (Button) playerRoot.lookup("#stopButton");

            if (playButton == null || pauseButton == null || stopButton == null) {
                throw new IOException("Control buttons (#playButton, #pauseButton, #stopButton) not found in player.fxml");
            }

            playButton.setOnAction(e -> finalPlayer.play());
            pauseButton.setOnAction(e -> finalPlayer.pause());
            stopButton.setOnAction(e -> {
                logger.debug("Stop button clicked for {}", video.getFile().getName());
                // Dispose happens via onCloseRequest when window closes
                Stage currentStage = (Stage) playerRoot.getScene().getWindow();
                if (currentStage != null) {
                    currentStage.close(); // Trigger close request handler
                } else {
                    disposePlayer(finalPlayer); // Fallback if stage is somehow null
                }
            });

            // Configure and show stage
            stage.setTitle(video.getFile().getName());
            stage.setScene(new Scene(playerRoot)); // Auto-size or set preferred size
            stage.setMinWidth(400);
            stage.setMinHeight(300);


            // Ensure player is stopped and disposed when the window is closed manually
            final MediaPlayer playerToDisposeOnClose = player; // Final variable for lambda
            stage.setOnCloseRequest(e -> {
                logger.debug("Player window closing request for {}", video.getFile().getName());
                disposePlayer(playerToDisposeOnClose); // Ensure disposal
            });

            stage.show();
            logger.info("Player window shown for {}", video.getFile().getName());

            // Start playback automatically
            player.play();

        } catch (Exception e) {
            logger.error("Failed to open or setup player window for {}: {}", video.getFile().getName(), e.getMessage(), e);
            showAlert("Player Error", "Could not open video player: " + e.getMessage());
            // Ensure player created before error is disposed
            if (player != null) {
                disposePlayer(player);
            }
            // Close stage if it was created but failed before showing
            if (stage.getOwner() == null && !stage.isShowing()) {
                // Stage might be created but not shown yet
            } else if (stage.isShowing()) {
                stage.close();
            }
        }
    }
}