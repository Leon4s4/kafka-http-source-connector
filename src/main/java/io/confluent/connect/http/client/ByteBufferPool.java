package io.confluent.connect.http.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Byte buffer pool for memory optimization.
 * Provides reusable byte buffers to reduce garbage collection pressure.
 */
public class ByteBufferPool {
    
    private static final Logger log = LoggerFactory.getLogger(ByteBufferPool.class);
    
    private final int bufferSize;
    private final BlockingQueue<ByteBuffer> buffers;
    private final AtomicInteger totalBuffers = new AtomicInteger(0);
    private final AtomicInteger availableBuffers = new AtomicInteger(0);
    private final AtomicLong buffersCreated = new AtomicLong(0);
    private final AtomicLong buffersReused = new AtomicLong(0);
    
    private volatile boolean isShutdown = false;
    
    public ByteBufferPool(int poolSize, int bufferSize) {
        this.bufferSize = bufferSize;
        this.buffers = new ArrayBlockingQueue<>(poolSize);
        
        // Pre-allocate some buffers
        int initialBuffers = Math.min(poolSize / 2, 10);
        for (int i = 0; i < initialBuffers; i++) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(bufferSize);
            buffers.offer(buffer);
            totalBuffers.incrementAndGet();
            availableBuffers.incrementAndGet();
            buffersCreated.incrementAndGet();
        }
        
        log.info("ByteBuffer pool initialized: poolSize={}, bufferSize={}, initial={}", 
                poolSize, bufferSize, initialBuffers);
    }
    
    /**
     * Get a buffer from the pool.
     */
    public ByteBuffer getBuffer() {
        if (isShutdown) {
            throw new IllegalStateException("Buffer pool is shutdown");
        }
        
        ByteBuffer buffer = buffers.poll();
        if (buffer != null) {
            availableBuffers.decrementAndGet();
            buffersReused.incrementAndGet();
            buffer.clear();
            return buffer;
        }
        
        // Create new buffer if pool is empty
        buffer = ByteBuffer.allocateDirect(bufferSize);
        totalBuffers.incrementAndGet();
        buffersCreated.incrementAndGet();
        
        log.debug("Created new buffer: total={}, available={}", 
                 totalBuffers.get(), availableBuffers.get());
        
        return buffer;
    }
    
    /**
     * Return a buffer to the pool.
     */
    public void returnBuffer(ByteBuffer buffer) {
        if (isShutdown || buffer == null) {
            return;
        }
        
        // Clear and reset buffer
        buffer.clear();
        
        // Try to return to pool
        if (buffers.offer(buffer)) {
            availableBuffers.incrementAndGet();
        } else {
            // Pool is full, buffer will be garbage collected
            totalBuffers.decrementAndGet();
            log.debug("Buffer pool full, discarding buffer");
        }
    }
    
    /**
     * Get buffer pool statistics.
     */
    public BufferPoolStatistics getStatistics() {
        return new BufferPoolStatistics(
            totalBuffers.get(),
            availableBuffers.get(),
            buffersCreated.get(),
            buffersReused.get(),
            bufferSize
        );
    }
    
    /**
     * Perform buffer pool maintenance.
     */
    public void performMaintenance() {
        if (isShutdown) {
            return;
        }
        
        // Remove excess buffers if pool is too large
        int targetSize = buffers.size() / 2;
        int removed = 0;
        
        while (buffers.size() > targetSize && removed < 5) {
            ByteBuffer buffer = buffers.poll();
            if (buffer != null) {
                totalBuffers.decrementAndGet();
                availableBuffers.decrementAndGet();
                removed++;
            } else {
                break;
            }
        }
        
        if (removed > 0) {
            log.debug("Removed {} excess buffers during maintenance", removed);
        }
    }
    
    /**
     * Shutdown the buffer pool.
     */
    public void shutdown() {
        isShutdown = true;
        
        int clearedBuffers = buffers.size();
        buffers.clear();
        totalBuffers.set(0);
        availableBuffers.set(0);
        
        log.info("Buffer pool shutdown completed: cleared {} buffers", clearedBuffers);
    }
    
    /**
     * Buffer pool statistics.
     */
    public static class BufferPoolStatistics {
        private final int totalBuffers;
        private final int availableBuffers;
        private final long buffersCreated;
        private final long buffersReused;
        private final int bufferSize;
        
        public BufferPoolStatistics(int totalBuffers, int availableBuffers,
                                  long buffersCreated, long buffersReused, int bufferSize) {
            this.totalBuffers = totalBuffers;
            this.availableBuffers = availableBuffers;
            this.buffersCreated = buffersCreated;
            this.buffersReused = buffersReused;
            this.bufferSize = bufferSize;
        }
        
        public int getTotalBuffers() { return totalBuffers; }
        public int getAvailableBuffers() { return availableBuffers; }
        public long getBuffersCreated() { return buffersCreated; }
        public long getBuffersReused() { return buffersReused; }
        public int getBufferSize() { return bufferSize; }
        
        public double getReuseRate() {
            long totalRequests = buffersCreated + buffersReused;
            return totalRequests > 0 ? (double) buffersReused / totalRequests : 0.0;
        }
        
        public long getTotalMemoryBytes() {
            return (long) totalBuffers * bufferSize;
        }
        
        @Override
        public String toString() {
            return String.format("BufferStats{total=%d, available=%d, reuseRate=%.2f%%, memory=%dKB}",
                               totalBuffers, availableBuffers, getReuseRate() * 100, getTotalMemoryBytes() / 1024);
        }
    }
}
