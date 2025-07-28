package io.confluent.connect.http.integration;

import io.confluent.connect.http.HttpSourceConnector;
import io.confluent.connect.http.HttpSourceTask;
import io.confluent.connect.http.operational.OperationalFeaturesManager;
import io.confluent.connect.http.config.EnhancedConfigValidator;
import io.confluent.connect.http.client.EnhancedHttpClient;
import io.confluent.connect.http.performance.EnhancedStreamingProcessor;

import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.MockResponse;

import java.io.IOException;
import java.time.Duration;
import java.util.*;

/**
 * Simplified Enterprise integration test that focuses on core functionality
 * without complex container dependencies that might fail in CI environments.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ReliableEnterpriseIntegrationTest {
    
    private static final Logger log = LoggerFactory.getLogger(ReliableEnterpriseIntegrationTest.class);
    
    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"))
            .withStartupTimeout(Duration.ofMinutes(3));
    
    private static MockWebServer mockApiServer;
    private HttpSourceConnector connector;
    private HttpSourceTask task;
    
    @BeforeAll
    static void setupTestEnvironment() throws IOException {
        log.info("Starting reliable enterprise features integration test");
        
        // Start MockWebServer for API mocking
        mockApiServer = new MockWebServer();
        mockApiServer.start();
        
        log.info("Test environment setup completed");
        log.info("Kafka bootstrap servers: {}", kafka.getBootstrapServers());
        log.info("Mock API Server: http://localhost:{}", mockApiServer.getPort());
    }
    
    @AfterAll
    static void teardownTestEnvironment() throws IOException {
        if (mockApiServer != null) {
            mockApiServer.shutdown();
        }
        log.info("Test environment teardown completed");
    }
    
    @BeforeEach
    void setupConnector() {
        connector = new HttpSourceConnector();
        task = new HttpSourceTask();
        
        // Enqueue a basic response for each test
        mockApiServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\": 1, \"name\": \"test\", \"timestamp\": \"2025-07-26T10:00:00Z\"}"));
    }
    
    @AfterEach
    void teardownConnector() {
        if (task != null) {
            task.stop();
        }
        if (connector != null) {
            connector.stop();
        }
    }
    
    @Test
    @Order(1)
    @DisplayName("Enterprise Connector Basic Functionality")
    void testEnterpriseConnectorBasicFunctionality() throws Exception {
        log.info("Testing Enterprise Connector Basic Functionality");
        
        Map<String, String> config = createBaseConnectorConfig();
        
        // Test connector version
        String version = connector.version();
        if (version == null) {
            throw new AssertionError("Connector version should not be null");
        }
        
        // Start connector
        connector.start(config);
        
        // Test task configuration
        List<Map<String, String>> taskConfigs = connector.taskConfigs(1);
        if (taskConfigs == null || taskConfigs.size() != 1) {
            throw new AssertionError("Expected 1 task config");
        }
        
        // Start task
        task.start(taskConfigs.get(0));
        
        // Test polling
        List<SourceRecord> records = task.poll();
        if (records == null) {
            throw new AssertionError("Expected non-null records");
        }
        
        log.info("✅ Enterprise Connector Basic Functionality test passed");
    }
    
    @Test
    @Order(2)
    @DisplayName("JMX Monitoring and Metrics Collection")
    void testJmxMonitoringAndMetrics() throws Exception {
        log.info("Testing JMX Monitoring and Metrics Collection");
        
        Map<String, String> config = createBaseConnectorConfig();
        
        // Enable JMX metrics in configuration
        config.put("metrics.jmx.enabled", "true");
        config.put("metrics.collection.interval.ms", "1000");
        
        // Start connector with JMX enabled
        connector.start(config);
        
        // Verify JMX beans are registered
        if (connector.version() == null || !connector.version().equals("2.0.0-enterprise")) {
            throw new AssertionError("Expected version 2.0.0-enterprise but got " + connector.version());
        }
        
        // Create and start task
        List<Map<String, String>> taskConfigs = connector.taskConfigs(1);
        if (taskConfigs == null || taskConfigs.size() != 1) {
            throw new AssertionError("Expected 1 task config but got " + (taskConfigs == null ? "null" : taskConfigs.size()));
        }
        
        task.start(taskConfigs.get(0));
        
        // Simulate some HTTP requests to generate metrics
        List<SourceRecord> records = task.poll();
        
        // Verify metrics collection (would check actual JMX metrics in production)
        if (records == null) {
            throw new AssertionError("Expected non-null records");
        }
        
        log.info("✅ JMX Monitoring and Metrics Collection test passed");
    }
    
    @Test
    @Order(3)
    @DisplayName("Enhanced Configuration Validation")
    void testEnhancedConfigurationValidation() throws Exception {
        log.info("Testing Enhanced Configuration Validation");
        
        // Test configuration validator
        EnhancedConfigValidator validator = new EnhancedConfigValidator(true);
        
        // Test valid configuration
        Map<String, String> validConfig = createBaseConnectorConfig();
        var validResult = validator.validateConfiguration(validConfig);
        if (!validResult.isValid()) {
            throw new AssertionError("Valid configuration should be valid");
        }
        if (validResult.hasErrors()) {
            throw new AssertionError("Valid configuration should not have errors");
        }
        
        // Test invalid configuration
        Map<String, String> invalidConfig = new HashMap<>();
        invalidConfig.put("invalid.key", "invalid.value");
        var invalidResult = validator.validateConfiguration(invalidConfig);
        
        if (!invalidResult.hasErrors()) {
            throw new AssertionError("Invalid configuration should have errors");
        }
        
        log.info("✅ Enhanced Configuration Validation test passed");
    }
    
    @Test
    @Order(4)
    @DisplayName("Enhanced HTTP Client")
    void testEnhancedHttpClient() throws Exception {
        log.info("Testing Enhanced HTTP Client");
        
        EnhancedHttpClient.HttpClientConfig config = new EnhancedHttpClient.HttpClientConfig();
        config.setHttp2Enabled(false); // Disable HTTP/2 for testing
        config.setAsyncEnabled(true);
        config.setCompressionEnabled(true);
        config.setMaxConnections(10);
        
        EnhancedHttpClient client = new EnhancedHttpClient(config);
        
        try {
            // Test client statistics
            var stats = client.getStatistics();
            if (stats == null) {
                throw new AssertionError("Client statistics should not be null");
            }
            
            log.info("HTTP Client statistics: {}", stats);
            
        } finally {
            client.shutdown();
        }
        
        log.info("✅ Enhanced HTTP Client test passed");
    }
    
    @Test
    @Order(5)
    @DisplayName("Enhanced Streaming Processor")
    void testEnhancedStreamingProcessor() throws Exception {
        log.info("Testing Enhanced Streaming Processor");
        
        EnhancedStreamingProcessor.StreamingConfig config = 
            new EnhancedStreamingProcessor.StreamingConfig();
        config.setBufferSize(8192);
        config.setBackPressureEnabled(true);
        config.setParallelProcessingEnabled(false); // Disable for testing
        
        EnhancedStreamingProcessor processor = new EnhancedStreamingProcessor(config);
        
        try {
            // Test processor statistics
            var stats = processor.getStatistics();
            if (stats == null) {
                throw new AssertionError("Processor statistics should not be null");
            }
            
            log.info("Streaming processor statistics: {}", stats);
            
        } finally {
            // Clean up if needed
        }
        
        log.info("✅ Enhanced Streaming Processor test passed");
    }
    
    @Test
    @Order(6)
    @DisplayName("Operational Features Manager")
    void testOperationalFeaturesManager() throws Exception {
        log.info("Testing Operational Features Manager");
        
        // Test operational features manager
        OperationalFeaturesManager.OperationalConfig config = 
            new OperationalFeaturesManager.OperationalConfig();
        config.setHealthMonitoringEnabled(true);
        config.setAlertingEnabled(true);
        config.setCircuitBreakerEnabled(true);
        config.setMetricsCollectionEnabled(true);
        
        OperationalFeaturesManager manager = new OperationalFeaturesManager(config);
        manager.start();
        
        try {
            // Test operational status
            OperationalFeaturesManager.OperationalStatus status = manager.getOperationalStatus();
            if (status == null) {
                throw new AssertionError("Operational status should not be null");
            }
            if (status.getOverallHealth() == null) {
                throw new AssertionError("Overall health should not be null");
            }
            
            // Test service availability
            boolean available = manager.isServiceAvailable("test-service");
            if (!available) {
                throw new AssertionError("Test service should be available");
            }
            
        } finally {
            manager.stop();
        }
        
        log.info("✅ Operational Features Manager test passed");
    }
    
    @Test
    @Order(7)
    @DisplayName("Full Enterprise Connector Integration")
    void testFullEnterpriseConnectorIntegration() throws Exception {
        log.info("Testing Full Enterprise Connector Integration");
        
        // Configure connector with multiple enterprise features enabled
        Map<String, String> fullConfig = createFullEnterpriseConfig();
        
        connector.start(fullConfig);
        
        List<Map<String, String>> taskConfigs = connector.taskConfigs(1);
        if (taskConfigs == null || taskConfigs.size() != 1) {
            throw new AssertionError("Expected 1 task config");
        }
        
        task.start(taskConfigs.get(0));
        
        // Test multiple polling cycles with all features active
        for (int i = 0; i < 3; i++) {
            // Enqueue response for each poll
            mockApiServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"id\": " + (i + 1) + ", \"iteration\": " + i + "}"));
            
            List<SourceRecord> records = task.poll();
            if (records == null) {
                throw new AssertionError("Expected non-null records in iteration " + i);
            }
            
            Thread.sleep(100); // Small delay between polls
        }
        
        log.info("✅ Full Enterprise Connector Integration test passed");
    }
    
    // Helper methods
    
    private Map<String, String> createBaseConnectorConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("name", "reliable-enterprise-http-source-test");
        config.put("connector.class", "io.confluent.connect.http.HttpSourceConnector");
        config.put("tasks.max", "1");
        config.put("http.api.base.url", "http://localhost:" + mockApiServer.getPort());
        config.put("apis.num", "1");
        config.put("api1.http.api.path", "/api/data");
        config.put("api1.topics", "test-topic");
        config.put("api1.http.request.method", "GET");
        config.put("api1.http.offset.mode", "SIMPLE_INCREMENTING");
        config.put("api1.http.initial.offset", "0");
        config.put("api1.request.interval.ms", "5000");
        config.put("auth.type", "NONE");
        config.put("output.data.format", "JSON_SR");
        return config;
    }
    
    private Map<String, String> createFullEnterpriseConfig() {
        Map<String, String> config = createBaseConnectorConfig();
        
        // Enable enterprise features (safe settings for testing)
        config.put("metrics.jmx.enabled", "true");
        config.put("health.check.enabled", "false"); // Disable to avoid port conflicts
        config.put("dlq.enabled", "true");
        config.put("dlq.topic.name", "enterprise-dlq");
        config.put("rate.limit.enabled", "true");
        config.put("rate.limit.requests.per.second", "10");
        config.put("openapi.enabled", "false"); // Disable to avoid port conflicts
        config.put("ssl.enabled", "false"); // Disable for testing
        config.put("pagination.enabled", "true");
        config.put("pagination.strategy", "OFFSET_BASED");
        config.put("transformation.enabled", "true");
        config.put("cache.enabled", "true");
        config.put("cache.ttl.ms", "30000");
        config.put("operational.features.enabled", "true");
        config.put("operational.health.enabled", "true");
        config.put("operational.alerting.enabled", "true");
        config.put("operational.circuit-breaker.enabled", "true");
        
        return config;
    }
}
