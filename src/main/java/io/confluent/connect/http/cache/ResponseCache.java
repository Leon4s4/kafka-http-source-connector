package io.confluent.connect.http.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance HTTP response cache with conditional caching and cache-control support.
 * Provides intelligent caching based on HTTP headers and response characteristics.
 */
public class ResponseCache {
    
    private static final Logger log = LoggerFactory.getLogger(ResponseCache.class);
    
    private final IntelligentCacheManager cacheManager;
    
    // Response-specific metrics
    private final AtomicLong responseHits = new AtomicLong(0);
    private final AtomicLong responseMisses = new AtomicLong(0);
    private final AtomicLong conditionalCacheHits = new AtomicLong(0);
    private final AtomicLong cacheableResponses = new AtomicLong(0);
    private final AtomicLong nonCacheableResponses = new AtomicLong(0);
    
    public ResponseCache(IntelligentCacheManager cacheManager) {
        this.cacheManager = cacheManager;
        
        log.info("Response cache initialized with intelligent cache manager");
    }
    
    /**
     * Get a cached HTTP response.
     */
    public CachedResponse getResponse(String cacheKey) {
        if (cacheKey == null || cacheKey.trim().isEmpty()) {
            return null;
        }
        
        CachedResponse response = cacheManager.get(IntelligentCacheManager.CacheType.RESPONSE, cacheKey, CachedResponse.class);
        
        if (response != null) {
            responseHits.incrementAndGet();
            log.trace("Response cache hit for key: {}", cacheKey);
            
            // Check if response is still fresh
            if (response.isStale()) {
                cacheManager.remove(IntelligentCacheManager.CacheType.RESPONSE, cacheKey);
                responseMisses.incrementAndGet();
                log.trace("Stale response removed from cache: {}", cacheKey);
                return null;
            }
        } else {
            responseMisses.incrementAndGet();
            log.trace("Response cache miss for key: {}", cacheKey);
        }
        
        return response;
    }
    
    /**
     * Cache an HTTP response with intelligent caching logic.
     */
    public void putResponse(String cacheKey, HttpResponseData responseData) {
        if (cacheKey == null || responseData == null) {
            return;
        }
        
        // Check if response is cacheable
        if (!isCacheable(responseData)) {
            nonCacheableResponses.incrementAndGet();
            log.trace("Response not cacheable for key: {}", cacheKey);
            return;
        }
        
        cacheableResponses.incrementAndGet();
        
        // Calculate TTL based on cache-control headers
        long ttlSeconds = calculateTtl(responseData);
        
        // Create cached response
        CachedResponse cachedResponse = new CachedResponse(
            responseData.getBody(),
            responseData.getHeaders(),
            responseData.getStatusCode(),
            responseData.getContentType(),
            Instant.now(),
            ttlSeconds
        );
        
        // Cache the response
        cacheManager.put(IntelligentCacheManager.CacheType.RESPONSE, cacheKey, cachedResponse, ttlSeconds);
        
        log.trace("Cached response for key: {} (TTL: {}s)", cacheKey, ttlSeconds);
    }
    
    /**
     * Get a cached response if it matches the conditional headers.
     */
    public CachedResponse getConditionalResponse(String cacheKey, String etag, Instant ifModifiedSince) {
        CachedResponse response = getResponse(cacheKey);
        
        if (response == null) {
            return null;
        }
        
        // Check ETag
        if (etag != null && etag.equals(response.getEtag())) {
            conditionalCacheHits.incrementAndGet();
            log.trace("Conditional cache hit (ETag) for key: {}", cacheKey);
            return response;
        }
        
        // Check If-Modified-Since
        if (ifModifiedSince != null && response.getLastModified() != null) {
            if (!response.getLastModified().isAfter(ifModifiedSince)) {
                conditionalCacheHits.incrementAndGet();
                log.trace("Conditional cache hit (If-Modified-Since) for key: {}", cacheKey);
                return response;
            }
        }
        
        return null;
    }
    
    /**
     * Generate a cache key for an HTTP request.
     */
    public String generateCacheKey(String url, String method, String headers) {
        StringBuilder keyBuilder = new StringBuilder();
        keyBuilder.append(method != null ? method.toUpperCase() : "GET");
        keyBuilder.append(":");
        keyBuilder.append(url != null ? url : "");
        
        // Add relevant headers to cache key
        if (headers != null && !headers.isEmpty()) {
            keyBuilder.append(":");
            keyBuilder.append(Integer.toHexString(headers.hashCode()));
        }
        
        return keyBuilder.toString();
    }
    
    /**
     * Remove a response from cache.
     */
    public void removeResponse(String cacheKey) {
        if (cacheKey == null) {
            return;
        }
        
        cacheManager.remove(IntelligentCacheManager.CacheType.RESPONSE, cacheKey);
        log.debug("Removed response from cache: {}", cacheKey);
    }
    
    /**
     * Clear all cached responses.
     */
    public void clearAll() {
        cacheManager.clear(IntelligentCacheManager.CacheType.RESPONSE);
        log.info("All responses cleared from cache");
    }
    
    /**
     * Get response cache statistics.
     */
    public ResponseCacheStatistics getStatistics() {
        ResponseCacheStatistics stats = new ResponseCacheStatistics();
        
        stats.hits = responseHits.get();
        stats.misses = responseMisses.get();
        stats.conditionalHits = conditionalCacheHits.get();
        stats.cacheableResponses = cacheableResponses.get();
        stats.nonCacheableResponses = nonCacheableResponses.get();
        stats.totalRequests = stats.hits + stats.misses;
        stats.hitRatio = stats.totalRequests > 0 ? (double) stats.hits / stats.totalRequests : 0.0;
        
        // Get cache size from cache manager
        IntelligentCacheManager.CacheStatistics globalStats = cacheManager.getStatistics();
        IntelligentCacheManager.CacheTypeStatistics typeStats = globalStats.typeStatistics.get(IntelligentCacheManager.CacheType.RESPONSE);
        if (typeStats != null) {
            stats.cacheSize = typeStats.size;
            stats.maxCacheSize = typeStats.maxSize;
            stats.utilization = typeStats.getUtilization();
        }
        
        return stats;
    }
    
    /**
     * Check if a response is cacheable based on HTTP semantics.
     */
    private boolean isCacheable(HttpResponseData responseData) {
        // Don't cache error responses (4xx, 5xx)
        if (responseData.getStatusCode() >= 400) {
            return false;
        }
        
        // Check cache-control header
        String cacheControl = responseData.getHeaderValue("cache-control");
        if (cacheControl != null) {
            String lowerCaseControl = cacheControl.toLowerCase();
            if (lowerCaseControl.contains("no-cache") || 
                lowerCaseControl.contains("no-store") || 
                lowerCaseControl.contains("private")) {
                return false;
            }
        }
        
        // Don't cache very large responses (> 1MB)
        if (responseData.getBody() != null && responseData.getBody().length() > 1024 * 1024) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Calculate TTL from cache-control headers.
     */
    private long calculateTtl(HttpResponseData responseData) {
        // Default TTL
        long defaultTtl = 3600; // 1 hour
        
        // Parse cache-control header
        String cacheControl = responseData.getHeaderValue("cache-control");
        if (cacheControl != null) {
            String[] directives = cacheControl.split(",");
            for (String directive : directives) {
                directive = directive.trim().toLowerCase();
                if (directive.startsWith("max-age=")) {
                    try {
                        return Long.parseLong(directive.substring(8));
                    } catch (NumberFormatException e) {
                        log.debug("Invalid max-age value: {}", directive);
                    }
                }
            }
        }
        
        // Parse expires header
        String expires = responseData.getHeaderValue("expires");
        if (expires != null) {
            try {
                // This would need proper date parsing in a real implementation
                // For now, use default TTL
                return defaultTtl;
            } catch (Exception e) {
                log.debug("Failed to parse expires header: {}", expires);
            }
        }
        
        return defaultTtl;
    }
    
    /**
     * Represents cached HTTP response data.
     */
    public static class CachedResponse {
        private final String body;
        private final String headers;
        private final int statusCode;
        private final String contentType;
        private final Instant cachedAt;
        private final long ttlSeconds;
        
        public CachedResponse(String body, String headers, int statusCode, 
                            String contentType, Instant cachedAt, long ttlSeconds) {
            this.body = body;
            this.headers = headers;
            this.statusCode = statusCode;
            this.contentType = contentType;
            this.cachedAt = cachedAt;
            this.ttlSeconds = ttlSeconds;
        }
        
        public String getBody() {
            return body;
        }
        
        public String getHeaders() {
            return headers;
        }
        
        public int getStatusCode() {
            return statusCode;
        }
        
        public String getContentType() {
            return contentType;
        }
        
        public Instant getCachedAt() {
            return cachedAt;
        }
        
        public long getTtlSeconds() {
            return ttlSeconds;
        }
        
        public boolean isStale() {
            return Instant.now().isAfter(cachedAt.plusSeconds(ttlSeconds));
        }
        
        public String getEtag() {
            if (headers == null) {
                return null;
            }
            // Simple ETag extraction - would need more sophisticated parsing in real implementation
            return extractHeaderValue(headers, "etag");
        }
        
        public Instant getLastModified() {
            if (headers == null) {
                return null;
            }
            // Simple last-modified extraction - would need proper date parsing
            String lastModified = extractHeaderValue(headers, "last-modified");
            return lastModified != null ? cachedAt : null; // Simplified for now
        }
        
        private String extractHeaderValue(String headers, String headerName) {
            String lowerHeaders = headers.toLowerCase();
            String lowerHeaderName = headerName.toLowerCase();
            int index = lowerHeaders.indexOf(lowerHeaderName + ":");
            if (index >= 0) {
                int start = index + lowerHeaderName.length() + 1;
                int end = lowerHeaders.indexOf("\n", start);
                if (end < 0) end = lowerHeaders.length();
                return headers.substring(start, end).trim();
            }
            return null;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CachedResponse)) return false;
            CachedResponse that = (CachedResponse) o;
            return statusCode == that.statusCode &&
                   ttlSeconds == that.ttlSeconds &&
                   Objects.equals(body, that.body) &&
                   Objects.equals(headers, that.headers) &&
                   Objects.equals(contentType, that.contentType) &&
                   Objects.equals(cachedAt, that.cachedAt);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(body, headers, statusCode, contentType, cachedAt, ttlSeconds);
        }
    }
    
    /**
     * Represents HTTP response data for caching.
     */
    public static class HttpResponseData {
        private final String body;
        private final String headers;
        private final int statusCode;
        private final String contentType;
        
        public HttpResponseData(String body, String headers, int statusCode, String contentType) {
            this.body = body;
            this.headers = headers;
            this.statusCode = statusCode;
            this.contentType = contentType;
        }
        
        public String getBody() {
            return body;
        }
        
        public String getHeaders() {
            return headers;
        }
        
        public int getStatusCode() {
            return statusCode;
        }
        
        public String getContentType() {
            return contentType;
        }
        
        public String getHeaderValue(String headerName) {
            if (headers == null || headerName == null) {
                return null;
            }
            
            String lowerHeaders = headers.toLowerCase();
            String lowerHeaderName = headerName.toLowerCase();
            int index = lowerHeaders.indexOf(lowerHeaderName + ":");
            if (index >= 0) {
                int start = index + lowerHeaderName.length() + 1;
                int end = lowerHeaders.indexOf("\n", start);
                if (end < 0) end = lowerHeaders.length();
                return headers.substring(start, end).trim();
            }
            return null;
        }
    }
    
    /**
     * Response cache statistics.
     */
    public static class ResponseCacheStatistics {
        public long hits;
        public long misses;
        public long conditionalHits;
        public long cacheableResponses;
        public long nonCacheableResponses;
        public long totalRequests;
        public double hitRatio;
        public int cacheSize;
        public int maxCacheSize;
        public double utilization;
        
        @Override
        public String toString() {
            return String.format("ResponseCacheStats{hits=%d, misses=%d, hitRatio=%.2f%%, conditional=%d, cacheable=%d, non-cacheable=%d, size=%d/%d (%.1f%%)}",
                               hits, misses, hitRatio * 100, conditionalHits, cacheableResponses, nonCacheableResponses, cacheSize, maxCacheSize, utilization * 100);
        }
    }
}
