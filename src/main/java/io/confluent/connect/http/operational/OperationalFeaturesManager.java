package io.confluent.connect.http.operational;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Comprehensive operational features for enterprise monitoring and alerting.
 * Provides health monitoring, alert management, circuit breaker, and operational metrics.
 */
public class OperationalFeaturesManager {
    
    private static final Logger log = LoggerFactory.getLogger(OperationalFeaturesManager.class);
    
    private final OperationalConfig config;
    private final HealthMonitor healthMonitor;
    private final AlertManager alertManager;
    private final CircuitBreaker circuitBreaker;
    private final MetricsCollector metricsCollector;
    private final ScheduledExecutorService scheduler;
    private final Map<String, ServiceStatus> serviceStatuses;
    
    private volatile boolean isRunning = false;
    
    public OperationalFeaturesManager(OperationalConfig config) {
        this.config = config;
        this.healthMonitor = new HealthMonitor(config);
        this.alertManager = new AlertManager(config);
        this.circuitBreaker = new CircuitBreaker(config);
        this.metricsCollector = new MetricsCollector(config);
        this.serviceStatuses = new ConcurrentHashMap<>();
        this.scheduler = Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "operational-features");
            t.setDaemon(true);
            return t;
        });
        
        log.info("Operational features manager initialized: monitoring={}, alerting={}, circuitBreaker={}",
                config.isHealthMonitoringEnabled(), config.isAlertingEnabled(), config.isCircuitBreakerEnabled());
    }
    
    /**
     * Start operational features.
     */
    public void start() {
        if (isRunning) {
            return;
        }
        
        isRunning = true;
        
        // Start health monitoring
        if (config.isHealthMonitoringEnabled()) {
            scheduler.scheduleWithFixedDelay(
                this::performHealthCheck,
                0, config.getHealthCheckIntervalSeconds(), TimeUnit.SECONDS
            );
        }
        
        // Start metrics collection
        if (config.isMetricsCollectionEnabled()) {
            scheduler.scheduleWithFixedDelay(
                metricsCollector::collectMetrics,
                0, config.getMetricsCollectionIntervalSeconds(), TimeUnit.SECONDS
            );
        }
        
        // Start alert processing
        if (config.isAlertingEnabled()) {
            scheduler.scheduleWithFixedDelay(
                alertManager::processAlerts,
                5, config.getAlertProcessingIntervalSeconds(), TimeUnit.SECONDS
            );
        }
        
        // Initialize circuit breaker monitoring
        if (config.isCircuitBreakerEnabled()) {
            scheduler.scheduleWithFixedDelay(
                circuitBreaker::performMaintenance,
                10, 30, TimeUnit.SECONDS
            );
        }
        
        log.info("Operational features started successfully");
    }
    
    /**
     * Stop operational features.
     */
    public void stop() {
        if (!isRunning) {
            return;
        }
        
        isRunning = false;
        
        try {
            scheduler.shutdown();
            if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        log.info("Operational features stopped");
    }
    
    /**
     * Record service operation for monitoring.
     */
    public void recordOperation(String serviceName, boolean success, long duration) {
        ServiceStatus status = serviceStatuses.computeIfAbsent(serviceName, 
            k -> new ServiceStatus(serviceName));
        
        status.recordOperation(success, duration);
        
        // Circuit breaker evaluation
        if (config.isCircuitBreakerEnabled()) {
            circuitBreaker.recordResult(serviceName, success);
        }
        
        // Check for alerts
        if (config.isAlertingEnabled()) {
            checkForAlerts(serviceName, status);
        }
    }
    
    /**
     * Check service availability through circuit breaker.
     */
    public boolean isServiceAvailable(String serviceName) {
        if (!config.isCircuitBreakerEnabled()) {
            return true;
        }
        
        return circuitBreaker.canExecute(serviceName);
    }
    
    /**
     * Get current operational status.
     */
    public OperationalStatus getOperationalStatus() {
        OverallHealth overallHealth = healthMonitor.getOverallHealth();
        Map<String, ServiceStatus> currentStatuses = new HashMap<>(serviceStatuses);
        List<Alert> activeAlerts = alertManager.getActiveAlerts();
        Map<String, CircuitBreakerState> circuitBreakerStates = circuitBreaker.getAllStates();
        
        return new OperationalStatus(
            overallHealth,
            currentStatuses,
            activeAlerts,
            circuitBreakerStates,
            metricsCollector.getCurrentMetrics()
        );
    }
    
    /**
     * Perform comprehensive health check.
     */
    private void performHealthCheck() {
        try {
            HealthCheckResult result = healthMonitor.performHealthCheck(serviceStatuses);
            
            if (result.getOverallHealth() != OverallHealth.HEALTHY) {
                log.warn("Health check warning: overall={}, failed={}", 
                        result.getOverallHealth(), result.getFailedChecks());
                
                if (config.isAlertingEnabled()) {
                    alertManager.raiseAlert(new Alert(
                        AlertType.HEALTH_CHECK_FAILED,
                        AlertSeverity.WARNING,
                        "Health check failed: " + result.getFailedChecks(),
                        Instant.now()
                    ));
                }
            }
            
        } catch (Exception e) {
            log.error("Health check failed", e);
        }
    }
    
    /**
     * Check for alert conditions.
     */
    private void checkForAlerts(String serviceName, ServiceStatus status) {
        // Error rate alert
        if (status.getErrorRate() > config.getErrorRateThreshold()) {
            alertManager.raiseAlert(new Alert(
                AlertType.HIGH_ERROR_RATE,
                AlertSeverity.WARNING,
                String.format("High error rate for %s: %.2f%%", serviceName, status.getErrorRate() * 100),
                Instant.now()
            ));
        }
        
        // Response time alert
        if (status.getAverageResponseTime() > config.getResponseTimeThreshold()) {
            alertManager.raiseAlert(new Alert(
                AlertType.HIGH_RESPONSE_TIME,
                AlertSeverity.WARNING,
                String.format("High response time for %s: %dms", serviceName, status.getAverageResponseTime()),
                Instant.now()
            ));
        }
        
        // Consecutive failures alert
        if (status.getConsecutiveFailures() > config.getConsecutiveFailuresThreshold()) {
            alertManager.raiseAlert(new Alert(
                AlertType.CONSECUTIVE_FAILURES,
                AlertSeverity.CRITICAL,
                String.format("Multiple consecutive failures for %s: %d", serviceName, status.getConsecutiveFailures()),
                Instant.now()
            ));
        }
    }
    
    /**
     * Health monitor for system components.
     */
    private static class HealthMonitor {
        private final OperationalConfig config;
        
        public HealthMonitor(OperationalConfig config) {
            this.config = config;
        }
        
        public HealthCheckResult performHealthCheck(Map<String, ServiceStatus> serviceStatuses) {
            List<String> healthyServices = new ArrayList<>();
            List<String> unhealthyServices = new ArrayList<>();
            
            for (Map.Entry<String, ServiceStatus> entry : serviceStatuses.entrySet()) {
                ServiceStatus status = entry.getValue();
                
                if (isServiceHealthy(status)) {
                    healthyServices.add(entry.getKey());
                } else {
                    unhealthyServices.add(entry.getKey());
                }
            }
            
            OverallHealth overallHealth = determineOverallHealth(healthyServices.size(), unhealthyServices.size());
            
            return new HealthCheckResult(overallHealth, healthyServices, unhealthyServices);
        }
        
        public OverallHealth getOverallHealth() {
            // Simplified implementation
            return OverallHealth.HEALTHY;
        }
        
        private boolean isServiceHealthy(ServiceStatus status) {
            return status.getErrorRate() < config.getErrorRateThreshold() &&
                   status.getAverageResponseTime() < config.getResponseTimeThreshold() &&
                   status.getConsecutiveFailures() < config.getConsecutiveFailuresThreshold();
        }
        
        private OverallHealth determineOverallHealth(int healthyCount, int unhealthyCount) {
            if (unhealthyCount == 0) {
                return OverallHealth.HEALTHY;
            } else if (unhealthyCount < healthyCount) {
                return OverallHealth.DEGRADED;
            } else {
                return OverallHealth.UNHEALTHY;
            }
        }
    }
    
    /**
     * Alert manager for operational alerts.
     */
    private static class AlertManager {
        private final OperationalConfig config;
        private final Queue<Alert> alertQueue = new ConcurrentLinkedQueue<>();
        private final Map<String, Alert> activeAlerts = new ConcurrentHashMap<>();
        
        public AlertManager(OperationalConfig config) {
            this.config = config;
        }
        
        public void raiseAlert(Alert alert) {
            String alertKey = alert.getType() + ":" + alert.getMessage().hashCode();
            
            Alert existingAlert = activeAlerts.get(alertKey);
            if (existingAlert != null && 
                Duration.between(existingAlert.getTimestamp(), alert.getTimestamp())
                    .toMinutes() < config.getAlertDeduplicationMinutes()) {
                return; // Skip duplicate alert
            }
            
            activeAlerts.put(alertKey, alert);
            alertQueue.offer(alert);
            
            log.warn("Alert raised: {} - {}", alert.getSeverity(), alert.getMessage());
        }
        
        public void processAlerts() {
            Alert alert;
            int processed = 0;
            
            while ((alert = alertQueue.poll()) != null && processed < config.getMaxAlertsPerBatch()) {
                processAlert(alert);
                processed++;
            }
        }
        
        public List<Alert> getActiveAlerts() {
            return new ArrayList<>(activeAlerts.values());
        }
        
        private void processAlert(Alert alert) {
            // In a real implementation, would send to external systems
            // (email, Slack, PagerDuty, etc.)
            log.info("Processing alert: {}", alert);
        }
    }
    
    /**
     * Circuit breaker for service protection.
     */
    private static class CircuitBreaker {
        private final OperationalConfig config;
        private final Map<String, CircuitBreakerState> states = new ConcurrentHashMap<>();
        
        public CircuitBreaker(OperationalConfig config) {
            this.config = config;
        }
        
        public void recordResult(String serviceName, boolean success) {
            CircuitBreakerState state = states.computeIfAbsent(serviceName,
                k -> new CircuitBreakerState(serviceName));
            
            state.recordResult(success);
            evaluateState(state);
        }
        
        public boolean canExecute(String serviceName) {
            CircuitBreakerState state = states.get(serviceName);
            if (state == null) {
                return true;
            }
            
            return state.canExecute();
        }
        
        public Map<String, CircuitBreakerState> getAllStates() {
            return new HashMap<>(states);
        }
        
        public void performMaintenance() {
            for (CircuitBreakerState state : states.values()) {
                if (state.getState() == CircuitState.HALF_OPEN) {
                    // Reset to closed if enough time has passed
                    if (state.shouldAttemptReset()) {
                        state.reset();
                    }
                }
            }
        }
        
        private void evaluateState(CircuitBreakerState state) {
            if (state.getState() == CircuitState.CLOSED) {
                if (state.getRecentFailures() >= config.getCircuitBreakerFailureThreshold()) {
                    state.open();
                    log.warn("Circuit breaker opened for service: {}", state.getServiceName());
                }
            } else if (state.getState() == CircuitState.OPEN) {
                if (state.shouldAttemptReset()) {
                    state.halfOpen();
                    log.info("Circuit breaker half-opened for service: {}", state.getServiceName());
                }
            }
        }
    }
    
    /**
     * Metrics collector for operational data.
     */
    private static class MetricsCollector {
        private final AtomicLong totalRequests = new AtomicLong(0);
        private final AtomicLong totalErrors = new AtomicLong(0);
        private final AtomicReference<Map<String, Object>> currentMetrics = 
            new AtomicReference<>(new HashMap<>());
        
        public MetricsCollector(OperationalConfig config) {
            // Configuration stored for potential future use
        }
        
        public void collectMetrics() {
            Map<String, Object> metrics = new HashMap<>();
            
            // JVM metrics
            Runtime runtime = Runtime.getRuntime();
            metrics.put("jvm.memory.used", runtime.totalMemory() - runtime.freeMemory());
            metrics.put("jvm.memory.total", runtime.totalMemory());
            metrics.put("jvm.memory.max", runtime.maxMemory());
            
            // Application metrics
            metrics.put("requests.total", totalRequests.get());
            metrics.put("errors.total", totalErrors.get());
            metrics.put("timestamp", System.currentTimeMillis());
            
            currentMetrics.set(metrics);
        }
        
        public Map<String, Object> getCurrentMetrics() {
            return new HashMap<>(currentMetrics.get());
        }
    }
    
    // Supporting classes and enums
    
    public enum OverallHealth {
        HEALTHY, DEGRADED, UNHEALTHY
    }
    
    public enum AlertType {
        HIGH_ERROR_RATE, HIGH_RESPONSE_TIME, CONSECUTIVE_FAILURES, HEALTH_CHECK_FAILED, CIRCUIT_BREAKER_OPENED
    }
    
    public enum AlertSeverity {
        INFO, WARNING, CRITICAL
    }
    
    public enum CircuitState {
        CLOSED, OPEN, HALF_OPEN
    }
    
    public static class Alert {
        private final AlertType type;
        private final AlertSeverity severity;
        private final String message;
        private final Instant timestamp;
        
        public Alert(AlertType type, AlertSeverity severity, String message, Instant timestamp) {
            this.type = type;
            this.severity = severity;
            this.message = message;
            this.timestamp = timestamp;
        }
        
        public AlertType getType() { return type; }
        public AlertSeverity getSeverity() { return severity; }
        public String getMessage() { return message; }
        public Instant getTimestamp() { return timestamp; }
        
        @Override
        public String toString() {
            return String.format("Alert{type=%s, severity=%s, message='%s', time=%s}",
                               type, severity, message, timestamp);
        }
    }
    
    public static class ServiceStatus {
        private final String serviceName;
        private final AtomicLong totalRequests = new AtomicLong(0);
        private final AtomicLong successfulRequests = new AtomicLong(0);
        private final AtomicLong totalResponseTime = new AtomicLong(0);
        private volatile int consecutiveFailures = 0;
        private volatile Instant lastRequestTime = Instant.now();
        
        public ServiceStatus(String serviceName) {
            this.serviceName = serviceName;
        }
        
        public void recordOperation(boolean success, long duration) {
            totalRequests.incrementAndGet();
            totalResponseTime.addAndGet(duration);
            lastRequestTime = Instant.now();
            
            if (success) {
                successfulRequests.incrementAndGet();
                consecutiveFailures = 0;
            } else {
                consecutiveFailures++;
            }
        }
        
        public String getServiceName() { return serviceName; }
        public long getTotalRequests() { return totalRequests.get(); }
        public long getSuccessfulRequests() { return successfulRequests.get(); }
        public int getConsecutiveFailures() { return consecutiveFailures; }
        public Instant getLastRequestTime() { return lastRequestTime; }
        
        public double getErrorRate() {
            long total = totalRequests.get();
            return total > 0 ? 1.0 - (double) successfulRequests.get() / total : 0.0;
        }
        
        public long getAverageResponseTime() {
            long total = totalRequests.get();
            return total > 0 ? totalResponseTime.get() / total : 0;
        }
    }
    
    public static class CircuitBreakerState {
        private final String serviceName;
        private volatile CircuitState state = CircuitState.CLOSED;
        private final AtomicLong recentFailures = new AtomicLong(0);
        private volatile Instant lastFailureTime = Instant.now();
        
        public CircuitBreakerState(String serviceName) {
            this.serviceName = serviceName;
        }
        
        public void recordResult(boolean success) {
            if (!success) {
                recentFailures.incrementAndGet();
                lastFailureTime = Instant.now();
            } else if (state == CircuitState.HALF_OPEN) {
                reset();
            }
        }
        
        public boolean canExecute() {
            return state != CircuitState.OPEN;
        }
        
        public void open() {
            state = CircuitState.OPEN;
        }
        
        public void halfOpen() {
            state = CircuitState.HALF_OPEN;
        }
        
        public void reset() {
            state = CircuitState.CLOSED;
            recentFailures.set(0);
        }
        
        public boolean shouldAttemptReset() {
            return Duration.between(lastFailureTime, Instant.now()).toMinutes() >= 5;
        }
        
        public String getServiceName() { return serviceName; }
        public CircuitState getState() { return state; }
        public long getRecentFailures() { return recentFailures.get(); }
    }
    
    public static class HealthCheckResult {
        private final OverallHealth overallHealth;
        private final List<String> healthyServices;
        private final List<String> failedChecks;
        
        public HealthCheckResult(OverallHealth overallHealth, List<String> healthyServices, List<String> failedChecks) {
            this.overallHealth = overallHealth;
            this.healthyServices = healthyServices;
            this.failedChecks = failedChecks;
        }
        
        public OverallHealth getOverallHealth() { return overallHealth; }
        public List<String> getHealthyServices() { return healthyServices; }
        public List<String> getFailedChecks() { return failedChecks; }
    }
    
    public static class OperationalStatus {
        private final OverallHealth overallHealth;
        private final Map<String, ServiceStatus> serviceStatuses;
        private final List<Alert> activeAlerts;
        private final Map<String, CircuitBreakerState> circuitBreakerStates;
        private final Map<String, Object> metrics;
        
        public OperationalStatus(OverallHealth overallHealth, Map<String, ServiceStatus> serviceStatuses,
                               List<Alert> activeAlerts, Map<String, CircuitBreakerState> circuitBreakerStates,
                               Map<String, Object> metrics) {
            this.overallHealth = overallHealth;
            this.serviceStatuses = serviceStatuses;
            this.activeAlerts = activeAlerts;
            this.circuitBreakerStates = circuitBreakerStates;
            this.metrics = metrics;
        }
        
        public OverallHealth getOverallHealth() { return overallHealth; }
        public Map<String, ServiceStatus> getServiceStatuses() { return serviceStatuses; }
        public List<Alert> getActiveAlerts() { return activeAlerts; }
        public Map<String, CircuitBreakerState> getCircuitBreakerStates() { return circuitBreakerStates; }
        public Map<String, Object> getMetrics() { return metrics; }
    }
    
    /**
     * Operational features configuration.
     */
    public static class OperationalConfig {
        private boolean healthMonitoringEnabled = true;
        private boolean alertingEnabled = true;
        private boolean circuitBreakerEnabled = true;
        private boolean metricsCollectionEnabled = true;
        
        private int healthCheckIntervalSeconds = 60;
        private int metricsCollectionIntervalSeconds = 30;
        private int alertProcessingIntervalSeconds = 15;
        
        private double errorRateThreshold = 0.1; // 10%
        private long responseTimeThreshold = 5000; // 5 seconds
        private int consecutiveFailuresThreshold = 5;
        private int circuitBreakerFailureThreshold = 10;
        
        private int alertDeduplicationMinutes = 15;
        private int maxAlertsPerBatch = 10;
        
        // Getters and setters
        public boolean isHealthMonitoringEnabled() { return healthMonitoringEnabled; }
        public void setHealthMonitoringEnabled(boolean healthMonitoringEnabled) { 
            this.healthMonitoringEnabled = healthMonitoringEnabled; 
        }
        
        public boolean isAlertingEnabled() { return alertingEnabled; }
        public void setAlertingEnabled(boolean alertingEnabled) { this.alertingEnabled = alertingEnabled; }
        
        public boolean isCircuitBreakerEnabled() { return circuitBreakerEnabled; }
        public void setCircuitBreakerEnabled(boolean circuitBreakerEnabled) { 
            this.circuitBreakerEnabled = circuitBreakerEnabled; 
        }
        
        public boolean isMetricsCollectionEnabled() { return metricsCollectionEnabled; }
        public void setMetricsCollectionEnabled(boolean metricsCollectionEnabled) { 
            this.metricsCollectionEnabled = metricsCollectionEnabled; 
        }
        
        public int getHealthCheckIntervalSeconds() { return healthCheckIntervalSeconds; }
        public void setHealthCheckIntervalSeconds(int healthCheckIntervalSeconds) { 
            this.healthCheckIntervalSeconds = healthCheckIntervalSeconds; 
        }
        
        public int getMetricsCollectionIntervalSeconds() { return metricsCollectionIntervalSeconds; }
        public void setMetricsCollectionIntervalSeconds(int metricsCollectionIntervalSeconds) { 
            this.metricsCollectionIntervalSeconds = metricsCollectionIntervalSeconds; 
        }
        
        public int getAlertProcessingIntervalSeconds() { return alertProcessingIntervalSeconds; }
        public void setAlertProcessingIntervalSeconds(int alertProcessingIntervalSeconds) { 
            this.alertProcessingIntervalSeconds = alertProcessingIntervalSeconds; 
        }
        
        public double getErrorRateThreshold() { return errorRateThreshold; }
        public void setErrorRateThreshold(double errorRateThreshold) { this.errorRateThreshold = errorRateThreshold; }
        
        public long getResponseTimeThreshold() { return responseTimeThreshold; }
        public void setResponseTimeThreshold(long responseTimeThreshold) { 
            this.responseTimeThreshold = responseTimeThreshold; 
        }
        
        public int getConsecutiveFailuresThreshold() { return consecutiveFailuresThreshold; }
        public void setConsecutiveFailuresThreshold(int consecutiveFailuresThreshold) { 
            this.consecutiveFailuresThreshold = consecutiveFailuresThreshold; 
        }
        
        public int getCircuitBreakerFailureThreshold() { return circuitBreakerFailureThreshold; }
        public void setCircuitBreakerFailureThreshold(int circuitBreakerFailureThreshold) { 
            this.circuitBreakerFailureThreshold = circuitBreakerFailureThreshold; 
        }
        
        public int getAlertDeduplicationMinutes() { return alertDeduplicationMinutes; }
        public void setAlertDeduplicationMinutes(int alertDeduplicationMinutes) { 
            this.alertDeduplicationMinutes = alertDeduplicationMinutes; 
        }
        
        public int getMaxAlertsPerBatch() { return maxAlertsPerBatch; }
        public void setMaxAlertsPerBatch(int maxAlertsPerBatch) { this.maxAlertsPerBatch = maxAlertsPerBatch; }
    }
}
