package io.confluent.connect.http.offset;

/**
 * Interface for managing offsets in the HTTP Source Connector.
 * Different implementations handle various offset modes (simple incrementing, chaining, cursor pagination, etc.).
 */
public interface OffsetManager {
    
    /**
     * Gets the current offset value for making the next API request
     * 
     * @return The current offset as a string, or null if no offset is available
     */
    String getCurrentOffset();
    
    /**
     * Updates the offset based on processed data
     * 
     * @param newOffset The new offset value extracted from the API response
     */
    void updateOffset(String newOffset);
    
    /**
     * Resets the offset to its initial value
     */
    void resetOffset();
    
    /**
     * Gets the offset mode handled by this manager
     * 
     * @return The offset mode
     */
    io.confluent.connect.http.config.ApiConfig.HttpOffsetMode getOffsetMode();
    
    /**
     * Closes any resources used by the offset manager
     */
    void close();
}