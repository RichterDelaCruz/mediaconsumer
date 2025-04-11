package com.mediaconsumer.test;

import java.io.*;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch; // For better waiting
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class VideoProducerTest {

    // Shared configuration
    private static final String HOST = "localhost";
    private static final int PORT = 9090;
    private static final int BUFFER_SIZE = 4096;

    public static void main(String[] args) {
        // --- Argument Parsing ---
        if (args.length < 1) { // Need at least one file path
            System.err.println("Usage: VideoProducerTest <file_path1> [file_path2] [file_path3] ...");
            System.err.println("  Sends each specified video file concurrently using separate threads.");
            return;
        }

        List<Path> filePaths = new ArrayList<>();
        for (String arg : args) {
            try {
                Path p = Paths.get(arg).toAbsolutePath();
                if (!Files.exists(p)) {
                    System.err.println("Error: File not found: " + p);
                    continue; // Skip this file, process others
                }
                if (!Files.isReadable(p)) {
                    System.err.println("Error: File not readable: " + p);
                    continue;
                }
                if (!Files.isRegularFile(p)) {
                    System.err.println("Error: Path is not a regular file: " + p);
                    continue;
                }
                filePaths.add(p);
            } catch (Exception e) {
                System.err.println("Error processing file path '" + arg + "': " + e.getMessage());
            }
        }

        if (filePaths.isEmpty()) {
            System.err.println("Error: No valid files found to send.");
            return;
        }

        int numThreads = filePaths.size(); // One thread per file
        System.out.printf("--> Starting %d producer thread(s) for %d file(s)...%n", numThreads, numThreads);

        // --- Thread Creation and Execution ---
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        // Use CountDownLatch to wait for all threads to finish reliably
        CountDownLatch latch = new CountDownLatch(numThreads);

        int producerIdCounter = 1;
        for (Path filePath : filePaths) {
            final int producerId = producerIdCounter++;
            final Path currentFilePath = filePath; // Need final variable for lambda

            // Create a Runnable task for each producer thread
            Runnable producerTask = () -> {
                try {
                    sendFile(currentFilePath, producerId);
                } finally {
                    latch.countDown(); // Ensure latch is decremented even if sendFile throws exception
                }
            };
            executor.submit(producerTask);
            System.out.printf("    Launched Producer-%d for %s...%n", producerId, currentFilePath.getFileName());
        }

        // --- Wait for all threads to complete ---
        executor.shutdown(); // Disable new tasks from being submitted
        try {
            System.out.println("--> Waiting for all producer threads to finish...");
            // Wait for existing tasks to terminate
            if (!latch.await(5, TimeUnit.MINUTES)) { // Wait up to 5 minutes
                System.err.println("Timeout waiting for producer threads to finish!");
                executor.shutdownNow(); // Cancel currently executing tasks
            } else {
                System.out.println("--> All producer tasks counted down.");
            }
            // Wait a while for existing tasks to terminate after shutdown request
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                System.err.println("Executor did not terminate after shutdown request.");
            }
        } catch (InterruptedException e) {
            System.err.println("Main thread interrupted while waiting.");
            executor.shutdownNow(); // (Re-)Cancel if current thread was interrupted
            Thread.currentThread().interrupt(); // Preserve interrupt status
        }

        System.out.printf("--> Producer test finished.%n");
    }

    // --- Logic executed by each producer thread (same as before) ---
    private static void sendFile(Path filePath, int producerId) {
        // ... (Keep the sendFile method exactly as it was in the previous corrected version) ...
        long fileSize = -1;
        String fileName = filePath.getFileName().toString();

        try {
            fileSize = Files.size(filePath);
        } catch (IOException e) {
            System.err.printf("[Producer-%d] ERROR: Cannot get size for file %s: %s%n",
                    producerId, fileName, e.getMessage());
            return; // Cannot proceed without size
        }

        System.out.printf("[Producer-%d] Attempting to send: %s (%d bytes) to %s:%d%n",
                producerId, fileName, fileSize, HOST, PORT);

        // Use try-with-resources for automatic closing of socket and streams
        try (Socket socket = new Socket(HOST, PORT);
             OutputStream socketOut = socket.getOutputStream(); // Get raw stream first
             DataOutputStream dos = new DataOutputStream(socketOut); // Wrap for UTF/long
             InputStream socketIn = socket.getInputStream();   // Get raw stream first
             DataInputStream dis = new DataInputStream(socketIn); // Wrap for UTF
             InputStream fileIn = Files.newInputStream(filePath)) // Open file stream for reading
        {
            System.out.printf("[Producer-%d] Connected to consumer.%n", producerId);

            // 1. Send metadata
            dos.writeUTF(fileName);
            dos.writeLong(fileSize);
            dos.flush(); // Ensure metadata is sent before file data
            System.out.printf("[Producer-%d] Metadata sent.%n", producerId);

            // 2. Send file data
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            long totalBytesSent = 0;
            // System.out.printf("[Producer-%d] Sending file data... %n", producerId); // Less noisy
            while ((bytesRead = fileIn.read(buffer)) != -1) {
                dos.write(buffer, 0, bytesRead);
                totalBytesSent += bytesRead;
            }
            dos.flush(); // Ensure all file data is sent
            System.out.printf("[Producer-%d] File data sent (%d bytes).%n", producerId, totalBytesSent);

            // 3. Receive response
            String response = dis.readUTF();
            System.out.printf("[Producer-%d] SERVER RESPONSE (%s): %s%n", producerId, fileName, response); // Log filename with response

        } catch (UnknownHostException e) {
            System.err.printf("[Producer-%d] ERROR: Unknown host '%s': %s%n",
                    producerId, HOST, e.getMessage());
        } catch (IOException e) {
            System.err.printf("[Producer-%d] ERROR: Network or I/O failure for %s: %s%n",
                    producerId, fileName, e.getMessage());
        } catch (Exception e) {
            System.err.printf("[Producer-%d] ERROR: Unexpected failure for %s: %s%n",
                    producerId, fileName, e.getMessage());
        }
        System.out.printf("[Producer-%d] Task finished for %s.%n", producerId, fileName); // Log task finish
    }
}