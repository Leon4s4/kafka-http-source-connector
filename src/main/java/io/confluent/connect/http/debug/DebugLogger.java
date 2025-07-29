package io.confluent.connect.http.debug;

import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Debug logging utility for HTTP Source Connector.
 * Provides configurable debug logging for HTTP requests and responses.
 */
public class DebugLogger {
    
    private static final Logger log = LoggerFactory.getLogger(DebugLogger.class);
    
    private final boolean debugLoggingEnabled;
    private final boolean logRequestHeaders;
    private final boolean logResponseBody;
    private final boolean logResponseHeaders;
    private final boolean logRequestBody;
    
    // Maximum body size to log (to prevent memory issues)
    private static final int MAX_BODY_SIZE = 10000; // 10KB
    
    public DebugLogger(HttpSourceConnectorConfig config) {
        this.debugLoggingEnabled = config.isDebugLoggingEnabled();
        this.logRequestHeaders = config.isDebugLogRequestHeaders();
        this.logResponseBody = config.isDebugLogResponseBody();
        this.logResponseHeaders = config.isDebugLogResponseHeaders();
        this.logRequestBody = config.isDebugLogRequestBody();
        
        if (debugLoggingEnabled) {
            log.info("Debug logging enabled - Request Headers: {}, Request Body: {}, Response Headers: {}, Response Body: {}",
                    logRequestHeaders, logRequestBody, logResponseHeaders, logResponseBody);
        }
    }
    
    /**
     * Log HTTP request details if debug logging is enabled.
     */
    public void logRequest(String method, String url, Map<String, String> headers, String body) {
        if (!debugLoggingEnabled) {
            return;
        }
        
        StringBuilder logMessage = new StringBuilder();
        logMessage.append("\n=== HTTP REQUEST DEBUG ===\n");
        logMessage.append("Method: ").append(method).append("\n");
        logMessage.append("URL: ").append(url).append("\n");
        
        if (logRequestHeaders && headers != null && !headers.isEmpty()) {
            logMessage.append("Headers:\n");
            headers.forEach((key, value) -> {
                // Mask sensitive headers
                String maskedValue = isSensitiveHeader(key) ? maskSensitiveValue(value) : value;
                logMessage.append("  ").append(key).append(": ").append(maskedValue).append("\n");
            });
        }
        
        if (logRequestBody && body != null && !body.isEmpty()) {
            logMessage.append("Body:\n");
            logMessage.append(truncateIfNeeded(body)).append("\n");
        }
        
        logMessage.append("=== END REQUEST DEBUG ===");
        log.debug(logMessage.toString());
    }
    
    /**
     * Log HTTP response details if debug logging is enabled.
     */
    public void logResponse(int statusCode, Map<String, java.util.List<String>> headers, String body, long responseTimeMs) {
        if (!debugLoggingEnabled) {
            return;
        }
        
        StringBuilder logMessage = new StringBuilder();
        logMessage.append("\n=== HTTP RESPONSE DEBUG ===\n");
        logMessage.append("Status Code: ").append(statusCode).append("\n");
        logMessage.append("Response Time: ").append(responseTimeMs).append("ms\n");
        
        if (logResponseHeaders && headers != null && !headers.isEmpty()) {
            logMessage.append("Headers:\n");
            headers.forEach((key, values) -> {
                String valueString = values != null ? String.join(", ", values) : "";
                logMessage.append("  ").append(key).append(": ").append(valueString).append("\n");
            });
        }
        
        if (logResponseBody && body != null && !body.isEmpty()) {
            logMessage.append("Body:\n");
            logMessage.append(truncateIfNeeded(body)).append("\n");
        }
        
        logMessage.append("=== END RESPONSE DEBUG ===");
        log.debug(logMessage.toString());
    }
    
    /**
     * Log error details if debug logging is enabled.
     */
    public void logError(String operation, String url, Throwable error) {
        if (!debugLoggingEnabled) {
            return;
        }
        
        StringBuilder logMessage = new StringBuilder();
        logMessage.append("\n=== HTTP ERROR DEBUG ===\n");
        logMessage.append("Operation: ").append(operation).append("\n");
        logMessage.append("URL: ").append(url).append("\n");
        logMessage.append("Error: ").append(error.getClass().getSimpleName()).append("\n");
        logMessage.append("Message: ").append(error.getMessage()).append("\n");
        
        if (error.getCause() != null) {
            logMessage.append("Cause: ").append(error.getCause().getClass().getSimpleName())
                     .append(" - ").append(error.getCause().getMessage()).append("\n");
        }
        
        logMessage.append("=== END ERROR DEBUG ===");
        log.debug(logMessage.toString(), error);
    }
    
    /**
     * Log connection details if debug logging is enabled.
     */
    public void logConnection(String url, String connectionInfo) {
        if (!debugLoggingEnabled) {
            return;
        }
        
        StringBuilder logMessage = new StringBuilder();
        logMessage.append("\n=== CONNECTION DEBUG ===\n");
        logMessage.append("URL: ").append(url).append("\n");
        logMessage.append("Connection Info: ").append(connectionInfo).append("\n");
        logMessage.append("=== END CONNECTION DEBUG ===");
        log.debug(logMessage.toString());
    }
    
    /**
     * Log authentication details if debug logging is enabled.
     */
    public void logAuthentication(String authType, String details) {
        if (!debugLoggingEnabled) {
            return;
        }
        
        StringBuilder logMessage = new StringBuilder();
        logMessage.append("\n=== AUTHENTICATION DEBUG ===\n");
        logMessage.append("Auth Type: ").append(authType).append("\n");
        logMessage.append("Details: ").append(details).append("\n");
        logMessage.append("=== END AUTHENTICATION DEBUG ===");
        log.debug(logMessage.toString());
    }
    
    /**
     * Log pagination details if debug logging is enabled.
     */
    public void logPagination(String apiId, String paginationType, String details) {
        if (!debugLoggingEnabled) {
            return;
        }
        
        StringBuilder logMessage = new StringBuilder();
        logMessage.append("\n=== PAGINATION DEBUG ===\n");
        logMessage.append("API ID: ").append(apiId).append("\n");
        logMessage.append("Pagination Type: ").append(paginationType).append("\n");
        logMessage.append("Details: ").append(details).append("\n");
        logMessage.append("=== END PAGINATION DEBUG ===");
        log.debug(logMessage.toString());
    }
    
    /**
     * Log cache operations if debug logging is enabled.
     */
    public void logCacheOperation(String operation, String cacheKey, boolean hit, String details) {
        if (!debugLoggingEnabled) {
            return;
        }
        
        StringBuilder logMessage = new StringBuilder();
        logMessage.append("\n=== CACHE DEBUG ===\n");
        logMessage.append("Operation: ").append(operation).append("\n");
        logMessage.append("Cache Key: ").append(cacheKey).append("\n");
        logMessage.append("Cache Hit: ").append(hit).append("\n");
        if (details != null) {
            logMessage.append("Details: ").append(details).append("\n");
        }
        logMessage.append("=== END CACHE DEBUG ===");
        log.debug(logMessage.toString());
    }
    
    /**
     * Check if debug logging is enabled.
     */
    public boolean isDebugLoggingEnabled() {
        return debugLoggingEnabled;
    }
    
    /**
     * Check if request headers should be logged.
     */
    public boolean shouldLogRequestHeaders() {
        return debugLoggingEnabled && logRequestHeaders;
    }
    
    /**
     * Check if response body should be logged.
     */
    public boolean shouldLogResponseBody() {
        return debugLoggingEnabled && logResponseBody;
    }
    
    /**
     * Check if response headers should be logged.
     */
    public boolean shouldLogResponseHeaders() {
        return debugLoggingEnabled && logResponseHeaders;
    }
    
    /**
     * Check if request body should be logged.
     */
    public boolean shouldLogRequestBody() {
        return debugLoggingEnabled && logRequestBody;
    }
    
    /**
     * Check if header contains sensitive information.
     */
    private boolean isSensitiveHeader(String headerName) {
        if (headerName == null) {
            return false;
        }
        
        String lowerName = headerName.toLowerCase();
        return lowerName.contains("authorization") ||
               lowerName.contains("token") ||
               lowerName.contains("key") ||
               lowerName.contains("secret") ||
               lowerName.contains("password") ||
               lowerName.contains("credential");
    }
    
    /**
     * Mask sensitive values for logging.
     */
    private String maskSensitiveValue(String value) {
        if (value == null || value.length() <= 8) {
            return "***";
        }
        
        // Show first 4 and last 4 characters, mask the middle
        String start = value.substring(0, 4);
        String end = value.substring(value.length() - 4);
        int maskedLength = value.length() - 8;
        String masked = "*".repeat(Math.min(maskedLength, 10)); // Max 10 asterisks
        
        return start + masked + end;
    }
    
    /**
     * Truncate body content if it exceeds maximum size.
     */
    private String truncateIfNeeded(String body) {
        if (body == null) {
            return null;
        }
        
        if (body.length() <= MAX_BODY_SIZE) {
            return body;
        }
        
        return body.substring(0, MAX_BODY_SIZE) + 
               "\n... [TRUNCATED - Original size: " + body.length() + " characters]";
    }
    
    /**
     * Create a simple debug logger with all options enabled (for testing).
     */
    public static DebugLogger createFullDebugLogger() {
        return new DebugLogger(true, true, true, true, true);
    }
    
    /**
     * Constructor for testing with explicit flags.
     */
    public DebugLogger(boolean debugEnabled, boolean requestHeaders, boolean requestBody, 
                      boolean responseHeaders, boolean responseBody) {
        this.debugLoggingEnabled = debugEnabled;
        this.logRequestHeaders = requestHeaders;
        this.logRequestBody = requestBody;
        this.logResponseHeaders = responseHeaders;
        this.logResponseBody = responseBody;
    }
}