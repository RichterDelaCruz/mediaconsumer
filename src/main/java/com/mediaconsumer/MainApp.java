package com.mediaconsumer;

import com.mediaconsumer.controller.MainController;
import com.mediaconsumer.model.VideoQueue;
import com.mediaconsumer.network.ConsumerServer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;

public class MainApp extends Application {
    private static final Logger logger = LoggerFactory.getLogger(MainApp.class);

    // --- Default Configuration ---
    private static final int DEFAULT_CONSUMER_THREADS = 4;
    private static final int DEFAULT_QUEUE_SIZE = 10;

    // --- Static variables to hold configuration ---
    private static int configuredConsumerThreads; // Will be set in main()
    private static int configuredQueueSize;     // Will be set in main()

    // --- Shared Instances ---
    private static VideoQueue videoQueue;
    private static ConsumerServer server;

    @Override
    public void start(Stage primaryStage) throws Exception {
        // Values should have been validated and set in main() before this point
        logger.info("Initializing application with Configuration:");
        logger.info(" - Consumer Threads: {}", configuredConsumerThreads);
        logger.info(" - Max Queue Size:   {}", configuredQueueSize);

        // --- Ensure uploads directory exists ---
        Path uploadDir = Paths.get("uploads");
        try {
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
                logger.info("Created uploads directory at: {}", uploadDir.toAbsolutePath());
            }
        } catch (IOException e) {
            logger.error("CRITICAL: Failed to create uploads directory: {}. Exiting.", uploadDir.toAbsolutePath(), e);
            // Show alert or just exit in a headless-possible scenario
            System.err.println("Error: Could not create required directory 'uploads'. Check permissions.");
            Platform.exit(); // Exit if we can't create the essential directory
            return;
        }

        // --- Initialize Core Components using configured values ---
        videoQueue = new VideoQueue(configuredQueueSize);
        server = new ConsumerServer(configuredConsumerThreads, videoQueue);

        // Start the server in a separate thread
        Thread serverThread = new Thread(server, "ConsumerServerThread");
        serverThread.setDaemon(true);
        serverThread.start();
        logger.info("ConsumerServer started.");

        // --- Load the GUI ---
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/mediaconsumer/view/main.fxml"));
            Parent root = loader.load();
            MainController controller = loader.getController();
            controller.setVideoQueue(videoQueue);

            primaryStage.setTitle("Media Consumer");
            primaryStage.setScene(new Scene(root, 1200, 800));

            primaryStage.setOnCloseRequest(event -> {
                logger.info("Primary stage closing request received.");
                stopServer();
            });

            primaryStage.show();
            logger.info("GUI Initialized and shown.");

        } catch (IOException e) {
            logger.error("CRITICAL: Failed to load main FXML view. Exiting.", e);
            stopServer();
            Platform.exit();
        } catch (Exception e) {
            logger.error("CRITICAL: An unexpected error occurred during GUI initialization. Exiting.", e);
            stopServer();
            Platform.exit();
        }
    }

    @Override
    public void stop() {
        logger.info("JavaFX Application stop() method called.");
        stopServer();
        logger.info("Application shutdown sequence complete.");
    }

    private static void stopServer() {
        if (server != null) {
            logger.info("Attempting to stop ConsumerServer...");
            server.stop();
            server = null;
        } else {
            logger.warn("stopServer called but server instance was null.");
        }
    }

    /**
     * Prints usage instructions to standard error.
     */
    private static void printUsage() {
        System.err.println("\nUsage: java -jar mediaconsumer.jar [num_threads] [queue_size]");
        System.err.println("  num_threads (optional): Positive integer for consumer thread count (Default: " + DEFAULT_CONSUMER_THREADS + ")");
        System.err.println("  queue_size  (optional): Positive integer for max queue capacity (Default: " + DEFAULT_QUEUE_SIZE + ")");
        System.err.println("\nExample: java -jar mediaconsumer.jar 8 20");
    }

    /**
     * Parses a positive integer argument.
     *
     * @param argValue The string value from args array. Can be null if arg wasn't provided.
     * @param argName  The name of the argument for error messages (e.g., "Consumer threads").
     * @param defaultValue The default value to use if argValue is null.
     * @return The parsed positive integer.
     * @throws IllegalArgumentException if argValue is provided but is not a valid positive integer.
     */
    private static int parsePositiveIntArg(String argValue, String argName, int defaultValue) {
        if (argValue == null) {
            logger.info("{} argument not provided. Using default: {}", argName, defaultValue);
            return defaultValue;
        }

        try {
            int value = Integer.parseInt(argValue);
            if (value <= 0) {
                throw new IllegalArgumentException(argName + " must be a positive integer, but received: " + value);
            }
            logger.info("Parsed {} from argument: {}", argName, value);
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid format for " + argName + ". Expected an integer, but received: '" + argValue + "'", e);
        }
    }


    /**
     * Application entry point. Parses and validates command-line arguments here.
     * If validation fails, prints usage and exits.
     * @param args Command line arguments. Expected: [num_threads] [queue_size]
     */
    public static void main(String[] args) {
        logger.info("Application starting...");

        int threads;
        int queue;

        try {
            // Parse arguments using the strict helper method
            String threadsArg = (args.length >= 1) ? args[0] : null;
            threads = parsePositiveIntArg(threadsArg, "Number of consumer threads", DEFAULT_CONSUMER_THREADS);

            String queueArg = (args.length >= 2) ? args[1] : null;
            queue = parsePositiveIntArg(queueArg, "Maximum queue size", DEFAULT_QUEUE_SIZE);

            // Check for and warn about extra arguments
            if (args.length > 2) {
                logger.warn("Ignoring extra command-line arguments starting from: '{}'. Only the first two arguments (threads, queue size) are used.", args[2]);
            }

        } catch (IllegalArgumentException e) {
            // Catch validation errors from parsePositiveIntArg
            logger.error("Invalid command-line arguments provided: {}", e.getMessage());
            System.err.println("\nError: " + e.getMessage());
            printUsage();
            System.exit(1); // Exit with error code 1
            return; // Necessary because System.exit doesn't immediately stop execution flow for compiler
        }

        // Store the successfully validated/defaulted values
        configuredConsumerThreads = threads;
        configuredQueueSize = queue;

        // Proceed to launch JavaFX only if arguments are valid or defaulted correctly
        logger.info("Command-line arguments processed successfully.");
        launch(args);
    }
}