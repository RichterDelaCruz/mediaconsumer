package com.mediaconsumer.network;

import com.mediaconsumer.model.VideoFile;
import com.mediaconsumer.model.VideoQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConsumerServer implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(ConsumerServer.class);
    private static final int PORT = 9090;

    private final ExecutorService consumerPool;
    private final VideoQueue videoQueue;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;

    public ConsumerServer(int numConsumers, VideoQueue videoQueue) {
        this.consumerPool = Executors.newFixedThreadPool(numConsumers);
        this.videoQueue = videoQueue;
    }

    @Override
    public void run() {
        running.set(true);
        try {
            serverSocket = new ServerSocket(PORT);
            logger.info("Server started on port {}", PORT);

            while (running.get()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    logger.info("New connection from {}", clientSocket.getInetAddress());
                    consumerPool.execute(new VideoConsumer(clientSocket, videoQueue));
                } catch (IOException e) {
                    if (running.get()) {
                        logger.error("Error accepting client connection", e);
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Failed to start server", e);
        } finally {
            consumerPool.shutdown();
            logger.info("Server stopped");
        }
    }

    public void stop() {
        running.set(false);
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            logger.error("Error closing server socket", e);
        }
    }
}