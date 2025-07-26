package io.confluent.connect.http.client;

import io.confluent.connect.http.config.ApiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles retry logic for HTTP requests with configurable backoff policies.
 */
public class RetryHandler {
    
    private static final Logger log = LoggerFactory.getLogger(RetryHandler.class);
    
    private final ApiConfig apiConfig;
    private final List<StatusCodeRange> retryStatusCodes;
    private final Random random;
    
    // Always retry on these status codes regardless of configuration
    private static final int[] ALWAYS_RETRY_CODES = {401, 408, 429, 502, 503, 504};
    
    public RetryHandler(ApiConfig apiConfig) {
        this.apiConfig = apiConfig;
        this.retryStatusCodes = parseRetryStatusCodes(apiConfig.getRetryOnStatusCodes());
        this.random = new Random();
        
        log.debug("Initialized RetryHandler for API: {} with max retries: {} and backoff policy: {}",
            apiConfig.getId(), apiConfig.getMaxRetries(), apiConfig.getRetryBackoffPolicy());
    }
    
    /**
     * Executes the given callable with retry logic
     * 
     * @param callable The operation to execute
     * @return The result of the operation
     * @throws Exception if all retry attempts fail
     */
    public <T> T executeWithRetry(Callable<T> callable) throws Exception {
        int maxRetries = apiConfig.getMaxRetries();
        Exception lastException = null;
        
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                if (attempt > 0) {
                    // Wait before retry (except for first attempt)
                    long backoffMs = calculateBackoffTime(attempt);
                    log.debug("Retrying request in {}ms (attempt {} of {})", 
                        backoffMs, attempt, maxRetries);
                    Thread.sleep(backoffMs);
                }
                
                return callable.call();
                
            } catch (Exception e) {
                lastException = e;
                
                if (attempt == maxRetries) {
                    log.error("Request failed after {} attempts", maxRetries + 1, e);
                    break;
                }
                
                if (!shouldRetry(e)) {
                    log.debug("Not retrying due to exception type: {}", e.getClass().getSimpleName());
                    break;
                }
                
                log.warn("Request attempt {} failed, will retry: {}", attempt + 1, e.getMessage());
            }
        }
        
        throw lastException;
    }
    
    /**
     * Determines if the request should be retried based on the exception
     */
    private boolean shouldRetry(Exception e) {
        if (e instanceof HttpApiClient.HttpRequestException) {
            HttpApiClient.HttpRequestException httpException = (HttpApiClient.HttpRequestException) e;
            return shouldRetryStatusCode(httpException.getStatusCode());
        }
        
        if (e instanceof IOException) {
            // Retry on network/IO exceptions
            return true;
        }
        
        // Don't retry on other types of exceptions
        return false;
    }
    
    /**
     * Determines if the given HTTP status code should be retried
     */
    private boolean shouldRetryStatusCode(int statusCode) {
        // Always retry certain status codes
        for (int alwaysRetryCode : ALWAYS_RETRY_CODES) {
            if (statusCode == alwaysRetryCode) {
                return true;
            }
        }
        
        // Check configured retry status codes
        for (StatusCodeRange range : retryStatusCodes) {
            if (range.contains(statusCode)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Calculates the backoff time for the given attempt number
     */
    private long calculateBackoffTime(int attempt) {
        long baseBackoffMs = apiConfig.getRetryBackoffMs();
        
        switch (apiConfig.getRetryBackoffPolicy()) {
            case CONSTANT_VALUE:
                return baseBackoffMs;
                
            case EXPONENTIAL_WITH_JITTER:
                // Exponential backoff: baseBackoffMs * 2^(attempt-1)
                long exponentialBackoff = baseBackoffMs * (1L << (attempt - 1));
                
                // Add jitter: random value between 0.5 and 1.5 times the calculated backoff
                double jitterFactor = 0.5 + random.nextDouble(); // 0.5 to 1.5
                return (long) (exponentialBackoff * jitterFactor);
                
            default:
                throw new IllegalArgumentException("Unsupported backoff policy: " + apiConfig.getRetryBackoffPolicy());
        }
    }
    
    /**
     * Parses the retry status codes configuration string
     * Format: "400-,404,500-502" means retry on 400+, 404, and 500-502
     */
    private List<StatusCodeRange> parseRetryStatusCodes(String retryOnStatusCodes) {
        List<StatusCodeRange> ranges = new ArrayList<>();
        
        if (retryOnStatusCodes == null || retryOnStatusCodes.trim().isEmpty()) {
            // Default to retrying on 4xx and 5xx errors
            ranges.add(new StatusCodeRange(400, Integer.MAX_VALUE));
            return ranges;
        }
        
        String[] parts = retryOnStatusCodes.split(",");
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;
            
            try {
                if (part.contains("-")) {
                    // Range format: "400-500" or "400-" (open-ended)
                    String[] rangeParts = part.split("-", 2);
                    int start = Integer.parseInt(rangeParts[0]);
                    int end = rangeParts[1].isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(rangeParts[1]);
                    ranges.add(new StatusCodeRange(start, end));
                } else {
                    // Single status code
                    int statusCode = Integer.parseInt(part);
                    ranges.add(new StatusCodeRange(statusCode, statusCode));
                }
            } catch (NumberFormatException e) {
                log.warn("Invalid retry status code configuration: {}", part);
            }
        }
        
        return ranges;
    }
    
    /**
     * Represents a range of HTTP status codes
     */
    private static class StatusCodeRange {
        private final int start;
        private final int end;
        
        public StatusCodeRange(int start, int end) {
            this.start = start;
            this.end = end;
        }
        
        public boolean contains(int statusCode) {
            return statusCode >= start && statusCode <= end;
        }
        
        @Override
        public String toString() {
            if (start == end) {
                return String.valueOf(start);
            } else if (end == Integer.MAX_VALUE) {
                return start + "-";
            } else {
                return start + "-" + end;
            }
        }
    }
}