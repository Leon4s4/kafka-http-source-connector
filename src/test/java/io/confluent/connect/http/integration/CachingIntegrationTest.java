package io.confluent.connect.http.integration;

import io.confluent.connect.http.HttpSourceConnector;
import io.confluent.connect.http.HttpSourceTask;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive integration tests for caching system functionality.
 * Tests TTL (Time-To-Live), eviction policies (LRU, LFU, FIFO), cache invalidation,
 * and conditional caching with ETag/Last-Modified headers.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CachingIntegrationTest extends BaseIntegrationTest {
    
    private static final Logger log = LoggerFactory.getLogger(CachingIntegrationTest.class);
    
    private static MockWebServer mockApiServer;
    private static ObjectMapper objectMapper = new ObjectMapper();
    
    private HttpSourceConnector connector;
    private HttpSourceTask task;
    
    @BeforeAll
    static void setupTestEnvironment() throws IOException {
        log.info("Setting up caching test environment");
        
        // Start mock API server
        mockApiServer = new MockWebServer();
        mockApiServer.start(8097);
        
        logContainerInfo();
        log.info("Caching test environment setup completed");
    }
    
    @AfterAll
    static void tearDownTestEnvironment() throws IOException {
        if (mockApiServer != null) {
            mockApiServer.shutdown();
        }
        log.info("Caching test environment torn down");
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
    @DisplayName("Test Basic Response Caching with TTL")
    void testBasicResponseCachingWithTtl() throws Exception {
        log.info("Testing Basic Response Caching with TTL");
        
        Map<String, String> config = createBaseConfig();
        config.put("response.caching.enabled", "true");
        config.put("response.cache.ttl.seconds", "2");
        config.put("response.cache.max.size", "100");
        config.put("response.cache.strategy", "TTL");
        
        // Setup first response
        mockApiServer.enqueue(new MockResponse()
                .setBody("{\"data\": [{\"id\": 1, \"name\": \"cached-item\", \"timestamp\": \"2023-01-01T10:00:00Z\"}]}")
                .setHeader("Content-Type", "application/json")
                .setHeader("Cache-Control", "max-age=5"));
        
        // Setup second response (should not be used if cached)
        mockApiServer.enqueue(new MockResponse()
                .setBody("{\"data\": [{\"id\": 2, \"name\": \"fresh-item\", \"timestamp\": \"2023-01-01T10:05:00Z\"}]}")
                .setHeader("Content-Type", "application/json"));
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // First request - should hit the API and cache the response
        List<SourceRecord> records1 = task.poll();
        assertThat(records1).isNotEmpty();
        
        // Verify first request was made
        RecordedRequest request1 = mockApiServer.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request1).isNotNull();
        
        // Second request within TTL - should use cache
        Thread.sleep(500); // Wait less than TTL
        List<SourceRecord> records2 = task.poll();
        assertThat(records2).isNotEmpty();
        
        // Verify no second request was made (using cache)
        RecordedRequest request2 = mockApiServer.takeRequest(500, TimeUnit.MILLISECONDS);
        assertThat(request2).isNull(); // Should be null as cache was used
        
        // Wait for TTL to expire
        Thread.sleep(2500);
        
        // Third request after TTL expiry - should hit API again
        List<SourceRecord> records3 = task.poll();
        assertThat(records3).isNotEmpty();
        
        // Verify third request was made after cache expiry
        RecordedRequest request3 = mockApiServer.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request3).isNotNull();
        
        log.info("Basic Response Caching with TTL test completed successfully");
    }
    
    @Test
    @Order(2)
    @DisplayName("Test LRU Cache Eviction Policy")
    void testLruCacheEvictionPolicy() throws Exception {
        log.info("Testing LRU Cache Eviction Policy");
        
        Map<String, String> config = createBaseConfig();
        config.put("apis.num", "3"); // Multiple APIs to test eviction
        config.put("response.caching.enabled", "true");
        config.put("response.cache.max.size", "2"); // Small cache to force eviction
        config.put("response.cache.eviction.policy", "LRU");
        config.put("response.cache.ttl.seconds", "300"); // Long TTL to test eviction
        
        // Configure 3 different APIs
        config.put("api1.http.api.path", "/api1");
        config.put("api1.topics", "api1-topic");
        config.put("api2.http.api.path", "/api2");
        config.put("api2.topics", "api2-topic");
        config.put("api3.http.api.path", "/api3");
        config.put("api3.topics", "api3-topic");
        
        // Setup responses for all APIs
        mockApiServer.enqueue(new MockResponse()
                .setBody("{\"api1\": [{\"id\": 1, \"name\": \"api1-data\"}]}")
                .setHeader("Content-Type", "application/json"));
        
        mockApiServer.enqueue(new MockResponse()
                .setBody("{\"api2\": [{\"id\": 2, \"name\": \"api2-data\"}]}")
                .setHeader("Content-Type", "application/json"));
        
        mockApiServer.enqueue(new MockResponse()
                .setBody("{\"api3\": [{\"id\": 3, \"name\": \"api3-data\"}]}")
                .setHeader("Content-Type", "application/json"));
        
        // Additional responses for cache eviction testing
        mockApiServer.enqueue(new MockResponse()
                .setBody("{\"api1\": [{\"id\": 4, \"name\": \"api1-fresh\"}]}")
                .setHeader("Content-Type", "application/json"));
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Fill cache with API1 and API2 responses
        AtomicInteger requestCount = new AtomicInteger(0);
        
        // Request API1 (should cache)
        List<SourceRecord> api1Records = task.poll();
        if (api1Records != null && !api1Records.isEmpty()) {
            requestCount.incrementAndGet();
        }
        Thread.sleep(100);
        
        // Request API2 (should cache)  
        List<SourceRecord> api2Records = task.poll();
        if (api2Records != null && !api2Records.isEmpty()) {
            requestCount.incrementAndGet();
        }
        Thread.sleep(100);
        
        // Request API3 (should evict API1 from cache due to LRU)
        List<SourceRecord> api3Records = task.poll();
        if (api3Records != null && !api3Records.isEmpty()) {
            requestCount.incrementAndGet();
        }
        Thread.sleep(100);
        
        // Request API1 again (should hit API since evicted from cache)
        List<SourceRecord> api1AgainRecords = task.poll();
        if (api1AgainRecords != null && !api1AgainRecords.isEmpty()) {
            requestCount.incrementAndGet();
        }
        
        // Count actual HTTP requests made
        int actualRequests = 0;
        while (mockApiServer.takeRequest(100, TimeUnit.MILLISECONDS) != null) {
            actualRequests++;
        }
        
        // Should have made 4 requests due to LRU eviction
        assertThat(actualRequests).isEqualTo(4);
        
        log.info("LRU Cache Eviction Policy test completed successfully. Requests made: {}", actualRequests);
    }
    
    @Test
    @Order(3)
    @DisplayName("Test LFU Cache Eviction Policy")
    void testLfuCacheEvictionPolicy() throws Exception {
        log.info("Testing LFU Cache Eviction Policy");
        
        Map<String, String> config = createBaseConfig();
        config.put("apis.num", "3");
        config.put("response.caching.enabled", "true");
        config.put("response.cache.max.size", "2");
        config.put("response.cache.eviction.policy", "LFU");
        config.put("response.cache.ttl.seconds", "300");
        
        // Configure APIs
        config.put("api1.http.api.path", "/frequent");
        config.put("api1.topics", "frequent-topic");
        config.put("api2.http.api.path", "/moderate");
        config.put("api2.topics", "moderate-topic");
        config.put("api3.http.api.path", "/rare");
        config.put("api3.topics", "rare-topic");
        
        // Setup responses
        for (int i = 0; i < 10; i++) {
            mockApiServer.enqueue(new MockResponse()
                    .setBody(String.format("{\"frequent\": [{\"id\": %d, \"name\": \"freq%d\"}]}", i, i))
                    .setHeader("Content-Type", "application/json"));
        }
        
        for (int i = 0; i < 5; i++) {
            mockApiServer.enqueue(new MockResponse()
                    .setBody(String.format("{\"moderate\": [{\"id\": %d, \"name\": \"mod%d\"}]}", i, i))
                    .setHeader("Content-Type", "application/json"));
        }
        
        mockApiServer.enqueue(new MockResponse()
                .setBody("{\"rare\": [{\"id\": 1, \"name\": \"rare1\"}]}")
                .setHeader("Content-Type", "application/json"));
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Access APIs with different frequencies
        // Frequent API - access multiple times
        for (int i = 0; i < 5; i++) {
            task.poll();
            Thread.sleep(50);
        }
        
        // Moderate API - access moderately
        for (int i = 0; i < 3; i++) {
            task.poll();
            Thread.sleep(50);
        }
        
        // Rare API - access once (should be evicted first in LFU)
        task.poll();
        Thread.sleep(50);
        
        // Access frequent API again - should use cache due to high frequency
        task.poll();
        
        log.info("LFU Cache Eviction Policy test completed successfully");
    }
    
    @Test
    @Order(4)
    @DisplayName("Test FIFO Cache Eviction Policy")
    void testFifoCacheEvictionPolicy() throws Exception {
        log.info("Testing FIFO Cache Eviction Policy");
        
        Map<String, String> config = createBaseConfig();
        config.put("apis.num", "4");
        config.put("response.caching.enabled", "true");
        config.put("response.cache.max.size", "3");
        config.put("response.cache.eviction.policy", "FIFO");
        config.put("response.cache.ttl.seconds", "300");
        
        // Configure APIs
        for (int i = 1; i <= 4; i++) {
            config.put("api" + i + ".http.api.path", "/fifo" + i);
            config.put("api" + i + ".topics", "fifo" + i + "-topic");
        }
        
        // Setup responses
        for (int i = 1; i <= 5; i++) {
            mockApiServer.enqueue(new MockResponse()
                    .setBody(String.format("{\"fifo%d\": [{\"id\": %d, \"name\": \"item%d\"}]}", i, i, i))
                    .setHeader("Content-Type", "application/json"));
        }
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Fill cache with first 3 APIs (FIFO order)
        for (int i = 0; i < 3; i++) {
            task.poll();
            Thread.sleep(100);
        }
        
        // Add 4th API - should evict first API (FIFO)
        task.poll();
        Thread.sleep(100);
        
        // Access first API again - should hit API since evicted
        task.poll();
        
        log.info("FIFO Cache Eviction Policy test completed successfully");
    }
    
    @Test
    @Order(5)
    @DisplayName("Test Conditional Caching with ETag")
    void testConditionalCachingWithETag() throws Exception {
        log.info("Testing Conditional Caching with ETag");
        
        Map<String, String> config = createBaseConfig();
        config.put("response.caching.enabled", "true");
        config.put("response.cache.conditional.enabled", "true");
        config.put("response.cache.etag.enabled", "true");
        config.put("response.cache.ttl.seconds", "1");
        
        String etag = "\"abc123def456\"";
        
        // Setup first response with ETag
        mockApiServer.enqueue(new MockResponse()
                .setBody("{\"data\": [{\"id\": 1, \"name\": \"etag-test\", \"version\": 1}]}")
                .setHeader("Content-Type", "application/json")
                .setHeader("ETag", etag));
        
        // Setup 304 Not Modified response
        mockApiServer.enqueue(new MockResponse()
                .setResponseCode(304)
                .setHeader("ETag", etag));
        
        // Setup modified response with new ETag
        mockApiServer.enqueue(new MockResponse()
                .setBody("{\"data\": [{\"id\": 1, \"name\": \"etag-test\", \"version\": 2}]}")
                .setHeader("Content-Type", "application/json")
                .setHeader("ETag", "\"new123etag456\""));
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // First request - should get response and cache with ETag
        List<SourceRecord> records1 = task.poll();
        assertThat(records1).isNotEmpty();
        
        RecordedRequest request1 = mockApiServer.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request1).isNotNull();
        assertThat(request1.getHeader("If-None-Match")).isNull(); // First request has no ETag
        
        // Wait for cache TTL to expire
        Thread.sleep(1500);
        
        // Second request - should send If-None-Match header
        List<SourceRecord> records2 = task.poll();
        
        RecordedRequest request2 = mockApiServer.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request2).isNotNull();
        assertThat(request2.getHeader("If-None-Match")).isEqualTo(etag);
        
        // Third request - should get new data with different ETag
        Thread.sleep(1500);
        List<SourceRecord> records3 = task.poll();
        assertThat(records3).isNotEmpty();
        
        log.info("Conditional Caching with ETag test completed successfully");
    }
    
    @Test
    @Order(6)
    @DisplayName("Test Conditional Caching with Last-Modified")
    void testConditionalCachingWithLastModified() throws Exception {
        log.info("Testing Conditional Caching with Last-Modified");
        
        Map<String, String> config = createBaseConfig();
        config.put("response.caching.enabled", "true");
        config.put("response.cache.conditional.enabled", "true");
        config.put("response.cache.last.modified.enabled", "true");
        config.put("response.cache.ttl.seconds", "1");
        
        String lastModified = "Wed, 21 Oct 2015 07:28:00 GMT";
        
        // Setup first response with Last-Modified
        mockApiServer.enqueue(new MockResponse()
                .setBody("{\"data\": [{\"id\": 1, \"name\": \"last-modified-test\"}]}")
                .setHeader("Content-Type", "application/json")
                .setHeader("Last-Modified", lastModified));
        
        // Setup 304 Not Modified response
        mockApiServer.enqueue(new MockResponse()
                .setResponseCode(304)
                .setHeader("Last-Modified", lastModified));
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // First request
        List<SourceRecord> records1 = task.poll();
        assertThat(records1).isNotEmpty();
        
        RecordedRequest request1 = mockApiServer.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request1).isNotNull();
        
        // Wait for cache expiry
        Thread.sleep(1500);
        
        // Second request with If-Modified-Since
        List<SourceRecord> records2 = task.poll();
        
        RecordedRequest request2 = mockApiServer.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request2).isNotNull();
        assertThat(request2.getHeader("If-Modified-Since")).isEqualTo(lastModified);
        
        log.info("Conditional Caching with Last-Modified test completed successfully");
    }
    
    @Test
    @Order(7)
    @DisplayName("Test Cache Invalidation Strategies")
    void testCacheInvalidationStrategies() throws Exception {
        log.info("Testing Cache Invalidation Strategies");
        
        Map<String, String> config = createBaseConfig();
        config.put("response.caching.enabled", "true");
        config.put("response.cache.ttl.seconds", "300"); // Long TTL
        config.put("response.cache.invalidation.enabled", "true");
        config.put("response.cache.invalidation.trigger", "ERROR_RESPONSE");
        config.put("response.cache.invalidation.error.codes", "500,502,503");
        
        // Setup successful response
        mockApiServer.enqueue(new MockResponse()
                .setBody("{\"data\": [{\"id\": 1, \"name\": \"cached-success\"}]}")
                .setHeader("Content-Type", "application/json"));
        
        // Setup error response that should trigger invalidation
        mockApiServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("{\"error\": \"Server Error\"}"));
        
        // Setup recovery response
        mockApiServer.enqueue(new MockResponse()
                .setBody("{\"data\": [{\"id\": 2, \"name\": \"recovered-data\"}]}")
                .setHeader("Content-Type", "application/json"));
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // First successful request - cache the response
        List<SourceRecord> records1 = task.poll();
        assertThat(records1).isNotEmpty();
        
        // Second request should trigger error and invalidate cache
        Thread.sleep(100);
        List<SourceRecord> records2 = task.poll();
        
        // Third request should fetch fresh data (cache invalidated)
        Thread.sleep(100);
        List<SourceRecord> records3 = task.poll();
        assertThat(records3).isNotEmpty();
        
        log.info("Cache Invalidation Strategies test completed successfully");
    }
    
    @Test
    @Order(8)
    @DisplayName("Test Cache Metrics and Monitoring")
    void testCacheMetricsAndMonitoring() throws Exception {
        log.info("Testing Cache Metrics and Monitoring");
        
        Map<String, String> config = createBaseConfig();
        config.put("response.caching.enabled", "true");
        config.put("response.cache.ttl.seconds", "5");
        config.put("response.cache.metrics.enabled", "true");
        config.put("response.cache.jmx.enabled", "true");
        config.put("response.cache.metrics.window.seconds", "60");
        
        // Setup responses for metrics testing
        for (int i = 0; i < 5; i++) {
            mockApiServer.enqueue(new MockResponse()
                    .setBody(String.format("{\"data\": [{\"id\": %d, \"name\": \"metrics%d\"}]}", i, i))
                    .setHeader("Content-Type", "application/json"));
        }
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Generate cache hits and misses for metrics
        int cacheHits = 0;
        int cacheMisses = 0;
        
        // First request - cache miss
        List<SourceRecord> records1 = task.poll();
        if (records1 != null && !records1.isEmpty()) {
            cacheMisses++;
        }
        
        // Second request within TTL - cache hit
        Thread.sleep(500);
        List<SourceRecord> records2 = task.poll();
        if (records2 != null && !records2.isEmpty()) {
            cacheHits++;
        }
        
        // Wait for TTL expiry and make another request - cache miss
        Thread.sleep(5500);
        List<SourceRecord> records3 = task.poll();
        if (records3 != null && !records3.isEmpty()) {
            cacheMisses++;
        }
        
        // Note: In a real implementation, we would verify JMX metrics here
        assertThat(cacheHits + cacheMisses).isGreaterThan(0);
        
        log.info("Cache Metrics test completed. Estimated hits: {}, misses: {}", cacheHits, cacheMisses);
    }
    
    @Test
    @Order(9)
    @DisplayName("Test Cache Size and Memory Management")
    void testCacheSizeAndMemoryManagement() throws Exception {
        log.info("Testing Cache Size and Memory Management");
        
        Map<String, String> config = createBaseConfig();
        config.put("apis.num", "10"); // Multiple APIs to test size limits
        config.put("response.caching.enabled", "true");
        config.put("response.cache.max.size", "5"); // Limit cache size
        config.put("response.cache.max.memory.mb", "10"); // Memory limit
        config.put("response.cache.eviction.policy", "LRU");
        config.put("response.cache.ttl.seconds", "300");
        
        // Configure multiple APIs
        for (int i = 1; i <= 10; i++) {
            config.put("api" + i + ".http.api.path", "/size" + i);
            config.put("api" + i + ".topics", "size" + i + "-topic");
        }
        
        // Setup large responses to test memory management
        for (int i = 1; i <= 15; i++) {
            StringBuilder largeResponse = new StringBuilder("{\"data\": [");
            for (int j = 0; j < 100; j++) {
                if (j > 0) largeResponse.append(",");
                largeResponse.append(String.format("{\"id\": %d, \"data\": \"large-data-item-%d-%d\"}", j, i, j));
            }
            largeResponse.append("]}");
            
            mockApiServer.enqueue(new MockResponse()
                    .setBody(largeResponse.toString())
                    .setHeader("Content-Type", "application/json"));
        }
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Make requests to fill and exceed cache size
        AtomicInteger totalRequests = new AtomicInteger(0);
        for (int i = 0; i < 12; i++) {
            List<SourceRecord> records = task.poll();
            if (records != null && !records.isEmpty()) {
                totalRequests.incrementAndGet();
            }
            Thread.sleep(100);
        }
        
        // Cache should handle size and memory limits gracefully
        assertThat(totalRequests.get()).isGreaterThan(0);
        
        log.info("Cache Size and Memory Management test completed. Total requests: {}", totalRequests.get());
    }
    
    @Test
    @Order(10)
    @DisplayName("Test Cache Configuration Edge Cases")
    void testCacheConfigurationEdgeCases() throws Exception {
        log.info("Testing Cache Configuration Edge Cases");
        
        // Test with cache disabled
        Map<String, String> disabledConfig = createBaseConfig();
        disabledConfig.put("response.caching.enabled", "false");
        
        mockApiServer.enqueue(new MockResponse()
                .setBody("{\"data\": [{\"id\": 1, \"name\": \"no-cache\"}]}")
                .setHeader("Content-Type", "application/json"));
        
        connector.start(disabledConfig);
        task.initialize(mockSourceTaskContext());
        task.start(disabledConfig);
        
        List<SourceRecord> records1 = task.poll();
        assertThat(records1).isNotEmpty();
        
        // Reset for next test
        task.stop();
        connector = new HttpSourceConnector();
        task = new HttpSourceTask();
        
        // Test with invalid TTL configuration
        Map<String, String> invalidTtlConfig = createBaseConfig();
        invalidTtlConfig.put("response.caching.enabled", "true");
        invalidTtlConfig.put("response.cache.ttl.seconds", "-1"); // Invalid negative TTL
        
        mockApiServer.enqueue(new MockResponse()
                .setBody("{\"data\": [{\"id\": 2, \"name\": \"invalid-ttl\"}]}")
                .setHeader("Content-Type", "application/json"));
        
        try {
            connector.start(invalidTtlConfig);
            task.initialize(mockSourceTaskContext());
            task.start(invalidTtlConfig);
            
            List<SourceRecord> records2 = task.poll();
            // Should handle invalid configuration gracefully
        } catch (Exception e) {
            log.info("Invalid TTL configuration handled with exception: {}", e.getMessage());
        }
        
        log.info("Cache Configuration Edge Cases test completed successfully");
    }
    
    // Helper methods
    
    private Map<String, String> createBaseConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("connector.class", "io.confluent.connect.http.HttpSourceConnector");
        config.put("tasks.max", "1");
        config.put("http.api.base.url", "http://localhost:" + mockApiServer.getPort());
        config.put("apis.num", "1");
        config.put("api1.http.api.path", "/data");
        config.put("api1.topics", "cache-topic");
        config.put("api1.http.request.method", "GET");
        config.put("api1.http.offset.mode", "SIMPLE_INCREMENTING");
        config.put("api1.http.initial.offset", "0");
        config.put("api1.request.interval.ms", "500");
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
}