package io.confluent.connect.http.pagination;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks pagination statistics and performance metrics.
 * Thread-safe implementation for collecting pagination analytics.
 */
public class PaginationStats {
    
    private final String apiId;
    private final long startTime;
    
    // Request counters
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successfulRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);
    private final AtomicLong retriedRequests = new AtomicLong(0);
    
    // Record counters
    private final AtomicLong totalRecords = new AtomicLong(0);
    private final AtomicLong emptyPages = new AtomicLong(0);
    private final AtomicLong maxRecordsPerPage = new AtomicLong(0);
    private final AtomicLong minRecordsPerPage = new AtomicLong(Long.MAX_VALUE);
    
    // Timing statistics
    private final AtomicLong totalRequestTime = new AtomicLong(0);
    private final AtomicLong maxRequestTime = new AtomicLong(0);
    private final AtomicLong minRequestTime = new AtomicLong(Long.MAX_VALUE);
    
    // State tracking
    private volatile long lastRequestTime = 0;
    private volatile long firstRecordTime = 0;
    private volatile long lastRecordTime = 0;
    private volatile boolean paginationComplete = false;
    private volatile String lastError = null;
    
    public PaginationStats(String apiId) {
        this.apiId = apiId;
        this.startTime = System.currentTimeMillis();
    }
    
    public String getApiId() {
        return apiId;
    }
    
    public long getStartTime() {
        return startTime;
    }
    
    public long getTotalRequests() {
        return totalRequests.get();
    }
    
    public long getSuccessfulRequests() {
        return successfulRequests.get();
    }
    
    public long getFailedRequests() {
        return failedRequests.get();
    }
    
    public long getRetriedRequests() {
        return retriedRequests.get();
    }
    
    public long getTotalRecords() {
        return totalRecords.get();
    }
    
    public long getEmptyPages() {
        return emptyPages.get();
    }
    
    public long getMaxRecordsPerPage() {
        return maxRecordsPerPage.get();
    }
    
    public long getMinRecordsPerPage() {
        long min = minRecordsPerPage.get();
        return min == Long.MAX_VALUE ? 0 : min;
    }
    
    public long getTotalRequestTime() {
        return totalRequestTime.get();
    }
    
    public long getMaxRequestTime() {
        return maxRequestTime.get();
    }
    
    public long getMinRequestTime() {
        long min = minRequestTime.get();
        return min == Long.MAX_VALUE ? 0 : min;
    }
    
    public long getLastRequestTime() {
        return lastRequestTime;
    }
    
    public long getFirstRecordTime() {
        return firstRecordTime;
    }
    
    public long getLastRecordTime() {
        return lastRecordTime;
    }
    
    public boolean isPaginationComplete() {
        return paginationComplete;
    }
    
    public String getLastError() {
        return lastError;
    }
    
    /**
     * Record a successful request with timing and record count.
     */
    public void recordSuccessfulRequest(long requestTimeMs, int recordCount) {
        totalRequests.incrementAndGet();
        successfulRequests.incrementAndGet();
        
        // Update timing statistics
        totalRequestTime.addAndGet(requestTimeMs);
        updateMaxValue(maxRequestTime, requestTimeMs);
        updateMinValue(minRequestTime, requestTimeMs);
        
        // Update record statistics
        totalRecords.addAndGet(recordCount);
        if (recordCount == 0) {
            emptyPages.incrementAndGet();
        } else {
            updateMaxValue(maxRecordsPerPage, recordCount);
            updateMinValue(minRecordsPerPage, recordCount);
            
            // Track first and last record times
            long currentTime = System.currentTimeMillis();
            if (firstRecordTime == 0) {
                firstRecordTime = currentTime;
            }
            lastRecordTime = currentTime;
        }
        
        lastRequestTime = System.currentTimeMillis();
        lastError = null; // Clear previous error
    }
    
    /**
     * Record a failed request with error details.
     */
    public void recordFailedRequest(String error) {
        totalRequests.incrementAndGet();
        failedRequests.incrementAndGet();
        lastRequestTime = System.currentTimeMillis();
        lastError = error;
    }
    
    /**
     * Record a retry attempt.
     */
    public void recordRetry() {
        retriedRequests.incrementAndGet();
    }
    
    /**
     * Mark pagination as complete.
     */
    public void markComplete() {
        this.paginationComplete = true;
    }
    
    /**
     * Get success rate as a percentage.
     */
    public double getSuccessRate() {
        long total = totalRequests.get();
        if (total == 0) {
            return 100.0;
        }
        return (double) successfulRequests.get() / total * 100.0;
    }
    
    /**
     * Get average request time in milliseconds.
     */
    public double getAverageRequestTime() {
        long requests = successfulRequests.get();
        if (requests == 0) {
            return 0.0;
        }
        return (double) totalRequestTime.get() / requests;
    }
    
    /**
     * Get average records per page.
     */
    public double getAverageRecordsPerPage() {
        long requests = successfulRequests.get();
        long emptyPageCount = emptyPages.get();
        long nonEmptyRequests = requests - emptyPageCount;
        
        if (nonEmptyRequests == 0) {
            return 0.0;
        }
        return (double) totalRecords.get() / nonEmptyRequests;
    }
    
    /**
     * Get pagination efficiency (records per second).
     */
    public double getRecordsPerSecond() {
        long duration = getElapsedTime();
        if (duration == 0) {
            return 0.0;
        }
        return (double) totalRecords.get() / (duration / 1000.0);
    }
    
    /**
     * Get requests per second.
     */
    public double getRequestsPerSecond() {
        long duration = getElapsedTime();
        if (duration == 0) {
            return 0.0;
        }
        return (double) totalRequests.get() / (duration / 1000.0);
    }
    
    /**
     * Get total elapsed time in milliseconds.
     */
    public long getElapsedTime() {
        if (paginationComplete && lastRecordTime > 0) {
            return lastRecordTime - startTime;
        }
        return System.currentTimeMillis() - startTime;
    }
    
    /**
     * Get time since last request in milliseconds.
     */
    public long getTimeSinceLastRequest() {
        if (lastRequestTime == 0) {
            return 0;
        }
        return System.currentTimeMillis() - lastRequestTime;
    }
    
    /**
     * Reset all statistics.
     */
    public void reset() {
        totalRequests.set(0);
        successfulRequests.set(0);
        failedRequests.set(0);
        retriedRequests.set(0);
        totalRecords.set(0);
        emptyPages.set(0);
        maxRecordsPerPage.set(0);
        minRecordsPerPage.set(Long.MAX_VALUE);
        totalRequestTime.set(0);
        maxRequestTime.set(0);
        minRequestTime.set(Long.MAX_VALUE);
        
        lastRequestTime = 0;
        firstRecordTime = 0;
        lastRecordTime = 0;
        paginationComplete = false;
        lastError = null;
    }
    
    /**
     * Get a snapshot of current statistics.
     */
    public PaginationSnapshot getSnapshot() {
        return new PaginationSnapshot(
            apiId,
            startTime,
            totalRequests.get(),
            successfulRequests.get(),
            failedRequests.get(),
            retriedRequests.get(),
            totalRecords.get(),
            emptyPages.get(),
            getAverageRecordsPerPage(),
            getAverageRequestTime(),
            getSuccessRate(),
            getRecordsPerSecond(),
            getRequestsPerSecond(),
            getElapsedTime(),
            paginationComplete,
            lastError
        );
    }
    
    /**
     * Utility method to update maximum value atomically.
     */
    private void updateMaxValue(AtomicLong atomicMax, long newValue) {
        atomicMax.updateAndGet(current -> Math.max(current, newValue));
    }
    
    /**
     * Utility method to update minimum value atomically.
     */
    private void updateMinValue(AtomicLong atomicMin, long newValue) {
        atomicMin.updateAndGet(current -> Math.min(current, newValue));
    }
    
    @Override
    public String toString() {
        return String.format("PaginationStats{apiId='%s', requests=%d/%d (%.1f%%), records=%d, " +
                           "avgTime=%.1fms, rate=%.1f/s}",
                           apiId, successfulRequests.get(), totalRequests.get(), getSuccessRate(),
                           totalRecords.get(), getAverageRequestTime(), getRecordsPerSecond());
    }
    
    /**
     * Immutable snapshot of pagination statistics.
     */
    public static class PaginationSnapshot {
        public final String apiId;
        public final long startTime;
        public final long totalRequests;
        public final long successfulRequests;
        public final long failedRequests;
        public final long retriedRequests;
        public final long totalRecords;
        public final long emptyPages;
        public final double averageRecordsPerPage;
        public final double averageRequestTime;
        public final double successRate;
        public final double recordsPerSecond;
        public final double requestsPerSecond;
        public final long elapsedTime;
        public final boolean paginationComplete;
        public final String lastError;
        
        public PaginationSnapshot(String apiId, long startTime, long totalRequests,
                                long successfulRequests, long failedRequests, long retriedRequests,
                                long totalRecords, long emptyPages, double averageRecordsPerPage,
                                double averageRequestTime, double successRate, double recordsPerSecond,
                                double requestsPerSecond, long elapsedTime, boolean paginationComplete,
                                String lastError) {
            this.apiId = apiId;
            this.startTime = startTime;
            this.totalRequests = totalRequests;
            this.successfulRequests = successfulRequests;
            this.failedRequests = failedRequests;
            this.retriedRequests = retriedRequests;
            this.totalRecords = totalRecords;
            this.emptyPages = emptyPages;
            this.averageRecordsPerPage = averageRecordsPerPage;
            this.averageRequestTime = averageRequestTime;
            this.successRate = successRate;
            this.recordsPerSecond = recordsPerSecond;
            this.requestsPerSecond = requestsPerSecond;
            this.elapsedTime = elapsedTime;
            this.paginationComplete = paginationComplete;
            this.lastError = lastError;
        }
        
        @Override
        public String toString() {
            return String.format("PaginationSnapshot{apiId='%s', requests=%d, records=%d, " +
                               "successRate=%.1f%%, recordsPerSecond=%.1f}",
                               apiId, totalRequests, totalRecords, successRate, recordsPerSecond);
        }
    }
}
