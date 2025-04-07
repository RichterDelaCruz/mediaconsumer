package com.mediaconsumer.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class HashUtils {
    public static String calculateSHA256(Path filePath) throws IOException {
        try (InputStream is = Files.newInputStream(filePath);
             DigestInputStream dis = new DigestInputStream(is, MessageDigest.getInstance("SHA-256"))) {

            // Read the entire file to update the digest
            byte[] buffer = new byte[8192];
            while (dis.read(buffer) != -1) {
                // Just read to update digest
            }

            byte[] digest = dis.getMessageDigest().digest();
            return bytesToHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}