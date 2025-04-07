package com.mediaconsumer.utils;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

public class FileUtils {
    public static void receiveFile(DataInputStream dis, Path filePath, long fileSize) throws IOException {
        // Create parent directories if they don't exist
        Files.createDirectories(filePath.getParent());

        try (OutputStream fos = Files.newOutputStream(filePath)) {
            byte[] buffer = new byte[4096];
            long received = 0;

            while (received < fileSize) {
                int read = dis.read(buffer, 0, (int) Math.min(buffer.length, fileSize - received));
                if (read == -1) throw new EOFException("Unexpected end of stream");
                fos.write(buffer, 0, read);
                received += read;
            }

            // Verify complete transfer
            if (received != fileSize) {
                throw new IOException("File transfer incomplete. Expected " + fileSize + " bytes, received " + received);
            }
        }
    }

    public static boolean isDuplicate(String hash, Path uploadDir, Path excludedPath) throws IOException {
        if (!Files.exists(uploadDir)) return false;

        try (Stream<Path> paths = Files.list(uploadDir)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> !p.equals(excludedPath)) // Exclude the temp file
                    .filter(p -> !p.getFileName().toString().startsWith(".")) // Exclude hidden files
                    .filter(p -> !p.getFileName().toString().startsWith("TEMP_")) // Exclude all temp files
                    .anyMatch(path -> {
                        try {
                            String currentHash = HashUtils.calculateSHA256(path);
                            boolean isDuplicate = currentHash.equals(hash);
                            if (isDuplicate) {
                                System.out.println("Found duplicate: " + path + " with hash: " + currentHash);
                            }
                            return isDuplicate;
                        } catch (IOException e) {
                            return false;
                        }
                    });
        }
    }
}