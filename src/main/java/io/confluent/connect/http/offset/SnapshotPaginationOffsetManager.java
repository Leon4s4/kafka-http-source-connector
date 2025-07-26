package io.confluent.connect.http.offset;

import io.confluent.connect.http.config.ApiConfig;
import org.apache.kafka.connect.source.SourceTaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Offset manager for snapshot pagination mode.
 * In this mode, the API provides a static URL that returns incremental records over time.
 * The offset tracks the last processed record to avoid duplicates in subsequent polls.
 */
public class SnapshotPaginationOffsetManager implements OffsetManager {
    
    private static final Logger log = LoggerFactory.getLogger(SnapshotPaginationOffsetManager.class);
    
    private final ApiConfig apiConfig;
    private final SourceTaskContext context;
    private final Map<String, String> sourcePartition;
    
    private volatile String lastProcessedOffset;
    private final String initialOffset;
    
    public SnapshotPaginationOffsetManager(ApiConfig apiConfig, SourceTaskContext context) {
        this.apiConfig = apiConfig;
        this.context = context;
        
        // Create source partition for Kafka Connect offset storage
        this.sourcePartition = new HashMap<>();
        this.sourcePartition.put("url", apiConfig.getFullUrl());
        
        // Get initial offset
        this.initialOffset = apiConfig.getHttpInitialOffset();
        
        // Validate that offset JSON pointer is configured
        String offsetJsonPointer = apiConfig.getHttpOffsetJsonPointer();
        if (offsetJsonPointer == null || offsetJsonPointer.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "http.offset.json.pointer must be configured for SNAPSHOT_PAGINATION mode in API: " + apiConfig.getId());
        }
        
        // Load current offset from Kafka Connect's offset storage
        loadCurrentOffset();
        
        log.debug("Initialized SnapshotPaginationOffsetManager for API: {} with initial offset: {} and current offset: {}",
            apiConfig.getId(), initialOffset, lastProcessedOffset);
    }
    
    @Override
    public String getCurrentOffset() {
        // In snapshot pagination, we don't use offset for template replacement
        // The offset is used internally to track the last processed record
        return null;
    }
    
    @Override
    public void updateOffset(String newOffset) {
        if (newOffset != null && !newOffset.trim().isEmpty()) {
            // Update the last processed offset to avoid processing duplicates
            String trimmedOffset = newOffset.trim();
            
            // Only update if the new offset is different (assuming natural ordering)
            if (!trimmedOffset.equals(lastProcessedOffset)) {
                this.lastProcessedOffset = trimmedOffset;
                log.trace("Updated last processed offset for API {} to: {}", apiConfig.getId(), lastProcessedOffset);
            }
        }
    }
    
    @Override
    public void resetOffset() {
        lastProcessedOffset = initialOffset;
        log.info("Reset last processed offset for API {} to initial value: {}", apiConfig.getId(), initialOffset);
    }
    
    @Override
    public ApiConfig.HttpOffsetMode getOffsetMode() {
        return ApiConfig.HttpOffsetMode.SNAPSHOT_PAGINATION;
    }
    
    @Override
    public void close() {
        log.debug("Closing SnapshotPaginationOffsetManager for API: {}", apiConfig.getId());
        // Nothing specific to close for this implementation
    }
    
    /**
     * Checks if a record should be processed based on its offset value.
     * Records with offset values less than or equal to the last processed offset are skipped.
     * 
     * @param recordOffset The offset value from the record
     * @return true if the record should be processed, false if it should be skipped
     */
    public boolean shouldProcessRecord(String recordOffset) {
        if (recordOffset == null || recordOffset.trim().isEmpty()) {
            log.debug("Record has no offset value, processing it");
            return true;
        }
        
        if (lastProcessedOffset == null || lastProcessedOffset.isEmpty()) {
            log.debug("No last processed offset, processing record with offset: {}", recordOffset);
            return true;
        }
        
        if (isLastProcessedOffsetNumeric) {
            try {
                long recordOffsetLong = Long.parseLong(recordOffset.trim());
                long lastProcessedOffsetLong = Long.parseLong(lastProcessedOffset);
                
                boolean shouldProcess = recordOffsetLong > lastProcessedOffsetLong;
                log.trace("Comparing record offset {} with last processed {}: shouldProcess = {}", 
                    recordOffsetLong, lastProcessedOffsetLong, shouldProcess);
                return shouldProcess;
            } catch (NumberFormatException e) {
                log.warn("Record offset '{}' is not numeric, falling back to string comparison", recordOffset);
            }
        }
        
        // Fall back to string comparison
        boolean shouldProcess = recordOffset.trim().compareTo(lastProcessedOffset) > 0;
        log.trace("Comparing record offset '{}' with last processed '{}' (string comparison): shouldProcess = {}", 
            recordOffset.trim(), lastProcessedOffset, shouldProcess);
        return shouldProcess;
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
                        this.lastProcessedOffset = offsetValue.toString();
                        log.debug("Loaded existing last processed offset for API {}: {}", 
                            apiConfig.getId(), lastProcessedOffset);
                        return;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to load existing offset for API {}, using initial offset: {}", 
                    apiConfig.getId(), e.getMessage());
            }
        }
        
        // Use initial offset if no stored offset is found
        this.lastProcessedOffset = initialOffset;
        log.debug("Using initial offset for API {}: {}", apiConfig.getId(), lastProcessedOffset);
    }
    
    /**
     * Gets the source partition for Kafka Connect offset storage
     */
    public Map<String, String> getSourcePartition() {
        return sourcePartition;
    }
    
    /**
     * Gets the JSON pointer used for extracting offsets from records
     */
    public String getOffsetJsonPointer() {
        return apiConfig.getHttpOffsetJsonPointer();
    }
    
    /**
     * Gets the last processed offset value
     */
    public String getLastProcessedOffset() {
        return lastProcessedOffset;
    }
}