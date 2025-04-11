# MediaConsumer Application

## Overview

MediaConsumer is a Java application designed to act as a server that receives video file uploads over network sockets, processes them concurrently, and displays them in a JavaFX graphical user interface. It demonstrates concepts like producer-consumer patterns, concurrent programming, network sockets, file I/O, and basic media handling.

This application is the "Consumer" part of a producer-consumer system. It listens for connections from "Producer" applications (which are responsible for sending the video files).

## Features

*   **Network Listener:** Listens on a configured port (default 9090) for incoming producer connections via TCP sockets.
*   **Concurrent Upload Handling:** Uses a configurable thread pool (`c` threads) to process multiple simultaneous video uploads without blocking the main server listener.
*   **File Storage:** Saves successfully uploaded videos to a local `uploads/` directory with timestamped filenames.
*   **Bounded Queue:** Implements a configurable bounded queue (`q` max size) to hold references to processed videos before they are displayed in the UI. Uses a leaky bucket approach (rejects new uploads if the queue is full).
*   **Duplicate Detection:** Calculates the SHA-256 hash of incoming videos and rejects uploads if a finalized file with the same hash already exists in the `uploads/` directory (prevents storing identical content multiple times).
*   **Video Compression (Optional):** If an uploaded video exceeds a configured size limit (`MAX_FILE_SIZE`, default 50MB), it attempts to compress the video using FFmpeg (requires FFmpeg to be installed and in the system PATH) before saving the final version. Uses H.264/AAC for potentially better compatibility.
*   **JavaFX GUI:**
    *   Displays thumbnails of successfully processed videos in a tile layout.
    *   Shows a placeholder image while the actual thumbnail is loading.
    *   Provides a 10-second video preview (muted) when hovering the mouse over a video tile.
    *   Allows playing the full video in a separate player window upon double-clicking a tile.
    *   Displays the current queue status (items / capacity).
    *   Handles and visually indicates videos that are incompatible with JavaFX's media player (e.g., unsupported codecs/formats like some `.mov` files).
*   **Producer Feedback:** Sends status codes back to the connecting producer (`SUCCESS`, `QUEUE_FULL`, `DUPLICATE_FILE`, `COMPRESSION_FAILED`, `TRANSFER_ERROR`, `INTERNAL_ERROR`).

## Prerequisites

1.  **Java Development Kit (JDK):** Version 21 (as specified in `pom.xml`). Make sure `JAVA_HOME` is set correctly.
2.  **Maven:** Apache Maven (version 3.6.0 or later recommended) for building the project.
3.  **FFmpeg:** (Required *only* if you want the video compression feature to work for large files).
    *   FFmpeg must be installed on the machine running the MediaConsumer application.
    *   The `ffmpeg` executable must be available in the system's **PATH environment variable** so the application can run it directly. You can test this by simply typing `ffmpeg -version` in your terminal.

## Build Instructions

1.  **Clone/Download:** Get the source code for the `mediaconsumer` project.
2.  **Open Terminal:** Navigate to the root directory of the `mediaconsumer` project (where the `pom.xml` file is located).
3.  **Build with Maven:** Run the following command:
    ```bash
    mvn clean package
    ```
4.  **Output:** This command will compile the source code, run tests (if any), and create necessary files in the `target/` directory. The main executable JAR needed for running the consumer (if using `java -jar`) is typically `mediaconsumer-1.0-SNAPSHOT.jar` when built with the Shade plugin (as assumed by the latest discussions), or potentially a differently named JAR if using only the default JAR plugin. The `javafx:run` goal doesn't require a separate JAR build step.

## Running the Application

You can run the MediaConsumer application in two primary ways:

**Method 1: Using Maven JavaFX Plugin (Recommended for Development)**

1.  **Navigate:** Open a terminal in the project's root directory.
2.  **Run:** Execute the `javafx:run` goal, passing configuration arguments after `--`.
    ```bash
    # Run with default settings (c=4, q=10)
    mvn javafx:run

    # Run with specific settings (e.g., c=8 consumer threads, q=5 queue size)
    mvn javafx:run -- 8 5

    # Run with c=8, q=1 (useful for testing queue limit)
    mvn javafx:run -- 8 1
    ```

**Method 2: Running the Executable JAR (After `mvn package`)**

*   **Note:** This assumes you have built the project using `mvn package` and have correctly configured either the `maven-shade-plugin` or `maven-assembly-plugin` in your `pom.xml` to create a runnable "fat" JAR containing dependencies and the `Main-Class` manifest attribute. Assuming the Shade plugin was used as per recent fixes, the output JAR is `target/mediaconsumer-1.0-SNAPSHOT.jar`.

1.  **Navigate:** Open a terminal in the project's root directory.
2.  **Run:** Execute the `java -jar` command, providing the path to the JAR and configuration arguments.
    ```bash
    # Run with default settings (c=4, q=10)
    java -jar target/mediaconsumer-1.0-SNAPSHOT.jar

    # Run with specific settings (e.g., c=8 consumer threads, q=5 queue size)
    java -jar target/mediaconsumer-1.0-SNAPSHOT.jar 8 5

    # Run with c=8, q=1 (useful for testing queue limit)
    java -jar target/mediaconsumer-1.0-SNAPSHOT.jar 8 1
    ```

## Configuration Arguments

The application accepts two optional command-line arguments:

1.  **`<num_threads>` (c):**
    *   The number of concurrent threads used to process incoming video uploads.
    *   Must be a positive integer.
    *   Default: `4`
2.  **`<queue_size>` (q):**
    *   The maximum number of successfully processed videos that can wait in the internal queue before being displayed in the UI.
    *   Acts as a buffer and backpressure mechanism. If the queue is full when a video finishes processing, the corresponding producer will receive a `QUEUE_FULL` status.
    *   Must be a positive integer.
    *   Default: `10`

**Argument Order:** Arguments must be provided in the order `<num_threads> <queue_size>`. If only one argument is provided, it's interpreted as `<num_threads>`. If invalid arguments are provided, the application prints usage instructions and exits.

## Interacting with the Consumer (Producer Protocol)

A separate Producer application needs to connect to the Consumer's host and port (default `localhost:9090`) via a TCP socket and follow this simple protocol:

1.  **Connect:** Establish a socket connection.
2.  **Send Metadata:**
    *   Send the original filename as a UTF-encoded string (`DataOutputStream.writeUTF(fileName)`).
    *   Send the total file size in bytes as a long (`DataOutputStream.writeLong(fileSize)`).
    *   Flush the output stream (`dos.flush()`).
3.  **Send File Data:** Write the raw byte content of the video file to the socket's output stream.
4.  **Receive Response:** Read the status response from the consumer as a UTF-encoded string (`DataInputStream.readUTF()`).
5.  **Close Connection:** Close the socket.

**Expected Consumer Responses:**

*   `SUCCESS`: File received, processed (hashed, compressed if needed), passed duplicate check, and successfully added to the display queue.
*   `QUEUE_FULL`: File was received/processed, but the internal display queue was full at the time of adding OR the queue was full on the initial check. The upload was rejected.
*   `DUPLICATE_FILE`: File was received, but its content hash matched a previously finalized file already in the `uploads` directory. The upload was rejected.
*   `COMPRESSION_FAILED`: File was large and required compression, but the FFmpeg compression process failed. The upload was rejected.
*   `TRANSFER_ERROR`: An I/O error occurred during file reception or processing (e.g., size mismatch, disk error).
*   `INTERNAL_ERROR`: An unexpected error occurred on the consumer side during processing.

## Directory Structure

*   **`uploads/`:** This directory is created automatically in the location where the application is run.
    *   **Final Videos:** Successfully processed videos are stored here with filenames like `YYYYMMDD_HHMMSSsss_UUID_OriginalName.mp4`.
    *   **Temporary Files:** While processing, temporary files like `vid-NUMBER.tmp` are created here. These should be automatically deleted upon successful processing, rejection, or errors.

## Key Components (Code Structure)

*   **`com.mediaconsumer` (Main Package):**
    *   `MainApp.java`: Entry point, handles argument parsing, JavaFX setup, initializes core components.
*   **`com.mediaconsumer.controller`:**
    *   `MainController.java`: Controller for the main JavaFX UI (`main.fxml`). Manages the display grid, monitors the queue, handles thumbnail loading, hover previews, and launching the full player. Handles incompatible format display.
*   **`com.mediaconsumer.model`:**
    *   `VideoFile.java`: Represents a processed video file, holding its path, hash, and methods to get/check JavaFX `Media` object status (including compatibility).
    *   `VideoQueue.java`: A wrapper around a synchronized `BlockingQueue` with a fixed capacity (`q`), providing thread-safe `add` and `take` operations.
*   **`com.mediaconsumer.network`:**
    *   `ConsumerServer.java`: Listens for incoming socket connections and submits `VideoConsumer` tasks to a thread pool.
    *   `VideoConsumer.java`: Runnable task executed by a consumer thread (`c`). Handles the entire lifecycle of one upload connection (metadata, file reception, hashing, duplicate check, compression, move, queue add). Implements locking for duplicate finalization.
*   **`com.mediaconsumer.utils`:**
    *   `FileUtils.java`: Helper methods for receiving files via sockets and checking for duplicates (ignoring temp files).
    *   `HashUtils.java`: Helper method for calculating SHA-256 hashes of files.
    *   `VideoUtils.java`: Helper method for invoking the external `ffmpeg` process to compress videos.
*   **`src/main/resources`:**
    *   `com.mediaconsumer.view/main.fxml`: FXML layout for the main UI.
    *   `com.mediaconsumer.view/player.fxml`: FXML layout for the full video player window.
    *   `placeholder.png`: Image used before thumbnails load.
    *   `logback.xml`: Logging configuration.

## Logging

*   The application uses **SLF4J** as the logging facade and **Logback** as the backend implementation.
*   Configuration is controlled by `src/main/resources/logback.xml`.
*   By default, logs at `INFO` level and above are printed to the console.
*   Logs from the `com.mediaconsumer` package are configured to show `DEBUG` level messages as well, providing more detail during execution.

## Troubleshooting / Notes

*   **FFmpeg Not Found:** If compression fails, ensure FFmpeg is installed and its executable is in the system's PATH.
*   **Permissions:** The application needs write permissions in the directory where it's run to create the `uploads/` folder and save files. It also needs read permissions for the video files themselves.
*   **Firewall:** If running the producer and consumer on different machines, ensure any firewalls allow TCP connections on the configured port (default 9090).
*   **`StaticLoggerBinder` Warning:** If you see SLF4J warnings about `StaticLoggerBinder`, ensure the `logback-classic` dependency is correctly included in the build (especially if building a fat JAR).
*   **Concurrency Artifacts:** When testing with high concurrency (many producers sending identical files quickly), you might observe behaviors related to race conditions during file finalization or queue addition, even with locking in place. The final implemented logic aims to ensure only one instance of duplicate content is successfully added.
*   **Temporary Files:** If the application crashes or is terminated abruptly, temporary files (`vid-*.tmp`) might be left in the `uploads` directory. These can be manually deleted.
