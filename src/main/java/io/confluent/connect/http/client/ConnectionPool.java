package io.confluent.connect.http.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Connection pool for managing HTTP connections efficiently.
 * Provides connection reuse, lifecycle management, and monitoring.
 */
public class ConnectionPool {
    
    private static final Logger log = LoggerFactory.getLogger(ConnectionPool.class);
    
    private final EnhancedHttpClient.HttpClientConfig config;
    private final ConcurrentHashMap<String, BlockingQueue<PooledConnection>> pools;
    private final AtomicInteger totalConnections = new AtomicInteger(0);
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicLong connectionRequests = new AtomicLong(0);
    private final AtomicLong connectionHits = new AtomicLong(0);
    
    private volatile boolean isShutdown = false;
    
    public ConnectionPool(EnhancedHttpClient.HttpClientConfig config) {
        this.config = config;
        this.pools = new ConcurrentHashMap<>();
        
        log.info("Connection pool initialized with max connections: {}", config.getMaxConnections());
    }
    
    /**
     * Get a connection for the specified URL.
     */
    public HttpConnection getConnection(String url) throws IOException {
        if (isShutdown) {
            throw new IOException("Connection pool is shutdown");
        }
        
        connectionRequests.incrementAndGet();
        
        String host = extractHost(url);
        BlockingQueue<PooledConnection> pool = getOrCreatePool(host);
        
        PooledConnection pooledConnection = pool.poll();
        if (pooledConnection != null && pooledConnection.isValid()) {
            connectionHits.incrementAndGet();
            activeConnections.incrementAndGet();
            pooledConnection.setLastUsed(Instant.now());
            return pooledConnection.getConnection();
        }
        
        // Create new connection
        if (totalConnections.get() >= config.getMaxConnections()) {
            throw new IOException("Maximum connections exceeded: " + config.getMaxConnections());
        }
        
        HttpConnection connection = createConnection(url);
        totalConnections.incrementAndGet();
        activeConnections.incrementAndGet();
        
        log.debug("Created new connection for {}: total={}, active={}", 
                 host, totalConnections.get(), activeConnections.get());
        
        return connection;
    }
    
    /**
     * Return a connection to the pool.
     */
    public void returnConnection(HttpConnection connection) {
        if (isShutdown || connection == null) {
            return;
        }
        
        activeConnections.decrementAndGet();
        
        String host = connection.getHost();
        BlockingQueue<PooledConnection> pool = pools.get(host);
        
        if (pool != null && connection.isReusable()) {
            PooledConnection pooledConnection = new PooledConnection(connection, Instant.now());
            if (!pool.offer(pooledConnection)) {
                // Pool is full, close the connection
                closeConnection(connection);
                totalConnections.decrementAndGet();
            }
        } else {
            // Connection not reusable, close it
            closeConnection(connection);
            totalConnections.decrementAndGet();
        }
    }
    
    /**
     * Get pool statistics.
     */
    public PoolStatistics getStatistics() {
        return new PoolStatistics(
            totalConnections.get(),
            activeConnections.get(),
            connectionRequests.get(),
            connectionHits.get(),
            pools.size()
        );
    }
    
    /**
     * Perform pool maintenance (remove stale connections).
     */
    public void performMaintenance() {
        if (isShutdown) {
            return;
        }
        
        int removedConnections = 0;
        Instant now = Instant.now();
        
        for (BlockingQueue<PooledConnection> pool : pools.values()) {
            PooledConnection[] connections = pool.toArray(new PooledConnection[0]);
            
            for (PooledConnection pooledConnection : connections) {
                if (!pooledConnection.isValid() || 
                    isStale(pooledConnection.getLastUsed(), now)) {
                    
                    if (pool.remove(pooledConnection)) {
                        closeConnection(pooledConnection.getConnection());
                        totalConnections.decrementAndGet();
                        removedConnections++;
                    }
                }
            }
        }
        
        if (removedConnections > 0) {
            log.debug("Removed {} stale connections during maintenance", removedConnections);
        }
    }
    
    /**
     * Shutdown the connection pool.
     */
    public void shutdown() {
        isShutdown = true;
        
        int closedConnections = 0;
        
        for (BlockingQueue<PooledConnection> pool : pools.values()) {
            for (PooledConnection pooledConnection : pool) {
                closeConnection(pooledConnection.getConnection());
                closedConnections++;
            }
            pool.clear();
        }
        
        pools.clear();
        totalConnections.set(0);
        activeConnections.set(0);
        
        log.info("Connection pool shutdown completed: closed {} connections", closedConnections);
    }
    
    /**
     * Extract host from URL.
     */
    private String extractHost(String urlString) throws IOException {
        try {
            URL url = new URL(urlString);
            return url.getHost() + ":" + (url.getPort() != -1 ? url.getPort() : url.getDefaultPort());
        } catch (Exception e) {
            throw new IOException("Invalid URL: " + urlString, e);
        }
    }
    
    /**
     * Get or create connection pool for host.
     */
    private BlockingQueue<PooledConnection> getOrCreatePool(String host) {
        return pools.computeIfAbsent(host, k -> 
            new ArrayBlockingQueue<>(config.getMaxConnections() / 4 + 1));
    }
    
    /**
     * Create new HTTP connection.
     */
    private HttpConnection createConnection(String url) throws IOException {
        return new HttpConnection(url, config);
    }
    
    /**
     * Close HTTP connection.
     */
    private void closeConnection(HttpConnection connection) {
        try {
            connection.close();
        } catch (Exception e) {
            log.warn("Error closing connection: {}", e.getMessage());
        }
    }
    
    /**
     * Check if connection is stale.
     */
    private boolean isStale(Instant lastUsed, Instant now) {
        long maxIdleTimeMs = 300000; // 5 minutes
        return lastUsed.plusMillis(maxIdleTimeMs).isBefore(now);
    }
    
    /**
     * Pooled connection wrapper.
     */
    private static class PooledConnection {
        private final HttpConnection connection;
        private volatile Instant lastUsed;
        
        public PooledConnection(HttpConnection connection, Instant lastUsed) {
            this.connection = connection;
            this.lastUsed = lastUsed;
        }
        
        public HttpConnection getConnection() {
            return connection;
        }
        
        public Instant getLastUsed() {
            return lastUsed;
        }
        
        public void setLastUsed(Instant lastUsed) {
            this.lastUsed = lastUsed;
        }
        
        public boolean isValid() {
            return connection != null && connection.isConnected() && !connection.isClosed();
        }
    }
    
    /**
     * Pool statistics.
     */
    public static class PoolStatistics {
        private final int totalConnections;
        private final int activeConnections;
        private final long connectionRequests;
        private final long connectionHits;
        private final int poolCount;
        
        public PoolStatistics(int totalConnections, int activeConnections,
                             long connectionRequests, long connectionHits, int poolCount) {
            this.totalConnections = totalConnections;
            this.activeConnections = activeConnections;
            this.connectionRequests = connectionRequests;
            this.connectionHits = connectionHits;
            this.poolCount = poolCount;
        }
        
        public int getTotalConnections() { return totalConnections; }
        public int getActiveConnections() { return activeConnections; }
        public long getConnectionRequests() { return connectionRequests; }
        public long getConnectionHits() { return connectionHits; }
        public int getPoolCount() { return poolCount; }
        
        public double getHitRate() {
            return connectionRequests > 0 ? (double) connectionHits / connectionRequests : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("PoolStats{total=%d, active=%d, hitRate=%.2f%%, pools=%d}",
                               totalConnections, activeConnections, getHitRate() * 100, poolCount);
        }
    }
}
