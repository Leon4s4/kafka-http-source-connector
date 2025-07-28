package io.confluent.connect.http.integration;

import io.confluent.connect.http.HttpSourceConnector;
import io.confluent.connect.http.HttpSourceTask;
import io.confluent.connect.http.config.EnhancedConfigValidator;
import io.confluent.connect.http.cache.IntelligentCacheManager;
import io.confluent.connect.http.cache.CacheManagerConfig;
import io.confluent.connect.http.client.EnhancedHttpClient;
import io.confluent.connect.http.performance.EnhancedStreamingProcessor;
import io.confluent.connect.http.operational.OperationalFeaturesManager;

import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.assertj.core.api.Assertions.assertThat;

import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.MockResponse;

import java.io.IOException;
import java.util.*;

/**
 * Optimized simplified integration test using shared containers for faster execution.
 * This test validates enterprise features with container reuse for improved performance.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class OptimizedSimplifiedEnterpriseIntegrationTest extends BaseIntegrationTest {
    
    private static final Logger log = LoggerFactory.getLogger(OptimizedSimplifiedEnterpriseIntegrationTest.class);
    
    private static MockWebServer mockApiServer;
    private HttpSourceConnector connector;
    private HttpSourceTask task;
    
    @BeforeAll
    static void setupTestEnvironment() throws IOException {
        long startTime = System.currentTimeMillis();
        log.info("Starting optimized simplified enterprise features integration test");
        
        // Start MockWebServer for API mocking
        mockApiServer = new MockWebServer();
        mockApiServer.start();
        
        // Log container information for performance monitoring
        logContainerInfo();
        
        long setupTime = System.currentTimeMillis() - startTime;
        log.info("Test environment setup completed in {}ms (using shared containers)", setupTime);
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
    @DisplayName("Enterprise Connector Basic Functionality - Fast")
    void testEnterpriseConnectorBasicFunctionality() throws Exception {
        log.info("Testing Enterprise Connector Basic Functionality (Fast)");
        
        Map<String, String> config = createBaseConnectorConfig();
        
        // Test connector version
        String version = connector.version();
        assertThat(version).isNotNull();
        
        // Start connector
        connector.start(config);
        
        // Test task configuration
        List<Map<String, String>> taskConfigs = connector.taskConfigs(1);
        assertThat(taskConfigs).isNotNull().hasSize(1);
        
        // Start task
        task.start(taskConfigs.get(0));
        
        // Test polling
        List<SourceRecord> records = task.poll();
        assertThat(records).isNotNull();
        
        log.info("✅ Enterprise Connector Basic Functionality test passed");
    }
    
    @Test
    @Order(2)
    @DisplayName("Enhanced Configuration Validation - Fast")
    void testEnhancedConfigurationValidation() throws Exception {
        log.info("Testing Enhanced Configuration Validation (Fast)");
        
        EnhancedConfigValidator validator = new EnhancedConfigValidator(true);
        
        // Test valid configuration
        Map<String, String> validConfig = createBaseConnectorConfig();
        var validResult = validator.validateConfiguration(validConfig);
        
        assertThat(validResult.isValid()).isTrue();
        assertThat(validResult.hasErrors()).isFalse();
        
        // Test invalid configuration
        Map<String, String> invalidConfig = new HashMap<>();
        invalidConfig.put("invalid.key", "invalid.value");
        var invalidResult = validator.validateConfiguration(invalidConfig);
        
        assertThat(invalidResult.hasErrors()).isTrue();
        
        log.info("✅ Enhanced Configuration Validation test passed");
    }
    
    @Test
    @Order(3)
    @DisplayName("Enhanced HTTP Client - Fast")
    void testEnhancedHttpClient() throws Exception {
        log.info("Testing Enhanced HTTP Client (Fast)");
        
        EnhancedHttpClient.HttpClientConfig config = new EnhancedHttpClient.HttpClientConfig();
        config.setHttp2Enabled(false); // Disable HTTP/2 for testing
        config.setAsyncEnabled(true);
        config.setCompressionEnabled(true);
        config.setMaxConnections(5); // Reduced for faster tests
        
        EnhancedHttpClient client = new EnhancedHttpClient(config);
        
        try {
            // Test client statistics
            var stats = client.getStatistics();
            assertThat(stats).isNotNull();
            
            log.info("HTTP Client statistics: {}", stats);
            
        } finally {
            client.shutdown();
        }
        
        log.info("✅ Enhanced HTTP Client test passed");
    }
    
    @Test
    @Order(4)
    @DisplayName("Enhanced Streaming Processor - Fast")
    void testEnhancedStreamingProcessor() throws Exception {
        log.info("Testing Enhanced Streaming Processor (Fast)");
        
        EnhancedStreamingProcessor.StreamingConfig config = 
            new EnhancedStreamingProcessor.StreamingConfig();
        config.setBufferSize(4096); // Reduced for faster tests
        config.setBackPressureEnabled(true);
        config.setParallelProcessingEnabled(false); // Disable for testing
        
        EnhancedStreamingProcessor processor = new EnhancedStreamingProcessor(config);
        
        try {
            // Test processor statistics
            var stats = processor.getStatistics();
            assertThat(stats).isNotNull();
            
            log.info("Streaming processor statistics: {}", stats);
            
        } finally {
            // Clean up if needed
        }
        
        log.info("✅ Enhanced Streaming Processor test passed");
    }
    
    @Test
    @Order(5)
    @DisplayName("Intelligent Cache Manager - Fast")
    void testIntelligentCacheManager() throws Exception {
        log.info("Testing Intelligent Cache Manager (Fast)");
        
        CacheManagerConfig config = new CacheManagerConfig.Builder()
                .enabled(true)
                .maintenanceIntervalSeconds(30) // Reduced for faster tests
                .enableStatistics(true)
                .responseCacheMaxSize(100) // Reduced for faster tests
                .responseCacheTtlSeconds(10) // Minimum allowed value
                .build();
        
        IntelligentCacheManager cacheManager = new IntelligentCacheManager(config);
        
        try {
            // Test cache operations using the correct API
            String testKey = "test-key-fast";
            String testValue = "test-value-fast";
            
            cacheManager.put(IntelligentCacheManager.CacheType.RESPONSE, testKey, testValue);
            
            Object cachedValue = cacheManager.get(IntelligentCacheManager.CacheType.RESPONSE, testKey, String.class);
            
            assertThat(cachedValue).isEqualTo(testValue);
            
            // Test cache statistics
            var stats = cacheManager.getStatistics();
            assertThat(stats).isNotNull();
            
            log.info("Cache statistics: {}", stats);
            
        } finally {
            cacheManager.clear(IntelligentCacheManager.CacheType.RESPONSE);
        }
        
        log.info("✅ Intelligent Cache Manager test passed");
    }
    
    @Test
    @Order(6)
    @DisplayName("Operational Features Manager - Fast")
    void testOperationalFeaturesManager() throws Exception {
        log.info("Testing Operational Features Manager (Fast)");
        
        OperationalFeaturesManager.OperationalConfig config = 
            new OperationalFeaturesManager.OperationalConfig();
        config.setHealthMonitoringEnabled(true);
        config.setAlertingEnabled(true);
        config.setCircuitBreakerEnabled(true);
        config.setMetricsCollectionEnabled(true);
        
        OperationalFeaturesManager manager = new OperationalFeaturesManager(config);
        
        try {
            manager.start();
            
            // Test operational status
            var status = manager.getOperationalStatus();
            assertThat(status).isNotNull();
            assertThat(status.getOverallHealth()).isNotNull();
            
            // Test service availability
            boolean available = manager.isServiceAvailable("test-service");
            // Service may not be available for new service (expected)
            
            log.info("Operational status: {}", status);
            
        } finally {
            manager.stop();
        }
        
        log.info("✅ Operational Features Manager test passed");
    }
    
    @Test
    @Order(7)
    @DisplayName("Full Enterprise Connector Integration - Fast")
    void testFullEnterpriseConnectorIntegration() throws Exception {
        log.info("Testing Full Enterprise Connector Integration (Fast)");
        
        // Configure connector with multiple enterprise features enabled
        Map<String, String> fullConfig = createFullEnterpriseConfig();
        
        connector.start(fullConfig);
        
        List<Map<String, String>> taskConfigs = connector.taskConfigs(1);
        assertThat(taskConfigs).isNotNull().hasSize(1);
        
        task.start(taskConfigs.get(0));
        
        // Test reduced polling cycles for speed
        for (int i = 0; i < 2; i++) { // Reduced from 3 to 2
            // Enqueue response for each poll
            mockApiServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"id\": " + (i + 1) + ", \"iteration\": " + i + "}"));
            
            List<SourceRecord> records = task.poll();
            assertThat(records).isNotNull();
            
            Thread.sleep(25); // Very small delay
        }
        
        log.info("✅ Full Enterprise Connector Integration test passed");
    }
    
    // Helper methods
    
    private Map<String, String> createBaseConnectorConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("name", "optimized-simplified-http-source-test");
        config.put("connector.class", "io.confluent.connect.http.HttpSourceConnector");
        config.put("tasks.max", "1");
        config.put("http.api.base.url", "http://localhost:" + mockApiServer.getPort());
        config.put("apis.num", "1");
        config.put("api1.http.api.path", "/api/data");
        config.put("api1.topics", "test-topic-fast");
        config.put("api1.http.request.method", "GET");
        config.put("api1.http.offset.mode", "SIMPLE_INCREMENTING");
        config.put("api1.http.initial.offset", "0");
        config.put("api1.request.interval.ms", "1000"); // Reduced for faster tests
        config.put("auth.type", "NONE");
        config.put("output.data.format", "JSON_SR");
        
        // Use shared container connection info
        config.putAll(getBaseTestProperties());
        
        return config;
    }
    
    private Map<String, String> createFullEnterpriseConfig() {
        Map<String, String> config = createBaseConnectorConfig();
        
        // Enable enterprise features with optimized settings for speed
        config.put("metrics.jmx.enabled", "true");
        config.put("health.check.enabled", "false"); // Disable to avoid port conflicts
        config.put("dlq.enabled", "true");
        config.put("dlq.topic.name", "enterprise-dlq-fast");
        config.put("rate.limit.enabled", "true");
        config.put("rate.limit.requests.per.second", "50"); // High for fast tests
        config.put("openapi.enabled", "false"); // Disable to avoid port conflicts
        config.put("ssl.enabled", "false"); // Disable for testing
        config.put("pagination.enabled", "true");
        config.put("pagination.strategy", "OFFSET_BASED");
        config.put("transformation.enabled", "true");
        config.put("cache.enabled", "true");
        config.put("cache.ttl.ms", "10000"); // Minimum allowed value
        config.put("operational.features.enabled", "true");
        config.put("operational.health.enabled", "true");
        config.put("operational.alerting.enabled", "true");
        config.put("operational.circuit-breaker.enabled", "true");
        
        return config;
    }
}
