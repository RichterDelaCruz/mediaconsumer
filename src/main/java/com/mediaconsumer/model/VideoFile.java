package com.mediaconsumer.model;

import javafx.scene.image.Image;
import javafx.scene.media.Media;
import javafx.scene.media.MediaException; // Import MediaException
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;

public class VideoFile {
    private static final Logger logger = LoggerFactory.getLogger(VideoFile.class);

    private final File file;
    private final String hash;
    private final LocalDateTime uploadTime;
    private transient Media media; // Use transient if you ever serialize this
    private transient Image thumbnail;

    // --- New fields for state tracking ---
    private MediaStatus mediaStatus = MediaStatus.UNKNOWN;
    private String mediaError = null;
    // --- End of new fields ---


    public VideoFile(File file, String hash) {
        this.file = file;
        this.hash = hash;
        this.uploadTime = LocalDateTime.now();
        // Initialize media status immediately (optional, can be done in getMedia)
        initializeMediaState();
    }

    // --- Helper to check initial state ---
    private void initializeMediaState() {
        // Pre-check if file exists and is readable. Do this early.
        if (!file.exists()) {
            mediaStatus = MediaStatus.ERROR;
            mediaError = "File not found: " + file.getAbsolutePath();
            logger.error(mediaError + " (File object created at " + uploadTime + ")");
            return;
        }
        if (!Files.isReadable(file.toPath())) {
            mediaStatus = MediaStatus.ERROR;
            mediaError = "File not readable: " + file.getAbsolutePath();
            logger.error(mediaError);
            return;
        }
        // If basic checks pass, status remains UNKNOWN until getMedia() is called
        logger.trace("Initial file checks passed for {}", file.getName());
    }


    public File getFile() {
        return file;
    }

    public String getHash() {
        return hash;
    }

    public LocalDateTime getUploadTime() {
        return uploadTime;
    }

    // --- Modified getMedia ---
    public Media getMedia() {
        // Only attempt to initialize if status is UNKNOWN (or maybe READY, though unlikely needed)
        // and basic checks passed in constructor/initializeMediaState
        if (media == null && mediaStatus == MediaStatus.UNKNOWN) {
            logger.debug("Attempting to initialize Media object for: {}", file.getName());
            try {
                // Re-verify existence/readability just in case file was deleted after VideoFile creation
                if (!file.exists()) {
                    throw new FileNotFoundException("File deleted after VideoFile object creation: " + file.getAbsolutePath());
                }
                if (!Files.isReadable(file.toPath())) {
                    throw new IOException("File became unreadable after VideoFile object creation: " + file.getAbsolutePath());
                }

                media = new Media(file.toURI().toString());

                // Check for immediate errors after creation (important!)
                if (media.getError() != null) {
                    // Throw the exception associated with the Media object
                    throw media.getError();
                }

                // Add an error listener for asynchronous errors (e.g., during buffering)
                media.setOnError(() -> {
                    MediaException error = media.getError();
                    // Update status only if it wasn't already marked as ERROR
                    if (this.mediaStatus == MediaStatus.READY || this.mediaStatus == MediaStatus.UNKNOWN) {
                        this.mediaStatus = MediaStatus.ERROR;
                        this.mediaError = (error != null && error.getMessage() != null) ?
                                error.getMessage() : "Async media playback error";
                        logger.error("Asynchronous Media Error occurred for [{}] - Status set to ERROR. Type={}, Message={}",
                                file.getName(),
                                error != null ? error.getType() : "N/A",
                                this.mediaError);
                    }
                    // Note: This listener won't help if the initial 'new Media()' fails.
                });

                mediaStatus = MediaStatus.READY; // Mark as ready if no immediate error
                mediaError = null;
                logger.info("Media object initialized successfully for: {}", file.getName());

            } catch (MediaException me) {
                // Catch specific JavaFX media exceptions
                mediaStatus = MediaStatus.ERROR;
                mediaError = String.format("[%s] %s", me.getType(), me.getMessage() != null ? me.getMessage() : "Media format/codec likely unsupported");
                logger.warn("Failed to initialize Media for [{}] (MediaException: {}): {}", file.getName(), me.getType(), me.getMessage());
                media = null; // Ensure media is null on error
            } catch (Exception e) {
                // Catch other exceptions (FileNotFound, Security, Runtime etc.)
                mediaStatus = MediaStatus.ERROR;
                mediaError = e.getMessage() != null ? e.getMessage() : "Failed to initialize media object";
                logger.error("Failed to initialize Media for [{}] (General Exception): {}", file.getName(), e.getMessage(), e);
                media = null; // Ensure media is null on error
            }
        } else if (mediaStatus != MediaStatus.READY) {
            // If called again and status is not READY (i.e., UNKNOWN failed or ERROR), return null
            // or handle as appropriate. Returning null is safer for callers.
            logger.trace("getMedia() called for {}, but status is {}, returning null media object.", file.getName(), mediaStatus);
            return null;
        } else {
            logger.trace("getMedia() called for {}, status is READY, returning existing media object.", file.getName());
        }
        // Only return non-null media if status ended up as READY
        return (mediaStatus == MediaStatus.READY) ? media : null;
    }

    // --- Getters for status and error ---
    public MediaStatus getMediaStatus() {
        // Ensure getMedia() has been called at least once if status is UNKNOWN
        if (mediaStatus == MediaStatus.UNKNOWN) {
            logger.trace("getMediaStatus() called for {} while status UNKNOWN, triggering getMedia().", file.getName());
            getMedia(); // Trigger initialization attempt
        }
        return mediaStatus;
    }

    public String getMediaError() {
        // Ensure getMedia() has been called at least once if status is UNKNOWN
        if (mediaStatus == MediaStatus.UNKNOWN) {
            logger.trace("getMediaError() called for {} while status UNKNOWN, triggering getMedia().", file.getName());
            getMedia(); // Trigger initialization attempt
        }
        return mediaError;
    }

    // --- Enum for Media State ---
    public enum MediaStatus {
        UNKNOWN, // Initial state or checks passed, but Media object not yet created/attempted
        READY,   // Media object created successfully without immediate errors
        ERROR    // File missing/unreadable OR Media object creation failed OR async error occurred
    }
    // --- End of Enum ---


    public Image getThumbnail() {
        return thumbnail;
    }

    public void setThumbnail(Image thumbnail) {
        this.thumbnail = thumbnail;
    }

    @Override
    public String toString() {
        return file.getName();
    }
}