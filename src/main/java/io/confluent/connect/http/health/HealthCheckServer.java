package io.confluent.connect.http.health;

import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import io.confluent.connect.http.metrics.HttpSourceConnectorMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

/**
 * HTTP health check endpoints for monitoring the connector status.
 * Provides REST endpoints for health monitoring, circuit breaker states,
 * and authentication status.
 */
public class HealthCheckServer {
    
    private static final Logger log = LoggerFactory.getLogger(HealthCheckServer.class);
    
    private final HttpSourceConnectorConfig config;
    private final HttpSourceConnectorMetrics metrics;
    private final ObjectMapper objectMapper;
    private final Map<String, HealthStatus> apiHealthStatus;
    
    private HttpServer server;
    private ScheduledExecutorService healthCheckExecutor;
    private volatile boolean running = false;
    
    // Health check configuration
    private final int healthCheckPort;
    private final String healthCheckHost;
    private final long healthCheckIntervalMs;
    
    public HealthCheckServer(HttpSourceConnectorConfig config, HttpSourceConnectorMetrics metrics) {
        this.config = config;
        this.metrics = metrics;
        this.objectMapper = new ObjectMapper();
        this.apiHealthStatus = new ConcurrentHashMap<>();
        
        // Configuration from connector config or defaults
        this.healthCheckPort = getHealthCheckPort(config);
        this.healthCheckHost = getHealthCheckHost(config);
        this.healthCheckIntervalMs = getHealthCheckIntervalMs(config);
    }
    
    public void start() {
        try {
            // Start HTTP server
            server = HttpServer.create(new InetSocketAddress(healthCheckHost, healthCheckPort), 0);
            
            // Register health endpoints
            server.createContext("/health", new HealthHandler());
            server.createContext("/health/live", new LivenessHandler());
            server.createContext("/health/ready", new ReadinessHandler());
            server.createContext("/health/apis", new ApiHealthHandler());
            server.createContext("/health/circuit-breakers", new CircuitBreakerHandler());
            server.createContext("/health/metrics", new MetricsHandler());
            server.createContext("/health/status", new StatusHandler());
            
            server.setExecutor(Executors.newFixedThreadPool(2));
            server.start();
            
            // Start periodic health checks
            healthCheckExecutor = Executors.newScheduledThreadPool(1);
            healthCheckExecutor.scheduleAtFixedRate(
                this::performHealthChecks,
                0,
                healthCheckIntervalMs,
                TimeUnit.MILLISECONDS
            );
            
            running = true;
            log.info("Health check server started on {}:{}", healthCheckHost, healthCheckPort);
            
        } catch (Exception e) {
            log.error("Failed to start health check server", e);
            throw new RuntimeException("Failed to start health check server", e);
        }
    }
    
    public void stop() {
        running = false;
        
        if (healthCheckExecutor != null) {
            healthCheckExecutor.shutdown();
            try {
                if (!healthCheckExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    healthCheckExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                healthCheckExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        if (server != null) {
            server.stop(1);
        }
        
        log.info("Health check server stopped");
    }
    
    private void performHealthChecks() {
        try {
            for (String apiId : metrics.getApiIds()) {
                HealthStatus status = checkApiHealth(apiId);
                apiHealthStatus.put(apiId, status);
            }
        } catch (Exception e) {
            log.warn("Error during health check execution", e);
        }
    }
    
    private HealthStatus checkApiHealth(String apiId) {
        try {
            // Get metrics for the API
            long requestCount = metrics.getRequestCountForApi(apiId);
            double successRate = metrics.getSuccessRateForApi(apiId);
            double avgResponseTime = metrics.getAverageResponseTimeForApi(apiId);
            String circuitBreakerState = metrics.getCircuitBreakerStateForApi(apiId);
            
            // Determine health based on metrics
            HealthStatus.Status status;
            String message;
            
            if ("OPEN".equals(circuitBreakerState)) {
                status = HealthStatus.Status.DOWN;
                message = "Circuit breaker is open";
            } else if (requestCount == 0) {
                status = HealthStatus.Status.UNKNOWN;
                message = "No requests processed yet";
            } else if (successRate >= 95.0) {
                status = HealthStatus.Status.UP;
                message = "API is healthy";
            } else if (successRate >= 85.0) {
                status = HealthStatus.Status.DEGRADED;
                message = "API performance is degraded";
            } else {
                status = HealthStatus.Status.DOWN;
                message = "API has high error rate";
            }
            
            return new HealthStatus(
                status,
                message,
                requestCount,
                successRate,
                avgResponseTime,
                circuitBreakerState,
                System.currentTimeMillis()
            );
            
        } catch (Exception e) {
            log.warn("Error checking health for API: " + apiId, e);
            return new HealthStatus(
                HealthStatus.Status.DOWN,
                "Health check failed: " + e.getMessage(),
                0,
                0.0,
                0.0,
                "UNKNOWN",
                System.currentTimeMillis()
            );
        }
    }
    
    private HealthStatus getOverallHealth() {
        if (apiHealthStatus.isEmpty()) {
            return new HealthStatus(
                HealthStatus.Status.UNKNOWN,
                "No API health data available",
                0, 0.0, 0.0, "N/A",
                System.currentTimeMillis()
            );
        }
        
        long totalRequests = metrics.getTotalRequestCount();
        double overallSuccessRate = metrics.getSuccessRate();
        double avgResponseTime = metrics.getAverageResponseTimeMs();
        
        // Determine overall status based on individual API status
        long upCount = apiHealthStatus.values().stream()
            .mapToLong(status -> status.getStatus() == HealthStatus.Status.UP ? 1 : 0)
            .sum();
        
        long degradedCount = apiHealthStatus.values().stream()
            .mapToLong(status -> status.getStatus() == HealthStatus.Status.DEGRADED ? 1 : 0)
            .sum();
        
        long downCount = apiHealthStatus.values().stream()
            .mapToLong(status -> status.getStatus() == HealthStatus.Status.DOWN ? 1 : 0)
            .sum();
        
        HealthStatus.Status overallStatus;
        String message;
        
        if (downCount > 0) {
            overallStatus = HealthStatus.Status.DOWN;
            message = String.format("%d APIs down, %d degraded, %d up", downCount, degradedCount, upCount);
        } else if (degradedCount > 0) {
            overallStatus = HealthStatus.Status.DEGRADED;
            message = String.format("%d APIs degraded, %d up", degradedCount, upCount);
        } else if (upCount > 0) {
            overallStatus = HealthStatus.Status.UP;
            message = String.format("All %d APIs healthy", upCount);
        } else {
            overallStatus = HealthStatus.Status.UNKNOWN;
            message = "Unknown health status";
        }
        
        return new HealthStatus(
            overallStatus,
            message,
            totalRequests,
            overallSuccessRate,
            avgResponseTime,
            "N/A",
            System.currentTimeMillis()
        );
    }
    
    // HTTP Handlers
    
    private class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, createErrorResponse("Method not allowed"));
                return;
            }
            
            try {
                HealthStatus overallHealth = getOverallHealth();
                ObjectNode response = createHealthResponse(overallHealth);
                
                int statusCode = overallHealth.getStatus() == HealthStatus.Status.UP ? 200 : 503;
                sendJsonResponse(exchange, statusCode, response);
                
            } catch (Exception e) {
                log.error("Error in health endpoint", e);
                sendJsonResponse(exchange, 500, createErrorResponse("Internal server error"));
            }
        }
    }
    
    private class LivenessHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, createErrorResponse("Method not allowed"));
                return;
            }
            
            // Liveness check - just verify the service is running
            ObjectNode response = objectMapper.createObjectNode();
            response.put("status", running ? "UP" : "DOWN");
            response.put("timestamp", System.currentTimeMillis());
            response.put("uptime", metrics.getUptimeMs());
            
            int statusCode = running ? 200 : 503;
            sendJsonResponse(exchange, statusCode, response);
        }
    }
    
    private class ReadinessHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, createErrorResponse("Method not allowed"));
                return;
            }
            
            try {
                // Readiness check - verify the service can handle requests
                HealthStatus overallHealth = getOverallHealth();
                boolean ready = running && 
                    (overallHealth.getStatus() == HealthStatus.Status.UP || 
                     overallHealth.getStatus() == HealthStatus.Status.DEGRADED);
                
                ObjectNode response = objectMapper.createObjectNode();
                response.put("status", ready ? "UP" : "DOWN");
                response.put("timestamp", System.currentTimeMillis());
                response.put("message", ready ? "Service is ready" : "Service is not ready");
                
                int statusCode = ready ? 200 : 503;
                sendJsonResponse(exchange, statusCode, response);
                
            } catch (Exception e) {
                log.error("Error in readiness endpoint", e);
                sendJsonResponse(exchange, 500, createErrorResponse("Internal server error"));
            }
        }
    }
    
    private class ApiHealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, createErrorResponse("Method not allowed"));
                return;
            }
            
            try {
                ObjectNode response = objectMapper.createObjectNode();
                ObjectNode apis = objectMapper.createObjectNode();
                
                for (Map.Entry<String, HealthStatus> entry : apiHealthStatus.entrySet()) {
                    ObjectNode apiHealth = createHealthResponse(entry.getValue());
                    apis.set(entry.getKey(), apiHealth);
                }
                
                response.set("apis", apis);
                response.put("timestamp", System.currentTimeMillis());
                
                sendJsonResponse(exchange, 200, response);
                
            } catch (Exception e) {
                log.error("Error in API health endpoint", e);
                sendJsonResponse(exchange, 500, createErrorResponse("Internal server error"));
            }
        }
    }
    
    private class CircuitBreakerHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, createErrorResponse("Method not allowed"));
                return;
            }
            
            try {
                ObjectNode response = objectMapper.createObjectNode();
                
                response.put("openCount", metrics.getCircuitBreakerOpenCount());
                response.put("halfOpenCount", metrics.getCircuitBreakerHalfOpenCount());
                response.put("closedCount", metrics.getCircuitBreakerClosedCount());
                
                ObjectNode states = objectMapper.createObjectNode();
                for (String state : metrics.getCircuitBreakerStates()) {
                    String[] parts = state.split(":", 2);
                    if (parts.length == 2) {
                        states.put(parts[0], parts[1]);
                    }
                }
                response.set("states", states);
                response.put("timestamp", System.currentTimeMillis());
                
                sendJsonResponse(exchange, 200, response);
                
            } catch (Exception e) {
                log.error("Error in circuit breaker endpoint", e);
                sendJsonResponse(exchange, 500, createErrorResponse("Internal server error"));
            }
        }
    }
    
    private class MetricsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, createErrorResponse("Method not allowed"));
                return;
            }
            
            try {
                ObjectNode response = objectMapper.createObjectNode();
                
                // Request metrics
                ObjectNode requests = objectMapper.createObjectNode();
                requests.put("total", metrics.getTotalRequestCount());
                requests.put("successful", metrics.getSuccessfulRequestCount());
                requests.put("failed", metrics.getFailedRequestCount());
                requests.put("successRate", metrics.getSuccessRate());
                requests.put("requestsPerSecond", metrics.getRequestsPerSecond());
                response.set("requests", requests);
                
                // Response time metrics
                ObjectNode responseTimes = objectMapper.createObjectNode();
                responseTimes.put("average", metrics.getAverageResponseTimeMs());
                responseTimes.put("max", metrics.getMaxResponseTimeMs());
                responseTimes.put("min", metrics.getMinResponseTimeMs());
                response.set("responseTimes", responseTimes);
                
                // Error metrics
                ObjectNode errors = objectMapper.createObjectNode();
                errors.put("authentication", metrics.getAuthenticationErrors());
                errors.put("client", metrics.getClientErrors());
                errors.put("server", metrics.getServerErrors());
                errors.put("timeout", metrics.getTimeoutErrors());
                errors.put("connection", metrics.getConnectionErrors());
                errors.put("rateLimit", metrics.getRateLimitErrors());
                response.set("errors", errors);
                
                // Cache metrics
                if (metrics.isCachingEnabled()) {
                    ObjectNode cache = objectMapper.createObjectNode();
                    cache.put("hits", metrics.getCacheHits());
                    cache.put("misses", metrics.getCacheMisses());
                    cache.put("hitRate", metrics.getCacheHitRate());
                    cache.put("evictions", metrics.getCacheEvictions());
                    cache.put("currentSize", metrics.getCurrentCacheSize());
                    response.set("cache", cache);
                }
                
                // Throughput metrics
                ObjectNode throughput = objectMapper.createObjectNode();
                throughput.put("recordsProduced", metrics.getTotalRecordsProduced());
                throughput.put("recordsPerSecond", metrics.getRecordsPerSecond());
                throughput.put("bytesProduced", metrics.getTotalBytesProduced());
                throughput.put("bytesPerSecond", metrics.getBytesPerSecond());
                response.set("throughput", throughput);
                
                response.put("timestamp", System.currentTimeMillis());
                response.put("uptime", metrics.getUptimeMs());
                
                sendJsonResponse(exchange, 200, response);
                
            } catch (Exception e) {
                log.error("Error in metrics endpoint", e);
                sendJsonResponse(exchange, 500, createErrorResponse("Internal server error"));
            }
        }
    }
    
    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, createErrorResponse("Method not allowed"));
                return;
            }
            
            try {
                ObjectNode response = objectMapper.createObjectNode();
                
                response.put("connectorName", metrics.getConnectorName());
                response.put("version", metrics.getConnectorVersion());
                response.put("numberOfApis", metrics.getNumberOfApis());
                response.put("numberOfTasks", metrics.getNumberOfTasks());
                response.put("authenticationType", metrics.getAuthenticationType());
                response.put("cachingEnabled", metrics.isCachingEnabled());
                response.put("circuitBreakerEnabled", metrics.isCircuitBreakerEnabled());
                response.put("overallHealth", metrics.getOverallHealthStatus());
                response.put("lastSuccessfulRequest", metrics.getLastSuccessfulRequestTime());
                response.put("uptime", metrics.getUptimeMs());
                response.put("timestamp", System.currentTimeMillis());
                
                sendJsonResponse(exchange, 200, response);
                
            } catch (Exception e) {
                log.error("Error in status endpoint", e);
                sendJsonResponse(exchange, 500, createErrorResponse("Internal server error"));
            }
        }
    }
    
    // Helper methods
    
    private ObjectNode createHealthResponse(HealthStatus health) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("status", health.getStatus().name());
        response.put("message", health.getMessage());
        response.put("requestCount", health.getRequestCount());
        response.put("successRate", health.getSuccessRate());
        response.put("averageResponseTime", health.getAverageResponseTime());
        response.put("circuitBreakerState", health.getCircuitBreakerState());
        response.put("timestamp", health.getTimestamp());
        return response;
    }
    
    private ObjectNode createErrorResponse(String message) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("error", message);
        response.put("timestamp", System.currentTimeMillis());
        return response;
    }
    
    private void sendJsonResponse(HttpExchange exchange, int statusCode, ObjectNode response) throws IOException {
        String responseBody = objectMapper.writeValueAsString(response);
        sendResponse(exchange, statusCode, responseBody);
    }
    
    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(statusCode, response.length());
        
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response.getBytes());
        }
    }
    
    // Configuration helpers
    private int getHealthCheckPort(HttpSourceConnectorConfig config) {
        // Try to get from config, fallback to default
        try {
            return Integer.parseInt(System.getProperty("connector.health.port", "8084"));
        } catch (NumberFormatException e) {
            return 8084;
        }
    }
    
    private String getHealthCheckHost(HttpSourceConnectorConfig config) {
        return System.getProperty("connector.health.host", "localhost");
    }
    
    private long getHealthCheckIntervalMs(HttpSourceConnectorConfig config) {
        try {
            return Long.parseLong(System.getProperty("connector.health.interval.ms", "30000"));
        } catch (NumberFormatException e) {
            return 30000; // 30 seconds default
        }
    }
    
    public boolean isRunning() {
        return running;
    }
    
    public int getPort() {
        return healthCheckPort;
    }
    
    public String getHost() {
        return healthCheckHost;
    }
}
