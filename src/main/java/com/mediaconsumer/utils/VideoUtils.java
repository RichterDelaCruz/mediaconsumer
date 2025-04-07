package com.mediaconsumer.utils;

import org.slf4j.Logger; // Import Logger
import org.slf4j.LoggerFactory; // Import LoggerFactory

import java.io.BufferedReader; // For reading process output
import java.io.IOException;
import java.io.InputStreamReader; // For reading process output
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit; // For process timeout

public class VideoUtils {
    private static final Logger logger = LoggerFactory.getLogger(VideoUtils.class); // Add logger
    private static final long FFMPEG_TIMEOUT_SECONDS = 60; // Add a timeout for FFmpeg

    public static Path compressVideo(Path inputPath) throws IOException {
        String outputFileName = "compressed_" + inputPath.getFileName().toString();
        Path outputPath = Paths.get(inputPath.getParent().toString(), outputFileName);

        // --- Check if FFmpeg executable exists or is configured ---
        // String ffmpegPath = System.getenv("FFMPEG_PATH"); // Example: Get from env variable
        String ffmpegPath = "ffmpeg"; // Default: assume it's in PATH
        // You might want a more robust way to find/configure ffmpeg location

        List<String> commands = new ArrayList<>();
        commands.add(ffmpegPath);
        commands.add("-y"); // Overwrite output file if it exists
        commands.add("-i");
        commands.add(inputPath.toAbsolutePath().toString()); // Use absolute path
        commands.add("-vcodec");
        commands.add("libx264"); // Common codec, good compatibility
        commands.add("-crf");
        commands.add("28"); // Constant Rate Factor (quality vs size)
        commands.add("-preset");
        commands.add("fast"); // Encoding speed preset
        commands.add("-acodec");
        commands.add("aac"); // Use AAC audio codec for better compatibility than 'copy' sometimes
        commands.add("-strict"); // Needed for experimental AAC sometimes
        commands.add("-2");      // Needed for experimental AAC sometimes
        commands.add(outputPath.toAbsolutePath().toString()); // Use absolute path

        logger.info("Running FFmpeg command: {}", String.join(" ", commands));
        ProcessBuilder pb = new ProcessBuilder(commands);
        // Do NOT redirect error stream if you want to capture it separately
        // pb.redirectErrorStream(true); // We will read stderr manually

        Process process = null;
        StringBuilder processOutput = new StringBuilder(); // To capture output

        try {
            process = pb.start();

            // Capture stderr (FFmpeg often logs progress/errors here)
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    processOutput.append(line).append(System.lineSeparator());
                    // logger.trace("FFmpeg stderr: {}", line); // Optional: log detailed output
                }
            }
            // Capture stdout as well (less common for errors, but possible)
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // processOutput.append(line).append(System.lineSeparator()); // Usually less interesting
                    logger.trace("FFmpeg stdout: {}", line);
                }
            }

            // Wait for process to complete with a timeout
            boolean finished = process.waitFor(FFMPEG_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly(); // Kill if timed out
                throw new IOException("FFmpeg compression timed out after " + FFMPEG_TIMEOUT_SECONDS + " seconds.");
            }

            int exitCode = process.exitValue();

            if (exitCode != 0) {
                // If failed, delete the potentially incomplete output file
                Files.deleteIfExists(outputPath);
                logger.error("FFmpeg failed with exit code {}. Output:\n{}", exitCode, processOutput);
                // Throw exception with captured output
                throw new IOException("FFmpeg compression failed with exit code " + exitCode + ". Output: " + processOutput);
            }

            logger.info("FFmpeg compression successful for {}. Output path: {}", inputPath.getFileName(), outputPath);
            return outputPath;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Clean up if interrupted
            if (process != null) process.destroyForcibly();
            Files.deleteIfExists(outputPath);
            logger.warn("FFmpeg compression interrupted.", e);
            throw new IOException("Compression interrupted", e);
        } catch (IOException io) {
            // Clean up if other IO errors occur (e.g., process cannot start)
            if (process != null) process.destroyForcibly();
            Files.deleteIfExists(outputPath);
            // Re-throw IOExceptions (including our custom one)
            throw io;
        } catch (Exception ex) {
            // Catch unexpected errors
            if (process != null) process.destroyForcibly();
            Files.deleteIfExists(outputPath);
            logger.error("Unexpected error during FFmpeg execution.", ex);
            throw new IOException("Unexpected error during compression: " + ex.getMessage(), ex);
        }
    }
}