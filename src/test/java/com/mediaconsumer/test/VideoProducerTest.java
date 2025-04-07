package com.mediaconsumer.test;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;

public class VideoProducerTest {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: VideoProducerTest <file_path>");
            return;
        }

        try {
            Path filePath = Path.of(args[0]).toAbsolutePath();
            if (!Files.exists(filePath)) {
                System.out.println("File not found: " + filePath);
                return;
            }

            long fileSize = Files.size(filePath);
            System.out.println("Sending file: " + filePath + " (" + fileSize + " bytes)");

            try (Socket socket = new Socket("localhost", 9090);
                 DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                 DataInputStream dis = new DataInputStream(socket.getInputStream());
                 InputStream fis = Files.newInputStream(filePath)) {

                // Send metadata
                dos.writeUTF(filePath.getFileName().toString());
                dos.writeLong(fileSize);
                dos.flush();

                // Send file data
                byte[] buffer = new byte[4096];
                int read;
                long sent = 0;
                while ((read = fis.read(buffer)) > 0) {
                    dos.write(buffer, 0, read);
                    sent += read;
                    System.out.printf("Progress: %.1f%%\r", (sent * 100.0 / fileSize));
                }
                System.out.println("\nFile sent completely");

                // Get response
                String response = dis.readUTF();
                System.out.println("Server response: " + response);

            } catch (IOException e) {
                System.err.println("Transfer failed: " + e.getMessage());
                e.printStackTrace();
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}