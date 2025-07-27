package io.confluent.connect.http.ratelimit;

import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Advanced rate limiting and throttling implementation for HTTP Source Connector.
 * Supports multiple rate limiting algorithms and per-API throttling.
 */
public class RateLimitManager {
    
    private static final Logger log = LoggerFactory.getLogger(RateLimitManager.class);
    
    public enum RateLimitAlgorithm {
        TOKEN_BUCKET,
        SLIDING_WINDOW,
        FIXED_WINDOW,
        LEAKY_BUCKET
    }
    
    public enum RateLimitScope {
        GLOBAL,      // Rate limit applies to all APIs
        PER_API,     // Rate limit per individual API
        PER_TASK     // Rate limit per connector task
    }
    
    private final HttpSourceConnectorConfig config;
    private final Map<String, RateLimiter> rateLimiters;
    private final RateLimitAlgorithm algorithm;
    private final RateLimitScope scope;
    private final boolean enabled;
    
    // Global rate limiting settings
    private final long globalRequestsPerSecond;
    private final long globalBurstSize;
    private final long windowSizeMs;
    
    public RateLimitManager(HttpSourceConnectorConfig config) {
        this.config = config;
        this.rateLimiters = new ConcurrentHashMap<>();
        
        // Extract configuration
        this.enabled = isRateLimitingEnabled(config);
        this.algorithm = getRateLimitAlgorithm(config);
        this.scope = getRateLimitScope(config);
        this.globalRequestsPerSecond = getGlobalRequestsPerSecond(config);
        this.globalBurstSize = getGlobalBurstSize(config);
        this.windowSizeMs = getWindowSizeMs(config);
        
        if (enabled) {
            log.info("Rate limiting enabled: algorithm={}, scope={}, rate={} req/sec", 
                    algorithm, scope, globalRequestsPerSecond);
        } else {
            log.info("Rate limiting disabled");
        }
    }
    
    /**
     * Check if a request should be allowed based on rate limits.
     * 
     * @param apiId The API identifier
     * @param taskId The task identifier
     * @return RateLimitResult containing decision and metadata
     */
    public RateLimitResult checkRateLimit(String apiId, int taskId) {
        if (!enabled) {
            return RateLimitResult.allowed();
        }
        
        String limiterId = getLimiterId(apiId, taskId);
        RateLimiter rateLimiter = getRateLimiter(limiterId, apiId);
        
        return rateLimiter.tryAcquire();
    }
    
    /**
     * Record a successful request for rate limiting calculations.
     */
    public void recordSuccess(String apiId, int taskId) {
        if (!enabled) {
            return;
        }
        
        String limiterId = getLimiterId(apiId, taskId);
        RateLimiter rateLimiter = getRateLimiter(limiterId, apiId);
        rateLimiter.recordSuccess();
    }
    
    /**
     * Record a failed request (may affect rate limiting differently).
     */
    public void recordFailure(String apiId, int taskId, int httpStatusCode) {
        if (!enabled) {
            return;
        }
        
        String limiterId = getLimiterId(apiId, taskId);
        RateLimiter rateLimiter = getRateLimiter(limiterId, apiId);
        rateLimiter.recordFailure(httpStatusCode);
    }
    
    /**
     * Get rate limiting statistics for monitoring.
     */
    public RateLimitStats getStats(String apiId, int taskId) {
        if (!enabled) {
            return RateLimitStats.disabled();
        }
        
        String limiterId = getLimiterId(apiId, taskId);
        RateLimiter rateLimiter = rateLimiters.get(limiterId);
        
        if (rateLimiter == null) {
            return RateLimitStats.notInitialized();
        }
        
        return rateLimiter.getStats();
    }
    
    /**
     * Get overall rate limiting statistics.
     */
    public Map<String, RateLimitStats> getAllStats() {
        Map<String, RateLimitStats> allStats = new ConcurrentHashMap<>();
        
        for (Map.Entry<String, RateLimiter> entry : rateLimiters.entrySet()) {
            allStats.put(entry.getKey(), entry.getValue().getStats());
        }
        
        return allStats;
    }
    
    /**
     * Update rate limits dynamically (if supported).
     */
    public void updateRateLimit(String apiId, long requestsPerSecond, long burstSize) {
        if (!enabled) {
            return;
        }
        
        String limiterId = getLimiterId(apiId, 0); // Use 0 for API-level updates
        RateLimiter rateLimiter = rateLimiters.get(limiterId);
        
        if (rateLimiter != null) {
            rateLimiter.updateLimits(requestsPerSecond, burstSize);
            log.info("Updated rate limits for {}: {} req/sec, burst={}", 
                    limiterId, requestsPerSecond, burstSize);
        }
    }
    
    private String getLimiterId(String apiId, int taskId) {
        switch (scope) {
            case GLOBAL:
                return "global";
            case PER_API:
                return "api:" + apiId;
            case PER_TASK:
                return "task:" + taskId;
            default:
                return "api:" + apiId;
        }
    }
    
    private RateLimiter getRateLimiter(String limiterId, String apiId) {
        return rateLimiters.computeIfAbsent(limiterId, id -> {
            // Get API-specific settings or use global defaults
            long requestsPerSecond = getApiRequestsPerSecond(apiId, globalRequestsPerSecond);
            long burstSize = getApiBurstSize(apiId, globalBurstSize);
            
            switch (algorithm) {
                case TOKEN_BUCKET:
                    return new TokenBucketRateLimiter(requestsPerSecond, burstSize);
                case SLIDING_WINDOW:
                    return new SlidingWindowRateLimiter(requestsPerSecond, windowSizeMs);
                case FIXED_WINDOW:
                    return new FixedWindowRateLimiter(requestsPerSecond, windowSizeMs);
                case LEAKY_BUCKET:
                    return new LeakyBucketRateLimiter(requestsPerSecond, burstSize);
                default:
                    return new TokenBucketRateLimiter(requestsPerSecond, burstSize);
            }
        });
    }
    
    // Configuration extraction methods
    
    private boolean isRateLimitingEnabled(HttpSourceConnectorConfig config) {
        return Boolean.parseBoolean(System.getProperty("ratelimit.enabled", "true"));
    }
    
    private RateLimitAlgorithm getRateLimitAlgorithm(HttpSourceConnectorConfig config) {
        String algorithm = System.getProperty("ratelimit.algorithm", "TOKEN_BUCKET");
        try {
            return RateLimitAlgorithm.valueOf(algorithm.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid rate limit algorithm: {}, using TOKEN_BUCKET", algorithm);
            return RateLimitAlgorithm.TOKEN_BUCKET;
        }
    }
    
    private RateLimitScope getRateLimitScope(HttpSourceConnectorConfig config) {
        String scope = System.getProperty("ratelimit.scope", "PER_API");
        try {
            return RateLimitScope.valueOf(scope.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid rate limit scope: {}, using PER_API", scope);
            return RateLimitScope.PER_API;
        }
    }
    
    private long getGlobalRequestsPerSecond(HttpSourceConnectorConfig config) {
        try {
            return Long.parseLong(System.getProperty("ratelimit.requests.per.second", "100"));
        } catch (NumberFormatException e) {
            return 100;
        }
    }
    
    private long getGlobalBurstSize(HttpSourceConnectorConfig config) {
        try {
            return Long.parseLong(System.getProperty("ratelimit.burst.size", "200"));
        } catch (NumberFormatException e) {
            return 200;
        }
    }
    
    private long getWindowSizeMs(HttpSourceConnectorConfig config) {
        try {
            return Long.parseLong(System.getProperty("ratelimit.window.size.ms", "60000"));
        } catch (NumberFormatException e) {
            return 60000; // 1 minute default
        }
    }
    
    private long getApiRequestsPerSecond(String apiId, long defaultValue) {
        String key = "ratelimit.api." + apiId + ".requests.per.second";
        try {
            return Long.parseLong(System.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
    
    private long getApiBurstSize(String apiId, long defaultValue) {
        String key = "ratelimit.api." + apiId + ".burst.size";
        try {
            return Long.parseLong(System.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
    
    public void close() {
        log.info("Rate limit manager shutting down");
        rateLimiters.clear();
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public RateLimitAlgorithm getAlgorithm() {
        return algorithm;
    }
    
    public RateLimitScope getScope() {
        return scope;
    }
    
    // Inner interface for rate limiter implementations
    private interface RateLimiter {
        RateLimitResult tryAcquire();
        void recordSuccess();
        void recordFailure(int httpStatusCode);
        void updateLimits(long requestsPerSecond, long burstSize);
        RateLimitStats getStats();
    }
    
    // Token Bucket Implementation
    private static class TokenBucketRateLimiter implements RateLimiter {
        private final long capacity;
        private volatile long requestsPerSecond;
        private volatile long tokens;
        private volatile long lastRefillTime;
        private final AtomicLong totalRequests = new AtomicLong(0);
        private final AtomicLong allowedRequests = new AtomicLong(0);
        private final AtomicLong deniedRequests = new AtomicLong(0);
        
        public TokenBucketRateLimiter(long requestsPerSecond, long capacity) {
            this.requestsPerSecond = requestsPerSecond;
            this.capacity = capacity;
            this.tokens = capacity;
            this.lastRefillTime = System.nanoTime();
        }
        
        @Override
        public synchronized RateLimitResult tryAcquire() {
            refillTokens();
            totalRequests.incrementAndGet();
            
            if (tokens > 0) {
                tokens--;
                allowedRequests.incrementAndGet();
                return RateLimitResult.allowed();
            } else {
                deniedRequests.incrementAndGet();
                long waitTimeMs = calculateWaitTime();
                return RateLimitResult.denied(waitTimeMs);
            }
        }
        
        private void refillTokens() {
            long now = System.nanoTime();
            long timePassed = now - lastRefillTime;
            long tokensToAdd = (timePassed * requestsPerSecond) / 1_000_000_000L;
            
            if (tokensToAdd > 0) {
                tokens = Math.min(capacity, tokens + tokensToAdd);
                lastRefillTime = now;
            }
        }
        
        private long calculateWaitTime() {
            // Calculate time until next token is available
            if (requestsPerSecond == 0) {
                return Long.MAX_VALUE;
            }
            return 1000 / requestsPerSecond; // milliseconds
        }
        
        @Override
        public void recordSuccess() {
            // Token bucket doesn't need additional success tracking
        }
        
        @Override
        public void recordFailure(int httpStatusCode) {
            // For 429 responses, we might want to be more aggressive
            if (httpStatusCode == 429) {
                // Reduce tokens more aggressively for rate limit responses
                synchronized (this) {
                    tokens = Math.max(0, tokens - 5);
                }
            }
        }
        
        @Override
        public synchronized void updateLimits(long requestsPerSecond, long burstSize) {
            this.requestsPerSecond = requestsPerSecond;
            // Don't update capacity for existing bucket, but could be enhanced
        }
        
        @Override
        public RateLimitStats getStats() {
            return new RateLimitStats(
                true,
                "TOKEN_BUCKET",
                requestsPerSecond,
                capacity,
                totalRequests.get(),
                allowedRequests.get(),
                deniedRequests.get(),
                tokens,
                System.currentTimeMillis()
            );
        }
    }
    
    // Sliding Window Implementation  
    private static class SlidingWindowRateLimiter implements RateLimiter {
        private final long requestsPerWindow;
        private final long windowSizeMs;
        private final Map<Long, AtomicInteger> windowCounts = new ConcurrentHashMap<>();
        private final AtomicLong totalRequests = new AtomicLong(0);
        private final AtomicLong allowedRequests = new AtomicLong(0);
        private final AtomicLong deniedRequests = new AtomicLong(0);
        
        public SlidingWindowRateLimiter(long requestsPerSecond, long windowSizeMs) {
            this.requestsPerWindow = requestsPerSecond * (windowSizeMs / 1000);
            this.windowSizeMs = windowSizeMs;
        }
        
        @Override
        public RateLimitResult tryAcquire() {
            long now = System.currentTimeMillis();
            long currentWindow = now / windowSizeMs;
            
            // Clean old windows
            cleanOldWindows(currentWindow);
            
            totalRequests.incrementAndGet();
            
            // Count requests in current and recent windows
            long requestCount = countRecentRequests(currentWindow);
            
            if (requestCount < requestsPerWindow) {
                windowCounts.computeIfAbsent(currentWindow, k -> new AtomicInteger(0))
                          .incrementAndGet();
                allowedRequests.incrementAndGet();
                return RateLimitResult.allowed();
            } else {
                deniedRequests.incrementAndGet();
                long waitTimeMs = calculateWaitTime(currentWindow);
                return RateLimitResult.denied(waitTimeMs);
            }
        }
        
        private void cleanOldWindows(long currentWindow) {
            windowCounts.entrySet().removeIf(entry -> 
                entry.getKey() < currentWindow - 1);
        }
        
        private long countRecentRequests(long currentWindow) {
            return windowCounts.entrySet().stream()
                .filter(entry -> entry.getKey() >= currentWindow - 1)
                .mapToLong(entry -> entry.getValue().get())
                .sum();
        }
        
        private long calculateWaitTime(long currentWindow) {
            return windowSizeMs - (System.currentTimeMillis() % windowSizeMs);
        }
        
        @Override
        public void recordSuccess() {
            // Already recorded in tryAcquire
        }
        
        @Override
        public void recordFailure(int httpStatusCode) {
            // Could implement adaptive behavior here
        }
        
        @Override
        public void updateLimits(long requestsPerSecond, long burstSize) {
            // Implementation would need to recalculate requestsPerWindow
        }
        
        @Override
        public RateLimitStats getStats() {
            long currentWindow = System.currentTimeMillis() / windowSizeMs;
            long currentRequests = countRecentRequests(currentWindow);
            
            return new RateLimitStats(
                true,
                "SLIDING_WINDOW",
                requestsPerWindow * 1000 / windowSizeMs, // Convert to per-second
                requestsPerWindow,
                totalRequests.get(),
                allowedRequests.get(),
                deniedRequests.get(),
                requestsPerWindow - currentRequests,
                System.currentTimeMillis()
            );
        }
    }
    
    // Fixed Window Implementation
    private static class FixedWindowRateLimiter implements RateLimiter {
        private final long requestsPerWindow;
        private final long windowSizeMs;
        private volatile long currentWindow;
        private final AtomicInteger windowCount = new AtomicInteger(0);
        private final AtomicLong totalRequests = new AtomicLong(0);
        private final AtomicLong allowedRequests = new AtomicLong(0);
        private final AtomicLong deniedRequests = new AtomicLong(0);
        
        public FixedWindowRateLimiter(long requestsPerSecond, long windowSizeMs) {
            this.requestsPerWindow = requestsPerSecond * (windowSizeMs / 1000);
            this.windowSizeMs = windowSizeMs;
            this.currentWindow = System.currentTimeMillis() / windowSizeMs;
        }
        
        @Override
        public synchronized RateLimitResult tryAcquire() {
            long now = System.currentTimeMillis();
            long window = now / windowSizeMs;
            
            // Reset counter if we're in a new window
            if (window != currentWindow) {
                currentWindow = window;
                windowCount.set(0);
            }
            
            totalRequests.incrementAndGet();
            
            if (windowCount.get() < requestsPerWindow) {
                windowCount.incrementAndGet();
                allowedRequests.incrementAndGet();
                return RateLimitResult.allowed();
            } else {
                deniedRequests.incrementAndGet();
                long waitTimeMs = windowSizeMs - (now % windowSizeMs);
                return RateLimitResult.denied(waitTimeMs);
            }
        }
        
        @Override
        public void recordSuccess() {
            // Already recorded in tryAcquire
        }
        
        @Override
        public void recordFailure(int httpStatusCode) {
            // Could implement adaptive behavior
        }
        
        @Override
        public void updateLimits(long requestsPerSecond, long burstSize) {
            // Implementation would recalculate requestsPerWindow
        }
        
        @Override
        public RateLimitStats getStats() {
            return new RateLimitStats(
                true,
                "FIXED_WINDOW",
                requestsPerWindow * 1000 / windowSizeMs,
                requestsPerWindow,
                totalRequests.get(),
                allowedRequests.get(),
                deniedRequests.get(),
                requestsPerWindow - windowCount.get(),
                System.currentTimeMillis()
            );
        }
    }
    
    // Leaky Bucket Implementation
    private static class LeakyBucketRateLimiter implements RateLimiter {
        private final long capacity;
        private volatile long leakRatePerSecond;
        private volatile long currentLevel;
        private volatile long lastLeakTime;
        private final AtomicLong totalRequests = new AtomicLong(0);
        private final AtomicLong allowedRequests = new AtomicLong(0);
        private final AtomicLong deniedRequests = new AtomicLong(0);
        
        public LeakyBucketRateLimiter(long leakRatePerSecond, long capacity) {
            this.leakRatePerSecond = leakRatePerSecond;
            this.capacity = capacity;
            this.currentLevel = 0;
            this.lastLeakTime = System.nanoTime();
        }
        
        @Override
        public synchronized RateLimitResult tryAcquire() {
            leak();
            totalRequests.incrementAndGet();
            
            if (currentLevel < capacity) {
                currentLevel++;
                allowedRequests.incrementAndGet();
                return RateLimitResult.allowed();
            } else {
                deniedRequests.incrementAndGet();
                long waitTimeMs = calculateWaitTime();
                return RateLimitResult.denied(waitTimeMs);
            }
        }
        
        private void leak() {
            long now = System.nanoTime();
            long timePassed = now - lastLeakTime;
            long leakAmount = (timePassed * leakRatePerSecond) / 1_000_000_000L;
            
            if (leakAmount > 0) {
                currentLevel = Math.max(0, currentLevel - leakAmount);
                lastLeakTime = now;
            }
        }
        
        private long calculateWaitTime() {
            if (leakRatePerSecond == 0) {
                return Long.MAX_VALUE;
            }
            // Time to leak one item
            return 1000 / leakRatePerSecond;
        }
        
        @Override
        public void recordSuccess() {
            // Already handled in leak mechanism
        }
        
        @Override
        public void recordFailure(int httpStatusCode) {
            // Could adjust leak rate for failures
        }
        
        @Override
        public synchronized void updateLimits(long requestsPerSecond, long burstSize) {
            this.leakRatePerSecond = requestsPerSecond;
        }
        
        @Override
        public RateLimitStats getStats() {
            return new RateLimitStats(
                true,
                "LEAKY_BUCKET",
                leakRatePerSecond,
                capacity,
                totalRequests.get(),
                allowedRequests.get(),
                deniedRequests.get(),
                capacity - currentLevel,
                System.currentTimeMillis()
            );
        }
    }
}
