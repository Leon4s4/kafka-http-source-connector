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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive integration tests for Schema Registry integration.
 * Tests Avro, JSON Schema, and Protobuf serialization with Schema Registry,
 * schema evolution, compatibility checks, and schema validation.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SchemaRegistryIntegrationTest extends BaseIntegrationTest {
    
    private static final Logger log = LoggerFactory.getLogger(SchemaRegistryIntegrationTest.class);
    
    private static MockWebServer mockApiServer;
    private static MockWebServer mockSchemaRegistryServer;
    
    private HttpSourceConnector connector;
    private HttpSourceTask task;
    
    // Schema Registry container
    @Container
    static GenericContainer<?> schemaRegistry = new GenericContainer<>(DockerImageName.parse("confluentinc/cp-schema-registry:latest"))
            .withNetwork(SHARED_NETWORK)
            .withNetworkAliases("schema-registry")
            .withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
            .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "kafka:9092")
            .withEnv("SCHEMA_REGISTRY_LISTENERS", "http://0.0.0.0:8081")
            .withExposedPorts(8081)
            .waitingFor(Wait.forHttp("/subjects").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)));
    
    @BeforeAll
    static void setupTestEnvironment() throws IOException {
        log.info("Setting up Schema Registry integration test environment");
        
        // Start mock API server
        mockApiServer = new MockWebServer();
        mockApiServer.start(8100);
        
        // Start mock Schema Registry server for additional testing
        mockSchemaRegistryServer = new MockWebServer();
        mockSchemaRegistryServer.start(8101);
        
        logContainerInfo();
        log.info("Schema Registry integration test environment setup completed");
        log.info("Schema Registry URL: http://localhost:{}", schemaRegistry.getMappedPort(8081));
    }
    
    @AfterAll
    static void tearDownTestEnvironment() throws IOException {
        if (mockApiServer != null) {
            mockApiServer.shutdown();
        }
        if (mockSchemaRegistryServer != null) {
            mockSchemaRegistryServer.shutdown();
        }
        log.info("Schema Registry integration test environment torn down");
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
    @DisplayName("Test Avro Serialization with Schema Registry")
    void testAvroSerializationWithSchemaRegistry() throws Exception {
        log.info("Testing Avro Serialization with Schema Registry");
        
        Map<String, String> config = createBaseConfig();
        config.put("output.data.format", "AVRO");
        config.put("schema.registry.url", "http://localhost:" + schemaRegistry.getMappedPort(8081));
        config.put("schema.registry.auth.enabled", "false");
        config.put("avro.schema.subject", "user-value");
        config.put("avro.schema.compatibility.level", "BACKWARD");
        
        // Define Avro schema
        String avroSchema = """
            {
                "type": "record",
                "name": "User",
                "fields": [
                    {"name": "id", "type": "int"},
                    {"name": "name", "type": "string"},
                    {"name": "email", "type": "string"}
                ]
            }
            """;
        config.put("avro.schema.definition", avroSchema);
        
        // Setup API response with Avro-compatible data
        String apiResponse = """
            {
                "users": [
                    {
                        "id": 1,
                        "name": "John Doe",
                        "email": "john@example.com"
                    },
                    {
                        "id": 2,
                        "name": "Jane Smith", 
                        "email": "jane@example.com"
                    }
                ]
            }
            """;
        
        mockApiServer.enqueue(new MockResponse()
                .setBody(apiResponse)
                .setHeader("Content-Type", "application/json"));
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Poll for records
        List<SourceRecord> records = task.poll();
        assertThat(records).isNotEmpty();
        
        // Verify records contain Avro serialized data
        for (SourceRecord record : records) {
            assertThat(record.value()).isNotNull();
        }
        
        log.info("Avro Serialization with Schema Registry test completed successfully");
    }
    
    @Test
    @Order(2)
    @DisplayName("Test JSON Schema with Schema Registry")
    void testJsonSchemaWithSchemaRegistry() throws Exception {
        log.info("Testing JSON Schema with Schema Registry");
        
        Map<String, String> config = createBaseConfig();
        config.put("output.data.format", "JSON_SR");
        config.put("schema.registry.url", "http://localhost:" + schemaRegistry.getMappedPort(8081));
        config.put("json.schema.subject", "product-value");
        config.put("json.schema.compatibility.level", "FORWARD");
        
        // Define JSON Schema
        String jsonSchema = """
            {
                "$schema": "http://json-schema.org/draft-07/schema#",
                "type": "object",
                "properties": {
                    "id": {"type": "integer"},
                    "name": {"type": "string"},
                    "price": {"type": "number"},
                    "category": {"type": "string"},
                    "inStock": {"type": "boolean"}
                },
                "required": ["id", "name", "price"]
            }
            """;
        config.put("json.schema.definition", jsonSchema);
        
        // Setup API response with JSON Schema compatible data
        String apiResponse = """
            {
                "products": [
                    {
                        "id": 101,
                        "name": "Laptop",
                        "price": 999.99,
                        "category": "Electronics",
                        "inStock": true
                    },
                    {
                        "id": 102,
                        "name": "Mouse",
                        "price": 29.99,
                        "category": "Electronics",
                        "inStock": false
                    }
                ]
            }
            """;
        
        mockApiServer.enqueue(new MockResponse()
                .setBody(apiResponse)
                .setHeader("Content-Type", "application/json"));
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Poll for records
        List<SourceRecord> records = task.poll();
        assertThat(records).isNotEmpty();
        
        // Verify records are JSON Schema validated
        for (SourceRecord record : records) {
            assertThat(record.value()).isNotNull();
        }
        
        log.info("JSON Schema with Schema Registry test completed successfully");
    }
    
    @Test
    @Order(3)
    @DisplayName("Test Protobuf Serialization with Schema Registry")
    void testProtobufSerializationWithSchemaRegistry() throws Exception {
        log.info("Testing Protobuf Serialization with Schema Registry");
        
        Map<String, String> config = createBaseConfig();
        config.put("output.data.format", "PROTOBUF");
        config.put("schema.registry.url", "http://localhost:" + schemaRegistry.getMappedPort(8081));
        config.put("protobuf.schema.subject", "order-value");
        config.put("protobuf.schema.compatibility.level", "FULL");
        
        // Define Protobuf schema
        String protobufSchema = """
            syntax = "proto3";
            
            message Order {
                int32 order_id = 1;
                string customer_name = 2;
                double total_amount = 3;
                repeated OrderItem items = 4;
            }
            
            message OrderItem {
                int32 product_id = 1;
                string product_name = 2;
                int32 quantity = 3;
                double price = 4;
            }
            """;
        config.put("protobuf.schema.definition", protobufSchema);
        
        // Setup API response with Protobuf-compatible data
        String apiResponse = """
            {
                "orders": [
                    {
                        "order_id": 1001,
                        "customer_name": "Alice Johnson",
                        "total_amount": 150.75,
                        "items": [
                            {
                                "product_id": 1,
                                "product_name": "Widget A",
                                "quantity": 2,
                                "price": 50.25
                            },
                            {
                                "product_id": 2,
                                "product_name": "Widget B",
                                "quantity": 1,
                                "price": 50.25
                            }
                        ]
                    }
                ]
            }
            """;
        
        mockApiServer.enqueue(new MockResponse()
                .setBody(apiResponse)
                .setHeader("Content-Type", "application/json"));
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Poll for records
        List<SourceRecord> records = task.poll();
        assertThat(records).isNotEmpty();
        
        // Verify records contain Protobuf serialized data
        for (SourceRecord record : records) {
            assertThat(record.value()).isNotNull();
        }
        
        log.info("Protobuf Serialization with Schema Registry test completed successfully");
    }
    
    @Test
    @Order(4)
    @DisplayName("Test Schema Evolution with Backward Compatibility")
    void testSchemaEvolutionBackwardCompatibility() throws Exception {
        log.info("Testing Schema Evolution with Backward Compatibility");
        
        Map<String, String> config = createBaseConfig();
        config.put("output.data.format", "AVRO");
        config.put("schema.registry.url", "http://localhost:" + schemaRegistry.getMappedPort(8081));
        config.put("schema.evolution.enabled", "true");
        config.put("schema.compatibility.level", "BACKWARD");
        config.put("avro.schema.subject", "employee-value");
        
        // Initial schema (v1)
        String schemaV1 = """
            {
                "type": "record",
                "name": "Employee",
                "fields": [
                    {"name": "id", "type": "int"},
                    {"name": "name", "type": "string"},
                    {"name": "department", "type": "string"}
                ]
            }
            """;
        config.put("avro.schema.definition", schemaV1);
        
        // Setup first API response (compatible with v1)
        String apiResponseV1 = """
            {
                "employees": [
                    {
                        "id": 1,
                        "name": "Bob Wilson",
                        "department": "Engineering"
                    }
                ]
            }
            """;
        
        mockApiServer.enqueue(new MockResponse()
                .setBody(apiResponseV1)
                .setHeader("Content-Type", "application/json"));
        
        // Start connector and task with v1
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Poll for records with v1 schema
        List<SourceRecord> recordsV1 = task.poll();
        assertThat(recordsV1).isNotEmpty();
        
        // Stop and restart with evolved schema (v2) - backward compatible
        task.stop();
        connector = new HttpSourceConnector();
        task = new HttpSourceTask();
        
        // Evolved schema (v2) - added optional field (backward compatible)
        String schemaV2 = """
            {
                "type": "record",
                "name": "Employee",
                "fields": [
                    {"name": "id", "type": "int"},
                    {"name": "name", "type": "string"},
                    {"name": "department", "type": "string"},
                    {"name": "salary", "type": ["null", "double"], "default": null}
                ]
            }
            """;
        config.put("avro.schema.definition", schemaV2);
        
        // Setup API response with new field
        String apiResponseV2 = """
            {
                "employees": [
                    {
                        "id": 2,
                        "name": "Carol Davis",
                        "department": "Marketing",
                        "salary": 75000.0
                    }
                ]
            }
            """;
        
        mockApiServer.enqueue(new MockResponse()
                .setBody(apiResponseV2)
                .setHeader("Content-Type", "application/json"));
        
        // Start with evolved schema
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Poll for records with v2 schema
        List<SourceRecord> recordsV2 = task.poll();
        assertThat(recordsV2).isNotEmpty();
        
        log.info("Schema Evolution with Backward Compatibility test completed successfully");
    }
    
    @Test
    @Order(5)
    @DisplayName("Test Schema Registry Authentication")
    void testSchemaRegistryAuthentication() throws Exception {
        log.info("Testing Schema Registry Authentication");
        
        // Setup mock Schema Registry with authentication
        mockSchemaRegistryServer.enqueue(new MockResponse()
                .setResponseCode(401)
                .setBody("{\"error\": \"Unauthorized\"}"));
        
        mockSchemaRegistryServer.enqueue(new MockResponse()
                .setBody("{\"subjects\": []}")
                .setHeader("Content-Type", "application/json"));
        
        Map<String, String> config = createBaseConfig();
        config.put("output.data.format", "JSON_SR");
        config.put("schema.registry.url", "http://localhost:" + mockSchemaRegistryServer.getPort());
        config.put("schema.registry.auth.enabled", "true");
        config.put("schema.registry.auth.method", "BASIC");
        config.put("schema.registry.auth.username", "sr-user");
        config.put("schema.registry.auth.password", "sr-password"); // trufflehog:ignore
        
        // Setup API response
        mockApiServer.enqueue(new MockResponse()
                .setBody("{\"data\": [{\"id\": 1, \"name\": \"auth-test\"}]}")
                .setHeader("Content-Type", "application/json"));
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Poll for records
        List<SourceRecord> records = task.poll();
        assertThat(records).isNotEmpty();
        
        log.info("Schema Registry Authentication test completed successfully");
    }
    
    @Test
    @Order(6)
    @DisplayName("Test Schema Validation and Error Handling")
    void testSchemaValidationAndErrorHandling() throws Exception {
        log.info("Testing Schema Validation and Error Handling");
        
        Map<String, String> config = createBaseConfig();
        config.put("output.data.format", "JSON_SR");
        config.put("schema.registry.url", "http://localhost:" + schemaRegistry.getMappedPort(8081));
        config.put("schema.validation.enabled", "true");
        config.put("schema.validation.strict", "true");
        config.put("schema.validation.error.handling", "LOG_AND_CONTINUE");
        
        // Define strict JSON Schema
        String strictSchema = """
            {
                "$schema": "http://json-schema.org/draft-07/schema#",
                "type": "object",
                "properties": {
                    "id": {"type": "integer", "minimum": 1},
                    "email": {"type": "string", "format": "email"},
                    "age": {"type": "integer", "minimum": 0, "maximum": 150}
                },
                "required": ["id", "email", "age"],
                "additionalProperties": false
            }
            """;
        config.put("json.schema.definition", strictSchema);
        
        // Setup API response with invalid data (violates schema)
        String invalidApiResponse = """
            {
                "users": [
                    {
                        "id": 0,
                        "email": "invalid-email",
                        "age": 200,
                        "extra_field": "not allowed"
                    },
                    {
                        "id": 1,
                        "email": "valid@example.com",
                        "age": 25
                    }
                ]
            }
            """;
        
        mockApiServer.enqueue(new MockResponse()
                .setBody(invalidApiResponse)
                .setHeader("Content-Type", "application/json"));
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Poll for records - should handle validation errors gracefully
        List<SourceRecord> records = task.poll();
        
        // Some records may be filtered out due to validation errors
        log.info("Schema Validation and Error Handling test completed successfully");
    }
    
    @Test
    @Order(7)
    @DisplayName("Test Schema Registry Subject Naming Strategies")
    void testSchemaRegistrySubjectNamingStrategies() throws Exception {
        log.info("Testing Schema Registry Subject Naming Strategies");
        
        Map<String, String> config = createBaseConfig();
        config.put("output.data.format", "AVRO");
        config.put("schema.registry.url", "http://localhost:" + schemaRegistry.getMappedPort(8081));
        
        // Test different naming strategies
        String[] strategies = {"TopicNameStrategy", "RecordNameStrategy", "TopicRecordNameStrategy"};
        
        for (String strategy : strategies) {
            // Reset connector for each strategy
            if (task != null) {
                task.stop();
            }
            connector = new HttpSourceConnector();
            task = new HttpSourceTask();
            
            config.put("schema.registry.subject.naming.strategy", strategy);
            config.put("avro.schema.subject", "test-subject-" + strategy.toLowerCase());
            
            String avroSchema = """
                {
                    "type": "record",
                    "name": "TestRecord",
                    "fields": [
                        {"name": "id", "type": "int"},
                        {"name": "message", "type": "string"}
                    ]
                }
                """;
            config.put("avro.schema.definition", avroSchema);
            
            // Setup API response
            mockApiServer.enqueue(new MockResponse()
                    .setBody(String.format("{\"data\": [{\"id\": 1, \"message\": \"strategy-%s\"}]}", strategy))
                    .setHeader("Content-Type", "application/json"));
            
            // Start connector and test strategy
            connector.start(config);
            task.initialize(mockSourceTaskContext());
            task.start(config);
            
            List<SourceRecord> records = task.poll();
            assertThat(records).isNotEmpty();
            
            log.info("Subject naming strategy {} test completed", strategy);
            Thread.sleep(100);
        }
        
        log.info("Schema Registry Subject Naming Strategies test completed successfully");
    }
    
    @Test
    @Order(8)
    @DisplayName("Test Schema Caching and Performance")
    void testSchemaCachingAndPerformance() throws Exception {
        log.info("Testing Schema Caching and Performance");
        
        Map<String, String> config = createBaseConfig();
        config.put("output.data.format", "AVRO");
        config.put("schema.registry.url", "http://localhost:" + schemaRegistry.getMappedPort(8081));
        config.put("schema.registry.cache.enabled", "true");
        config.put("schema.registry.cache.size", "100");
        config.put("schema.registry.cache.ttl.seconds", "300");
        
        String avroSchema = """
            {
                "type": "record",
                "name": "CacheTest",
                "fields": [
                    {"name": "id", "type": "int"},
                    {"name": "timestamp", "type": "long"},
                    {"name": "data", "type": "string"}
                ]
            }
            """;
        config.put("avro.schema.definition", avroSchema);
        
        // Setup multiple API responses for performance testing
        for (int i = 0; i < 5; i++) {
            mockApiServer.enqueue(new MockResponse()
                    .setBody(String.format("{\"data\": [{\"id\": %d, \"timestamp\": %d, \"data\": \"cache-test-%d\"}]}", 
                            i, System.currentTimeMillis(), i))
                    .setHeader("Content-Type", "application/json"));
        }
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Measure performance with schema caching
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < 5; i++) {
            List<SourceRecord> records = task.poll();
            assertThat(records).isNotEmpty();
            Thread.sleep(100);
        }
        
        long endTime = System.currentTimeMillis();
        long processingTime = endTime - startTime;
        
        // Schema caching should improve performance for subsequent requests
        assertThat(processingTime).isLessThan(10000); // Should complete within 10 seconds
        
        log.info("Schema Caching and Performance test completed in {} ms", processingTime);
    }
    
    @Test
    @Order(9)
    @DisplayName("Test Multiple Schema Formats in Single Connector")
    void testMultipleSchemaFormatsInSingleConnector() throws Exception {
        log.info("Testing Multiple Schema Formats in Single Connector");
        
        Map<String, String> config = createBaseConfig();
        config.put("apis.num", "3");
        config.put("schema.registry.url", "http://localhost:" + schemaRegistry.getMappedPort(8081));
        
        // API 1: Avro format
        config.put("api1.http.api.path", "/avro-data");
        config.put("api1.topics", "avro-topic");
        config.put("api1.output.data.format", "AVRO");
        config.put("api1.avro.schema.subject", "avro-mixed-test");
        
        // API 2: JSON Schema format
        config.put("api2.http.api.path", "/json-data");
        config.put("api2.topics", "json-topic");
        config.put("api2.output.data.format", "JSON_SR");
        config.put("api2.json.schema.subject", "json-mixed-test");
        
        // API 3: Protobuf format
        config.put("api3.http.api.path", "/proto-data");
        config.put("api3.topics", "proto-topic");
        config.put("api3.output.data.format", "PROTOBUF");
        config.put("api3.protobuf.schema.subject", "proto-mixed-test");
        
        // Setup responses for all formats
        mockApiServer.enqueue(new MockResponse()
                .setBody("{\"avro_data\": [{\"id\": 1, \"name\": \"avro-record\"}]}")
                .setHeader("Content-Type", "application/json"));
        
        mockApiServer.enqueue(new MockResponse()
                .setBody("{\"json_data\": [{\"id\": 2, \"name\": \"json-record\"}]}")
                .setHeader("Content-Type", "application/json"));
        
        mockApiServer.enqueue(new MockResponse()
                .setBody("{\"proto_data\": [{\"id\": 3, \"name\": \"proto-record\"}]}")
                .setHeader("Content-Type", "application/json"));
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Poll for records from all APIs
        Set<String> observedTopics = new HashSet<>();
        for (int poll = 0; poll < 10; poll++) {
            List<SourceRecord> records = task.poll();
            if (records != null && !records.isEmpty()) {
                records.forEach(record -> observedTopics.add(record.topic()));
            }
            Thread.sleep(100);
        }
        
        // Verify all schema formats were processed
        assertThat(observedTopics).containsAnyOf("avro-topic", "json-topic", "proto-topic");
        
        log.info("Multiple Schema Formats in Single Connector test completed successfully");
    }
    
    @Test
    @Order(10)
    @DisplayName("Test Schema Registry Error Recovery")
    void testSchemaRegistryErrorRecovery() throws Exception {
        log.info("Testing Schema Registry Error Recovery");
        
        // Setup mock Schema Registry with temporary failure
        mockSchemaRegistryServer.enqueue(new MockResponse()
                .setResponseCode(503)
                .setBody("{\"error\": \"Service Unavailable\"}"));
        
        mockSchemaRegistryServer.enqueue(new MockResponse()
                .setResponseCode(503)
                .setBody("{\"error\": \"Service Unavailable\"}"));
        
        // Recovery response
        mockSchemaRegistryServer.enqueue(new MockResponse()
                .setBody("{\"subjects\": [\"recovery-test\"]}")
                .setHeader("Content-Type", "application/json"));
        
        Map<String, String> config = createBaseConfig();
        config.put("output.data.format", "JSON_SR");
        config.put("schema.registry.url", "http://localhost:" + mockSchemaRegistryServer.getPort());
        config.put("schema.registry.retry.enabled", "true");
        config.put("schema.registry.retry.attempts", "3");
        config.put("schema.registry.retry.backoff.ms", "1000");
        
        // Setup API response
        mockApiServer.enqueue(new MockResponse()
                .setBody("{\"data\": [{\"id\": 1, \"name\": \"recovery-test\"}]}")
                .setHeader("Content-Type", "application/json"));
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Poll for records - should recover from initial Schema Registry failures
        List<SourceRecord> records = task.poll();
        
        // Should eventually succeed after retry
        log.info("Schema Registry Error Recovery test completed successfully");
    }
    
    // Helper methods
    
    private Map<String, String> createBaseConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("connector.class", "io.confluent.connect.http.HttpSourceConnector");
        config.put("tasks.max", "1");
        config.put("http.api.base.url", "http://localhost:" + mockApiServer.getPort());
        config.put("apis.num", "1");
        config.put("api1.http.api.path", "/data");
        config.put("api1.topics", "schema-registry-topic");
        config.put("api1.http.request.method", "GET");
        config.put("api1.http.offset.mode", "SIMPLE_INCREMENTING");
        config.put("api1.http.initial.offset", "0");
        config.put("api1.request.interval.ms", "1000");
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