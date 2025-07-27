package io.confluent.connect.http.ratelimit;

/**
 * Result of a rate limit check indicating whether the request is allowed
 * and providing additional context for rate limiting decisions.
 */
public class RateLimitResult {
    
    private final boolean allowed;
    private final long waitTimeMs;
    private final String reason;
    private final long remainingTokens;
    private final long resetTimeMs;
    
    private RateLimitResult(boolean allowed, long waitTimeMs, String reason, 
                           long remainingTokens, long resetTimeMs) {
        this.allowed = allowed;
        this.waitTimeMs = waitTimeMs;
        this.reason = reason;
        this.remainingTokens = remainingTokens;
        this.resetTimeMs = resetTimeMs;
    }
    
    /**
     * Create a result indicating the request is allowed.
     */
    public static RateLimitResult allowed() {
        return new RateLimitResult(true, 0, "Request allowed", -1, -1);
    }
    
    /**
     * Create a result indicating the request is allowed with metadata.
     */
    public static RateLimitResult allowed(long remainingTokens, long resetTimeMs) {
        return new RateLimitResult(true, 0, "Request allowed", remainingTokens, resetTimeMs);
    }
    
    /**
     * Create a result indicating the request is denied.
     */
    public static RateLimitResult denied(long waitTimeMs) {
        return new RateLimitResult(false, waitTimeMs, "Rate limit exceeded", 0, -1);
    }
    
    /**
     * Create a result indicating the request is denied with custom reason.
     */
    public static RateLimitResult denied(long waitTimeMs, String reason) {
        return new RateLimitResult(false, waitTimeMs, reason, 0, -1);
    }
    
    /**
     * Create a result indicating the request is denied with full metadata.
     */
    public static RateLimitResult denied(long waitTimeMs, String reason, long resetTimeMs) {
        return new RateLimitResult(false, waitTimeMs, reason, 0, resetTimeMs);
    }
    
    public boolean isAllowed() {
        return allowed;
    }
    
    public boolean isDenied() {
        return !allowed;
    }
    
    public long getWaitTimeMs() {
        return waitTimeMs;
    }
    
    public String getReason() {
        return reason;
    }
    
    public long getRemainingTokens() {
        return remainingTokens;
    }
    
    public long getResetTimeMs() {
        return resetTimeMs;
    }
    
    /**
     * Get retry-after value in seconds (for HTTP 429 responses).
     */
    public long getRetryAfterSeconds() {
        return waitTimeMs > 0 ? (waitTimeMs + 999) / 1000 : 0; // Round up
    }
    
    @Override
    public String toString() {
        if (allowed) {
            return String.format("RateLimitResult{allowed=true, remaining=%d}", remainingTokens);
        } else {
            return String.format("RateLimitResult{allowed=false, waitMs=%d, reason='%s'}", 
                               waitTimeMs, reason);
        }
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        
        RateLimitResult that = (RateLimitResult) o;
        
        if (allowed != that.allowed) return false;
        if (waitTimeMs != that.waitTimeMs) return false;
        if (remainingTokens != that.remainingTokens) return false;
        if (resetTimeMs != that.resetTimeMs) return false;
        return reason != null ? reason.equals(that.reason) : that.reason == null;
    }
    
    @Override
    public int hashCode() {
        int result = (allowed ? 1 : 0);
        result = 31 * result + (int) (waitTimeMs ^ (waitTimeMs >>> 32));
        result = 31 * result + (reason != null ? reason.hashCode() : 0);
        result = 31 * result + (int) (remainingTokens ^ (remainingTokens >>> 32));
        result = 31 * result + (int) (resetTimeMs ^ (resetTimeMs >>> 32));
        return result;
    }
}
