package io.confluent.connect.http.offset;

import io.confluent.connect.http.config.ApiConfig;
import org.apache.kafka.connect.source.SourceTaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Offset manager for cursor pagination mode.
 * In this mode, the API response contains a cursor/token that points to the next page of data.
 * The cursor is extracted from the response and used for the next request.
 */
public class CursorPaginationOffsetManager implements OffsetManager {
    
    private static final Logger log = LoggerFactory.getLogger(CursorPaginationOffsetManager.class);
    
    private final ApiConfig apiConfig;
    private final SourceTaskContext context;
    private final Map<String, String> sourcePartition;
    
    private volatile String currentCursor;
    private final String initialCursor;
    
    public CursorPaginationOffsetManager(ApiConfig apiConfig, SourceTaskContext context) {
        this.apiConfig = apiConfig;
        this.context = context;
        
        // Create source partition for Kafka Connect offset storage
        this.sourcePartition = new HashMap<>();
        this.sourcePartition.put("url", apiConfig.getFullUrl());
        
        // Get initial cursor
        this.initialCursor = apiConfig.getHttpInitialOffset();
        
        // Validate that next page JSON pointer is configured
        String nextPageJsonPointer = apiConfig.getHttpNextPageJsonPointer();
        if (nextPageJsonPointer == null || nextPageJsonPointer.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "http.next.page.json.pointer must be configured for CURSOR_PAGINATION mode in API: " + apiConfig.getId());
        }
        
        // Load current cursor from Kafka Connect's offset storage
        loadCurrentCursor();
        
        log.debug("Initialized CursorPaginationOffsetManager for API: {} with initial cursor: {} and current cursor: {}",
            apiConfig.getId(), initialCursor, currentCursor);
    }
    
    @Override
    public String getCurrentOffset() {
        return currentCursor;
    }
    
    @Override
    public void updateOffset(String newCursor) {
        if (newCursor != null && !newCursor.trim().isEmpty()) {
            this.currentCursor = newCursor.trim();
            log.trace("Updated cursor for API {} to: {}", apiConfig.getId(), currentCursor);
        } else {
            log.debug("Received null or empty cursor for API {}, this might indicate end of pagination", 
                apiConfig.getId());
            // In cursor pagination, a null cursor often means we've reached the end
            this.currentCursor = null;
        }
    }
    
    @Override
    public void resetOffset() {
        currentCursor = initialCursor;
        log.info("Reset cursor for API {} to initial value: {}", apiConfig.getId(), initialCursor);
    }
    
    @Override
    public ApiConfig.HttpOffsetMode getOffsetMode() {
        return ApiConfig.HttpOffsetMode.CURSOR_PAGINATION;
    }
    
    @Override
    public void close() {
        log.debug("Closing CursorPaginationOffsetManager for API: {}", apiConfig.getId());
        // Nothing specific to close for this implementation
    }
    
    /**
     * Checks if there are more pages to fetch (cursor is not null/empty)
     * 
     * @return true if there are more pages, false if pagination is complete
     */
    public boolean hasMorePages() {
        return currentCursor != null && !currentCursor.trim().isEmpty();
    }
    
    /**
     * Loads the current cursor from Kafka Connect's offset storage
     */
    private void loadCurrentCursor() {
        if (context != null && context.offsetStorageReader() != null) {
            try {
                Map<String, Object> offset = context.offsetStorageReader().offset(sourcePartition);
                
                if (offset != null && offset.containsKey("offset")) {
                    Object offsetValue = offset.get("offset");
                    if (offsetValue != null) {
                        this.currentCursor = offsetValue.toString();
                        log.debug("Loaded existing cursor for API {}: {}", apiConfig.getId(), currentCursor);
                        return;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to load existing cursor for API {}, using initial cursor: {}", 
                    apiConfig.getId(), e.getMessage());
            }
        }
        
        // Use initial cursor if no stored cursor is found
        this.currentCursor = initialCursor;
        log.debug("Using initial cursor for API {}: {}", apiConfig.getId(), currentCursor);
    }
    
    /**
     * Gets the source partition for Kafka Connect offset storage
     */
    public Map<String, String> getSourcePartition() {
        return sourcePartition;
    }
    
    /**
     * Gets the JSON pointer used for extracting next page cursor
     */
    public String getNextPageJsonPointer() {
        return apiConfig.getHttpNextPageJsonPointer();
    }
    
    /**
     * Gets the current cursor value
     */
    public String getCurrentCursor() {
        return currentCursor;
    }
}