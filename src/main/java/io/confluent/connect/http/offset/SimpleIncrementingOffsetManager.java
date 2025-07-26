package io.confluent.connect.http.offset;

import io.confluent.connect.http.config.ApiConfig;
import org.apache.kafka.connect.source.SourceTaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Offset manager for simple incrementing mode.
 * In this mode, the offset is simply incremented by 1 for each record processed.
 */
public class SimpleIncrementingOffsetManager implements OffsetManager {
    
    private static final Logger log = LoggerFactory.getLogger(SimpleIncrementingOffsetManager.class);
    
    private final ApiConfig apiConfig;
    private final SourceTaskContext context;
    private final Map<String, String> sourcePartition;
    
    private volatile long currentOffset;
    private final long initialOffset;
    
    public SimpleIncrementingOffsetManager(ApiConfig apiConfig, SourceTaskContext context) {
        this.apiConfig = apiConfig;
        this.context = context;
        
        // Create source partition for Kafka Connect offset storage
        this.sourcePartition = new HashMap<>();
        this.sourcePartition.put("url", apiConfig.getFullUrl());
        
        // Parse initial offset
        String initialOffsetStr = apiConfig.getHttpInitialOffset();
        long parsedInitialOffset;
        try {
            parsedInitialOffset = initialOffsetStr != null && !initialOffsetStr.isEmpty() ? 
                                 Long.parseLong(initialOffsetStr) : 0L;
        } catch (NumberFormatException e) {
            log.warn("Invalid initial offset '{}', using 0", initialOffsetStr);
            parsedInitialOffset = 0L;
        }
        this.initialOffset = parsedInitialOffset;
        
        // Load current offset from Kafka Connect's offset storage
        loadCurrentOffset();
        
        log.debug("Initialized SimpleIncrementingOffsetManager for API: {} with initial offset: {} and current offset: {}",
            apiConfig.getId(), initialOffset, currentOffset);
    }
    
    @Override
    public String getCurrentOffset() {
        return String.valueOf(currentOffset);
    }
    
    @Override
    public void updateOffset(String newOffset) {
        // In simple incrementing mode, we ignore the newOffset parameter
        // and just increment the current offset by 1
        currentOffset++;
        
        log.trace("Updated offset for API {} to: {}", apiConfig.getId(), currentOffset);
    }
    
    @Override
    public void resetOffset() {
        currentOffset = initialOffset;
        log.info("Reset offset for API {} to initial value: {}", apiConfig.getId(), initialOffset);
    }
    
    @Override
    public ApiConfig.HttpOffsetMode getOffsetMode() {
        return ApiConfig.HttpOffsetMode.SIMPLE_INCREMENTING;
    }
    
    @Override
    public void close() {
        log.debug("Closing SimpleIncrementingOffsetManager for API: {}", apiConfig.getId());
        // Nothing specific to close for this implementation
    }
    
    /**
     * Loads the current offset from Kafka Connect's offset storage
     */
    private void loadCurrentOffset() {
        if (context != null && context.offsetStorageReader() != null) {
            try {
                Map<String, Object> offset = context.offsetStorageReader().offset(sourcePartition);
                
                if (offset != null && offset.containsKey("offset")) {
                    Object offsetValue = offset.get("offset");
                    if (offsetValue != null) {
                        this.currentOffset = Long.parseLong(offsetValue.toString());
                        log.debug("Loaded existing offset for API {}: {}", apiConfig.getId(), currentOffset);
                        return;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to load existing offset for API {}, using initial offset: {}", 
                    apiConfig.getId(), e.getMessage());
            }
        }
        
        // Use initial offset if no stored offset is found
        this.currentOffset = initialOffset;
        log.debug("Using initial offset for API {}: {}", apiConfig.getId(), currentOffset);
    }
    
    /**
     * Gets the source partition for Kafka Connect offset storage
     */
    public Map<String, String> getSourcePartition() {
        return sourcePartition;
    }
    
    /**
     * Gets the current offset value as a long
     */
    public long getCurrentOffsetAsLong() {
        return currentOffset;
    }
}