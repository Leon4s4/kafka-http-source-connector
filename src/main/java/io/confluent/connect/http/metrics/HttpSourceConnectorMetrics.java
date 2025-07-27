package io.confluent.connect.http.metrics;

import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Implementation of HTTP Source Connector JMX metrics.
 * Tracks various performance and operational metrics for monitoring and alerting.
 */
public class HttpSourceConnectorMetrics implements HttpSourceConnectorMetricsMBean {
    
    private static final Logger log = LoggerFactory.getLogger(HttpSourceConnectorMetrics.class);
    
    private final String connectorName;
    private final HttpSourceConnectorConfig config;
    private final long startTime;
    
    // Request metrics
    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder successfulRequests = new LongAdder();
    private final LongAdder failedRequests = new LongAdder();
    
    // Response time tracking
    private final AtomicLong totalResponseTime = new AtomicLong(0);
    private final AtomicLong maxResponseTime = new AtomicLong(0);
    private final AtomicLong minResponseTime = new AtomicLong(Long.MAX_VALUE);
    
    // Error type counters
    private final LongAdder authenticationErrors = new LongAdder();
    private final LongAdder clientErrors = new LongAdder();
    private final LongAdder serverErrors = new LongAdder();
    private final LongAdder timeoutErrors = new LongAdder();
    private final LongAdder connectionErrors = new LongAdder();
    private final LongAdder rateLimitErrors = new LongAdder();
    
    // Circuit breaker tracking
    private final Map<String, String> circuitBreakerStates = new ConcurrentHashMap<>();
    
    // Cache metrics
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder cacheMisses = new LongAdder();
    private final LongAdder cacheEvictions = new LongAdder();
    private final AtomicLong currentCacheSize = new AtomicLong(0);
    
    // Throughput metrics
    private final LongAdder recordsProduced = new LongAdder();
    private final LongAdder bytesProduced = new LongAdder();
    
    // API-specific metrics
    private final Map<String, ApiMetrics> apiMetrics = new ConcurrentHashMap<>();
    
    // Health tracking
    private final AtomicLong lastSuccessfulRequest = new AtomicLong(0);
    
    // Time-based metrics for rate calculation
    private final AtomicLong lastMetricsUpdate = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong previousRequestCount = new AtomicLong(0);
    private final AtomicLong previousRecordCount = new AtomicLong(0);
    private final AtomicLong previousByteCount = new AtomicLong(0);
    
    public HttpSourceConnectorMetrics(String connectorName, HttpSourceConnectorConfig config) {
        this.connectorName = connectorName;
        this.config = config;
        this.startTime = System.currentTimeMillis();
        
        registerMBean();
        log.info("JMX metrics initialized for connector: {}", connectorName);
    }
    
    private void registerMBean() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName objectName = new ObjectName(String.format(
                "io.confluent.connect.http:type=HttpSourceConnector,name=%s",
                ObjectName.quote(connectorName)
            ));
            
            if (server.isRegistered(objectName)) {
                server.unregisterMBean(objectName);
            }
            
            server.registerMBean(this, objectName);
            log.info("Registered JMX MBean: {}", objectName);
        } catch (Exception e) {
            log.error("Failed to register JMX MBean for connector: " + connectorName, e);
        }
    }
    
    public void unregisterMBean() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName objectName = new ObjectName(String.format(
                "io.confluent.connect.http:type=HttpSourceConnector,name=%s",
                ObjectName.quote(connectorName)
            ));
            
            if (server.isRegistered(objectName)) {
                server.unregisterMBean(objectName);
                log.info("Unregistered JMX MBean: {}", objectName);
            }
        } catch (Exception e) {
            log.warn("Failed to unregister JMX MBean for connector: " + connectorName, e);
        }
    }
    
    // Metric update methods
    public void recordRequest(String apiId, long responseTimeMs, boolean success) {
        totalRequests.increment();
        
        if (success) {
            successfulRequests.increment();
            lastSuccessfulRequest.set(System.currentTimeMillis());
        } else {
            failedRequests.increment();
        }
        
        // Update response time metrics
        totalResponseTime.addAndGet(responseTimeMs);
        updateMaxResponseTime(responseTimeMs);
        updateMinResponseTime(responseTimeMs);
        
        // Update API-specific metrics
        getApiMetrics(apiId).recordRequest(responseTimeMs, success);
    }
    
    public void recordError(String apiId, ErrorType errorType) {
        failedRequests.increment();
        
        switch (errorType) {
            case AUTHENTICATION:
                authenticationErrors.increment();
                break;
            case CLIENT_ERROR:
                clientErrors.increment();
                break;
            case SERVER_ERROR:
                serverErrors.increment();
                break;
            case TIMEOUT:
                timeoutErrors.increment();
                break;
            case CONNECTION:
                connectionErrors.increment();
                break;
            case RATE_LIMIT:
                rateLimitErrors.increment();
                break;
        }
        
        getApiMetrics(apiId).recordError(errorType);
    }
    
    public void recordCacheOperation(boolean hit) {
        if (hit) {
            cacheHits.increment();
        } else {
            cacheMisses.increment();
        }
    }
    
    public void recordCacheEviction() {
        cacheEvictions.increment();
    }
    
    public void updateCacheSize(long size) {
        currentCacheSize.set(size);
    }
    
    public void recordRecordsProduced(String apiId, int count, long bytes) {
        recordsProduced.add(count);
        bytesProduced.add(bytes);
        getApiMetrics(apiId).recordRecordsProduced(count, bytes);
    }
    
    public void updateCircuitBreakerState(String apiId, String state) {
        circuitBreakerStates.put(apiId, state);
    }
    
    private void updateMaxResponseTime(long responseTime) {
        long current = maxResponseTime.get();
        while (responseTime > current && !maxResponseTime.compareAndSet(current, responseTime)) {
            current = maxResponseTime.get();
        }
    }
    
    private void updateMinResponseTime(long responseTime) {
        long current = minResponseTime.get();
        while (responseTime < current && !minResponseTime.compareAndSet(current, responseTime)) {
            current = minResponseTime.get();
        }
    }
    
    private ApiMetrics getApiMetrics(String apiId) {
        return apiMetrics.computeIfAbsent(apiId, k -> new ApiMetrics());
    }
    
    // Rate calculation helpers
    private double calculateRate(long current, AtomicLong previous) {
        long now = System.currentTimeMillis();
        long lastUpdate = lastMetricsUpdate.get();
        long timeDiff = now - lastUpdate;
        
        if (timeDiff <= 0) return 0.0;
        
        long prev = previous.get();
        long diff = current - prev;
        
        // Update for next calculation
        previous.set(current);
        lastMetricsUpdate.set(now);
        
        return (diff * 1000.0) / timeDiff; // per second
    }
    
    // MBean interface implementations
    @Override
    public long getTotalRequestCount() {
        return totalRequests.sum();
    }
    
    @Override
    public long getSuccessfulRequestCount() {
        return successfulRequests.sum();
    }
    
    @Override
    public long getFailedRequestCount() {
        return failedRequests.sum();
    }
    
    @Override
    public double getSuccessRate() {
        long total = getTotalRequestCount();
        if (total == 0) return 0.0;
        return (double) getSuccessfulRequestCount() / total * 100.0;
    }
    
    @Override
    public double getRequestsPerSecond() {
        return calculateRate(getTotalRequestCount(), previousRequestCount);
    }
    
    @Override
    public double getAverageResponseTimeMs() {
        long total = getTotalRequestCount();
        if (total == 0) return 0.0;
        return (double) totalResponseTime.get() / total;
    }
    
    @Override
    public long getMaxResponseTimeMs() {
        long max = maxResponseTime.get();
        return max == 0 ? 0 : max;
    }
    
    @Override
    public long getMinResponseTimeMs() {
        long min = minResponseTime.get();
        return min == Long.MAX_VALUE ? 0 : min;
    }
    
    @Override
    public long getAuthenticationErrors() {
        return authenticationErrors.sum();
    }
    
    @Override
    public long getClientErrors() {
        return clientErrors.sum();
    }
    
    @Override
    public long getServerErrors() {
        return serverErrors.sum();
    }
    
    @Override
    public long getTimeoutErrors() {
        return timeoutErrors.sum();
    }
    
    @Override
    public long getConnectionErrors() {
        return connectionErrors.sum();
    }
    
    @Override
    public long getRateLimitErrors() {
        return rateLimitErrors.sum();
    }
    
    @Override
    public int getCircuitBreakerOpenCount() {
        return (int) circuitBreakerStates.values().stream()
            .filter("OPEN"::equals)
            .count();
    }
    
    @Override
    public int getCircuitBreakerHalfOpenCount() {
        return (int) circuitBreakerStates.values().stream()
            .filter("HALF_OPEN"::equals)
            .count();
    }
    
    @Override
    public int getCircuitBreakerClosedCount() {
        return (int) circuitBreakerStates.values().stream()
            .filter("CLOSED"::equals)
            .count();
    }
    
    @Override
    public String[] getCircuitBreakerStates() {
        return circuitBreakerStates.entrySet().stream()
            .map(entry -> entry.getKey() + ":" + entry.getValue())
            .toArray(String[]::new);
    }
    
    @Override
    public long getCacheHits() {
        return cacheHits.sum();
    }
    
    @Override
    public long getCacheMisses() {
        return cacheMisses.sum();
    }
    
    @Override
    public double getCacheHitRate() {
        long hits = getCacheHits();
        long misses = getCacheMisses();
        long total = hits + misses;
        if (total == 0) return 0.0;
        return (double) hits / total * 100.0;
    }
    
    @Override
    public long getCacheEvictions() {
        return cacheEvictions.sum();
    }
    
    @Override
    public int getCurrentCacheSize() {
        return (int) currentCacheSize.get();
    }
    
    @Override
    public long getTotalRecordsProduced() {
        return recordsProduced.sum();
    }
    
    @Override
    public double getRecordsPerSecond() {
        return calculateRate(getTotalRecordsProduced(), previousRecordCount);
    }
    
    @Override
    public long getTotalBytesProduced() {
        return bytesProduced.sum();
    }
    
    @Override
    public double getBytesPerSecond() {
        return calculateRate(getTotalBytesProduced(), previousByteCount);
    }
    
    @Override
    public String[] getApiIds() {
        return apiMetrics.keySet().toArray(new String[0]);
    }
    
    @Override
    public long getRequestCountForApi(String apiId) {
        ApiMetrics metrics = apiMetrics.get(apiId);
        return metrics != null ? metrics.getRequestCount() : 0;
    }
    
    @Override
    public double getSuccessRateForApi(String apiId) {
        ApiMetrics metrics = apiMetrics.get(apiId);
        return metrics != null ? metrics.getSuccessRate() : 0.0;
    }
    
    @Override
    public double getAverageResponseTimeForApi(String apiId) {
        ApiMetrics metrics = apiMetrics.get(apiId);
        return metrics != null ? metrics.getAverageResponseTime() : 0.0;
    }
    
    @Override
    public String getCircuitBreakerStateForApi(String apiId) {
        return circuitBreakerStates.getOrDefault(apiId, "UNKNOWN");
    }
    
    @Override
    public String getConnectorVersion() {
        return "1.0.0";
    }
    
    @Override
    public String getConnectorName() {
        return connectorName;
    }
    
    @Override
    public int getNumberOfApis() {
        return config != null ? config.getApisNum() : 0;
    }
    
    @Override
    public int getNumberOfTasks() {
        return config != null ? config.getTasksMax() : 0;
    }
    
    @Override
    public String getAuthenticationType() {
        return config != null ? config.getAuthType().name() : "UNKNOWN";
    }
    
    @Override
    public boolean isCachingEnabled() {
        return config != null ? config.isResponseCachingEnabled() : false;
    }
    
    @Override
    public boolean isCircuitBreakerEnabled() {
        return config != null ? config.getCircuitBreakerFailureThreshold() > 0 : false;
    }
    
    @Override
    public String getOverallHealthStatus() {
        long now = System.currentTimeMillis();
        long lastSuccess = lastSuccessfulRequest.get();
        
        if (lastSuccess == 0) {
            return "UNKNOWN";
        }
        
        // Consider healthy if last successful request was within 5 minutes
        if (now - lastSuccess < 300000) {
            return "HEALTHY";
        } else if (now - lastSuccess < 600000) {
            return "DEGRADED";
        } else {
            return "UNHEALTHY";
        }
    }
    
    @Override
    public String[] getApiHealthStatuses() {
        return apiMetrics.entrySet().stream()
            .map(entry -> entry.getKey() + ":" + getHealthStatus(entry.getValue()))
            .toArray(String[]::new);
    }
    
    private String getHealthStatus(ApiMetrics metrics) {
        if (metrics.getRequestCount() == 0) {
            return "UNKNOWN";
        }
        
        double successRate = metrics.getSuccessRate();
        if (successRate >= 95.0) {
            return "HEALTHY";
        } else if (successRate >= 85.0) {
            return "DEGRADED";
        } else {
            return "UNHEALTHY";
        }
    }
    
    @Override
    public long getLastSuccessfulRequestTime() {
        return lastSuccessfulRequest.get();
    }
    
    @Override
    public long getUptimeMs() {
        return System.currentTimeMillis() - startTime;
    }
    
    @Override
    public void resetMetrics() {
        resetErrorMetrics();
        resetCircuitBreakerMetrics();
        resetCacheMetrics();
        
        totalRequests.reset();
        successfulRequests.reset();
        failedRequests.reset();
        totalResponseTime.set(0);
        maxResponseTime.set(0);
        minResponseTime.set(Long.MAX_VALUE);
        recordsProduced.reset();
        bytesProduced.reset();
        
        apiMetrics.clear();
        
        log.info("All metrics reset for connector: {}", connectorName);
    }
    
    @Override
    public void resetErrorMetrics() {
        authenticationErrors.reset();
        clientErrors.reset();
        serverErrors.reset();
        timeoutErrors.reset();
        connectionErrors.reset();
        rateLimitErrors.reset();
        
        log.info("Error metrics reset for connector: {}", connectorName);
    }
    
    @Override
    public void resetCircuitBreakerMetrics() {
        circuitBreakerStates.clear();
        log.info("Circuit breaker metrics reset for connector: {}", connectorName);
    }
    
    @Override
    public void resetCacheMetrics() {
        cacheHits.reset();
        cacheMisses.reset();
        cacheEvictions.reset();
        currentCacheSize.set(0);
        
        log.info("Cache metrics reset for connector: {}", connectorName);
    }
    
    public enum ErrorType {
        AUTHENTICATION,
        CLIENT_ERROR,
        SERVER_ERROR,
        TIMEOUT,
        CONNECTION,
        RATE_LIMIT
    }
    
    private static class ApiMetrics {
        private final LongAdder requestCount = new LongAdder();
        private final LongAdder successCount = new LongAdder();
        private final AtomicLong totalResponseTime = new AtomicLong(0);
        private final LongAdder recordsProduced = new LongAdder();
        private final LongAdder bytesProduced = new LongAdder();
        private final Map<ErrorType, LongAdder> errorCounts = new ConcurrentHashMap<>();
        
        public void recordRequest(long responseTimeMs, boolean success) {
            requestCount.increment();
            totalResponseTime.addAndGet(responseTimeMs);
            if (success) {
                successCount.increment();
            }
        }
        
        public void recordError(ErrorType errorType) {
            errorCounts.computeIfAbsent(errorType, k -> new LongAdder()).increment();
        }
        
        public void recordRecordsProduced(int count, long bytes) {
            recordsProduced.add(count);
            bytesProduced.add(bytes);
        }
        
        public long getRequestCount() {
            return requestCount.sum();
        }
        
        public double getSuccessRate() {
            long total = getRequestCount();
            if (total == 0) return 0.0;
            return (double) successCount.sum() / total * 100.0;
        }
        
        public double getAverageResponseTime() {
            long count = getRequestCount();
            if (count == 0) return 0.0;
            return (double) totalResponseTime.get() / count;
        }
    }
}
