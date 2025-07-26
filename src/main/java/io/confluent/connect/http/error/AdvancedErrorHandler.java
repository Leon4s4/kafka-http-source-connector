package io.confluent.connect.http.error;

import io.confluent.connect.http.config.ApiConfig;
import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.errors.RetriableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Advanced error handling with circuit breaker patterns, error categorization,
 * and comprehensive error metrics tracking.
 */
public class AdvancedErrorHandler {
    
    private static final Logger log = LoggerFactory.getLogger(AdvancedErrorHandler.class);
    
    // Circuit breaker states
    public enum CircuitBreakerState {
        CLOSED,     // Normal operation
        OPEN,       // Circuit breaker tripped, failing fast
        HALF_OPEN   // Testing if service has recovered
    }
    
    // Error categories for different handling strategies
    public enum ErrorCategory {
        TRANSIENT,      // Temporary errors (network, 5xx)
        AUTHENTICATION, // Auth failures (401, 403)
        CLIENT_ERROR,   // Client errors (4xx except auth)
        CONFIGURATION,  // Configuration errors
        DATA_FORMAT,    // Data parsing/format errors
        RATE_LIMIT,     // Rate limiting (429)
        UNKNOWN         // Unknown/uncategorized errors
    }
    
    private final HttpSourceConnectorConfig config;
    private final Map<String, CircuitBreakerState> circuitStates;
    private final Map<String, AtomicInteger> failureCounters;
    private final Map<String, AtomicLong> lastFailureTimes;
    private final Map<String, ErrorMetrics> errorMetrics;
    
    // Circuit breaker configuration
    private final int failureThreshold;
    private final long timeoutMs;
    private final long recoveryTimeMs;
    
    public AdvancedErrorHandler(HttpSourceConnectorConfig config) {
        this.config = config;
        this.circuitStates = new ConcurrentHashMap<>();
        this.failureCounters = new ConcurrentHashMap<>();
        this.lastFailureTimes = new ConcurrentHashMap<>();
        this.errorMetrics = new ConcurrentHashMap<>();
        
        // Configure circuit breaker parameters
        this.failureThreshold = config.getCircuitBreakerFailureThreshold();
        this.timeoutMs = config.getCircuitBreakerTimeoutMs();
        this.recoveryTimeMs = config.getCircuitBreakerRecoveryTimeMs();
        
        log.info("Advanced error handler initialized with circuit breaker: threshold={}, timeout={}ms, recovery={}ms", 
            failureThreshold, timeoutMs, recoveryTimeMs);
    }
    
    /**
     * Handles an error for a specific API with circuit breaker logic
     */
    public void handleError(ApiConfig apiConfig, Exception error, Object failedData) {
        String apiId = apiConfig.getId();
        ErrorCategory category = categorizeError(error);
        
        // Update error metrics
        updateErrorMetrics(apiId, category, error);
        
        // Log the error with appropriate level based on category
        logError(apiId, category, error);
        
        // Handle circuit breaker logic
        handleCircuitBreaker(apiId, category, error);
        
        // Determine if error should be retried or failed
        if (shouldFailFast(apiId, category)) {
            throw new ConnectException("Circuit breaker open for API: " + apiId, error);
        } else if (isRetriableError(category)) {
            throw new RetriableException("Retriable error for API: " + apiId, error);
        } else {
            // Send to DLQ if configured
            sendToDeadLetterQueue(apiConfig, error, failedData);
        }
    }
    
    /**
     * Checks if an API can be called based on circuit breaker state
     */
    public boolean canCallApi(String apiId) {
        CircuitBreakerState state = circuitStates.getOrDefault(apiId, CircuitBreakerState.CLOSED);
        
        switch (state) {
            case CLOSED:
                return true;
                
            case OPEN:
                // Check if enough time has passed to try recovery
                long lastFailure = lastFailureTimes.getOrDefault(apiId, new AtomicLong(0)).get();
                if (System.currentTimeMillis() - lastFailure > recoveryTimeMs) {
                    // Move to half-open state
                    circuitStates.put(apiId, CircuitBreakerState.HALF_OPEN);
                    log.info("Circuit breaker for API {} moved to HALF_OPEN state", apiId);
                    return true;
                }
                return false;
                
            case HALF_OPEN:
                return true;
                
            default:
                return false;
        }
    }
    
    /**
     * Records a successful API call for circuit breaker recovery
     */
    public void recordSuccess(String apiId) {
        CircuitBreakerState state = circuitStates.get(apiId);
        
        if (state == CircuitBreakerState.HALF_OPEN) {
            // Successful call in half-open state, close the circuit
            circuitStates.put(apiId, CircuitBreakerState.CLOSED);
            failureCounters.put(apiId, new AtomicInteger(0));
            log.info("Circuit breaker for API {} closed after successful recovery", apiId);
        }
        
        // Update success metrics
        ErrorMetrics metrics = errorMetrics.computeIfAbsent(apiId, k -> new ErrorMetrics());
        metrics.successCount.incrementAndGet();
    }
    
    /**
     * Categorizes an error into specific types for handling
     */
    private ErrorCategory categorizeError(Exception error) {
        String errorMessage = error.getMessage().toLowerCase();
        
        // Check for HTTP-specific errors
        if (error instanceof io.confluent.connect.http.client.HttpApiClient.HttpRequestException) {
            io.confluent.connect.http.client.HttpApiClient.HttpRequestException httpError = 
                (io.confluent.connect.http.client.HttpApiClient.HttpRequestException) error;
            
            int statusCode = httpError.getStatusCode();
            
            if (statusCode == 401 || statusCode == 403) {
                return ErrorCategory.AUTHENTICATION;
            } else if (statusCode == 429) {
                return ErrorCategory.RATE_LIMIT;
            } else if (statusCode >= 400 && statusCode < 500) {
                return ErrorCategory.CLIENT_ERROR;
            } else if (statusCode >= 500) {
                return ErrorCategory.TRANSIENT;
            }
        }
        
        // Check for network/connection errors
        if (error instanceof java.net.ConnectException || 
            error instanceof java.net.SocketTimeoutException ||
            error instanceof java.net.UnknownHostException ||
            errorMessage.contains("connection") ||
            errorMessage.contains("timeout")) {
            return ErrorCategory.TRANSIENT;
        }
        
        // Check for data format errors
        if (error instanceof com.fasterxml.jackson.core.JsonProcessingException ||
            errorMessage.contains("json") ||
            errorMessage.contains("parse") ||
            errorMessage.contains("format")) {
            return ErrorCategory.DATA_FORMAT;
        }
        
        // Check for configuration errors
        if (error instanceof IllegalArgumentException ||
            error instanceof IllegalStateException ||
            errorMessage.contains("config") ||
            errorMessage.contains("invalid")) {
            return ErrorCategory.CONFIGURATION;
        }
        
        return ErrorCategory.UNKNOWN;
    }
    
    /**
     * Handles circuit breaker state transitions
     */
    private void handleCircuitBreaker(String apiId, ErrorCategory category, Exception error) {
        // Only count certain error types toward circuit breaker
        if (!shouldCountTowardCircuitBreaker(category)) {
            return;
        }
        
        AtomicInteger failureCount = failureCounters.computeIfAbsent(apiId, k -> new AtomicInteger(0));
        AtomicLong lastFailureTime = lastFailureTimes.computeIfAbsent(apiId, k -> new AtomicLong(0));
        
        int failures = failureCount.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());
        
        CircuitBreakerState currentState = circuitStates.getOrDefault(apiId, CircuitBreakerState.CLOSED);
        
        if (currentState == CircuitBreakerState.HALF_OPEN) {
            // Failure in half-open state, go back to open
            circuitStates.put(apiId, CircuitBreakerState.OPEN);
            failureCount.set(failureThreshold); // Reset to threshold
            log.warn("Circuit breaker for API {} reopened after failure in HALF_OPEN state", apiId);
        } else if (currentState == CircuitBreakerState.CLOSED && failures >= failureThreshold) {
            // Too many failures, open the circuit
            circuitStates.put(apiId, CircuitBreakerState.OPEN);
            log.error("Circuit breaker OPENED for API {} after {} failures", apiId, failures);
        }
    }
    
    /**
     * Determines if an error category should count toward circuit breaker
     */
    private boolean shouldCountTowardCircuitBreaker(ErrorCategory category) {
        switch (category) {
            case TRANSIENT:
            case RATE_LIMIT:
                return true;
            case AUTHENTICATION:
            case CLIENT_ERROR:
            case CONFIGURATION:
            case DATA_FORMAT:
            case UNKNOWN:
            default:
                return false;
        }
    }
    
    /**
     * Determines if the API should fail fast based on circuit breaker state
     */
    private boolean shouldFailFast(String apiId, ErrorCategory category) {
        CircuitBreakerState state = circuitStates.getOrDefault(apiId, CircuitBreakerState.CLOSED);
        return state == CircuitBreakerState.OPEN && shouldCountTowardCircuitBreaker(category);
    }
    
    /**
     * Determines if an error is retriable
     */
    private boolean isRetriableError(ErrorCategory category) {
        switch (category) {
            case TRANSIENT:
            case RATE_LIMIT:
                return true;
            case AUTHENTICATION:
            case CLIENT_ERROR:
            case CONFIGURATION:
            case DATA_FORMAT:
            case UNKNOWN:
            default:
                return false;
        }
    }
    
    /**
     * Logs errors with appropriate severity levels
     */
    private void logError(String apiId, ErrorCategory category, Exception error) {
        switch (category) {
            case TRANSIENT:
            case RATE_LIMIT:
                log.warn("Transient error for API {}: {}", apiId, error.getMessage());
                break;
            case AUTHENTICATION:
                log.error("Authentication error for API {}: {}", apiId, error.getMessage());
                break;
            case CLIENT_ERROR:
                log.warn("Client error for API {}: {}", apiId, error.getMessage());
                break;
            case CONFIGURATION:
                log.error("Configuration error for API {}: {}", apiId, error.getMessage());
                break;
            case DATA_FORMAT:
                log.warn("Data format error for API {}: {}", apiId, error.getMessage());
                break;
            case UNKNOWN:
            default:
                log.error("Unknown error for API {}: {}", apiId, error.getMessage(), error);
                break;
        }
    }
    
    /**
     * Updates error metrics for monitoring
     */
    private void updateErrorMetrics(String apiId, ErrorCategory category, Exception error) {
        ErrorMetrics metrics = errorMetrics.computeIfAbsent(apiId, k -> new ErrorMetrics());
        
        metrics.totalErrors.incrementAndGet();
        metrics.lastErrorTime.set(System.currentTimeMillis());
        metrics.errorsByCategory.computeIfAbsent(category, k -> new AtomicInteger(0)).incrementAndGet();
        
        // Track specific error messages
        String errorKey = error.getClass().getSimpleName();
        metrics.errorsByType.computeIfAbsent(errorKey, k -> new AtomicInteger(0)).incrementAndGet();
    }
    
    /**
     * Sends failed records to dead letter queue
     */
    private void sendToDeadLetterQueue(ApiConfig apiConfig, Exception error, Object failedData) {
        if (config.getBehaviorOnError() == HttpSourceConnectorConfig.BehaviorOnError.IGNORE) {
            log.debug("Ignoring error for API {} as configured", apiConfig.getId());
            return;
        }
        
        // TODO: Implement DLQ record production
        log.warn("Failed record from API {} would be sent to DLQ: {}", 
            apiConfig.getId(), error.getMessage());
    }
    
    /**
     * Gets error metrics for an API
     */
    public ErrorMetrics getErrorMetrics(String apiId) {
        return errorMetrics.getOrDefault(apiId, new ErrorMetrics());
    }
    
    /**
     * Gets circuit breaker state for an API
     */
    public CircuitBreakerState getCircuitBreakerState(String apiId) {
        return circuitStates.getOrDefault(apiId, CircuitBreakerState.CLOSED);
    }
    
    /**
     * Resets circuit breaker for an API (for administrative purposes)
     */
    public void resetCircuitBreaker(String apiId) {
        circuitStates.put(apiId, CircuitBreakerState.CLOSED);
        failureCounters.put(apiId, new AtomicInteger(0));
        lastFailureTimes.put(apiId, new AtomicLong(0));
        log.info("Circuit breaker reset for API: {}", apiId);
    }
    
    /**
     * Error metrics tracking
     */
    public static class ErrorMetrics {
        public final AtomicLong totalErrors = new AtomicLong(0);
        public final AtomicLong successCount = new AtomicLong(0);
        public final AtomicLong lastErrorTime = new AtomicLong(0);
        public final Map<ErrorCategory, AtomicInteger> errorsByCategory = new ConcurrentHashMap<>();
        public final Map<String, AtomicInteger> errorsByType = new ConcurrentHashMap<>();
        
        public double getErrorRate() {
            long total = totalErrors.get() + successCount.get();
            return total > 0 ? (double) totalErrors.get() / total : 0.0;
        }
        
        public long getTimeSinceLastError() {
            long lastError = lastErrorTime.get();
            return lastError > 0 ? System.currentTimeMillis() - lastError : -1;
        }
    }
}