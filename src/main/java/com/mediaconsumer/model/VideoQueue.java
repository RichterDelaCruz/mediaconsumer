package com.mediaconsumer.model;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
// Import Lock if using that approach
// import java.util.concurrent.locks.ReentrantLock;

public class VideoQueue {
    private final BlockingQueue<VideoFile> queue;
    private final int maxSize;
    // Optional Lock approach:
    // private final ReentrantLock addLock = new ReentrantLock();

    public VideoQueue(int maxSize) {
        // Ensure maxSize is at least 1
        this.maxSize = Math.max(1, maxSize);
        this.queue = new LinkedBlockingQueue<>(this.maxSize);
    }

    /**
     * Attempts to add a video to the queue. Returns true if successful,
     * false if the queue is full. This method is now synchronized
     * to ensure the size check and offer are atomic relative to each other.
     *
     * @param video the VideoFile to add
     * @return true if added successfully, false if queue was full.
     */
    public synchronized boolean add(VideoFile video) {
        // The 'synchronized' keyword on the method ensures only one thread
        // can execute this block at a time for this specific VideoQueue instance.
        if (queue.size() < maxSize) {
            // Offer is generally preferred as it doesn't throw exceptions on failure
            boolean offered = queue.offer(video);
            // In theory, offered should always be true here because we checked size
            // within the synchronized block, but check defensively.
            return offered;
        } else {
            // Queue is full
            return false;
        }

        /* // --- Alternative using ReentrantLock ---
        addLock.lock();
        try {
            if (queue.size() < maxSize) {
                return queue.offer(video);
            } else {
                return false;
            }
        } finally {
            addLock.unlock();
        }
        */
    }


    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * until an element becomes available.
     * (No change needed here, take() is inherently thread-safe and blocking)
     * @return the head of this queue
     * @throws InterruptedException if interrupted while waiting
     */
    public VideoFile take() throws InterruptedException {
        return queue.take();
    }

    /**
     * Checks if the queue currently contains the maximum number of items.
     * Note: The result is instantaneous and might change immediately after.
     * Primarily useful for external checks before attempting add.
     * @return true if the queue size is >= maxSize
     */
    public synchronized boolean isFull() {
        // Also synchronize isFull if the add check relies on it heavily,
        // although the primary fix is synchronizing add itself.
        return queue.size() >= maxSize;
    }

    /**
     * Returns the current number of elements in this queue.
     * @return the number of elements
     */
    public synchronized int size() {
        // Synchronize size() for consistency if other synchronized methods rely on it
        return queue.size();
    }

    /**
     * Returns the remaining capacity of this queue.
     * @return remaining capacity
     */
    public synchronized int remainingCapacity() {
        return queue.remainingCapacity();
    }


    // Add this method to get the underlying queue if needed (e.g., for status label)
    // Note: Accessing the queue directly bypasses synchronization added here.
    public BlockingQueue<VideoFile> getQueue() {
        return queue;
    }
}