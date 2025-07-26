package io.confluent.connect.http.offset;

import io.confluent.connect.http.config.ApiConfig;
import org.apache.kafka.connect.source.SourceTaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory class for creating offset managers based on the configured offset mode.
 */
public class OffsetManagerFactory {
    
    private static final Logger log = LoggerFactory.getLogger(OffsetManagerFactory.class);
    
    /**
     * Creates an appropriate OffsetManager based on the API configuration
     * 
     * @param apiConfig The API configuration
     * @param context The source task context for accessing Kafka Connect's offset storage
     * @return An OffsetManager instance for the specified offset mode
     */
    public static OffsetManager create(ApiConfig apiConfig, SourceTaskContext context) {
        ApiConfig.HttpOffsetMode offsetMode = apiConfig.getHttpOffsetMode();
        
        log.info("Creating offset manager for API {} with mode: {}", apiConfig.getId(), offsetMode);
        
        switch (offsetMode) {
            case SIMPLE_INCREMENTING:
                return new SimpleIncrementingOffsetManager(apiConfig, context);
            
            case CHAINING:
                return new ChainingOffsetManager(apiConfig, context);
            
            case CURSOR_PAGINATION:
                return new CursorPaginationOffsetManager(apiConfig, context);
            
            case SNAPSHOT_PAGINATION:
                return new SnapshotPaginationOffsetManager(apiConfig, context);
            
            default:
                throw new IllegalArgumentException("Unsupported offset mode: " + offsetMode);
        }
    }
}