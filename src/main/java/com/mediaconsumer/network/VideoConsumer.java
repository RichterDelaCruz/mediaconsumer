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
import java.util.stream.Stream; // Keep Stream import

public class VideoConsumer implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(VideoConsumer.class);
    private static final String UPLOAD_DIR = "uploads";
    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024; // 50MB - Keep consistent

    private final Socket clientSocket;
    private final VideoQueue videoQueue;

    // Define distinct status messages for the client
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_QUEUE_FULL = "QUEUE_FULL";
    private static final String STATUS_DUPLICATE = "DUPLICATE_FILE";
    private static final String STATUS_COMPRESSION_FAILED = "COMPRESSION_FAILED";
    private static final String STATUS_TRANSFER_ERROR = "TRANSFER_ERROR";
    private static final String STATUS_INTERNAL_ERROR = "INTERNAL_ERROR";


    public VideoConsumer(Socket clientSocket, VideoQueue videoQueue) {
        this.clientSocket = clientSocket;
        this.videoQueue = videoQueue;
    }

    @Override
    public void run() {
        Path tempPath = null; // Keep track of the primary temp file for cleanup
        String clientIp = clientSocket.getInetAddress().getHostAddress(); // Get client IP for logging
        String originalFileName = "unknown"; // Store original filename for logging

        try (DataInputStream dis = new DataInputStream(clientSocket.getInputStream());
             DataOutputStream dos = new DataOutputStream(clientSocket.getOutputStream())) {

            // 1. Read file metadata & Sanitize Filename
            originalFileName = dis.readUTF(); // Store original name
            String sanitizedFileName = sanitizeFileName(originalFileName);
            long fileSize = dis.readLong();
            logger.info("Connection from {}: Received metadata: OriginalName='{}', SanitizedName='{}', Size={}",
                    clientIp, originalFileName, sanitizedFileName, fileSize);


            // 2. Check queue capacity BEFORE receiving file
            if (videoQueue.isFull()) {
                sendResponse(dos, STATUS_QUEUE_FULL);
                logger.warn("Queue full. Rejecting file '{}' from {}", sanitizedFileName, clientIp);
                return; // Exit early
            }

            // 3. Prepare upload directory and file paths
            Path uploadDir = Paths.get(UPLOAD_DIR);
            Files.createDirectories(uploadDir); // Ensure directory exists

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String uniqueTempName = "TEMP_" + timestamp + "_" + sanitizedFileName;
            String finalName = timestamp + "_" + sanitizedFileName;

            tempPath = uploadDir.resolve(uniqueTempName); // Assign to tracked temp path
            Path finalPath = uploadDir.resolve(finalName);


            // 4. Receive file to temp location
            logger.debug("Receiving file to temporary path: {}", tempPath);
            FileUtils.receiveFile(dis, tempPath, fileSize);
            logger.info("File received to temp location: {}. Size: {} bytes", tempPath, Files.size(tempPath));

            // 5. Verify received file size
            if (Files.size(tempPath) != fileSize) {
                // Don't need to explicitly delete, caught by general exception handler below
                throw new IOException("File size mismatch after transfer for " + sanitizedFileName);
            }

            // 6. Calculate hash and check for duplicates
            String fileHash = HashUtils.calculateSHA256(tempPath);
            logger.debug("File hash calculated: {} for {}", fileHash, sanitizedFileName);

            if (FileUtils.isDuplicate(fileHash, uploadDir, tempPath)) {
                // Delete the received duplicate temp file
                Files.deleteIfExists(tempPath);
                sendResponse(dos, STATUS_DUPLICATE);
                logger.warn("Duplicate file detected (Hash: {}) for '{}' from {}. Temp file deleted.", fileHash, sanitizedFileName, clientIp);
                return; // Exit
            }

            Path currentPath = tempPath; // Path to the file we will potentially add to queue

            // 7. Handle large files - Compression Step with Error Handling
            if (fileSize > MAX_FILE_SIZE) {
                logger.info("File '{}' ({} bytes) exceeds max size ({} bytes). Attempting compression.",
                        sanitizedFileName, fileSize, MAX_FILE_SIZE);
                Path compressedPath = null;
                try {
                    compressedPath = VideoUtils.compressVideo(tempPath);
                    logger.info("Compression successful for '{}'. New path: {}", sanitizedFileName, compressedPath);
                    // If successful, delete the original large temp file
                    Files.delete(tempPath);
                    tempPath = null; // Mark original temp path as deleted
                    currentPath = compressedPath; // Update currentPath to the compressed one

                } catch (IOException | RuntimeException compressionEx) { // Catch expected + unexpected errors
                    logger.error("Compression failed for '{}' from {}: {}", sanitizedFileName, clientIp, compressionEx.getMessage());
                    // If compression failed, the original tempPath still exists, compressedPath might not.
                    // No need to delete original tempPath here, it will be cleaned up by outer catch/finally.
                    // Make sure the incomplete compressed file is deleted (VideoUtils should handle this)
                    sendResponse(dos, STATUS_COMPRESSION_FAILED);
                    return; // Exit processing
                }
            }

            // 8. Move to final location
            logger.debug("Moving processed file from {} to {}", currentPath, finalPath);
            Files.move(currentPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
            // After move, currentPath (temp or compressed) no longer exists.
            tempPath = null; // Mark as moved/deleted
            logger.info("File moved to final location: {}", finalPath);

            // 9. Add to queue
            VideoFile videoFile = new VideoFile(finalPath.toFile(), fileHash);
            if (videoQueue.add(videoFile)) {
                sendResponse(dos, STATUS_SUCCESS);
                logger.info("File '{}' successfully processed and added to queue.", finalName);
            } else {
                // Should be rare if we check isFull() at the start, but handle defensively
                Files.deleteIfExists(finalPath); // Delete the final file if queue is full now
                sendResponse(dos, STATUS_QUEUE_FULL);
                logger.warn("Queue became full after processing '{}'. Final file deleted.", finalName);
            }

        } catch (IOException e) {
            // Handle errors during transfer, file ops, hashing etc.
            logger.error("IOException during client request processing for '{}' from {}: {}", originalFileName, clientIp, e.getMessage(), e);
            // Attempt to send transfer error status if possible
            sendResponseSafe(clientSocket, STATUS_TRANSFER_ERROR);
            // Cleanup the primary temp file if it exists
            deleteFileIfExists(tempPath, "transfer error cleanup");

        } catch (Exception e) {
            // Catch unexpected errors (RuntimeExceptions etc.)
            logger.error("Unexpected error processing client request for '{}' from {}: {}", originalFileName, clientIp, e.getMessage(), e);
            // Attempt to send internal error status if possible
            sendResponseSafe(clientSocket, STATUS_INTERNAL_ERROR);
            // Cleanup the primary temp file if it exists
            deleteFileIfExists(tempPath, "unexpected error cleanup");

        } finally {
            // Ensure socket is always closed
            try {
                if (clientSocket != null && !clientSocket.isClosed()) {
                    logger.debug("Closing client socket for {}", clientIp);
                    clientSocket.close();
                }
            } catch (IOException e) {
                logger.warn("Error closing client socket for {}: {}", clientIp, e.getMessage());
            }
            // Final check: cleanup temp file if something went wrong and it wasn't handled/moved
            deleteFileIfExists(tempPath, "final cleanup");
        }
    }

    // Helper to send response via DataOutputStream (use within try-with-resources)
    private void sendResponse(DataOutputStream dos, String status) {
        try {
            dos.writeUTF(status);
            dos.flush();
            logger.debug("Sent status '{}' to client {}", status, clientSocket.getInetAddress().getHostAddress());
        } catch (IOException e) {
            logger.warn("Failed to send status '{}' to client {}: {}", status, clientSocket.getInetAddress().getHostAddress(), e.getMessage());
        }
    }

    // Helper to send response when DataOutputStream might not be available (in catch/finally)
    private void sendResponseSafe(Socket socket, String status) {
        try {
            if (socket != null && !socket.isClosed() && socket.isConnected()) {
                DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                dos.writeUTF(status);
                dos.flush();
                logger.debug("Sent status '{}' to client {} (safe mode)", status, socket.getInetAddress().getHostAddress());
            }
        } catch (IOException e) {
            logger.warn("Failed to send status '{}' to client {} (safe mode): {}", status, socket.getInetAddress().getHostAddress(), e.getMessage());
        }
    }

    // Helper function for filename sanitization
    private String sanitizeFileName(String fileName) {
        if (fileName == null) return "unknown_file";
        // Remove path components, allow alphanumeric, dots, underscores, hyphens
        String nameOnly = new File(fileName).getName();
        return nameOnly.replaceAll("[^a-zA-Z0-9.\\-_]+", "_");
    }

    // Helper to delete a file and log outcome
    private void deleteFileIfExists(Path path, String reason) {
        if (path != null) {
            try {
                if (Files.deleteIfExists(path)) {
                    logger.info("Deleted temporary file '{}' due to: {}", path.getFileName(), reason);
                }
            } catch (IOException e) {
                logger.warn("Failed to delete temporary file '{}' during {}: {}", path.getFileName(), reason, e.getMessage());
            }
        }
    }
}