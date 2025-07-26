package io.confluent.connect.http.integration;

import io.confluent.connect.http.HttpSourceConnector;
import io.confluent.connect.http.HttpSourceTask;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Simplified integration test for the HTTP Source Connector using TestContainers.
 * Uses MockWebServer instead of WireMock for better compatibility.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class HttpSourceConnectorSimpleIntegrationTest {
    
    private static final Network network = Network.newNetwork();
    
    @Container
    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"))
            .withNetwork(network)
            .withNetworkAliases("kafka");
    
    private MockWebServer mockWebServer;
    private HttpSourceConnector connector;
    private HttpSourceTask task;
    private Map<String, String> connectorConfig;
    
    @BeforeEach
    void setUp() throws IOException {
        // Start MockWebServer
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        
        // Initialize connector
        connector = new HttpSourceConnector();
        task = new HttpSourceTask();
        
        // Base connector configuration
        connectorConfig = new HashMap<>();
        connectorConfig.put("connector.class", "io.confluent.connect.http.HttpSourceConnector");
        connectorConfig.put("tasks.max", "1");
        connectorConfig.put("http.api.base.url", mockWebServer.url("/api").toString());
        connectorConfig.put("apis.num", "1");
        connectorConfig.put("api1.http.api.path", "/users");
        connectorConfig.put("api1.topics", "test-users");
        connectorConfig.put("api1.http.request.method", "GET");
        connectorConfig.put("api1.http.offset.mode", "SIMPLE_INCREMENTING");
        connectorConfig.put("api1.http.initial.offset", "0");
        connectorConfig.put("api1.request.interval.ms", "5000");
        connectorConfig.put("output.data.format", "JSON_SR");
        connectorConfig.put("behavior.on.error", "IGNORE");
    }
    
    @AfterEach
    void tearDown() throws IOException {
        if (task != null) {
            try {
                task.stop();
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
        if (mockWebServer != null) {
            mockWebServer.shutdown();
        }
    }
    
    @Test
    @Order(1)
    @DisplayName("Test basic HTTP API polling functionality")
    void testBasicHttpPolling() throws InterruptedException {
        // Setup mock API response
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\n" +
                        "  \"users\": [\n" +
                        "    {\"id\": 1, \"name\": \"John Doe\", \"email\": \"john@example.com\"},\n" +
                        "    {\"id\": 2, \"name\": \"Jane Smith\", \"email\": \"jane@example.com\"}\n" +
                        "  ]\n" +
                        "}"));
        
        // Configure data extraction
        connectorConfig.put("api1.http.response.data.json.pointer", "/users");
        
        // Start connector
        connector.start(connectorConfig);
        List<Map<String, String>> taskConfigs = connector.taskConfigs(1);
        task.start(taskConfigs.get(0));
        
        // Poll for records
        List<SourceRecord> records = task.poll();
        
        // Verify results
        assertThat(records).hasSize(2);
        assertThat(records.get(0).topic()).isEqualTo("test-users");
        
        // Verify API was called
        assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
    }
    
    @Test
    @Order(2)
    @DisplayName("Test field-level encryption")
    void testFieldLevelEncryption() throws InterruptedException {
        // Setup API response with sensitive data
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\n" +
                        "  \"users\": [\n" +
                        "    {\n" +
                        "      \"id\": 1,\n" +
                        "      \"name\": \"John Doe\",\n" +
                        "      \"ssn\": \"123-45-6789\",\n" +
                        "      \"salary\": 75000,\n" +
                        "      \"email\": \"john@example.com\"\n" +
                        "    }\n" +
                        "  ]\n" +
                        "}"));
        
        // Configure field encryption
        connectorConfig.put("field.encryption.enabled", "true");
        connectorConfig.put("field.encryption.key", "dGVzdC1lbmNyeXB0aW9uLWtleS0yNTYtYml0czEyMzQ="); // base64 encoded 32-byte key
        connectorConfig.put("field.encryption.rules", "ssn:AES_GCM,salary:DETERMINISTIC");
        connectorConfig.put("api1.http.response.data.json.pointer", "/users");
        
        // Start connector
        connector.start(connectorConfig);
        List<Map<String, String>> taskConfigs = connector.taskConfigs(1);
        task.start(taskConfigs.get(0));
        
        // Poll for records
        List<SourceRecord> records = task.poll();
        
        // Verify that sensitive fields are encrypted
        assertThat(records).hasSize(1);
        SourceRecord record = records.get(0);
        
        // The encrypted fields should not contain original values
        String recordValue = record.value().toString();
        assertThat(recordValue).doesNotContain("123-45-6789"); // SSN should be encrypted
        assertThat(recordValue).doesNotContain("75000"); // Salary should be encrypted
        assertThat(recordValue).contains("john@example.com"); // Email should not be encrypted
    }
    
    @Test
    @Order(3)
    @DisplayName("Test error handling")
    void testErrorHandling() throws InterruptedException {
        // Setup API to return server error for multiple retries
        for (int i = 0; i < 10; i++) {
            mockWebServer.enqueue(new MockResponse()
                    .setResponseCode(500)
                    .setBody("Internal Server Error"));
        }
        
        // Configure retries to be minimal for faster test
        connectorConfig.put("api1.max.retries", "5");
        connectorConfig.put("api1.retry.backoff.ms", "100");
        
        // Start connector
        connector.start(connectorConfig);
        List<Map<String, String>> taskConfigs = connector.taskConfigs(1);
        task.start(taskConfigs.get(0));
        
        // Poll should handle error gracefully
        List<SourceRecord> records = task.poll();
        
        // Should return empty list on error with IGNORE behavior
        assertThat(records).isEmpty();
        
        // Verify API was called (should be 1 + retries = 6 total)
        assertThat(mockWebServer.getRequestCount()).isEqualTo(6);
    }
    
    @Test
    @Order(4)
    @DisplayName("Test performance optimization features")
    void testPerformanceOptimization() throws InterruptedException {
        // Setup API response
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .addHeader("Cache-Control", "max-age=300")
                .setBody("{\n" +
                        "  \"users\": [\n" +
                        "    {\"id\": 1, \"name\": \"Cached User\", \"email\": \"cached@example.com\"}\n" +
                        "  ]\n" +
                        "}"));
        
        // Enable performance optimization
        connectorConfig.put("response.caching.enabled", "true");
        connectorConfig.put("response.cache.ttl.ms", "10000");
        connectorConfig.put("max.cache.size", "100");
        connectorConfig.put("adaptive.polling.enabled", "true");
        connectorConfig.put("api1.http.response.data.json.pointer", "/users");
        
        // Start connector
        connector.start(connectorConfig);
        List<Map<String, String>> taskConfigs = connector.taskConfigs(1);
        task.start(taskConfigs.get(0));
        
        // First poll should hit the API
        List<SourceRecord> records1 = task.poll();
        assertThat(records1).hasSize(1);
        
        // Verify API was called once
        assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
    }
    
    @Test
    @Order(5)
    @DisplayName("Test comprehensive configuration validation")
    void testConfigurationValidation() {
        // Test valid configuration
        Map<String, String> validConfig = new HashMap<>(connectorConfig);
        validConfig.put("circuit.breaker.failure.threshold", "3");
        validConfig.put("circuit.breaker.timeout.ms", "60000");
        validConfig.put("circuit.breaker.recovery.time.ms", "30000");
        
        // Should start without issues
        connector.start(validConfig);
        List<Map<String, String>> taskConfigs = connector.taskConfigs(1);
        assertThat(taskConfigs).hasSize(1);
        
        // Verify configuration is properly parsed
        assertThat(taskConfigs.get(0)).containsKey("http.api.base.url");
    }
}