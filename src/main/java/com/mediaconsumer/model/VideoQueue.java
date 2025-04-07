package com.mediaconsumer.model;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class VideoQueue {
    private final BlockingQueue<VideoFile> queue;
    private final int maxSize;

    public VideoQueue(int maxSize) {
        this.maxSize = maxSize;
        this.queue = new LinkedBlockingQueue<>(maxSize);
    }

    public boolean add(VideoFile video) {
        return queue.offer(video);
    }

    public VideoFile take() throws InterruptedException {
        return queue.take();
    }

    public boolean isFull() {
        return queue.size() >= maxSize;
    }

    public int size() {
        return queue.size();
    }

    // Add this method to get the underlying queue if needed
    public BlockingQueue<VideoFile> getQueue() {
        return queue;
    }
}