package io.confluent.connect.http.metrics;

/**
 * JMX MBean interface for HTTP Source Connector metrics.
 * Exposes key performance and operational metrics for monitoring.
 */
public interface HttpSourceConnectorMetricsMBean {
    
    // Connection and Performance Metrics
    long getTotalRequestCount();
    long getSuccessfulRequestCount();
    long getFailedRequestCount();
    double getSuccessRate();
    double getRequestsPerSecond();
    double getAverageResponseTimeMs();
    long getMaxResponseTimeMs();
    long getMinResponseTimeMs();
    
    // Error Metrics
    long getAuthenticationErrors();
    long getClientErrors();
    long getServerErrors();
    long getTimeoutErrors();
    long getConnectionErrors();
    long getRateLimitErrors();
    
    // Circuit Breaker Metrics
    int getCircuitBreakerOpenCount();
    int getCircuitBreakerHalfOpenCount();
    int getCircuitBreakerClosedCount();
    String[] getCircuitBreakerStates();
    
    // Cache Metrics (if caching enabled)
    long getCacheHits();
    long getCacheMisses();
    double getCacheHitRate();
    long getCacheEvictions();
    int getCurrentCacheSize();
    
    // Throughput Metrics
    long getTotalRecordsProduced();
    double getRecordsPerSecond();
    long getTotalBytesProduced();
    double getBytesPerSecond();
    
    // API-specific Metrics
    String[] getApiIds();
    long getRequestCountForApi(String apiId);
    double getSuccessRateForApi(String apiId);
    double getAverageResponseTimeForApi(String apiId);
    String getCircuitBreakerStateForApi(String apiId);
    
    // Configuration Info
    String getConnectorVersion();
    String getConnectorName();
    int getNumberOfApis();
    int getNumberOfTasks();
    String getAuthenticationType();
    boolean isCachingEnabled();
    boolean isCircuitBreakerEnabled();
    
    // Health Status
    String getOverallHealthStatus();
    String[] getApiHealthStatuses();
    long getLastSuccessfulRequestTime();
    long getUptimeMs();
    
    // Reset Operations
    void resetMetrics();
    void resetErrorMetrics();
    void resetCircuitBreakerMetrics();
    void resetCacheMetrics();
}
