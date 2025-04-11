package com.mediaconsumer.utils;

import org.slf4j.Logger; // Import Logger
import org.slf4j.LoggerFactory; // Import LoggerFactory

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
// No longer need StandardCopyOption here
import java.util.stream.Stream;

public class FileUtils {
    private static final Logger logger = LoggerFactory.getLogger(FileUtils.class); // Add logger

    public static void receiveFile(DataInputStream dis, Path filePath, long fileSize) throws IOException {
        // Ensure parent directories exist before creating the output stream
        if (filePath.getParent() != null) {
            Files.createDirectories(filePath.getParent());
        }

        try (OutputStream fos = Files.newOutputStream(filePath)) {
            byte[] buffer = new byte[4096];
            long received = 0;
            int read; // Declare read outside loop

            while (received < fileSize) {
                // Calculate max bytes to read in this iteration
                int bytesToRead = (int) Math.min(buffer.length, fileSize - received);
                read = dis.read(buffer, 0, bytesToRead);

                if (read == -1) {
                    // If EOF is reached before expected size, it's an error
                    throw new EOFException("Unexpected end of stream. Expected " + fileSize + " bytes, but received only " + received);
                }
                fos.write(buffer, 0, read);
                received += read;
            }
            // Optional final check (though loop condition handles it)
            // if (received != fileSize) { ... }

        } catch (IOException e) {
            // Attempt to clean up partially written file on error
            try {
                Files.deleteIfExists(filePath);
            } catch (IOException cleanupEx) {
                logger.warn("Failed to delete partially received file '{}': {}", filePath.getFileName(), cleanupEx.getMessage());
            }
            // Re-throw the original exception
            throw e;
        }
        // Verify size one last time outside the try-with-resources
        // This might catch issues if the stream closed prematurely but didn't throw EOF
        long finalSize = Files.size(filePath);
        if (finalSize != fileSize) {
            try {
                Files.deleteIfExists(filePath);
            } catch (IOException cleanupEx) {
                logger.warn("Failed to delete file '{}' after size mismatch ({} != {}): {}", filePath.getFileName(), finalSize, fileSize, cleanupEx.getMessage());
            }
            throw new IOException("File transfer size mismatch. Expected " + fileSize + " bytes, final size " + finalSize);
        }
    }

    /**
     * Checks if a file with the given hash already exists in the upload directory,
     * EXCLUDING temporary files and the specified excludedPath.
     *
     * @param hash         The SHA-256 hash of the file to check.
     * @param uploadDir    The directory containing finalized uploads.
     * @param excludedPath The path of the temporary file currently being processed (to ignore).
     * @return true if a non-temporary file with the same hash exists, false otherwise.
     * @throws IOException If an I/O error occurs reading files.
     */
    public static boolean isDuplicate(String hash, Path uploadDir, Path excludedPath) throws IOException {
        if (hash == null || hash.isEmpty()) {
            logger.warn("isDuplicate check called with null or empty hash.");
            return false; // Cannot be a duplicate if hash is invalid
        }
        if (!Files.isDirectory(uploadDir)) {
            logger.trace("Upload directory {} does not exist, cannot be duplicate.", uploadDir);
            return false; // No directory, no duplicates
        }

        logger.trace("Checking for duplicates with hash {} in {}, excluding {}", hash, uploadDir, excludedPath != null ? excludedPath.getFileName() : "null");

        try (Stream<Path> paths = Files.list(uploadDir)) {
            return paths
                    .filter(Files::isRegularFile) // Ensure it's a file
                    .filter(p -> !p.equals(excludedPath)) // Ignore the temp file itself if provided
                    // *** CRITICAL FILTER: Ignore ALL files starting with "vid-" and ending with ".tmp" (or your temp pattern) ***
                    // *** AND ignore files starting with "TEMP_" (old pattern, defensive) ***
                    .filter(p -> {
                        String filename = p.getFileName().toString();
                        boolean isTemp = filename.startsWith("vid-") && filename.endsWith(".tmp");
                        boolean isOldTemp = filename.startsWith("TEMP_");
                        // Also good to ignore macOS hidden files etc.
                        boolean isHidden = filename.startsWith(".");
                        return !isTemp && !isOldTemp && !isHidden; // Keep only if NOT temp and NOT hidden
                    })
                    .anyMatch(path -> {
                        // Only calculate hash for potential non-temp matches
                        logger.trace("Calculating hash for potential duplicate check: {}", path.getFileName());
                        try {
                            String existingHash = HashUtils.calculateSHA256(path);
                            boolean match = hash.equalsIgnoreCase(existingHash); // Case-insensitive compare just in case
                            if (match) {
                                logger.warn("Duplicate found! Hash {} matches existing file: {}", hash, path.getFileName());
                            }
                            return match;
                        } catch (IOException e) {
                            logger.error("IOException calculating hash for {}: {}", path.getFileName(), e.getMessage());
                            return false; // Treat as non-duplicate if error occurs hashing existing file
                        }
                    });
        }
    }
}