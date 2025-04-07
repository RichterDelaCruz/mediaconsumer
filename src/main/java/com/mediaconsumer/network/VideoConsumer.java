package com.mediaconsumer.network;

import com.mediaconsumer.model.VideoFile;
import com.mediaconsumer.model.VideoQueue;
import com.mediaconsumer.utils.FileUtils;
import com.mediaconsumer.utils.HashUtils;
import com.mediaconsumer.utils.VideoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;

public class VideoConsumer implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(VideoConsumer.class);
    private static final String UPLOAD_DIR = "uploads";
    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024; // 50MB

    private final Socket clientSocket;
    private final VideoQueue videoQueue;

    public VideoConsumer(Socket clientSocket, VideoQueue videoQueue) {
        this.clientSocket = clientSocket;
        this.videoQueue = videoQueue;
    }

    @Override
    public void run() {
        try (DataInputStream dis = new DataInputStream(clientSocket.getInputStream());
             DataOutputStream dos = new DataOutputStream(clientSocket.getOutputStream())) {

            // Read file metadata
            String fileName = dis.readUTF();
            long fileSize = dis.readLong();

            // Check queue capacity
            if (videoQueue.isFull()) {
                dos.writeUTF("QUEUE_FULL");
                dos.flush();
                logger.warn("Queue full. Rejecting file: {}", fileName);
                return;
            }

            // Prepare upload directory
            Path uploadDir = Paths.get(UPLOAD_DIR);
            Files.createDirectories(uploadDir);

            // Create temp file path
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String savedFileName = timestamp + "_" + fileName;
            Path tempPath = uploadDir.resolve("TEMP_" + savedFileName);
            Path finalPath = uploadDir.resolve(savedFileName);

            // Receive file to temp location
            FileUtils.receiveFile(dis, tempPath, fileSize);
            logger.info("File received to temp location: {}", tempPath);
            logger.info("Temp file size: {} bytes", Files.size(tempPath));

            // Verify file was completely received
            if (Files.size(tempPath) != fileSize) {
                Files.deleteIfExists(tempPath);
                throw new IOException("File size mismatch after transfer");
            }

            // Calculate hash and check for duplicates (excluding temp files)
            String fileHash = HashUtils.calculateSHA256(tempPath);
            logger.debug("File hash calculated: {}", fileHash);

            // List existing files for debugging
            try (Stream<Path> files = Files.list(uploadDir)) {
                logger.info("Existing files in uploads directory:");
                files.forEach(f -> logger.info("- {}", f));
            }

            if (FileUtils.isDuplicate(fileHash, uploadDir, tempPath)) {
                Files.delete(tempPath);
                dos.writeUTF("DUPLICATE_FILE");
                dos.flush();
                logger.warn("Duplicate file detected (excluding temp files)");
                return;
            }

            // Handle large files
            if (fileSize > MAX_FILE_SIZE) {
                logger.info("Compressing large file: {}", savedFileName);
                Path compressedPath = VideoUtils.compressVideo(tempPath);
                Files.delete(tempPath);
                tempPath = compressedPath;
            }

            // Move to final location
            Files.move(tempPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
            logger.info("File moved to final location: {}", finalPath);

            // Add to queue
            VideoFile videoFile = new VideoFile(finalPath.toFile(), fileHash);
            if (videoQueue.add(videoFile)) {
                dos.writeUTF("SUCCESS");
                dos.flush();
                logger.info("File added to queue: {}", savedFileName);
            } else {
                Files.deleteIfExists(finalPath);
                dos.writeUTF("QUEUE_FULL");
                dos.flush();
                logger.warn("Queue full after processing. File deleted: {}", savedFileName);
            }
        } catch (Exception e) {
            logger.error("Error processing client request", e);
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                logger.warn("Error closing client socket", e);
            }
        }
    }
}