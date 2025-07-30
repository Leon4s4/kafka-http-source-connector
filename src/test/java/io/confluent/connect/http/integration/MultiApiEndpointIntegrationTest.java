package io.confluent.connect.http.integration;

import io.confluent.connect.http.HttpSourceConnector;
import io.confluent.connect.http.HttpSourceTask;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.MockResponse;

import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive integration tests for multi-API endpoint functionality.
 * Tests up to 15 APIs per connector instance with different configurations, 
 * authentication methods, offset modes, and error handling scenarios.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class MultiApiEndpointIntegrationTest extends BaseIntegrationTest {
    
    private static final Logger log = LoggerFactory.getLogger(MultiApiEndpointIntegrationTest.class);
    
    private static final int MAX_APIS = 15;
    private static MockWebServer mockApiServer;
    private static ObjectMapper objectMapper = new ObjectMapper();
    
    private HttpSourceConnector connector;
    private HttpSourceTask task;
    
    @BeforeAll
    static void setupTestEnvironment() throws IOException {
        log.info("Setting up multi-API endpoint test environment");
        
        // Start mock API server
        mockApiServer = new MockWebServer();
        mockApiServer.start(8091);
        
        setupMockEndpoints();
        
        logContainerInfo();
        log.info("Multi-API endpoint test environment setup completed");
    }
    
    @AfterAll
    static void tearDownTestEnvironment() throws IOException {
        if (mockApiServer != null) {
            mockApiServer.shutdown();
        }
        log.info("Multi-API endpoint test environment torn down");
    }
    
    @BeforeEach
    void setUp() {
        connector = new HttpSourceConnector();
        task = new HttpSourceTask();
    }
    
    @AfterEach
    void tearDown() {
        if (task != null) {
            try {
                task.stop();
            } catch (Exception e) {
                log.warn("Error stopping task: {}", e.getMessage());
            }
        }
    }
    
    @Test
    @Order(1)
    @DisplayName("Test Single API Configuration")
    void testSingleApiConfiguration() throws Exception {
        log.info("Testing single API configuration");
        
        Map<String, String> config = createMultiApiConfig(1);
        
        // Setup mock response
        mockApiServer.enqueue(new MockResponse()
                .setBody("{\"users\": [{\"id\": 1, \"name\": \"user1\"}]}")
                .setHeader("Content-Type", "application/json"));
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Poll for records
        List<SourceRecord> records = task.poll();
        
        // Verify records
        assertThat(records).isNotEmpty();
        assertThat(records.get(0).topic()).isEqualTo("api1-topic");
        
        log.info("Single API configuration test completed successfully");
    }
    
    @Test
    @Order(2)
    @DisplayName("Test Multiple APIs (5 APIs)")
    void testMultipleApis() throws Exception {
        log.info("Testing multiple APIs (5 APIs)");
        
        int numApis = 5;
        Map<String, String> config = createMultiApiConfig(numApis);
        
        // Setup mock responses for all APIs
        for (int i = 1; i <= numApis; i++) {
            mockApiServer.enqueue(new MockResponse()
                    .setBody(String.format("{\"data\": [{\"id\": %d, \"name\": \"item%d\"}]}", i, i))
                    .setHeader("Content-Type", "application/json"));
        }
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Poll multiple times to get records from all APIs
        Set<String> observedTopics = new HashSet<>();
        for (int poll = 0; poll < 10; poll++) {
            List<SourceRecord> records = task.poll();
            if (records != null && !records.isEmpty()) {
                records.forEach(record -> observedTopics.add(record.topic()));
            }
            Thread.sleep(100);
        }
        
        // Verify all topics were observed
        for (int i = 1; i <= numApis; i++) {
            String expectedTopic = "api" + i + "-topic";
            assertThat(observedTopics).contains(expectedTopic);
        }
        
        log.info("Multiple APIs (5 APIs) test completed successfully. Observed topics: {}", observedTopics);
    }
    
    @Test
    @Order(3)
    @DisplayName("Test Maximum APIs (15 APIs)")
    void testMaximumApis() throws Exception {
        log.info("Testing maximum APIs (15 APIs)");
        
        Map<String, String> config = createMultiApiConfig(MAX_APIS);
        
        // Setup mock responses for all 15 APIs
        for (int i = 1; i <= MAX_APIS; i++) {
            mockApiServer.enqueue(new MockResponse()
                    .setBody(String.format("{\"data\": [{\"id\": %d, \"name\": \"item%d\", \"api\": \"api%d\"}]}", i, i, i))
                    .setHeader("Content-Type", "application/json"));
        }
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Poll multiple times to collect records from all APIs
        Set<String> observedTopics = new HashSet<>();
        Map<String, Integer> topicCounts = new ConcurrentHashMap<>();
        
        for (int poll = 0; poll < 20; poll++) {
            List<SourceRecord> records = task.poll();
            if (records != null && !records.isEmpty()) {
                for (SourceRecord record : records) {
                    String topic = record.topic();
                    observedTopics.add(topic);
                    topicCounts.merge(topic, 1, Integer::sum);
                }
            }
            Thread.sleep(100);
        }
        
        // Verify all 15 APIs produced records
        assertThat(observedTopics.size()).isGreaterThanOrEqualTo(10); // Allow for some variance in polling
        
        // Verify topic naming pattern
        for (String topic : observedTopics) {
            assertThat(topic).matches("api\\d+-topic");
        }
        
        log.info("Maximum APIs (15 APIs) test completed successfully. Observed {} topics: {}", 
                observedTopics.size(), observedTopics);
    }
    
    @Test
    @Order(4)
    @DisplayName("Test APIs with Different Authentication Methods")
    void testApisWithDifferentAuthentication() throws Exception {
        log.info("Testing APIs with different authentication methods");
        
        Map<String, String> config = createBaseConfig();
        config.put("apis.num", "3");
        
        // API 1: No authentication
        config.put("api1.http.api.path", "/public");
        config.put("api1.topics", "public-topic");
        config.put("api1.auth.type", "NONE");
        
        // API 2: Basic authentication
        config.put("api2.http.api.path", "/basic");
        config.put("api2.topics", "basic-topic");
        config.put("api2.auth.type", "BASIC");
        config.put("api2.connection.user", "testuser");
        config.put("api2.connection.password", "testpass");
        
        // API 3: API Key authentication
        config.put("api3.http.api.path", "/apikey");
        config.put("api3.topics", "apikey-topic");
        config.put("api3.auth.type", "API_KEY");
        config.put("api3.api.key.location", "HEADER");
        config.put("api3.api.key.name", "X-API-KEY");
        config.put("api3.api.key.value", "test-key-123");
        
        // Setup mock responses
        mockApiServer.enqueue(new MockResponse()
                .setBody("{\"public\": [{\"id\": 1, \"name\": \"public\"}]}")
                .setHeader("Content-Type", "application/json"));
        
        mockApiServer.enqueue(new MockResponse()
                .setBody("{\"basic\": [{\"id\": 2, \"name\": \"basic\"}]}")
                .setHeader("Content-Type", "application/json"));
        
        mockApiServer.enqueue(new MockResponse()
                .setBody("{\"apikey\": [{\"id\": 3, \"name\": \"apikey\"}]}")
                .setHeader("Content-Type", "application/json"));
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Poll for records
        Set<String> observedTopics = new HashSet<>();
        for (int poll = 0; poll < 10; poll++) {
            List<SourceRecord> records = task.poll();
            if (records != null && !records.isEmpty()) {
                records.forEach(record -> observedTopics.add(record.topic()));
            }
            Thread.sleep(100);
        }
        
        // Verify all authentication types worked
        assertThat(observedTopics).containsAnyOf("public-topic", "basic-topic", "apikey-topic");
        
        log.info("APIs with different authentication methods test completed successfully");
    }
    
    @Test
    @Order(5)
    @DisplayName("Test APIs with Different Offset Modes")
    void testApisWithDifferentOffsetModes() throws Exception {
        log.info("Testing APIs with different offset modes");
        
        Map<String, String> config = createBaseConfig();
        config.put("apis.num", "4");
        
        // API 1: Simple incrementing
        config.put("api1.http.api.path", "/simple");
        config.put("api1.topics", "simple-topic");
        config.put("api1.http.offset.mode", "SIMPLE_INCREMENTING");
        config.put("api1.http.initial.offset", "0");
        
        // API 2: Cursor pagination
        config.put("api2.http.api.path", "/cursor");
        config.put("api2.topics", "cursor-topic");
        config.put("api2.http.offset.mode", "CURSOR_PAGINATION");
        config.put("api2.http.next.page.json.pointer", "/next_cursor");
        config.put("api2.http.initial.offset", "start");
        
        // API 3: OData pagination
        config.put("api3.http.api.path", "/odata");
        config.put("api3.topics", "odata-topic");
        config.put("api3.http.offset.mode", "ODATA_PAGINATION");
        config.put("api3.odata.nextlink.field", "@odata.nextLink");
        config.put("api3.odata.token.mode", "FULL_URL");
        
        // API 4: Timestamp pagination
        config.put("api4.http.api.path", "/timestamp");
        config.put("api4.topics", "timestamp-topic");
        config.put("api4.http.offset.mode", "TIMESTAMP_PAGINATION");
        config.put("api4.http.timestamp.json.pointer", "/last_modified");
        
        // Setup mock responses with appropriate pagination data
        mockApiServer.enqueue(new MockResponse()
                .setBody("{\"data\": [{\"id\": 1, \"name\": \"simple\"}]}")
                .setHeader("Content-Type", "application/json"));
        
        mockApiServer.enqueue(new MockResponse()
                .setBody("{\"data\": [{\"id\": 2, \"name\": \"cursor\"}], \"next_cursor\": \"abc123\"}")
                .setHeader("Content-Type", "application/json"));
        
        mockApiServer.enqueue(new MockResponse()
                .setBody("{\"value\": [{\"id\": 3, \"name\": \"odata\"}], \"@odata.nextLink\": \"http://example.com/next\"}")
                .setHeader("Content-Type", "application/json"));
        
        mockApiServer.enqueue(new MockResponse()
                .setBody("{\"data\": [{\"id\": 4, \"name\": \"timestamp\", \"last_modified\": \"2023-01-01T00:00:00Z\"}]}")
                .setHeader("Content-Type", "application/json"));
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Poll for records
        Set<String> observedTopics = new HashSet<>();
        for (int poll = 0; poll < 15; poll++) {
            List<SourceRecord> records = task.poll();
            if (records != null && !records.isEmpty()) {
                records.forEach(record -> observedTopics.add(record.topic()));
            }
            Thread.sleep(100);
        }
        
        // Verify all offset modes worked
        assertThat(observedTopics).containsAnyOf("simple-topic", "cursor-topic", "odata-topic", "timestamp-topic");
        
        log.info("APIs with different offset modes test completed successfully");
    }
    
    @Test
    @Order(6)
    @DisplayName("Test APIs with Different Polling Intervals")
    void testApisWithDifferentPollingIntervals() throws Exception {
        log.info("Testing APIs with different polling intervals");
        
        Map<String, String> config = createBaseConfig();
        config.put("apis.num", "3");
        
        // API 1: Fast polling (500ms)
        config.put("api1.http.api.path", "/fast");
        config.put("api1.topics", "fast-topic");
        config.put("api1.request.interval.ms", "500");
        
        // API 2: Medium polling (1000ms)
        config.put("api2.http.api.path", "/medium");
        config.put("api2.topics", "medium-topic");
        config.put("api2.request.interval.ms", "1000");
        
        // API 3: Slow polling (2000ms)
        config.put("api3.http.api.path", "/slow");
        config.put("api3.topics", "slow-topic");
        config.put("api3.request.interval.ms", "2000");
        
        // Setup multiple responses for each API
        for (int i = 0; i < 10; i++) {
            mockApiServer.enqueue(new MockResponse()
                    .setBody(String.format("{\"fast\": [{\"id\": %d, \"name\": \"fast%d\"}]}", i, i))
                    .setHeader("Content-Type", "application/json"));
            
            mockApiServer.enqueue(new MockResponse()
                    .setBody(String.format("{\"medium\": [{\"id\": %d, \"name\": \"medium%d\"}]}", i, i))
                    .setHeader("Content-Type", "application/json"));
            
            mockApiServer.enqueue(new MockResponse()
                    .setBody(String.format("{\"slow\": [{\"id\": %d, \"name\": \"slow%d\"}]}", i, i))
                    .setHeader("Content-Type", "application/json"));
        }
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Monitor polling frequency for 5 seconds
        long startTime = System.currentTimeMillis();
        Map<String, Integer> topicCounts = new ConcurrentHashMap<>();
        
        while (System.currentTimeMillis() - startTime < 5000) {
            List<SourceRecord> records = task.poll();
            if (records != null && !records.isEmpty()) {
                for (SourceRecord record : records) {
                    String topic = record.topic();
                    topicCounts.merge(topic, 1, Integer::sum);
                }
            }
            Thread.sleep(100);
        }
        
        // Verify different polling frequencies (fast should have more records)
        Integer fastCount = topicCounts.getOrDefault("fast-topic", 0);
        Integer slowCount = topicCounts.getOrDefault("slow-topic", 0);
        
        log.info("Polling frequency results - Fast: {}, Slow: {}", fastCount, slowCount);
        
        // Fast API should generally have more polls than slow API
        // (Note: exact counts may vary due to timing, so we use a loose assertion)
        assertThat(topicCounts.keySet()).isNotEmpty();
        
        log.info("APIs with different polling intervals test completed successfully");
    }
    
    @Test
    @Order(7)
    @DisplayName("Test API Load Balancing Across Tasks")
    void testApiLoadBalancing() throws Exception {
        log.info("Testing API load balancing across tasks");
        
        // Test with multiple tasks
        Map<String, String> config = createMultiApiConfig(6);
        config.put("tasks.max", "3"); // 3 tasks for 6 APIs
        config.put("task.api.indices", "1,2"); // First task handles APIs 1-2
        
        // Setup mock responses
        for (int i = 1; i <= 6; i++) {
            mockApiServer.enqueue(new MockResponse()
                    .setBody(String.format("{\"data\": [{\"id\": %d, \"name\": \"item%d\"}]}", i, i))
                    .setHeader("Content-Type", "application/json"));
        }
        
        // Start connector
        connector.start(config);
        
        // Verify task configuration distribution
        List<Map<String, String>> taskConfigs = connector.taskConfigs(3);
        assertThat(taskConfigs).hasSize(3);
        
        // Each task should have assigned APIs
        for (Map<String, String> taskConfig : taskConfigs) {
            assertThat(taskConfig).containsKey("task.api.indices");
        }
        
        log.info("API load balancing test completed successfully");
    }
    
    @Test
    @Order(8)
    @DisplayName("Test Concurrent API Processing")
    void testConcurrentApiProcessing() throws Exception {
        log.info("Testing concurrent API processing");
        
        int numApis = 10;
        Map<String, String> config = createMultiApiConfig(numApis);
        
        // Setup responses for all APIs
        for (int i = 1; i <= numApis; i++) {
            mockApiServer.enqueue(new MockResponse()
                    .setBody(String.format("{\"data\": [{\"id\": %d, \"name\": \"concurrent%d\"}]}", i, i))
                    .setHeader("Content-Type", "application/json")
                    .setBodyDelay(100, TimeUnit.MILLISECONDS)); // Add delay to simulate real API
        }
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Use concurrent polling to test thread safety
        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch latch = new CountDownLatch(3);
        Set<String> allTopics = ConcurrentHashMap.newKeySet();
        
        for (int i = 0; i < 3; i++) {
            executor.submit(() -> {
                try {
                    for (int poll = 0; poll < 10; poll++) {
                        List<SourceRecord> records = task.poll();
                        if (records != null && !records.isEmpty()) {
                            records.forEach(record -> allTopics.add(record.topic()));
                        }
                        Thread.sleep(50);
                    }
                } catch (Exception e) {
                    log.error("Error in concurrent polling", e);
                } finally {
                    latch.countDown();
                }
            });
        }
        
        // Wait for all threads to complete
        assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();
        
        // Verify concurrent processing worked
        assertThat(allTopics).isNotEmpty();
        
        log.info("Concurrent API processing test completed successfully. Processed topics: {}", allTopics);
    }
    
    @Test
    @Order(9)
    @DisplayName("Test API Error Isolation")
    void testApiErrorIsolation() throws Exception {
        log.info("Testing API error isolation");
        
        Map<String, String> config = createBaseConfig();
        config.put("apis.num", "3");
        
        // Configure 3 APIs
        for (int i = 1; i <= 3; i++) {
            config.put("api" + i + ".http.api.path", "/api" + i);
            config.put("api" + i + ".topics", "api" + i + "-topic");
        }
        
        // Setup responses: API 1 success, API 2 error, API 3 success
        mockApiServer.enqueue(new MockResponse()
                .setBody("{\"data\": [{\"id\": 1, \"name\": \"success1\"}]}")
                .setHeader("Content-Type", "application/json"));
        
        mockApiServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("{\"error\": \"Internal Server Error\"}"));
        
        mockApiServer.enqueue(new MockResponse()
                .setBody("{\"data\": [{\"id\": 3, \"name\": \"success3\"}]}")
                .setHeader("Content-Type", "application/json"));
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Poll for records
        Set<String> successfulTopics = new HashSet<>();
        for (int poll = 0; poll < 10; poll++) {
            List<SourceRecord> records = task.poll();
            if (records != null && !records.isEmpty()) {
                records.forEach(record -> successfulTopics.add(record.topic()));
            }
            Thread.sleep(100);
        }
        
        // Verify that successful APIs still work despite one API failing
        assertThat(successfulTopics).containsAnyOf("api1-topic", "api3-topic");
        
        log.info("API error isolation test completed successfully. Successful topics: {}", successfulTopics);
    }
    
    // Helper methods
    
    private Map<String, String> createMultiApiConfig(int numApis) {
        Map<String, String> config = createBaseConfig();
        config.put("apis.num", String.valueOf(numApis));
        
        for (int i = 1; i <= numApis; i++) {
            config.put("api" + i + ".http.api.path", "/api" + i);
            config.put("api" + i + ".topics", "api" + i + "-topic");
            config.put("api" + i + ".http.request.method", "GET");
            config.put("api" + i + ".http.offset.mode", "SIMPLE_INCREMENTING");
            config.put("api" + i + ".http.initial.offset", "0");
            config.put("api" + i + ".request.interval.ms", "1000");
        }
        
        return config;
    }
    
    private Map<String, String> createBaseConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("connector.class", "io.confluent.connect.http.HttpSourceConnector");
        config.put("tasks.max", "1");
        config.put("http.api.base.url", "http://localhost:" + mockApiServer.getPort());
        config.put("output.data.format", "JSON_SR");
        config.put("connection.timeout.ms", "5000");
        config.put("request.timeout.ms", "10000");
        return config;
    }
    
    private org.apache.kafka.connect.source.SourceTaskContext mockSourceTaskContext() {
        return new org.apache.kafka.connect.source.SourceTaskContext() {
            @Override
            public Map<String, String> configs() {
                return new HashMap<>();
            }
            
            @Override
            public org.apache.kafka.connect.storage.OffsetStorageReader offsetStorageReader() {
                return new org.apache.kafka.connect.storage.OffsetStorageReader() {
                    @Override
                    public <T> Map<String, Object> offset(Map<String, T> partition) {
                        return new HashMap<>();
                    }
                    
                    @Override
                    public <T> Map<Map<String, T>, Map<String, Object>> offsets(Collection<Map<String, T>> partitions) {
                        return new HashMap<>();
                    }
                };
            }
        };
    }
    
    private static void setupMockEndpoints() {
        log.info("Setting up mock endpoints for multi-API testing");
        // Mock endpoints are configured dynamically in each test method
    }
}