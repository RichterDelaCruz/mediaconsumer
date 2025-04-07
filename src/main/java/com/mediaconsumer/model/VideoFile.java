package com.mediaconsumer.model;

import javafx.scene.image.Image;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;

import java.io.File;
import java.time.LocalDateTime;

public class VideoFile {
    private final File file;
    private final String hash;
    private final LocalDateTime uploadTime;
    private Media media;
    private Image thumbnail;

    public VideoFile(File file, String hash) {
        this.file = file;
        this.hash = hash;
        this.uploadTime = LocalDateTime.now();
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

    public Media getMedia() {
        if (media == null) {
            try {
                if (!file.exists()) {
                    throw new IllegalStateException("File missing: " + file.getAbsolutePath());
                }
                media = new Media(file.toURI().toString());

                // Quick validation
                MediaPlayer testPlayer = new MediaPlayer(media);
                testPlayer.setOnError(() -> {
                    throw new IllegalStateException("Invalid media file: " +
                            testPlayer.getError().getMessage());
                });
                testPlayer.dispose();
            } catch (Exception e) {
                throw new RuntimeException("Failed to initialize media: " + file.getName(), e);
            }
        }
        return media;
    }

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

    public void setMedia(Media media) {
        this.media = media;  // Actually store the media object!
    }
}