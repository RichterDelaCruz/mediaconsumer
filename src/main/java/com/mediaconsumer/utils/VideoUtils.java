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
    // Increased timeout slightly, adjust if needed for very large files/slow CPU
    private static final long FFMPEG_TIMEOUT_SECONDS = 120; // 2 minutes timeout

    public static Path compressVideo(Path inputPath) throws IOException {
        // --- Determine Output Filename with .mp4 Extension ---
        String inputFileName = inputPath.getFileName().toString();
        String namePart = inputFileName;
        // Remove original extension before adding .mp4
        int lastDot = inputFileName.lastIndexOf('.');
        if (lastDot > 0) {
            namePart = inputFileName.substring(0, lastDot);
        }
        // Construct filename like "compressed_vid-12345.mp4"
        String outputFileName = "compressed_" + namePart + ".mp4";
        Path outputPath = Paths.get(inputPath.getParent().toString(), outputFileName);
        // --- End Output Filename ---

        // --- Check if FFmpeg executable exists or is configured ---
        String ffmpegPath = "ffmpeg"; // Default: assume it's in PATH
        // Consider adding configuration for ffmpeg path if needed
        // Example: String ffmpegPath = System.getProperty("ffmpeg.path", "ffmpeg");

        List<String> commands = new ArrayList<>();
        commands.add(ffmpegPath);
        commands.add("-y"); // Overwrite output file without asking
        commands.add("-i");
        commands.add(inputPath.toAbsolutePath().toString()); // Input path
        commands.add("-vcodec");
        commands.add("libx264"); // H.264 video codec
        commands.add("-crf");
        commands.add("28");      // Quality setting (higher CRF = smaller file, lower quality)
        commands.add("-preset");
        commands.add("fast");    // Encoding speed preset (faster means less compression)
        commands.add("-acodec");
        commands.add("aac");     // AAC audio codec (widely compatible)
        commands.add("-strict"); // Needed for experimental AAC sometimes (can try removing)
        commands.add("-2");      // Needed for experimental AAC sometimes (can try removing)
        // Fix: Use correct flag for audio bitrate if needed, or let FFmpeg choose default
        // commands.add("-b:a");
        // commands.add("128k"); // Example audio bitrate - often not needed with AAC
        commands.add(outputPath.toAbsolutePath().toString()); // Output path (now ends in .mp4)

        logger.info("Running FFmpeg command: {}", String.join(" ", commands));
        ProcessBuilder pb = new ProcessBuilder(commands);
        pb.directory(inputPath.getParent().toFile()); // Set working directory (optional, safer)

        Process process = null;
        StringBuilder processOutput = new StringBuilder(); // To capture stderr output

        try {
            process = pb.start();

            // Capture stderr (where FFmpeg usually logs info/errors)
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    processOutput.append(line).append(System.lineSeparator());
                    // logger.trace("FFmpeg stderr: {}", line); // Uncomment for verbose FFmpeg output
                }
            }
            // Capture stdout (less common for errors, sometimes has summary)
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line; // Reuse line variable
                while ((line = reader.readLine()) != null) {
                    logger.trace("FFmpeg stdout: {}", line); // Log stdout just in case
                }
            }

            // Wait for process to complete with a timeout
            boolean finished = process.waitFor(FFMPEG_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                logger.error("FFmpeg process did not finish within {} seconds. Forcibly destroying.", FFMPEG_TIMEOUT_SECONDS);
                process.destroyForcibly();
                // Attempt to delete potentially incomplete output
                Files.deleteIfExists(outputPath);
                throw new IOException("FFmpeg compression timed out after " + FFMPEG_TIMEOUT_SECONDS + " seconds for input: " + inputPath.getFileName());
            }

            int exitCode = process.exitValue();

            if (exitCode != 0) {
                // If failed, delete the potentially incomplete output file
                Files.deleteIfExists(outputPath);
                logger.error("FFmpeg failed with exit code {}. Input: '{}'. Output attempted: '{}'.\nFFmpeg stderr output:\n{}",
                        exitCode, inputPath.getFileName(), outputPath.getFileName(), processOutput);
                // Throw exception including the captured stderr output
                throw new IOException("FFmpeg compression failed with exit code " + exitCode + " for input '" + inputPath.getFileName() + "'. Check logs for FFmpeg output.");
            }

            // Verify output file actually exists and has size > 0 after success exit code
            if (!Files.exists(outputPath) || Files.size(outputPath) == 0) {
                logger.error("FFmpeg exited successfully (code 0) but output file '{}' is missing or empty!", outputPath.getFileName());
                throw new IOException("FFmpeg compression failed: Output file missing or empty despite success exit code for input: " + inputPath.getFileName());
            }

            logger.info("FFmpeg compression successful for '{}'. Output path: {}", inputPath.getFileName(), outputPath.getFileName());
            return outputPath; // Return path to the compressed file

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            Files.deleteIfExists(outputPath);
            logger.warn("FFmpeg compression interrupted for input: {}", inputPath.getFileName(), e);
            throw new IOException("Compression interrupted for input: " + inputPath.getFileName(), e);
        } catch (IOException io) {
            // Covers process start errors, timeout, explicit throws above
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(outputPath); // Attempt cleanup
            // Log the original IOException unless it's one we generated with good info
            if (!io.getMessage().startsWith("FFmpeg compression failed") && !io.getMessage().startsWith("FFmpeg compression timed out")) {
                logger.error("IOException during FFmpeg execution for input '{}': {}", inputPath.getFileName(), io.getMessage(), io);
            }
            // Re-throw the original or our more specific exception
            throw io;
        } catch (Exception ex) {
            // Catch unexpected errors
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(outputPath);
            logger.error("Unexpected error during FFmpeg compression for input '{}'", inputPath.getFileName(), ex);
            throw new IOException("Unexpected error during compression for input '" + inputPath.getFileName() + "': " + ex.getMessage(), ex);
        }
    }
}