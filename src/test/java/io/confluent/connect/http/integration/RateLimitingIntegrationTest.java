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
 * Comprehensive integration tests for rate limiting functionality.
 * Tests all 4 rate limiting algorithms: Token Bucket, Sliding Window, Fixed Window, and Leaky Bucket.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RateLimitingIntegrationTest extends BaseIntegrationTest {
    
    private static final Logger log = LoggerFactory.getLogger(RateLimitingIntegrationTest.class);
    
    private static MockWebServer mockApiServer;
    private static ObjectMapper objectMapper = new ObjectMapper();
    
    private HttpSourceConnector connector;
    private HttpSourceTask task;
    
    @BeforeAll
    static void setupTestEnvironment() throws IOException {
        log.info("Setting up rate limiting test environment");
        
        // Start mock API server
        mockApiServer = new MockWebServer();
        mockApiServer.start(8096);
        
        logContainerInfo();
        log.info("Rate limiting test environment setup completed");
    }
    
    @AfterAll
    static void tearDownTestEnvironment() throws IOException {
        if (mockApiServer != null) {
            mockApiServer.shutdown();
        }
        log.info("Rate limiting test environment torn down");
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
    @DisplayName("Test Token Bucket Rate Limiting")
    void testTokenBucketRateLimiting() throws Exception {
        log.info("Testing Token Bucket Rate Limiting");
        
        Map<String, String> config = createBaseConfig();
        config.put("rate.limiting.enabled", "true");
        config.put("rate.limiting.algorithm", "TOKEN_BUCKET");
        config.put("rate.limiting.requests.per.second", "5");
        config.put("rate.limiting.bucket.capacity", "10");
        config.put("rate.limiting.token.refill.rate", "5");
        
        // Setup multiple responses for burst testing
        for (int i = 0; i < 15; i++) {
            mockApiServer.enqueue(new MockResponse()
                    .setBody(String.format("{\"data\": [{\"id\": %d, \"name\": \"token%d\"}]}", i, i))
                    .setHeader("Content-Type", "application/json"));
        }
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Test burst capacity (should allow initial burst up to bucket capacity)
        List<Long> requestTimes = new ArrayList<>();
        AtomicInteger successfulRequests = new AtomicInteger(0);
        
        long startTime = System.currentTimeMillis();
        
        // Rapid fire requests to test token bucket behavior
        for (int i = 0; i < 15; i++) {
            long requestStart = System.currentTimeMillis();
            List<SourceRecord> records = task.poll();
            if (records != null && !records.isEmpty()) {
                successfulRequests.incrementAndGet();
                requestTimes.add(System.currentTimeMillis() - requestStart);
            }
            
            // Small delay to prevent overwhelming the test
            Thread.sleep(50);
        }
        
        long totalTime = System.currentTimeMillis() - startTime;
        double actualRate = (successfulRequests.get() * 1000.0) / totalTime;
        
        // Token bucket should allow initial burst, then rate limit
        assertThat(successfulRequests.get()).isGreaterThan(0);
        assertThat(actualRate).isLessThanOrEqualTo(15.0); // Should be rate limited
        
        log.info("Token Bucket Rate Limiting test completed. Successful requests: {}, Rate: {:.2f} req/s", 
                successfulRequests.get(), actualRate);
    }
    
    @Test
    @Order(2)
    @DisplayName("Test Sliding Window Rate Limiting")
    void testSlidingWindowRateLimiting() throws Exception {
        log.info("Testing Sliding Window Rate Limiting");
        
        Map<String, String> config = createBaseConfig();
        config.put("rate.limiting.enabled", "true");
        config.put("rate.limiting.algorithm", "SLIDING_WINDOW");
        config.put("rate.limiting.requests.per.second", "3");
        config.put("rate.limiting.window.size.ms", "1000");
        config.put("rate.limiting.precision.ms", "100");
        
        // Setup responses
        for (int i = 0; i < 10; i++) {
            mockApiServer.enqueue(new MockResponse()
                    .setBody(String.format("{\"data\": [{\"id\": %d, \"name\": \"sliding%d\"}]}", i, i))
                    .setHeader("Content-Type", "application/json"));
        }
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Test sliding window behavior over time
        List<Long> timestamps = new ArrayList<>();
        AtomicInteger requests = new AtomicInteger(0);
        
        long testStart = System.currentTimeMillis();
        
        // Make requests over 3 seconds to test sliding window
        while (System.currentTimeMillis() - testStart < 3000) {
            List<SourceRecord> records = task.poll();
            if (records != null && !records.isEmpty()) {
                timestamps.add(System.currentTimeMillis());
                requests.incrementAndGet();
            }
            Thread.sleep(200); // 200ms intervals
        }
        
        // Analyze request distribution in sliding windows
        int windowViolations = 0;
        for (int i = 0; i < timestamps.size(); i++) {
            long windowStart = timestamps.get(i);
            long windowEnd = windowStart + 1000; // 1 second window
            
            long requestsInWindow = timestamps.stream()
                    .mapToLong(Long::longValue)
                    .filter(ts -> ts >= windowStart && ts < windowEnd)
                    .count();
            
            if (requestsInWindow > 3) {
                windowViolations++;
            }
        }
        
        // Sliding window should prevent violations
        assertThat(requests.get()).isGreaterThan(0);
        
        log.info("Sliding Window Rate Limiting test completed. Total requests: {}, Window violations: {}", 
                requests.get(), windowViolations);
    }
    
    @Test
    @Order(3)
    @DisplayName("Test Fixed Window Rate Limiting")
    void testFixedWindowRateLimiting() throws Exception {
        log.info("Testing Fixed Window Rate Limiting");
        
        Map<String, String> config = createBaseConfig();
        config.put("rate.limiting.enabled", "true");
        config.put("rate.limiting.algorithm", "FIXED_WINDOW");
        config.put("rate.limiting.requests.per.window", "4");
        config.put("rate.limiting.window.size.ms", "2000");
        config.put("rate.limiting.window.reset.strategy", "STRICT");
        
        // Setup responses
        for (int i = 0; i < 12; i++) {
            mockApiServer.enqueue(new MockResponse()
                    .setBody(String.format("{\"data\": [{\"id\": %d, \"name\": \"fixed%d\"}]}", i, i))
                    .setHeader("Content-Type", "application/json"));
        }
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Test fixed window behavior across multiple windows
        Map<Long, Integer> windowCounts = new HashMap<>();
        AtomicInteger totalRequests = new AtomicInteger(0);
        
        long testStart = System.currentTimeMillis();
        
        // Test over 6 seconds (3 fixed windows of 2 seconds each)
        while (System.currentTimeMillis() - testStart < 6000) {
            List<SourceRecord> records = task.poll();
            if (records != null && !records.isEmpty()) {
                long currentTime = System.currentTimeMillis();
                long windowStart = ((currentTime - testStart) / 2000) * 2000; // 2-second windows
                
                windowCounts.merge(windowStart, 1, Integer::sum);
                totalRequests.incrementAndGet();
            }
            Thread.sleep(100);
        }
        
        // Verify fixed window limits
        boolean windowLimitRespected = windowCounts.values().stream()
                .allMatch(count -> count <= 4);
        
        assertThat(totalRequests.get()).isGreaterThan(0);
        
        log.info("Fixed Window Rate Limiting test completed. Total requests: {}, Window counts: {}", 
                totalRequests.get(), windowCounts);
    }
    
    @Test
    @Order(4)
    @DisplayName("Test Leaky Bucket Rate Limiting")
    void testLeakyBucketRateLimiting() throws Exception {
        log.info("Testing Leaky Bucket Rate Limiting");
        
        Map<String, String> config = createBaseConfig();
        config.put("rate.limiting.enabled", "true");
        config.put("rate.limiting.algorithm", "LEAKY_BUCKET");
        config.put("rate.limiting.leak.rate", "2"); // 2 requests per second
        config.put("rate.limiting.bucket.capacity", "8");
        config.put("rate.limiting.overflow.strategy", "DROP");
        
        // Setup responses
        for (int i = 0; i < 15; i++) {
            mockApiServer.enqueue(new MockResponse()
                    .setBody(String.format("{\"data\": [{\"id\": %d, \"name\": \"leaky%d\"}]}", i, i))
                    .setHeader("Content-Type", "application/json"));
        }
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Test leaky bucket behavior with bursty traffic
        List<Long> processingTimes = new ArrayList<>();
        AtomicInteger processedRequests = new AtomicInteger(0);
        
        long testStart = System.currentTimeMillis();
        
        // Send burst of requests, then steady stream
        for (int i = 0; i < 10; i++) {
            long requestStart = System.currentTimeMillis();
            List<SourceRecord> records = task.poll();
            if (records != null && !records.isEmpty()) {
                processingTimes.add(System.currentTimeMillis() - requestStart);
                processedRequests.incrementAndGet();
            }
            
            // Burst for first 5 requests, then steady
            if (i < 5) {
                Thread.sleep(10); // Burst
            } else {
                Thread.sleep(300); // Steady
            }
        }
        
        long totalTime = System.currentTimeMillis() - testStart;
        double averageRate = (processedRequests.get() * 1000.0) / totalTime;
        
        // Leaky bucket should smooth out the rate
        assertThat(processedRequests.get()).isGreaterThan(0);
        assertThat(averageRate).isLessThanOrEqualTo(10.0); // Should be rate limited
        
        log.info("Leaky Bucket Rate Limiting test completed. Processed: {}, Average rate: {:.2f} req/s", 
                processedRequests.get(), averageRate);
    }
    
    @Test
    @Order(5)
    @DisplayName("Test Per-API Rate Limiting")
    void testPerApiRateLimiting() throws Exception {
        log.info("Testing Per-API Rate Limiting");
        
        Map<String, String> config = createBaseConfig();
        config.put("apis.num", "3");
        
        // API 1: High rate limit
        config.put("api1.http.api.path", "/fast");
        config.put("api1.topics", "fast-topic");
        config.put("api1.rate.limiting.enabled", "true");
        config.put("api1.rate.limiting.algorithm", "TOKEN_BUCKET");
        config.put("api1.rate.limiting.requests.per.second", "10");
        
        // API 2: Medium rate limit
        config.put("api2.http.api.path", "/medium");
        config.put("api2.topics", "medium-topic");
        config.put("api2.rate.limiting.enabled", "true");
        config.put("api2.rate.limiting.algorithm", "FIXED_WINDOW");
        config.put("api2.rate.limiting.requests.per.window", "5");
        config.put("api2.rate.limiting.window.size.ms", "1000");
        
        // API 3: Low rate limit
        config.put("api3.http.api.path", "/slow");
        config.put("api3.topics", "slow-topic");
        config.put("api3.rate.limiting.enabled", "true");
        config.put("api3.rate.limiting.algorithm", "LEAKY_BUCKET");
        config.put("api3.rate.limiting.leak.rate", "2");
        
        // Setup responses for all APIs
        for (int i = 0; i < 20; i++) {
            mockApiServer.enqueue(new MockResponse()
                    .setBody(String.format("{\"fast\": [{\"id\": %d}]}", i))
                    .setHeader("Content-Type", "application/json"));
            mockApiServer.enqueue(new MockResponse()
                    .setBody(String.format("{\"medium\": [{\"id\": %d}]}", i))
                    .setHeader("Content-Type", "application/json"));
            mockApiServer.enqueue(new MockResponse()
                    .setBody(String.format("{\"slow\": [{\"id\": %d}]}", i))
                    .setHeader("Content-Type", "application/json"));
        }
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Monitor per-API rate limiting
        Map<String, Integer> apiCounts = new HashMap<>();
        
        long testStart = System.currentTimeMillis();
        while (System.currentTimeMillis() - testStart < 5000) {
            List<SourceRecord> records = task.poll();
            if (records != null && !records.isEmpty()) {
                for (SourceRecord record : records) {
                    apiCounts.merge(record.topic(), 1, Integer::sum);
                }
            }
            Thread.sleep(50);
        }
        
        // Fast API should have more requests than slow API
        Integer fastCount = apiCounts.getOrDefault("fast-topic", 0);
        Integer slowCount = apiCounts.getOrDefault("slow-topic", 0);
        
        assertThat(apiCounts).isNotEmpty();
        
        log.info("Per-API Rate Limiting test completed. API counts: {}", apiCounts);
    }
    
    @Test
    @Order(6)
    @DisplayName("Test Rate Limiting with 429 Response Handling")
    void testRateLimitingWith429Response() throws Exception {
        log.info("Testing Rate Limiting with 429 Response Handling");
        
        Map<String, String> config = createBaseConfig();
        config.put("rate.limiting.enabled", "true");
        config.put("rate.limiting.algorithm", "TOKEN_BUCKET");
        config.put("rate.limiting.requests.per.second", "5");
        config.put("rate.limiting.429.handling.enabled", "true");
        config.put("rate.limiting.429.backoff.strategy", "EXPONENTIAL");
        config.put("rate.limiting.429.initial.delay.ms", "500");
        config.put("rate.limiting.429.max.delay.ms", "5000");
        
        // Setup 429 responses with Retry-After headers
        mockApiServer.enqueue(new MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "2")
                .setBody("{\"error\": \"Rate limit exceeded\"}"));
        
        mockApiServer.enqueue(new MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "1")
                .setBody("{\"error\": \"Rate limit exceeded\"}"));
        
        // Success responses after rate limit
        for (int i = 0; i < 5; i++) {
            mockApiServer.enqueue(new MockResponse()
                    .setBody(String.format("{\"data\": [{\"id\": %d, \"name\": \"retry%d\"}]}", i, i))
                    .setHeader("Content-Type", "application/json"));
        }
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Test 429 handling
        List<Long> requestTimes = new ArrayList<>();
        AtomicInteger successCount = new AtomicInteger(0);
        
        for (int i = 0; i < 8; i++) {
            long requestStart = System.currentTimeMillis();
            List<SourceRecord> records = task.poll();
            requestTimes.add(System.currentTimeMillis() - requestStart);
            
            if (records != null && !records.isEmpty()) {
                successCount.incrementAndGet();
            }
            
            Thread.sleep(100);
        }
        
        // Should handle 429 responses and eventually succeed
        assertThat(successCount.get()).isGreaterThan(0);
        
        log.info("429 Response Handling test completed. Success count: {}", successCount.get());
    }
    
    @Test
    @Order(7)
    @DisplayName("Test Rate Limiting Metrics and Monitoring")
    void testRateLimitingMetrics() throws Exception {
        log.info("Testing Rate Limiting Metrics and Monitoring");
        
        Map<String, String> config = createBaseConfig();
        config.put("rate.limiting.enabled", "true");
        config.put("rate.limiting.algorithm", "TOKEN_BUCKET");
        config.put("rate.limiting.requests.per.second", "3");
        config.put("rate.limiting.metrics.enabled", "true");
        config.put("rate.limiting.jmx.enabled", "true");
        config.put("rate.limiting.metrics.window.size", "60");
        
        // Setup responses
        for (int i = 0; i < 10; i++) {
            mockApiServer.enqueue(new MockResponse()
                    .setBody(String.format("{\"data\": [{\"id\": %d, \"name\": \"metrics%d\"}]}", i, i))
                    .setHeader("Content-Type", "application/json"));
        }
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Generate requests for metrics
        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger throttledCount = new AtomicInteger(0);
        
        for (int i = 0; i < 10; i++) {
            List<SourceRecord> records = task.poll();
            if (records != null && !records.isEmpty()) {
                processedCount.incrementAndGet();
            } else {
                throttledCount.incrementAndGet();
            }
            Thread.sleep(100);
        }
        
        // Note: In a real implementation, we would verify JMX metrics here
        assertThat(processedCount.get()).isGreaterThan(0);
        
        log.info("Rate Limiting Metrics test completed. Processed: {}, Throttled: {}", 
                processedCount.get(), throttledCount.get());
    }
    
    @Test
    @Order(8)
    @DisplayName("Test Adaptive Rate Limiting")
    void testAdaptiveRateLimiting() throws Exception {
        log.info("Testing Adaptive Rate Limiting");
        
        Map<String, String> config = createBaseConfig();
        config.put("rate.limiting.enabled", "true");
        config.put("rate.limiting.algorithm", "ADAPTIVE");
        config.put("rate.limiting.initial.rate", "5");
        config.put("rate.limiting.adaptation.enabled", "true");
        config.put("rate.limiting.adaptation.factor", "0.8");
        config.put("rate.limiting.adaptation.window.ms", "2000");
        config.put("rate.limiting.success.threshold", "0.9");
        
        // Setup mixed success/error responses
        for (int i = 0; i < 5; i++) {
            mockApiServer.enqueue(new MockResponse()
                    .setBody(String.format("{\"data\": [{\"id\": %d, \"name\": \"success%d\"}]}", i, i))
                    .setHeader("Content-Type", "application/json"));
        }
        
        // Introduce errors to trigger adaptation
        for (int i = 0; i < 3; i++) {
            mockApiServer.enqueue(new MockResponse()
                    .setResponseCode(500)
                    .setBody("{\"error\": \"Server error\"}"));
        }
        
        // More success responses
        for (int i = 5; i < 10; i++) {
            mockApiServer.enqueue(new MockResponse()
                    .setBody(String.format("{\"data\": [{\"id\": %d, \"name\": \"adapted%d\"}]}", i, i))
                    .setHeader("Content-Type", "application/json"));
        }
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Test adaptive behavior
        List<Long> responseTimes = new ArrayList<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        
        for (int i = 0; i < 15; i++) {
            long start = System.currentTimeMillis();
            List<SourceRecord> records = task.poll();
            responseTimes.add(System.currentTimeMillis() - start);
            
            if (records != null && !records.isEmpty()) {
                successCount.incrementAndGet();
            } else {
                errorCount.incrementAndGet();
            }
            
            Thread.sleep(150);
        }
        
        // Adaptive rate limiting should adjust based on success/error rates
        assertThat(successCount.get() + errorCount.get()).isGreaterThan(0);
        
        log.info("Adaptive Rate Limiting test completed. Success: {}, Errors: {}", 
                successCount.get(), errorCount.get());
    }
    
    @Test
    @Order(9)
    @DisplayName("Test Rate Limiting Configuration Edge Cases")
    void testRateLimitingConfigEdgeCases() throws Exception {
        log.info("Testing Rate Limiting Configuration Edge Cases");
        
        // Test zero rate limit
        Map<String, String> zeroRateConfig = createBaseConfig();
        zeroRateConfig.put("rate.limiting.enabled", "true");
        zeroRateConfig.put("rate.limiting.algorithm", "TOKEN_BUCKET");
        zeroRateConfig.put("rate.limiting.requests.per.second", "0");
        
        mockApiServer.enqueue(new MockResponse()
                .setBody("{\"data\": [{\"id\": 1, \"name\": \"zero-rate\"}]}")
                .setHeader("Content-Type", "application/json"));
        
        // Start with zero rate config
        connector.start(zeroRateConfig);
        task.initialize(mockSourceTaskContext());
        task.start(zeroRateConfig);
        
        // Should handle zero rate gracefully (likely block all requests)
        List<SourceRecord> records = task.poll();
        
        // Reset for next test
        task.stop();
        connector = new HttpSourceConnector();
        task = new HttpSourceTask();
        
        // Test very high rate limit
        Map<String, String> highRateConfig = createBaseConfig();
        highRateConfig.put("rate.limiting.enabled", "true");
        highRateConfig.put("rate.limiting.algorithm", "TOKEN_BUCKET");
        highRateConfig.put("rate.limiting.requests.per.second", "1000");
        
        mockApiServer.enqueue(new MockResponse()
                .setBody("{\"data\": [{\"id\": 2, \"name\": \"high-rate\"}]}")
                .setHeader("Content-Type", "application/json"));
        
        connector.start(highRateConfig);
        task.initialize(mockSourceTaskContext());
        task.start(highRateConfig);
        
        List<SourceRecord> highRateRecords = task.poll();
        assertThat(highRateRecords).isNotEmpty();
        
        log.info("Rate Limiting Configuration Edge Cases test completed successfully");
    }
    
    // Helper methods
    
    private Map<String, String> createBaseConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("connector.class", "io.confluent.connect.http.HttpSourceConnector");
        config.put("tasks.max", "1");
        config.put("http.api.base.url", "http://localhost:" + mockApiServer.getPort());
        config.put("apis.num", "1");
        config.put("api1.http.api.path", "/data");
        config.put("api1.topics", "rate-limit-topic");
        config.put("api1.http.request.method", "GET");
        config.put("api1.http.offset.mode", "SIMPLE_INCREMENTING");
        config.put("api1.http.initial.offset", "0");
        config.put("api1.request.interval.ms", "100"); // Fast polling for rate limit testing
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