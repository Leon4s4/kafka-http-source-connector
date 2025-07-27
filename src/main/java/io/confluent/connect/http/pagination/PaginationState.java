package io.confluent.connect.http.pagination;

import io.confluent.connect.http.pagination.PaginationManager.PaginationType;

/**
 * Tracks pagination state for a specific API endpoint.
 * Maintains current position, next tokens/cursors, and completion status.
 */
public class PaginationState {
    
    private final String apiId;
    private final String baseUrl;
    private final PaginationType paginationType;
    private final int pageSize;
    private final long startTime;
    
    private volatile int currentPage;
    private volatile boolean complete;
    private volatile String nextUrl;
    private volatile String nextCursor;
    private volatile long totalRecords;
    private volatile long recordsProcessed;
    private volatile long lastRequestTime;
    
    public PaginationState(String apiId, String baseUrl, PaginationType paginationType) {
        this.apiId = apiId;
        this.baseUrl = baseUrl;
        this.paginationType = paginationType;
        this.pageSize = 100; // Default page size
        this.startTime = System.currentTimeMillis();
        
        // Initialize state
        this.currentPage = 0;
        this.complete = false;
        this.totalRecords = -1; // Unknown
        this.recordsProcessed = 0;
        this.lastRequestTime = 0;
    }
    
    public PaginationState(String apiId, String baseUrl, PaginationType paginationType, int pageSize) {
        this.apiId = apiId;
        this.baseUrl = baseUrl;
        this.paginationType = paginationType;
        this.pageSize = pageSize;
        this.startTime = System.currentTimeMillis();
        
        // Initialize state
        this.currentPage = 0;
        this.complete = false;
        this.totalRecords = -1; // Unknown
        this.recordsProcessed = 0;
        this.lastRequestTime = 0;
    }
    
    public String getApiId() {
        return apiId;
    }
    
    public String getBaseUrl() {
        return baseUrl;
    }
    
    public PaginationType getPaginationType() {
        return paginationType;
    }
    
    public int getPageSize() {
        return pageSize;
    }
    
    public long getStartTime() {
        return startTime;
    }
    
    public int getCurrentPage() {
        return currentPage;
    }
    
    public boolean isComplete() {
        return complete;
    }
    
    public String getNextUrl() {
        return nextUrl;
    }
    
    public String getNextCursor() {
        return nextCursor;
    }
    
    public long getTotalRecords() {
        return totalRecords;
    }
    
    public long getRecordsProcessed() {
        return recordsProcessed;
    }
    
    public long getLastRequestTime() {
        return lastRequestTime;
    }
    
    /**
     * Increment the current page number.
     */
    public void incrementPage() {
        this.currentPage++;
        this.lastRequestTime = System.currentTimeMillis();
    }
    
    /**
     * Mark pagination as complete.
     */
    public void markComplete() {
        this.complete = true;
    }
    
    /**
     * Set the next URL for link-based pagination.
     */
    public void setNextUrl(String nextUrl) {
        this.nextUrl = nextUrl;
    }
    
    /**
     * Set the next cursor for cursor-based pagination.
     */
    public void setNextCursor(String nextCursor) {
        this.nextCursor = nextCursor;
    }
    
    /**
     * Set the total number of records (if known).
     */
    public void setTotalRecords(long totalRecords) {
        this.totalRecords = totalRecords;
    }
    
    /**
     * Add to the count of processed records.
     */
    public void addRecords(long count) {
        this.recordsProcessed += count;
    }
    
    /**
     * Reset pagination state to start over.
     */
    public void reset() {
        this.currentPage = 0;
        this.complete = false;
        this.nextUrl = null;
        this.nextCursor = null;
        this.recordsProcessed = 0;
        this.lastRequestTime = 0;
        // Keep totalRecords and other discovered metadata
    }
    
    /**
     * Check if pagination appears to be progressing normally.
     */
    public boolean isProgressing() {
        // Simple check: if we've been making requests recently
        long timeSinceLastRequest = System.currentTimeMillis() - lastRequestTime;
        return !complete && timeSinceLastRequest < 300000; // 5 minutes
    }
    
    /**
     * Get progress percentage (if total records known).
     */
    public double getProgressPercentage() {
        if (totalRecords <= 0) {
            return -1; // Unknown
        }
        return Math.min(100.0, (double) recordsProcessed / totalRecords * 100.0);
    }
    
    /**
     * Estimate remaining pages (if possible).
     */
    public int getEstimatedRemainingPages() {
        if (totalRecords <= 0 || pageSize <= 0) {
            return -1; // Unknown
        }
        
        long remainingRecords = totalRecords - recordsProcessed;
        return (int) Math.ceil((double) remainingRecords / pageSize);
    }
    
    /**
     * Get pagination efficiency (records per page).
     */
    public double getEfficiency() {
        if (currentPage == 0) {
            return 0.0;
        }
        return (double) recordsProcessed / currentPage;
    }
    
    @Override
    public String toString() {
        return String.format("PaginationState{apiId='%s', type=%s, page=%d, complete=%s, " +
                           "records=%d, efficiency=%.1f}",
                           apiId, paginationType, currentPage, complete, recordsProcessed, getEfficiency());
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        
        PaginationState that = (PaginationState) o;
        
        if (!apiId.equals(that.apiId)) return false;
        return baseUrl.equals(that.baseUrl);
    }
    
    @Override
    public int hashCode() {
        int result = apiId.hashCode();
        result = 31 * result + baseUrl.hashCode();
        return result;
    }
}
