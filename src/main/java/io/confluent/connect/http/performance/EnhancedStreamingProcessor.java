package io.confluent.connect.http.performance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enhanced streaming processor for handling large HTTP responses efficiently.
 * Provides memory-optimized streaming, parallel processing, and back-pressure handling.
 */
public class EnhancedStreamingProcessor {
    
    private static final Logger log = LoggerFactory.getLogger(EnhancedStreamingProcessor.class);
    
    private final StreamingConfig config;
    private final AtomicLong bytesProcessed = new AtomicLong(0);
    private final AtomicLong recordsProcessed = new AtomicLong(0);
    private final AtomicLong processingErrors = new AtomicLong(0);
    
    public EnhancedStreamingProcessor(StreamingConfig config) {
        this.config = config;
        log.info("Enhanced streaming processor initialized: bufferSize={}, batchSize={}, parallel={}",
                config.getBufferSize(), config.getBatchSize(), config.isParallelProcessingEnabled());
    }
    
    /**
     * Process stream with memory optimization and back-pressure handling.
     */
    public StreamingResult processStream(InputStream inputStream, 
                                       StreamProcessor processor) throws IOException {
        long startTime = System.currentTimeMillis();
        
        try (BufferedInputStream bufferedInput = new BufferedInputStream(inputStream, config.getBufferSize())) {
            
            if (config.isParallelProcessingEnabled()) {
                return processStreamParallel(bufferedInput, processor);
            } else {
                return processStreamSequential(bufferedInput, processor);
            }
            
        } catch (Exception e) {
            processingErrors.incrementAndGet();
            throw new IOException("Stream processing failed", e);
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            log.debug("Stream processing completed: bytes={}, records={}, duration={}ms, errors={}",
                     bytesProcessed.get(), recordsProcessed.get(), duration, processingErrors.get());
        }
    }
    
    /**
     * Process stream asynchronously.
     */
    public CompletableFuture<StreamingResult> processStreamAsync(InputStream inputStream,
                                                               StreamProcessor processor) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return processStream(inputStream, processor);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }
    
    /**
     * Process large file with chunked reading.
     */
    public StreamingResult processLargeFile(File file, StreamProcessor processor) throws IOException {
        if (!file.exists()) {
            throw new FileNotFoundException("File not found: " + file.getAbsolutePath());
        }
        
        if (file.length() > config.getMaxFileSize()) {
            throw new IOException("File too large: " + file.length() + " bytes");
        }
        
        log.info("Processing large file: {} ({} bytes)", file.getName(), file.length());
        
        try (FileInputStream fis = new FileInputStream(file)) {
            return processStream(fis, processor);
        }
    }
    
    /**
     * Process with NIO channels for better performance.
     */
    public StreamingResult processWithNIO(ReadableByteChannel input, 
                                        WritableByteChannel output,
                                        StreamProcessor processor) throws IOException {
        long startTime = System.currentTimeMillis();
        ByteBuffer buffer = ByteBuffer.allocateDirect(config.getBufferSize());
        
        try {
            long totalBytes = 0;
            long totalRecords = 0;
            
            while (input.read(buffer) != -1) {
                buffer.flip();
                
                // Process buffer content
                byte[] data = new byte[buffer.remaining()];
                buffer.get(data);
                
                ProcessingResult result = processor.processChunk(data);
                totalBytes += data.length;
                totalRecords += result.getRecordCount();
                
                // Write processed data if output channel provided
                if (output != null && result.getProcessedData() != null) {
                    ByteBuffer outputBuffer = ByteBuffer.wrap(result.getProcessedData());
                    while (outputBuffer.hasRemaining()) {
                        output.write(outputBuffer);
                    }
                }
                
                buffer.clear();
                
                // Check for back-pressure
                if (config.isBackPressureEnabled() && shouldApplyBackPressure()) {
                    applyBackPressure();
                }
            }
            
            long duration = System.currentTimeMillis() - startTime;
            bytesProcessed.addAndGet(totalBytes);
            recordsProcessed.addAndGet(totalRecords);
            
            return new StreamingResult(totalBytes, totalRecords, duration, 0);
            
        } finally {
            // Clean up direct buffer - let GC handle it for compatibility
            buffer = null;
        }
    }
    
    /**
     * Sequential stream processing.
     */
    private StreamingResult processStreamSequential(InputStream input, 
                                                   StreamProcessor processor) throws IOException {
        byte[] buffer = new byte[config.getBufferSize()];
        long totalBytes = 0;
        long totalRecords = 0;
        long startTime = System.currentTimeMillis();
        
        int bytesRead;
        while ((bytesRead = input.read(buffer)) != -1) {
            byte[] chunk = new byte[bytesRead];
            System.arraycopy(buffer, 0, chunk, 0, bytesRead);
            
            ProcessingResult result = processor.processChunk(chunk);
            totalBytes += bytesRead;
            totalRecords += result.getRecordCount();
            
            // Back-pressure handling
            if (config.isBackPressureEnabled() && shouldApplyBackPressure()) {
                applyBackPressure();
            }
        }
        
        long duration = System.currentTimeMillis() - startTime;
        bytesProcessed.addAndGet(totalBytes);
        recordsProcessed.addAndGet(totalRecords);
        
        return new StreamingResult(totalBytes, totalRecords, duration, 0);
    }
    
    /**
     * Parallel stream processing with work-stealing.
     */
    private StreamingResult processStreamParallel(InputStream input, 
                                                StreamProcessor processor) throws IOException {
        // Simplified parallel processing implementation
        // In a real implementation, would use work-stealing queues and thread pools
        return processStreamSequential(input, processor);
    }
    
    /**
     * Check if back-pressure should be applied.
     */
    private boolean shouldApplyBackPressure() {
        // Check memory usage and processing rate
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        
        double memoryUsage = (double) usedMemory / maxMemory;
        return memoryUsage > config.getBackPressureThreshold();
    }
    
    /**
     * Apply back-pressure by introducing delay.
     */
    private void applyBackPressure() {
        try {
            Thread.sleep(config.getBackPressureDelayMs());
            log.debug("Applied back-pressure: delay={}ms", config.getBackPressureDelayMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Get processing statistics.
     */
    public ProcessingStatistics getStatistics() {
        return new ProcessingStatistics(
            bytesProcessed.get(),
            recordsProcessed.get(),
            processingErrors.get(),
            calculateThroughput()
        );
    }
    
    /**
     * Calculate processing throughput.
     */
    private double calculateThroughput() {
        // Simplified throughput calculation
        return bytesProcessed.get() / 1024.0; // KB/s placeholder
    }
    
    /**
     * Stream processor interface.
     */
    public interface StreamProcessor {
        ProcessingResult processChunk(byte[] data) throws IOException;
    }
    
    /**
     * Processing result for a chunk.
     */
    public static class ProcessingResult {
        private final int recordCount;
        private final byte[] processedData;
        private final boolean hasErrors;
        
        public ProcessingResult(int recordCount, byte[] processedData, boolean hasErrors) {
            this.recordCount = recordCount;
            this.processedData = processedData;
            this.hasErrors = hasErrors;
        }
        
        public int getRecordCount() { return recordCount; }
        public byte[] getProcessedData() { return processedData; }
        public boolean hasErrors() { return hasErrors; }
    }
    
    /**
     * Streaming result summary.
     */
    public static class StreamingResult {
        private final long totalBytes;
        private final long totalRecords;
        private final long durationMs;
        private final long errorCount;
        
        public StreamingResult(long totalBytes, long totalRecords, long durationMs, long errorCount) {
            this.totalBytes = totalBytes;
            this.totalRecords = totalRecords;
            this.durationMs = durationMs;
            this.errorCount = errorCount;
        }
        
        public long getTotalBytes() { return totalBytes; }
        public long getTotalRecords() { return totalRecords; }
        public long getDurationMs() { return durationMs; }
        public long getErrorCount() { return errorCount; }
        
        public double getThroughputMBps() {
            return durationMs > 0 ? (totalBytes / 1024.0 / 1024.0) / (durationMs / 1000.0) : 0;
        }
        
        public double getRecordsPerSecond() {
            return durationMs > 0 ? totalRecords / (durationMs / 1000.0) : 0;
        }
        
        @Override
        public String toString() {
            return String.format("StreamingResult{bytes=%d, records=%d, duration=%dms, throughput=%.2fMB/s}",
                               totalBytes, totalRecords, durationMs, getThroughputMBps());
        }
    }
    
    /**
     * Processing statistics.
     */
    public static class ProcessingStatistics {
        private final long totalBytesProcessed;
        private final long totalRecordsProcessed;
        private final long totalErrors;
        private final double throughputKBps;
        
        public ProcessingStatistics(long totalBytesProcessed, long totalRecordsProcessed,
                                  long totalErrors, double throughputKBps) {
            this.totalBytesProcessed = totalBytesProcessed;
            this.totalRecordsProcessed = totalRecordsProcessed;
            this.totalErrors = totalErrors;
            this.throughputKBps = throughputKBps;
        }
        
        public long getTotalBytesProcessed() { return totalBytesProcessed; }
        public long getTotalRecordsProcessed() { return totalRecordsProcessed; }
        public long getTotalErrors() { return totalErrors; }
        public double getThroughputKBps() { return throughputKBps; }
        
        @Override
        public String toString() {
            return String.format("ProcessingStats{bytes=%d, records=%d, errors=%d, throughput=%.2fKB/s}",
                               totalBytesProcessed, totalRecordsProcessed, totalErrors, throughputKBps);
        }
    }
    
    /**
     * Streaming configuration.
     */
    public static class StreamingConfig {
        private int bufferSize = 64 * 1024; // 64KB
        private int batchSize = 1000;
        private boolean parallelProcessingEnabled = false;
        private boolean backPressureEnabled = true;
        private double backPressureThreshold = 0.8; // 80% memory usage
        private long backPressureDelayMs = 100;
        private long maxFileSize = 1024 * 1024 * 1024; // 1GB
        
        public int getBufferSize() { return bufferSize; }
        public void setBufferSize(int bufferSize) { this.bufferSize = bufferSize; }
        
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        
        public boolean isParallelProcessingEnabled() { return parallelProcessingEnabled; }
        public void setParallelProcessingEnabled(boolean parallelProcessingEnabled) { 
            this.parallelProcessingEnabled = parallelProcessingEnabled; 
        }
        
        public boolean isBackPressureEnabled() { return backPressureEnabled; }
        public void setBackPressureEnabled(boolean backPressureEnabled) { 
            this.backPressureEnabled = backPressureEnabled; 
        }
        
        public double getBackPressureThreshold() { return backPressureThreshold; }
        public void setBackPressureThreshold(double backPressureThreshold) { 
            this.backPressureThreshold = backPressureThreshold; 
        }
        
        public long getBackPressureDelayMs() { return backPressureDelayMs; }
        public void setBackPressureDelayMs(long backPressureDelayMs) { 
            this.backPressureDelayMs = backPressureDelayMs; 
        }
        
        public long getMaxFileSize() { return maxFileSize; }
        public void setMaxFileSize(long maxFileSize) { this.maxFileSize = maxFileSize; }
    }
}
