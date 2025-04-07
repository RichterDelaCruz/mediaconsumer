package com.mediaconsumer.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class VideoUtils {
    public static Path compressVideo(Path inputPath) throws IOException {
        String outputFileName = "compressed_" + inputPath.getFileName().toString();
        Path outputPath = Paths.get(inputPath.getParent().toString(), outputFileName);

        List<String> commands = new ArrayList<>();
        commands.add("ffmpeg");
        commands.add("-i");
        commands.add(inputPath.toString());
        commands.add("-vcodec");
        commands.add("libx264");
        commands.add("-crf");
        commands.add("28"); // Quality (lower is better, 28 is reasonable for compression)
        commands.add("-preset");
        commands.add("fast");
        commands.add("-acodec");
        commands.add("copy");
        commands.add(outputPath.toString());

        ProcessBuilder pb = new ProcessBuilder(commands);
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new IOException("FFmpeg compression failed with exit code " + exitCode);
            }

            return outputPath;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Compression interrupted", e);
        }
    }
}