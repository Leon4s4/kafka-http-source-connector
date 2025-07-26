package io.confluent.connect.http.error;

import io.confluent.connect.http.config.ApiConfig;
import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles errors that occur during HTTP API processing.
 * Supports different error handling strategies based on configuration.
 */
public class ErrorHandler {
    
    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);
    
    private final HttpSourceConnectorConfig config;
    
    public ErrorHandler(HttpSourceConnectorConfig config) {
        this.config = config;
        log.debug("Initialized ErrorHandler with behavior: {}", config.getBehaviorOnError());
    }
    
    /**
     * Handles an error that occurred during API processing
     * 
     * @param apiConfig The API configuration where the error occurred
     * @param error The error that occurred
     * @param data The data being processed when the error occurred (can be null)
     */
    public void handleError(ApiConfig apiConfig, Exception error, Object data) {
        log.error("Error processing API {}: {}", apiConfig.getId(), error.getMessage(), error);
        
        switch (config.getBehaviorOnError()) {
            case FAIL:
                // Let the error propagate to fail the task
                log.error("Failing task due to error in API: {}", apiConfig.getId());
                throw new RuntimeException("API processing failed: " + error.getMessage(), error);
                
            case IGNORE:
                // Log the error but continue processing
                log.warn("Ignoring error in API {} and continuing: {}", apiConfig.getId(), error.getMessage());
                
                // TODO: In a full implementation, send to Dead Letter Queue
                sendToDeadLetterQueue(apiConfig, error, data);
                break;
                
            default:
                throw new IllegalArgumentException("Unsupported error behavior: " + config.getBehaviorOnError());
        }
    }
    
    /**
     * Sends failed records to the Dead Letter Queue
     * This is a placeholder implementation - in a full version, this would
     * publish to the configured error topic.
     */
    private void sendToDeadLetterQueue(ApiConfig apiConfig, Exception error, Object data) {
        log.debug("Would send to DLQ - API: {}, Error: {}, Data: {}", 
            apiConfig.getId(), error.getMessage(), data);
        
        // TODO: Implement actual DLQ functionality
        // This would involve:
        // 1. Creating a record with error information
        // 2. Publishing to the configured error topic
        // 3. Including original data, error details, timestamp, etc.
    }
}