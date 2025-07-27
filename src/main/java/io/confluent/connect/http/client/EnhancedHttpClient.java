package io.confluent.connect.http.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Enhanced HTTP client with HTTP/2 support, async operations, and performance optimizations.
 * Provides connection pooling, memory optimization, and comprehensive metrics.
 */
public class EnhancedHttpClient {
    
    private static final Logger log = LoggerFactory.getLogger(EnhancedHttpClient.class);
    
    private final HttpClientConfig config;
    private final ConnectionPool connectionPool;
    private final ExecutorService asyncExecutor;
    private final ScheduledExecutorService maintenanceExecutor;
    private final ByteBufferPool bufferPool;
    
    // Performance metrics
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successfulRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);
    private final AtomicLong totalBytesTransferred = new AtomicLong(0);
    private final AtomicLong averageResponseTime = new AtomicLong(0);
    
    // Connection and performance settings
    private volatile boolean isShutdown = false;
    
    public EnhancedHttpClient(HttpClientConfig config) {
        this.config = config;
        this.connectionPool = new ConnectionPool(config);
        this.asyncExecutor = createAsyncExecutor();
        this.maintenanceExecutor = createMaintenanceExecutor();
        this.bufferPool = new ByteBufferPool(config.getBufferPoolSize(), config.getBufferSize());
        
        startMaintenanceTasks();
        
        log.info("Enhanced HTTP client initialized: http2={}, async={}, connections={}, bufferPool={}",
                config.isHttp2Enabled(), config.isAsyncEnabled(), 
                config.getMaxConnections(), config.getBufferPoolSize());
    }
    
    /**
     * Execute HTTP request synchronously.
     */
    public HttpResponse execute(HttpRequest request) throws IOException {
        long startTime = System.currentTimeMillis();
        totalRequests.incrementAndGet();
        
        try {
            // Get connection from pool
            HttpConnection connection = connectionPool.getConnection(request.getUrl());
            
            // Prepare request
            PreparedRequest preparedRequest = prepareRequest(request);
            
            // Execute request
            HttpResponse response = executeRequest(connection, preparedRequest);
            
            // Update metrics
            long responseTime = System.currentTimeMillis() - startTime;
            updateMetrics(response, responseTime);
            
            // Return connection to pool
            connectionPool.returnConnection(connection);
            
            successfulRequests.incrementAndGet();
            return response;
            
        } catch (Exception e) {
            failedRequests.incrementAndGet();
            log.error("HTTP request failed: {}", e.getMessage(), e);
            throw new IOException("HTTP request failed", e);
        }
    }
    
    /**
     * Execute HTTP request asynchronously.
     */
    public CompletableFuture<HttpResponse> executeAsync(HttpRequest request) {
        if (!config.isAsyncEnabled()) {
            throw new UnsupportedOperationException("Async execution not enabled");
        }
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                return execute(request);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, asyncExecutor);
    }
    
    /**
     * Execute HTTP request with callback.
     */
    public void executeAsync(HttpRequest request, Consumer<HttpResponse> onSuccess, Consumer<Exception> onError) {
        if (!config.isAsyncEnabled()) {
            throw new UnsupportedOperationException("Async execution not enabled");
        }
        
        asyncExecutor.submit(() -> {
            try {
                HttpResponse response = execute(request);
                onSuccess.accept(response);
            } catch (Exception e) {
                onError.accept(e);
            }
        });
    }
    
    /**
     * Execute multiple requests concurrently.
     */
    public CompletableFuture<Map<String, HttpResponse>> executeMultiple(Map<String, HttpRequest> requests) {
        Map<String, CompletableFuture<HttpResponse>> futures = new ConcurrentHashMap<>();
        
        for (Map.Entry<String, HttpRequest> entry : requests.entrySet()) {
            futures.put(entry.getKey(), executeAsync(entry.getValue()));
        }
        
        return CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                Map<String, HttpResponse> results = new ConcurrentHashMap<>();
                for (Map.Entry<String, CompletableFuture<HttpResponse>> entry : futures.entrySet()) {
                    try {
                        results.put(entry.getKey(), entry.getValue().get());
                    } catch (Exception e) {
                        log.error("Failed to get response for {}: {}", entry.getKey(), e.getMessage());
                    }
                }
                return results;
            });
    }
    
    /**
     * Get client performance statistics.
     */
    public ClientStatistics getStatistics() {
        return new ClientStatistics(
            totalRequests.get(),
            successfulRequests.get(),
            failedRequests.get(),
            totalBytesTransferred.get(),
            averageResponseTime.get(),
            connectionPool.getStatistics(),
            bufferPool.getStatistics()
        );
    }
    
    /**
     * Shutdown the client gracefully.
     */
    public void shutdown() {
        if (isShutdown) {
            return;
        }
        
        isShutdown = true;
        
        try {
            // Shutdown executors
            shutdownExecutor(asyncExecutor, "Async executor");
            shutdownExecutor(maintenanceExecutor, "Maintenance executor");
            
            // Close connection pool
            connectionPool.shutdown();
            
            // Release buffer pool
            bufferPool.shutdown();
            
            log.info("Enhanced HTTP client shutdown completed");
            
        } catch (Exception e) {
            log.error("Error during HTTP client shutdown", e);
        }
    }
    
    /**
     * Prepare HTTP request for execution.
     */
    private PreparedRequest prepareRequest(HttpRequest request) {
        // Get buffer from pool
        ByteBuffer buffer = bufferPool.getBuffer();
        
        // Apply compression if configured
        String body = request.getBody();
        if (config.isCompressionEnabled() && body != null) {
            body = compressBody(body);
        }
        
        // Apply memory optimization for large payloads
        if (body != null && body.length() > config.getMaxResponseSizeBytes()) {
            throw new IllegalArgumentException("Request body too large: " + body.length() + " bytes");
        }
        
        return new PreparedRequest(request, body, buffer);
    }
    
    /**
     * Execute prepared request.
     */
    private HttpResponse executeRequest(HttpConnection connection, PreparedRequest request) throws IOException {
        try {
            // Set up timeouts
            connection.setConnectionTimeout(config.getConnectionTimeoutMs());
            connection.setReadTimeout(config.getReadTimeoutMs());
            
            // Configure HTTP/2 if enabled
            if (config.isHttp2Enabled() && connection.supportsHttp2()) {
                connection.enableHttp2();
            }
            
            // Send request
            connection.sendRequest(request);
            
            // Read response with memory optimization
            return readResponseOptimized(connection, request);
            
        } finally {
            // Return buffer to pool
            if (request.getBuffer() != null) {
                bufferPool.returnBuffer(request.getBuffer());
            }
        }
    }
    
    /**
     * Read response with memory optimization.
     */
    private HttpResponse readResponseOptimized(HttpConnection connection, PreparedRequest request) throws IOException {
        // Check response size before reading
        long contentLength = connection.getContentLength();
        if (contentLength > config.getMaxResponseSizeBytes()) {
            throw new IOException("Response too large: " + contentLength + " bytes");
        }
        
        // Read response in chunks to optimize memory usage
        StringBuilder responseBody = new StringBuilder();
        ByteBuffer buffer = request.getBuffer();
        
        int totalBytesRead = 0;
        while (totalBytesRead < contentLength || contentLength == -1) {
            buffer.clear();
            int bytesRead = connection.read(buffer);
            
            if (bytesRead == -1) {
                break; // End of stream
            }
            
            totalBytesRead += bytesRead;
            
            // Check size limit during reading
            if (totalBytesRead > config.getMaxResponseSizeBytes()) {
                throw new IOException("Response size exceeded limit during reading");
            }
            
            // Append to response body
            buffer.flip();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            responseBody.append(new String(bytes, StandardCharsets.UTF_8));
        }
        
        return new HttpResponse(
            connection.getStatusCode(),
            connection.getHeaders(),
            responseBody.toString(),
            connection.getContentType(),
            totalBytesRead
        );
    }
    
    /**
     * Update performance metrics.
     */
    private void updateMetrics(HttpResponse response, long responseTime) {
        totalBytesTransferred.addAndGet(response.getContentLength());
        
        // Update average response time (simple moving average)
        long currentAvg = averageResponseTime.get();
        long newAvg = (currentAvg + responseTime) / 2;
        averageResponseTime.set(newAvg);
    }
    
    /**
     * Compress request body if compression is enabled.
     */
    private String compressBody(String body) {
        // Simplified compression implementation
        // In a real implementation, would use GZIP compression
        if (body.length() > 1000) {
            log.debug("Compressing request body of {} bytes", body.length());
            // Placeholder for actual compression
        }
        return body;
    }
    
    /**
     * Create async executor with optimized thread pool.
     */
    private ExecutorService createAsyncExecutor() {
        if (!config.isAsyncEnabled()) {
            return null;
        }
        
        int corePoolSize = Math.max(2, Runtime.getRuntime().availableProcessors());
        int maxPoolSize = Math.max(corePoolSize, config.getMaxConnections());
        
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            corePoolSize,
            maxPoolSize,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(config.getAsyncQueueSize()),
            r -> {
                Thread t = new Thread(r, "http-client-async");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
    
    /**
     * Create maintenance executor.
     */
    private ScheduledExecutorService createMaintenanceExecutor() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "http-client-maintenance");
            t.setDaemon(true);
            return t;
        });
    }
    
    /**
     * Start maintenance tasks.
     */
    private void startMaintenanceTasks() {
        // Connection pool maintenance
        maintenanceExecutor.scheduleWithFixedDelay(
            connectionPool::performMaintenance,
            60, 60, TimeUnit.SECONDS
        );
        
        // Buffer pool maintenance
        maintenanceExecutor.scheduleWithFixedDelay(
            bufferPool::performMaintenance,
            30, 30, TimeUnit.SECONDS
        );
        
        // Metrics reporting
        if (config.isMetricsEnabled()) {
            maintenanceExecutor.scheduleWithFixedDelay(
                this::logMetrics,
                300, 300, TimeUnit.SECONDS
            );
        }
    }
    
    /**
     * Log performance metrics.
     */
    private void logMetrics() {
        ClientStatistics stats = getStatistics();
        log.info("HTTP Client Metrics: requests={}, success={}, failed={}, avgResponse={}ms, bytes={}MB",
                stats.getTotalRequests(),
                stats.getSuccessfulRequests(),
                stats.getFailedRequests(),
                stats.getAverageResponseTime(),
                stats.getTotalBytesTransferred() / (1024 * 1024));
    }
    
    /**
     * Shutdown executor gracefully.
     */
    private void shutdownExecutor(ExecutorService executor, String name) {
        if (executor == null || executor.isShutdown()) {
            return;
        }
        
        try {
            executor.shutdown();
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("{} did not terminate gracefully, forcing shutdown", name);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            log.warn("{} shutdown interrupted", name);
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * HTTP client configuration.
     */
    public static class HttpClientConfig {
        private boolean http2Enabled = false;
        private boolean asyncEnabled = true;
        private boolean compressionEnabled = true;
        private boolean metricsEnabled = true;
        private int maxConnections = 20;
        private int connectionTimeoutMs = 30000;
        private int readTimeoutMs = 60000;
        private long maxResponseSizeBytes = 10 * 1024 * 1024; // 10MB
        private int bufferSize = 8192; // 8KB
        private int bufferPoolSize = 50;
        private int asyncQueueSize = 1000;
        private String proxyHost;
        private int proxyPort = 8080;
        
        // Getters and setters
        public boolean isHttp2Enabled() { return http2Enabled; }
        public void setHttp2Enabled(boolean http2Enabled) { this.http2Enabled = http2Enabled; }
        
        public boolean isAsyncEnabled() { return asyncEnabled; }
        public void setAsyncEnabled(boolean asyncEnabled) { this.asyncEnabled = asyncEnabled; }
        
        public boolean isCompressionEnabled() { return compressionEnabled; }
        public void setCompressionEnabled(boolean compressionEnabled) { this.compressionEnabled = compressionEnabled; }
        
        public boolean isMetricsEnabled() { return metricsEnabled; }
        public void setMetricsEnabled(boolean metricsEnabled) { this.metricsEnabled = metricsEnabled; }
        
        public int getMaxConnections() { return maxConnections; }
        public void setMaxConnections(int maxConnections) { this.maxConnections = maxConnections; }
        
        public int getConnectionTimeoutMs() { return connectionTimeoutMs; }
        public void setConnectionTimeoutMs(int connectionTimeoutMs) { this.connectionTimeoutMs = connectionTimeoutMs; }
        
        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
        
        public long getMaxResponseSizeBytes() { return maxResponseSizeBytes; }
        public void setMaxResponseSizeBytes(long maxResponseSizeBytes) { this.maxResponseSizeBytes = maxResponseSizeBytes; }
        
        public int getBufferSize() { return bufferSize; }
        public void setBufferSize(int bufferSize) { this.bufferSize = bufferSize; }
        
        public int getBufferPoolSize() { return bufferPoolSize; }
        public void setBufferPoolSize(int bufferPoolSize) { this.bufferPoolSize = bufferPoolSize; }
        
        public int getAsyncQueueSize() { return asyncQueueSize; }
        public void setAsyncQueueSize(int asyncQueueSize) { this.asyncQueueSize = asyncQueueSize; }
        
        public String getProxyHost() { return proxyHost; }
        public void setProxyHost(String proxyHost) { this.proxyHost = proxyHost; }
        
        public int getProxyPort() { return proxyPort; }
        public void setProxyPort(int proxyPort) { this.proxyPort = proxyPort; }
    }
    
    /**
     * Client performance statistics.
     */
    public static class ClientStatistics {
        private final long totalRequests;
        private final long successfulRequests;
        private final long failedRequests;
        private final long totalBytesTransferred;
        private final long averageResponseTime;
        private final ConnectionPool.PoolStatistics poolStats;
        private final ByteBufferPool.BufferPoolStatistics bufferStats;
        
        public ClientStatistics(long totalRequests, long successfulRequests, long failedRequests,
                              long totalBytesTransferred, long averageResponseTime,
                              ConnectionPool.PoolStatistics poolStats,
                              ByteBufferPool.BufferPoolStatistics bufferStats) {
            this.totalRequests = totalRequests;
            this.successfulRequests = successfulRequests;
            this.failedRequests = failedRequests;
            this.totalBytesTransferred = totalBytesTransferred;
            this.averageResponseTime = averageResponseTime;
            this.poolStats = poolStats;
            this.bufferStats = bufferStats;
        }
        
        public long getTotalRequests() { return totalRequests; }
        public long getSuccessfulRequests() { return successfulRequests; }
        public long getFailedRequests() { return failedRequests; }
        public long getTotalBytesTransferred() { return totalBytesTransferred; }
        public long getAverageResponseTime() { return averageResponseTime; }
        public ConnectionPool.PoolStatistics getPoolStats() { return poolStats; }
        public ByteBufferPool.BufferPoolStatistics getBufferStats() { return bufferStats; }
        
        public double getSuccessRate() {
            return totalRequests > 0 ? (double) successfulRequests / totalRequests : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("ClientStats{requests=%d, success=%.2f%%, avgTime=%dms, bytes=%dMB}",
                               totalRequests, getSuccessRate() * 100, averageResponseTime, totalBytesTransferred / (1024 * 1024));
        }
    }
}
