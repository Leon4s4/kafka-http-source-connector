package io.confluent.connect.http.ratelimit;

/**
 * Statistics and metrics for rate limiting operations.
 * Provides insight into rate limiter performance and current state.
 */
public class RateLimitStats {
    
    private final boolean enabled;
    private final String algorithm;
    private final long requestsPerSecond;
    private final long burstCapacity;
    private final long totalRequests;
    private final long allowedRequests;
    private final long deniedRequests;
    private final long availableTokens;
    private final long timestamp;
    
    public RateLimitStats(boolean enabled, String algorithm, long requestsPerSecond,
                         long burstCapacity, long totalRequests, long allowedRequests,
                         long deniedRequests, long availableTokens, long timestamp) {
        this.enabled = enabled;
        this.algorithm = algorithm;
        this.requestsPerSecond = requestsPerSecond;
        this.burstCapacity = burstCapacity;
        this.totalRequests = totalRequests;
        this.allowedRequests = allowedRequests;
        this.deniedRequests = deniedRequests;
        this.availableTokens = availableTokens;
        this.timestamp = timestamp;
    }
    
    /**
     * Create stats for disabled rate limiting.
     */
    public static RateLimitStats disabled() {
        return new RateLimitStats(
            false, "DISABLED", 0, 0, 0, 0, 0, 0, System.currentTimeMillis()
        );
    }
    
    /**
     * Create stats for uninitialized rate limiter.
     */
    public static RateLimitStats notInitialized() {
        return new RateLimitStats(
            true, "NOT_INITIALIZED", 0, 0, 0, 0, 0, 0, System.currentTimeMillis()
        );
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public String getAlgorithm() {
        return algorithm;
    }
    
    public long getRequestsPerSecond() {
        return requestsPerSecond;
    }
    
    public long getBurstCapacity() {
        return burstCapacity;
    }
    
    public long getTotalRequests() {
        return totalRequests;
    }
    
    public long getAllowedRequests() {
        return allowedRequests;
    }
    
    public long getDeniedRequests() {
        return deniedRequests;
    }
    
    public long getAvailableTokens() {
        return availableTokens;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    /**
     * Calculate the success rate (percentage of allowed requests).
     */
    public double getSuccessRate() {
        if (totalRequests == 0) {
            return 0.0;
        }
        return (double) allowedRequests / totalRequests * 100.0;
    }
    
    /**
     * Calculate the denial rate (percentage of denied requests).
     */
    public double getDenialRate() {
        if (totalRequests == 0) {
            return 0.0;
        }
        return (double) deniedRequests / totalRequests * 100.0;
    }
    
    /**
     * Calculate capacity utilization percentage.
     */
    public double getCapacityUtilization() {
        if (burstCapacity == 0) {
            return 0.0;
        }
        return (double) (burstCapacity - availableTokens) / burstCapacity * 100.0;
    }
    
    /**
     * Check if rate limiter is currently throttling.
     */
    public boolean isThrottling() {
        return enabled && availableTokens == 0;
    }
    
    /**
     * Get rate limiting efficiency (higher is better).
     */
    public double getEfficiency() {
        if (!enabled || totalRequests == 0) {
            return 100.0;
        }
        // Efficiency is the ratio of allowed to total requests
        return getSuccessRate();
    }
    
    @Override
    public String toString() {
        if (!enabled) {
            return "RateLimitStats{disabled}";
        }
        
        return String.format(
            "RateLimitStats{algorithm=%s, rate=%d/sec, capacity=%d, " +
            "total=%d, allowed=%d, denied=%d, available=%d, success=%.1f%%}",
            algorithm, requestsPerSecond, burstCapacity, totalRequests,
            allowedRequests, deniedRequests, availableTokens, getSuccessRate()
        );
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        
        RateLimitStats that = (RateLimitStats) o;
        
        if (enabled != that.enabled) return false;
        if (requestsPerSecond != that.requestsPerSecond) return false;
        if (burstCapacity != that.burstCapacity) return false;
        if (totalRequests != that.totalRequests) return false;
        if (allowedRequests != that.allowedRequests) return false;
        if (deniedRequests != that.deniedRequests) return false;
        if (availableTokens != that.availableTokens) return false;
        if (timestamp != that.timestamp) return false;
        return algorithm != null ? algorithm.equals(that.algorithm) : that.algorithm == null;
    }
    
    @Override
    public int hashCode() {
        int result = (enabled ? 1 : 0);
        result = 31 * result + (algorithm != null ? algorithm.hashCode() : 0);
        result = 31 * result + (int) (requestsPerSecond ^ (requestsPerSecond >>> 32));
        result = 31 * result + (int) (burstCapacity ^ (burstCapacity >>> 32));
        result = 31 * result + (int) (totalRequests ^ (totalRequests >>> 32));
        result = 31 * result + (int) (allowedRequests ^ (allowedRequests >>> 32));
        result = 31 * result + (int) (deniedRequests ^ (deniedRequests >>> 32));
        result = 31 * result + (int) (availableTokens ^ (availableTokens >>> 32));
        result = 31 * result + (int) (timestamp ^ (timestamp >>> 32));
        return result;
    }
}
