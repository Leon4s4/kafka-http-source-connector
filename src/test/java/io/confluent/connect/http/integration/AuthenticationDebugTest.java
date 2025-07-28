package io.confluent.connect.http.integration;

import io.confluent.connect.http.HttpSourceConnector;
import io.confluent.connect.http.HttpSourceTask;
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
import okhttp3.mockwebserver.RecordedRequest;

import java.io.IOException;
import java.time.Duration;
import java.util.*;

/**
 * Debug test for authentication issues
 */
@Testcontainers
public class AuthenticationDebugTest {
    
    private static final Logger log = LoggerFactory.getLogger(AuthenticationDebugTest.class);
    
    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"))
            .withStartupTimeout(Duration.ofMinutes(3));
    
    private static MockWebServer mockApiServer;
    private HttpSourceConnector connector;
    private HttpSourceTask task;
    
    @BeforeAll
    static void setupTestEnvironment() throws IOException {
        mockApiServer = new MockWebServer();
        mockApiServer.start();
        log.info("Mock API Server: http://localhost:{}", mockApiServer.getPort());
    }
    
    @AfterAll
    static void teardownTestEnvironment() throws IOException {
        if (mockApiServer != null) {
            mockApiServer.shutdown();
        }
    }
    
    @BeforeEach
    void setupConnector() {
        connector = new HttpSourceConnector();
        task = new HttpSourceTask();
        
        mockApiServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"test\": \"data\"}"));
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
    @DisplayName("Debug Basic Authentication")
    void testBasicAuthDebug() throws Exception {
        log.info("Testing Basic Authentication - Debug");
        
        Map<String, String> config = createBaseConnectorConfig();
        config.put("auth.type", "BASIC");
        config.put("connection.user", "testuser");
        config.put("connection.password", "testpass");
        
        log.info("Config: {}", config);
        
        connector.start(config);
        List<Map<String, String>> taskConfigs = connector.taskConfigs(1);
        task.start(taskConfigs.get(0));
        
        List<SourceRecord> records = task.poll();
        log.info("Records returned: {}", records != null ? records.size() : "null");
        
        RecordedRequest request = mockApiServer.takeRequest();
        log.info("Request path: {}", request.getPath());
        log.info("Request headers: {}", request.getHeaders());
        
        String authHeader = request.getHeader("Authorization");
        log.info("Authorization header: {}", authHeader);
        
        if (authHeader == null) {
            log.error("❌ Authorization header is null!");
        } else {
            log.info("✅ Authorization header present: {}", authHeader);
        }
        
        log.info("✅ Basic Authentication debug completed");
    }
    
    @Test
    @DisplayName("Debug API Key Authentication")
    void testApiKeyAuthDebug() throws Exception {
        log.info("Testing API Key Authentication - Debug");
        
        // Enqueue the response EXACTLY like the failing test does
        mockApiServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"authenticated\": true, \"data\": [\"secure_item1\", \"secure_item2\"]}"));
        
        Map<String, String> config = createBaseConnectorConfig();
        config.put("auth.type", "API_KEY");
        config.put("api.key.value", "test-api-key-12345");
        config.put("api.key.location", "HEADER");
        config.put("api.key.name", "X-API-KEY"); // Using exact same as failing test
        
        log.info("Config: {}", config);
        
        connector.start(config);
        List<Map<String, String>> taskConfigs = connector.taskConfigs(1);
        task.start(taskConfigs.get(0));
        
        List<SourceRecord> records = task.poll();
        log.info("Records returned: {}", records != null ? records.size() : "null");
        
        // Now check the request like the failing test does
        RecordedRequest request = mockApiServer.takeRequest();
        log.info("Request path: {}", request.getPath());
        log.info("Request headers: {}", request.getHeaders());
        
        String apiKeyHeader = request.getHeader("X-API-KEY");
        log.info("X-API-KEY header: {}", apiKeyHeader);
        
        // Replicate the exact assertion from the failing test
        if (apiKeyHeader == null || !apiKeyHeader.equals("test-api-key-12345")) {
            log.error("❌ Expected X-API-KEY header to be 'test-api-key-12345' but was: " + apiKeyHeader);
            throw new AssertionError("Expected X-API-KEY header to be 'test-api-key-12345' but was: " + apiKeyHeader);
        } else {
            log.info("✅ X-API-KEY header present: {}", apiKeyHeader);
        }
        
        log.info("✅ API Key Authentication debug completed");
    }
    
    private Map<String, String> createBaseConnectorConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("name", "debug-http-source-test");
        config.put("connector.class", "io.confluent.connect.http.HttpSourceConnector");
        config.put("tasks.max", "1");
        config.put("http.api.base.url", "http://localhost:" + mockApiServer.getPort());
        config.put("http.apis.num", "1");  // Use the exact same format as the failing test
        config.put("api1.http.api.path", "/api/data");
        config.put("api1.topics", "test-topic");
        config.put("http.api.1.endpoint", "/api/data");
        config.put("http.api.1.topic", "test-topic");
        config.put("http.api.1.method", "GET");
        config.put("http.poll.interval.ms", "5000");
        config.put("auth.type", "NONE");
        return config;
    }
}
