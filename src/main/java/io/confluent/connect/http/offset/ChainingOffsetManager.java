package io.confluent.connect.http.offset;

import io.confluent.connect.http.config.ApiConfig;
import org.apache.kafka.connect.source.SourceTaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Offset manager for chaining mode.
 * In this mode, the offset is extracted from the API response using a JSON pointer.
 * The extracted value is used as the offset for the next request.
 */
public class ChainingOffsetManager implements OffsetManager {
    
    private static final Logger log = LoggerFactory.getLogger(ChainingOffsetManager.class);
    
    private final ApiConfig apiConfig;
    private final SourceTaskContext context;
    private final Map<String, String> sourcePartition;
    
    private volatile String currentOffset;
    private final String initialOffset;
    
    public ChainingOffsetManager(ApiConfig apiConfig, SourceTaskContext context) {
        this.apiConfig = apiConfig;
        this.context = context;
        
        // Create source partition for Kafka Connect offset storage
        this.sourcePartition = new HashMap<>();
        this.sourcePartition.put("url", apiConfig.getFullUrl());
        
        // Get initial offset
        this.initialOffset = apiConfig.getHttpInitialOffset();
        
        // Validate that JSON pointer is configured
        String offsetJsonPointer = apiConfig.getHttpOffsetJsonPointer();
        if (offsetJsonPointer == null || offsetJsonPointer.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "http.offset.json.pointer must be configured for CHAINING mode in API: " + apiConfig.getId());
        }
        
        // Load current offset from Kafka Connect's offset storage
        loadCurrentOffset();
        
        log.debug("Initialized ChainingOffsetManager for API: {} with initial offset: {} and current offset: {}",
            apiConfig.getId(), initialOffset, currentOffset);
    }
    
    @Override
    public String getCurrentOffset() {
        return currentOffset;
    }
    
    @Override
    public void updateOffset(String newOffset) {
        if (newOffset != null && !newOffset.trim().isEmpty()) {
            this.currentOffset = newOffset.trim();
            log.trace("Updated offset for API {} to: {}", apiConfig.getId(), currentOffset);
        } else {
            log.debug("Received null or empty offset for API {}, keeping current offset: {}", 
                apiConfig.getId(), currentOffset);
        }
    }
    
    @Override
    public void resetOffset() {
        currentOffset = initialOffset;
        log.info("Reset offset for API {} to initial value: {}", apiConfig.getId(), initialOffset);
    }
    
    @Override
    public ApiConfig.HttpOffsetMode getOffsetMode() {
        return ApiConfig.HttpOffsetMode.CHAINING;
    }
    
    @Override
    public void close() {
        log.debug("Closing ChainingOffsetManager for API: {}", apiConfig.getId());
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
                        this.currentOffset = offsetValue.toString();
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
     * Gets the JSON pointer used for extracting offsets
     */
    public String getOffsetJsonPointer() {
        return apiConfig.getHttpOffsetJsonPointer();
    }
}