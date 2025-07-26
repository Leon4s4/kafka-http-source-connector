package io.confluent.connect.http.performance;

import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Performance optimization manager with connection pooling, caching,
 * and adaptive polling strategies.
 */
public class PerformanceOptimizer {
    
    private static final Logger log = LoggerFactory.getLogger(PerformanceOptimizer.class);
    
    // Response caching
    private final Map<String, CachedResponse> responseCache;
    private final boolean cachingEnabled;
    private final long cacheTimeToLiveMs;
    private final int maxCacheSize;
    
    // Adaptive polling
    private final Map<String, AdaptivePollingState> pollingStates;
    private final boolean adaptivePollingEnabled;
    
    // Performance metrics
    private final Map<String, PerformanceMetrics> performanceMetrics;
    
    // Background tasks
    private final ScheduledExecutorService backgroundExecutor;
    
    public PerformanceOptimizer(HttpSourceConnectorConfig config) {
        this.cachingEnabled = config.isResponseCachingEnabled();
        this.cacheTimeToLiveMs = config.getResponseCacheTtlMs();
        this.maxCacheSize = config.getMaxCacheSize();
        this.adaptivePollingEnabled = config.isAdaptivePollingEnabled();
        
        this.responseCache = new ConcurrentHashMap<>();
        this.pollingStates = new ConcurrentHashMap<>();
        this.performanceMetrics = new ConcurrentHashMap<>();
        
        this.backgroundExecutor = Executors.newScheduledThreadPool(2);
        
        // Start background tasks
        startCacheEvictionTask();
        startMetricsCollectionTask();
        
        log.info("Performance optimizer initialized: caching={}, adaptive polling={}", 
            cachingEnabled, adaptivePollingEnabled);
    }
    
    /**
     * Checks if a cached response is available and valid
     */
    public CachedResponse getCachedResponse(String cacheKey) {
        if (!cachingEnabled) {
            return null;
        }
        
        CachedResponse cached = responseCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            log.trace("Cache hit for key: {}", cacheKey);
            updateMetrics(cacheKey, true, 0, 0);
            return cached;
        } else if (cached != null) {
            // Remove expired entry
            responseCache.remove(cacheKey);
            log.trace("Cache expired for key: {}", cacheKey);
        }
        
        return null;
    }
    
    /**
     * Caches a response with TTL
     */
    public void cacheResponse(String cacheKey, String responseBody, Map<String, java.util.List<String>> headers) {
        if (!cachingEnabled || responseBody == null) {
            return;
        }
        
        // Check cache size limit
        if (responseCache.size() >= maxCacheSize) {
            evictOldestCacheEntry();
        }
        
        CachedResponse cached = new CachedResponse(
            responseBody, 
            headers, 
            System.currentTimeMillis() + cacheTimeToLiveMs
        );
        
        responseCache.put(cacheKey, cached);
        log.trace("Cached response for key: {}", cacheKey);
    }
    
    /**
     * Determines optimal polling interval based on API behavior
     */
    public long getOptimalPollingInterval(String apiId, long configuredInterval, boolean hasNewData) {
        if (!adaptivePollingEnabled) {
            return configuredInterval;
        }
        
        AdaptivePollingState state = pollingStates.computeIfAbsent(apiId, k -> new AdaptivePollingState(configuredInterval));
        
        long currentTime = System.currentTimeMillis();
        state.lastPollTime = currentTime;
        
        if (hasNewData) {
            // Data found, decrease interval (poll more frequently)
            state.consecutiveEmptyPolls = 0;
            state.currentInterval = Math.max(
                configuredInterval / 4, // Don't go below 1/4 of configured interval
                state.currentInterval * 0.8 // Decrease by 20%
            );
            log.trace("API {} has data, decreasing interval to {}", apiId, state.currentInterval);
        } else {
            // No data found, increase interval (poll less frequently)
            state.consecutiveEmptyPolls++;
            
            if (state.consecutiveEmptyPolls >= 3) {
                state.currentInterval = Math.min(
                    configuredInterval * 4, // Don't exceed 4x configured interval
                    state.currentInterval * 1.5 // Increase by 50%
                );
                log.trace("API {} has no data for {} polls, increasing interval to {}", 
                    apiId, state.consecutiveEmptyPolls, state.currentInterval);
            }
        }
        
        return Math.round(state.currentInterval);
    }
    
    /**
     * Records performance metrics for an API call
     */
    public void recordApiCall(String apiId, long responseTimeMs, int responseSize, boolean success) {
        updateMetrics(apiId, false, responseTimeMs, responseSize);
        
        if (success) {
            PerformanceMetrics metrics = performanceMetrics.get(apiId);
            if (metrics != null) {
                metrics.successfulCalls.incrementAndGet();
            }
        }
    }
    
    /**
     * Gets performance metrics for an API
     */
    public PerformanceMetrics getPerformanceMetrics(String apiId) {
        return performanceMetrics.getOrDefault(apiId, new PerformanceMetrics());
    }
    
    /**
     * Creates a cache key for HTTP requests
     */
    public String createCacheKey(String apiId, String url, String method, Map<String, String> parameters) {
        StringBuilder keyBuilder = new StringBuilder();
        keyBuilder.append(apiId).append(":").append(method).append(":").append(url);
        
        if (parameters != null && !parameters.isEmpty()) {
            keyBuilder.append("?");
            parameters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> keyBuilder.append(entry.getKey()).append("=").append(entry.getValue()).append("&"));
        }
        
        return keyBuilder.toString();
    }
    
    /**
     * Checks if response should be cached based on HTTP headers
     */
    public boolean shouldCacheResponse(Map<String, java.util.List<String>> headers, int statusCode) {
        if (!cachingEnabled || statusCode >= 400) {
            return false;
        }
        
        // Check for cache-control headers
        java.util.List<String> cacheControl = headers.get("cache-control");
        if (cacheControl != null) {
            for (String directive : cacheControl) {
                if (directive.toLowerCase().contains("no-cache") || 
                    directive.toLowerCase().contains("no-store")) {
                    return false;
                }
            }
        }
        
        // Check for explicit no-cache headers
        java.util.List<String> pragma = headers.get("pragma");
        if (pragma != null && pragma.contains("no-cache")) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Updates performance metrics
     */
    private void updateMetrics(String apiId, boolean cacheHit, long responseTimeMs, int responseSize) {
        PerformanceMetrics metrics = performanceMetrics.computeIfAbsent(apiId, k -> new PerformanceMetrics());
        
        metrics.totalCalls.incrementAndGet();
        
        if (cacheHit) {
            metrics.cacheHits.incrementAndGet();
        } else {
            metrics.cacheMisses.incrementAndGet();
            
            if (responseTimeMs > 0) {
                metrics.totalResponseTime.addAndGet(responseTimeMs);
                metrics.maxResponseTime.updateAndGet(current -> Math.max(current, responseTimeMs));
            }
            
            if (responseSize > 0) {
                metrics.totalResponseSize.addAndGet(responseSize);
            }
        }
    }
    
    /**
     * Evicts the oldest cache entry
     */
    private void evictOldestCacheEntry() {
        if (responseCache.isEmpty()) {
            return;
        }
        
        String oldestKey = responseCache.entrySet().stream()
            .min(Map.Entry.comparingByValue((a, b) -> Long.compare(a.creationTime, b.creationTime)))
            .map(Map.Entry::getKey)
            .orElse(null);
        
        if (oldestKey != null) {
            responseCache.remove(oldestKey);
            log.trace("Evicted oldest cache entry: {}", oldestKey);
        }
    }
    
    /**
     * Starts background cache eviction task
     */
    private void startCacheEvictionTask() {
        if (!cachingEnabled) {
            return;
        }
        
        backgroundExecutor.scheduleAtFixedRate(() -> {
            try {
                long currentTime = System.currentTimeMillis();
                
                responseCache.entrySet().removeIf(entry -> {
                    boolean expired = entry.getValue().isExpired();
                    if (expired) {
                        log.trace("Evicted expired cache entry: {}", entry.getKey());
                    }
                    return expired;
                });
                
            } catch (Exception e) {
                log.warn("Error during cache eviction: {}", e.getMessage());
            }
        }, 30, 30, TimeUnit.SECONDS);
    }
    
    /**
     * Starts background metrics collection task
     */
    private void startMetricsCollectionTask() {
        backgroundExecutor.scheduleAtFixedRate(() -> {
            try {
                logPerformanceMetrics();
            } catch (Exception e) {
                log.warn("Error during metrics collection: {}", e.getMessage());
            }
        }, 60, 60, TimeUnit.SECONDS);
    }
    
    /**
     * Logs performance metrics
     */
    private void logPerformanceMetrics() {
        for (Map.Entry<String, PerformanceMetrics> entry : performanceMetrics.entrySet()) {
            String apiId = entry.getKey();
            PerformanceMetrics metrics = entry.getValue();
            
            long totalCalls = metrics.totalCalls.get();
            if (totalCalls > 0) {
                double cacheHitRate = (double) metrics.cacheHits.get() / totalCalls * 100;
                double avgResponseTime = metrics.totalResponseTime.get() / (double) metrics.cacheMisses.get();
                
                log.info("Performance metrics for API {}: total calls={}, cache hit rate={:.1f}%, avg response time={:.1f}ms", 
                    apiId, totalCalls, cacheHitRate, avgResponseTime);
            }
        }
    }
    
    /**
     * Shuts down the performance optimizer
     */
    public void shutdown() {
        try {
            backgroundExecutor.shutdown();
            if (!backgroundExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                backgroundExecutor.shutdownNow();
            }
            
            responseCache.clear();
            performanceMetrics.clear();
            pollingStates.clear();
            
            log.info("Performance optimizer shut down");
            
        } catch (InterruptedException e) {
            backgroundExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Cached HTTP response
     */
    public static class CachedResponse {
        public final String body;
        public final Map<String, java.util.List<String>> headers;
        public final long expirationTime;
        public final long creationTime;
        
        public CachedResponse(String body, Map<String, java.util.List<String>> headers, long expirationTime) {
            this.body = body;
            this.headers = headers;
            this.expirationTime = expirationTime;
            this.creationTime = System.currentTimeMillis();
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() > expirationTime;
        }
    }
    
    /**
     * Adaptive polling state for an API
     */
    private static class AdaptivePollingState {
        public double currentInterval;
        public int consecutiveEmptyPolls;
        public long lastPollTime;
        
        public AdaptivePollingState(long initialInterval) {
            this.currentInterval = initialInterval;
            this.consecutiveEmptyPolls = 0;
            this.lastPollTime = 0;
        }
    }
    
    /**
     * Performance metrics for an API
     */
    public static class PerformanceMetrics {
        public final AtomicLong totalCalls = new AtomicLong(0);
        public final AtomicLong successfulCalls = new AtomicLong(0);
        public final AtomicLong cacheHits = new AtomicLong(0);
        public final AtomicLong cacheMisses = new AtomicLong(0);
        public final AtomicLong totalResponseTime = new AtomicLong(0);
        public final AtomicLong totalResponseSize = new AtomicLong(0);
        public final AtomicLong maxResponseTime = new AtomicLong(0);
        
        public double getCacheHitRate() {
            long total = totalCalls.get();
            return total > 0 ? (double) cacheHits.get() / total * 100 : 0.0;
        }
        
        public double getAverageResponseTime() {
            long misses = cacheMisses.get();
            return misses > 0 ? (double) totalResponseTime.get() / misses : 0.0;
        }
        
        public double getSuccessRate() {
            long total = totalCalls.get();
            return total > 0 ? (double) successfulCalls.get() / total * 100 : 0.0;
        }
    }
}