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
import java.nio.file.*; // Import FileAlreadyExistsException, Paths, StandardCopyOption
import java.nio.file.attribute.FileAttribute;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap; // Import ConcurrentHashMap

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

    // --- Static map to hold locks for specific file content hashes ---
    private static final ConcurrentHashMap<String, Object> hashLocks = new ConcurrentHashMap<>();

    public VideoConsumer(Socket clientSocket, VideoQueue videoQueue) {
        this.clientSocket = clientSocket;
        this.videoQueue = videoQueue;
    }

    @Override
    public void run() {
        Path uniqueTempPath = null;
        Path originalTempPathUsedForCompression = null;
        String clientIp = clientSocket.getInetAddress().getHostAddress();
        String originalFileName = "unknown";
        String fileHash = null;
        Path finalPath = null;
        String finalName = "unknown_final";
        boolean successfullyQueued = false; // Flag to track outcome for finally block

        try (DataInputStream dis = new DataInputStream(clientSocket.getInputStream());
             DataOutputStream dos = new DataOutputStream(clientSocket.getOutputStream())) {

            // 1. Read file metadata & Sanitize Filename
            originalFileName = dis.readUTF();
            String sanitizedFileName = sanitizeFileName(originalFileName);
            long fileSize = dis.readLong();
            logger.info("[{}] Received: '{}' -> '{}', Size={}", clientIp, originalFileName, sanitizedFileName, fileSize);

            // 2. Check queue capacity (Optional initial check)
            // Note: isFull() should be synchronized in VideoQueue for this check to be reliable
            if (videoQueue.isFull()) {
                sendResponse(dos, STATUS_QUEUE_FULL);
                logger.warn("[{}] Queue full (initial check). Rejecting '{}'.", clientIp, sanitizedFileName);
                return;
            }

            // 3. Prepare upload dir & UNIQUE temp file path
            Path uploadDir = Paths.get(UPLOAD_DIR);
            Files.createDirectories(uploadDir);
            try {
                uniqueTempPath = Files.createTempFile(uploadDir, "vid-", ".tmp");
                logger.debug("[{}] Created unique temp: {}", clientIp, uniqueTempPath.getFileName());
            } catch (IOException e) {
                logger.error("[{}] Failed to create temp file: {}", clientIp, e.getMessage());
                sendResponse(dos, STATUS_INTERNAL_ERROR);
                return;
            }

            // 4. Receive file
            logger.debug("[{}] Receiving to: {}", clientIp, uniqueTempPath.getFileName());
            FileUtils.receiveFile(dis, uniqueTempPath, fileSize);
            logger.info("[{}] Received {} bytes to {}", clientIp, Files.size(uniqueTempPath), uniqueTempPath.getFileName());

            // 5. Verify size
            if (Files.size(uniqueTempPath) != fileSize) {
                throw new IOException("Size mismatch for " + sanitizedFileName);
            }

            // 6. Calculate hash (needed for locking)
            fileHash = HashUtils.calculateSHA256(uniqueTempPath);
            logger.debug("[{}] Hash: {} for {}", clientIp, fileHash, sanitizedFileName);

            // --- Synchronization Block based on Content Hash ---
            Object hashLock = hashLocks.computeIfAbsent(fileHash, k -> new Object());

            synchronized (hashLock) {
                logger.debug("[{}] Acquired lock for hash {}", clientIp, fileHash);
                // --- CRITICAL SECTION START ---
                try {
                    // 7. Check for Duplicates AGAIN (Inside Lock)
                    if (FileUtils.isDuplicate(fileHash, uploadDir, uniqueTempPath)) {
                        deleteFileIfExists(uniqueTempPath, "duplicate detected inside lock");
                        sendResponse(dos, STATUS_DUPLICATE);
                        logger.warn("[{}] Duplicate file confirmed (Hash: {}) INSIDE LOCK for '{}'. Temp deleted.", clientIp, fileHash, sanitizedFileName);
                        uniqueTempPath = null;
                        return; // Exit synchronized block and run method
                    }

                    // 8. Determine FINAL Filename (Timestamp + Unique Suffix)
                    String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmssSSS"));
                    String tempFileName = uniqueTempPath.getFileName().toString();
                    String uniqueSuffix = "";
                    int prefixEnd = tempFileName.indexOf('-');
                    int suffixStart = tempFileName.lastIndexOf('.');
                    if (prefixEnd != -1 && suffixStart != -1 && suffixStart > prefixEnd + 1) {
                        uniqueSuffix = tempFileName.substring(prefixEnd + 1, suffixStart);
                    } else {
                        uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
                        logger.warn("[{}] Could not parse unique suffix from temp file '{}', using UUID fallback.", clientIp, tempFileName);
                    }
                    finalName = String.format("%s_%s_%s", timestamp, uniqueSuffix, sanitizedFileName);
                    finalPath = uploadDir.resolve(finalName);
                    logger.debug("[{}] Determined final path INSIDE LOCK: {}", clientIp, finalPath.getFileName());

                    Path currentPath = uniqueTempPath;

                    // 9. Compression (if needed)
                    if (fileSize > MAX_FILE_SIZE) {
                        logger.info("[{}] File '{}' needs compression INSIDE LOCK.", clientIp, sanitizedFileName);
                        originalTempPathUsedForCompression = uniqueTempPath;
                        Path compressedPath = null;
                        try {
                            compressedPath = VideoUtils.compressVideo(originalTempPathUsedForCompression);
                            logger.info("[{}] Compression successful INSIDE LOCK. New path: {}", clientIp, compressedPath.getFileName());
                            deleteFileIfExists(originalTempPathUsedForCompression, "replaced by compressed version");
                            uniqueTempPath = null; // Original temp is gone
                            currentPath = compressedPath; // Work with compressed file
                        } catch (IOException | RuntimeException compressionEx) {
                            logger.error("[{}] Compression failed INSIDE LOCK for '{}': {}", clientIp, sanitizedFileName, compressionEx.getMessage());
                            sendResponse(dos, STATUS_COMPRESSION_FAILED);
                            // Keep uniqueTempPath for cleanup in outer finally
                            return; // Exit synchronized block
                        }
                    }

                    // 10. Move to final location
                    logger.debug("[{}] Attempting to move {} to {} INSIDE LOCK", clientIp, currentPath.getFileName(), finalPath.getFileName());
                    try {
                        Files.move(currentPath, finalPath, StandardCopyOption.REPLACE_EXISTING);
                        uniqueTempPath = null; // Source temp is gone
                        originalTempPathUsedForCompression = null; // If compression happened, its source is gone too
                        logger.info("[{}] File finalized INSIDE LOCK: {}", clientIp, finalPath.getFileName());
                    } catch (IOException moveEx) {
                        logger.error("[{}] Failed to move {} to {} INSIDE LOCK: {}", clientIp, currentPath.getFileName(), finalPath.getFileName(), moveEx.getMessage());
                        sendResponse(dos, STATUS_INTERNAL_ERROR);
                        // Keep 'currentPath' (uniqueTempPath or compressedPath) for cleanup in outer finally
                        return; // Exit synchronized block
                    }

                    // 11. Add to queue
                    VideoFile videoFile = new VideoFile(finalPath.toFile(), fileHash);
                    // Check queue status *within the lock* before attempting add
                    if (!videoQueue.isFull()) { // Use synchronized isFull
                        if (videoQueue.add(videoFile)) { // Use synchronized add
                            successfullyQueued = true; // Mark success for finally block
                            sendResponse(dos, STATUS_SUCCESS);
                            logger.info("[{}] File '{}' ADDED to queue successfully INSIDE LOCK.", clientIp, finalName);
                            // Don't nullify finalPath here, let finally block know it was handled
                        } else {
                            // Should be rare now
                            sendResponse(dos, STATUS_QUEUE_FULL);
                            logger.error("[{}] Queue full when adding '{}' INSIDE LOCK despite check! Final file kept.", clientIp, finalName);
                            // successfullyQueued remains false
                        }
                    } else {
                        // Queue was full when checked.
                        sendResponse(dos, STATUS_QUEUE_FULL);
                        logger.warn("[{}] Queue full (checked inside lock) when adding '{}'. Final file kept.", clientIp, finalName);
                        // successfullyQueued remains false
                    }
                    // --- CRITICAL SECTION END ---
                } finally {
                    logger.debug("[{}] Releasing lock for hash {}", clientIp, fileHash);
                    // Optionally remove lock from map if not needed soon (complex)
                }
            } // --- End synchronization (hashLock) block ---

            // --- Outer Exception Handling ---
        } catch (IOException e) {
            logger.error("[{}] IOException processing '{}': {}", clientIp, originalFileName, e.getMessage(), e);
            sendResponseSafe(clientSocket, STATUS_TRANSFER_ERROR);
            // Temp file might still exist
        } catch (Exception e) {
            logger.error("[{}] Unexpected error processing '{}': {}", clientIp, originalFileName, e.getMessage(), e);
            sendResponseSafe(clientSocket, STATUS_INTERNAL_ERROR);
            // Temp file might still exist
        } finally {
            // --- Cleanup ---
            // Close socket
            try {
                if (clientSocket != null && !clientSocket.isClosed()) { clientSocket.close(); }
            } catch (IOException e) { logger.warn("[{}] Error closing socket: {}", clientIp, e.getMessage()); }

            // Delete any remaining temporary files created by THIS thread's execution.
            // uniqueTempPath might exist if error occurred before move/compression delete
            deleteFileIfExists(uniqueTempPath, "final cleanup (unique temp)");
            // originalTempPathUsedForCompression might exist if compression failed after assignment
            deleteFileIfExists(originalTempPathUsedForCompression, "final cleanup (original pre-comp)");

            // Delete the FINAL file ONLY IF it was created (finalPath is not null)
            // AND it was NOT successfully queued (successfullyQueued is false).
            // This covers errors within the sync block after move, and QUEUE_FULL rejections.
            if (!successfullyQueued && finalPath != null) {
                logger.warn("[{}] Deleting final file '{}' because it was not successfully queued.", clientIp, finalPath.getFileName());
                deleteFileIfExists(finalPath, "final cleanup (not queued)");
            }
        }
    } // --- End run() method ---

    // ... (sendResponse, sendResponseSafe, sanitizeFileName, deleteFileIfExists methods remain the same) ...
    // Helper to send response via DataOutputStream (use within try-with-resources)
    private void sendResponse(DataOutputStream dos, String status) {
        try {
            dos.writeUTF(status);
            dos.flush();
            logger.debug("[{}] Sent status '{}'.", clientSocket.getInetAddress().getHostAddress(), status);
        } catch (IOException e) {
            logger.warn("[{}] Failed to send status '{}': {}", clientSocket.getInetAddress().getHostAddress(), status, e.getMessage());
        }
    }

    // Helper to send response when DataOutputStream might not be available (in catch/finally)
    private void sendResponseSafe(Socket socket, String status) {
        try {
            if (socket != null && !socket.isClosed() && socket.isConnected()) {
                // No guarantee getOutputStream won't throw exception here, but worth trying
                DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                dos.writeUTF(status);
                dos.flush();
                logger.debug("[{}] Sent status '{}' (safe mode).", socket.getInetAddress().getHostAddress(), status);
            }
        } catch (IOException e) {
            logger.warn("[{}] Failed to send status '{}' (safe mode): {}", socket.getInetAddress().getHostAddress(), status, e.getMessage());
        }
    }

    // Helper function for filename sanitization
    private String sanitizeFileName(String fileName) {
        if (fileName == null) return "unknown_file";
        // Remove path components, allow alphanumeric, dots, underscores, hyphens
        String nameOnly = new File(fileName).getName();
        // Replace sequences of problematic chars with single underscore
        return nameOnly.replaceAll("[^a-zA-Z0-9.\\-_]+", "_").replaceAll("_+", "_");
    }

    // Helper to delete a file and log outcome
    private void deleteFileIfExists(Path path, String reason) {
        if (path != null) {
            try {
                // Check existence again before deleting, as another thread might have acted
                if (Files.exists(path)) {
                    if (Files.deleteIfExists(path)) {
                        logger.info("[{}] Deleted file '{}' due to: {}", clientSocket.getInetAddress().getHostAddress(), path.getFileName(), reason);
                    } else {
                        logger.warn("[{}] Attempted to delete file '{}' but deleteIfExists returned false (reason: {}).", clientSocket.getInetAddress().getHostAddress(), path.getFileName(), reason);
                    }
                } else {
                    // Log if we intended to delete but it was already gone (might be expected)
                    logger.trace("[{}] Intended to delete file '{}' ({}), but it did not exist.", clientSocket.getInetAddress().getHostAddress(), path.getFileName(), reason);
                }
            } catch (IOException e) {
                logger.warn("[{}] Failed during deletion check/attempt for file '{}' ({}): {}", clientSocket.getInetAddress().getHostAddress(), path.getFileName(), reason, e.getMessage());
            }
        }
    }
}