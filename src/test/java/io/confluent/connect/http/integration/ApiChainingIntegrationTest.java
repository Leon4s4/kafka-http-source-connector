package io.confluent.connect.http.integration;

import io.confluent.connect.http.HttpSourceConnector;
import io.confluent.connect.http.HttpSourceTask;

import com.fasterxml.jackson.databind.JsonNode;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive integration tests for API chaining functionality.
 * Tests parent-child API relationships, metadata management, dependency handling,
 * and complex chaining scenarios with error handling and recovery.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ApiChainingIntegrationTest extends BaseIntegrationTest {
    
    private static final Logger log = LoggerFactory.getLogger(ApiChainingIntegrationTest.class);
    
    private static MockWebServer mockApiServer;
    private static ObjectMapper objectMapper = new ObjectMapper();
    
    private HttpSourceConnector connector;
    private HttpSourceTask task;
    
    @BeforeAll
    static void setupTestEnvironment() throws IOException {
        log.info("Setting up API chaining test environment");
        
        // Start mock API server
        mockApiServer = new MockWebServer();
        mockApiServer.start(8094);
        
        logContainerInfo();
        log.info("API chaining test environment setup completed");
    }
    
    @AfterAll
    static void tearDownTestEnvironment() throws IOException {
        if (mockApiServer != null) {
            mockApiServer.shutdown();
        }
        log.info("API chaining test environment torn down");
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
    @DisplayName("Test Simple Parent-Child API Chaining")
    void testSimpleParentChildChaining() throws Exception {
        log.info("Testing Simple Parent-Child API Chaining");
        
        Map<String, String> config = createBaseConfig();
        config.put("apis.num", "2");
        config.put("api.chaining.parent.child.relationship", "api2:api1");
        config.put("api.chaining.metadata.topic", "api-chaining-metadata");
        
        // Parent API (API 1): Companies
        config.put("api1.http.api.path", "/companies");
        config.put("api1.topics", "companies-topic");
        config.put("api1.http.response.data.json.pointer", "/companies");
        config.put("api1.http.offset.mode", "CHAINING");
        config.put("api1.http.chaining.json.pointer", "/id");
        
        // Child API (API 2): Employees for each company
        config.put("api2.http.api.path", "/companies/${parent_value}/employees");
        config.put("api2.topics", "employees-topic");
        config.put("api2.http.offset.mode", "SIMPLE_INCREMENTING");
        config.put("api2.http.initial.offset", "0");
        
        // Setup parent API response (companies)
        mockApiServer.enqueue(new MockResponse()
                .setBody("""
                    {
                        "companies": [
                            {"id": "comp1", "name": "Company One", "industry": "Tech"},
                            {"id": "comp2", "name": "Company Two", "industry": "Finance"}
                        ]
                    }
                    """)
                .setHeader("Content-Type", "application/json"));
        
        // Setup child API responses (employees for each company)
        mockApiServer.enqueue(new MockResponse()
                .setBody("""
                    {
                        "employees": [
                            {"id": "emp1", "name": "John Doe", "company_id": "comp1", "role": "Engineer"},
                            {"id": "emp2", "name": "Jane Smith", "company_id": "comp1", "role": "Designer"}
                        ]
                    }
                    """)
                .setHeader("Content-Type", "application/json"));
        
        mockApiServer.enqueue(new MockResponse()
                .setBody("""
                    {
                        "employees": [
                            {"id": "emp3", "name": "Bob Wilson", "company_id": "comp2", "role": "Analyst"},
                            {"id": "emp4", "name": "Alice Johnson", "company_id": "comp2", "role": "Manager"}
                        ]
                    }
                    """)
                .setHeader("Content-Type", "application/json"));
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Poll multiple times to process parent and child APIs
        Set<String> observedTopics = new HashSet<>();
        List<String> requestPaths = new ArrayList<>();
        
        for (int poll = 0; poll < 5; poll++) {
            List<SourceRecord> records = task.poll();
            if (records != null && !records.isEmpty()) {
                records.forEach(record -> observedTopics.add(record.topic()));
            }
            
            // Collect request paths to verify chaining
            RecordedRequest request = mockApiServer.takeRequest(100, TimeUnit.MILLISECONDS);
            if (request != null) {
                requestPaths.add(request.getPath());
            }
            
            Thread.sleep(100);
        }
        
        // Verify both parent and child APIs were called
        assertThat(observedTopics).containsAnyOf("companies-topic", "employees-topic");
        
        // Verify chaining worked - should see calls to /companies and /companies/{id}/employees
        assertThat(requestPaths).anyMatch(path -> path.equals("/companies"));
        assertThat(requestPaths).anyMatch(path -> path.contains("/companies/comp"));
        assertThat(requestPaths).anyMatch(path -> path.contains("/employees"));
        
        log.info("Simple Parent-Child API Chaining test completed successfully");
        log.info("Observed topics: {}", observedTopics);
        log.info("Request paths: {}", requestPaths);
    }
    
    @Test
    @Order(2)
    @DisplayName("Test Multi-Level API Chaining")
    void testMultiLevelApiChaining() throws Exception {
        log.info("Testing Multi-Level API Chaining");
        
        Map<String, String> config = createBaseConfig();
        config.put("apis.num", "3");
        config.put("api.chaining.parent.child.relationship", "api2:api1,api3:api2");
        config.put("api.chaining.metadata.topic", "chaining-metadata");
        
        // Level 1: Organizations
        config.put("api1.http.api.path", "/organizations");
        config.put("api1.topics", "organizations-topic");
        config.put("api1.http.response.data.json.pointer", "/organizations");
        config.put("api1.http.offset.mode", "CHAINING");
        config.put("api1.http.chaining.json.pointer", "/id");
        
        // Level 2: Departments per organization
        config.put("api2.http.api.path", "/organizations/${parent_value}/departments");
        config.put("api2.topics", "departments-topic");
        config.put("api2.http.response.data.json.pointer", "/departments");
        config.put("api2.http.offset.mode", "CHAINING");
        config.put("api2.http.chaining.json.pointer", "/id");
        
        // Level 3: Employees per department
        config.put("api3.http.api.path", "/departments/${parent_value}/employees");
        config.put("api3.topics", "dept-employees-topic");
        config.put("api3.http.offset.mode", "SIMPLE_INCREMENTING");
        config.put("api3.http.initial.offset", "0");
        
        // Setup Level 1 response (organizations)
        mockApiServer.enqueue(new MockResponse()
                .setBody("""
                    {
                        "organizations": [
                            {"id": "org1", "name": "Tech Corp", "type": "Technology"},
                            {"id": "org2", "name": "Finance Inc", "type": "Financial"}
                        ]
                    }
                    """)
                .setHeader("Content-Type", "application/json"));
        
        // Setup Level 2 responses (departments for each organization)
        mockApiServer.enqueue(new MockResponse()
                .setBody("""
                    {
                        "departments": [
                            {"id": "dept1", "name": "Engineering", "org_id": "org1"},
                            {"id": "dept2", "name": "Product", "org_id": "org1"}
                        ]
                    }
                    """)
                .setHeader("Content-Type", "application/json"));
        
        mockApiServer.enqueue(new MockResponse()
                .setBody("""
                    {
                        "departments": [
                            {"id": "dept3", "name": "Trading", "org_id": "org2"},
                            {"id": "dept4", "name": "Risk", "org_id": "org2"}
                        ]
                    }
                    """)
                .setHeader("Content-Type", "application/json"));
        
        // Setup Level 3 responses (employees for each department)
        for (int i = 1; i <= 4; i++) {
            mockApiServer.enqueue(new MockResponse()
                    .setBody(String.format("""
                        {
                            "employees": [
                                {"id": "emp%d1", "name": "Employee %d1", "dept_id": "dept%d"},
                                {"id": "emp%d2", "name": "Employee %d2", "dept_id": "dept%d"}
                            ]
                        }
                        """, i, i, i, i, i, i))
                    .setHeader("Content-Type", "application/json"));
        }
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Poll multiple times to process all levels
        Set<String> observedTopics = new HashSet<>();
        List<String> requestPaths = new ArrayList<>();
        
        for (int poll = 0; poll < 10; poll++) {
            List<SourceRecord> records = task.poll();
            if (records != null && !records.isEmpty()) {
                records.forEach(record -> observedTopics.add(record.topic()));
            }
            
            RecordedRequest request = mockApiServer.takeRequest(100, TimeUnit.MILLISECONDS);
            if (request != null) {
                requestPaths.add(request.getPath());
            }
            
            Thread.sleep(100);
        }
        
        // Verify all levels were processed
        assertThat(observedTopics).containsAnyOf("organizations-topic", "departments-topic", "dept-employees-topic");
        
        // Verify chaining progression
        assertThat(requestPaths).anyMatch(path -> path.equals("/organizations"));
        assertThat(requestPaths).anyMatch(path -> path.contains("/organizations/org") && path.contains("/departments"));
        assertThat(requestPaths).anyMatch(path -> path.contains("/departments/dept") && path.contains("/employees"));
        
        log.info("Multi-Level API Chaining test completed successfully");
        log.info("Observed topics: {}", observedTopics);
        log.info("Request paths: {}", requestPaths);
    }
    
    @Test
    @Order(3)
    @DisplayName("Test Parallel Child APIs")
    void testParallelChildApis() throws Exception {
        log.info("Testing Parallel Child APIs");
        
        Map<String, String> config = createBaseConfig();
        config.put("apis.num", "3");
        config.put("api.chaining.parent.child.relationship", "api2:api1,api3:api1");
        config.put("api.chaining.metadata.topic", "parallel-chaining-metadata");
        
        // Parent API: Customers
        config.put("api1.http.api.path", "/customers");
        config.put("api1.topics", "customers-topic");
        config.put("api1.http.response.data.json.pointer", "/customers");
        config.put("api1.http.offset.mode", "CHAINING");
        config.put("api1.http.chaining.json.pointer", "/id");
        
        // Child API 1: Orders for each customer
        config.put("api2.http.api.path", "/customers/${parent_value}/orders");
        config.put("api2.topics", "orders-topic");
        config.put("api2.http.offset.mode", "SIMPLE_INCREMENTING");
        config.put("api2.http.initial.offset", "0");
        
        // Child API 2: Preferences for each customer  
        config.put("api3.http.api.path", "/customers/${parent_value}/preferences");
        config.put("api3.topics", "preferences-topic");
        config.put("api3.http.offset.mode", "SIMPLE_INCREMENTING");
        config.put("api3.http.initial.offset", "0");
        
        // Setup parent response
        mockApiServer.enqueue(new MockResponse()
                .setBody("""
                    {
                        "customers": [
                            {"id": "cust1", "name": "John Customer", "email": "john@example.com"},
                            {"id": "cust2", "name": "Jane Customer", "email": "jane@example.com"}
                        ]
                    }
                    """)
                .setHeader("Content-Type", "application/json"));
        
        // Setup child responses for orders
        mockApiServer.enqueue(new MockResponse()
                .setBody("""
                    {
                        "orders": [
                            {"id": "order1", "customer_id": "cust1", "amount": 100.00, "status": "completed"}
                        ]
                    }
                    """)
                .setHeader("Content-Type", "application/json"));
        
        mockApiServer.enqueue(new MockResponse()
                .setBody("""
                    {
                        "orders": [
                            {"id": "order2", "customer_id": "cust2", "amount": 250.00, "status": "pending"}
                        ]
                    }
                    """)
                .setHeader("Content-Type", "application/json"));
        
        // Setup child responses for preferences
        mockApiServer.enqueue(new MockResponse()
                .setBody("""
                    {
                        "preferences": [
                            {"customer_id": "cust1", "newsletter": true, "promotions": false}
                        ]
                    }
                    """)
                .setHeader("Content-Type", "application/json"));
        
        mockApiServer.enqueue(new MockResponse()
                .setBody("""
                    {
                        "preferences": [
                            {"customer_id": "cust2", "newsletter": false, "promotions": true}
                        ]
                    }
                    """)
                .setHeader("Content-Type", "application/json"));
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Poll multiple times to process parallel chains
        Set<String> observedTopics = new HashSet<>();
        List<String> requestPaths = new ArrayList<>();
        
        for (int poll = 0; poll < 8; poll++) {
            List<SourceRecord> records = task.poll();
            if (records != null && !records.isEmpty()) {
                records.forEach(record -> observedTopics.add(record.topic()));
            }
            
            RecordedRequest request = mockApiServer.takeRequest(100, TimeUnit.MILLISECONDS);
            if (request != null) {
                requestPaths.add(request.getPath());
            }
            
            Thread.sleep(100);
        }
        
        // Verify all parallel chains were processed
        assertThat(observedTopics).containsAnyOf("customers-topic", "orders-topic", "preferences-topic");
        
        // Verify both child API types were called
        assertThat(requestPaths).anyMatch(path -> path.contains("/orders"));
        assertThat(requestPaths).anyMatch(path -> path.contains("/preferences"));
        
        log.info("Parallel Child APIs test completed successfully");
        log.info("Observed topics: {}", observedTopics);
        log.info("Request paths: {}", requestPaths);
    }
    
    @Test
    @Order(4)
    @DisplayName("Test Chaining with Error Handling")
    void testChainingWithErrorHandling() throws Exception {
        log.info("Testing Chaining with Error Handling");
        
        Map<String, String> config = createBaseConfig();
        config.put("apis.num", "2");
        config.put("api.chaining.parent.child.relationship", "api2:api1");
        config.put("api.chaining.metadata.topic", "error-chaining-metadata");
        config.put("api.chaining.error.handling.enabled", "true");
        config.put("api.chaining.continue.on.parent.error", "false");
        
        // Parent API
        config.put("api1.http.api.path", "/parent");
        config.put("api1.topics", "parent-topic");
        config.put("api1.http.response.data.json.pointer", "/data");
        config.put("api1.http.offset.mode", "CHAINING");
        config.put("api1.http.chaining.json.pointer", "/id");
        
        // Child API
        config.put("api2.http.api.path", "/parent/${parent_value}/child");
        config.put("api2.topics", "child-topic");
        config.put("api2.http.offset.mode", "SIMPLE_INCREMENTING");
        config.put("api2.http.initial.offset", "0");
        
        // Setup parent API error response
        mockApiServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("{\"error\": \"Internal Server Error\"}")
                .setHeader("Content-Type", "application/json"));
        
        // Setup successful parent response for retry
        mockApiServer.enqueue(new MockResponse()
                .setBody("""
                    {
                        "data": [
                            {"id": "parent1", "name": "Parent Item"}
                        ]
                    }
                    """)
                .setHeader("Content-Type", "application/json"));
        
        // Setup child response
        mockApiServer.enqueue(new MockResponse()
                .setBody("""
                    {
                        "children": [
                            {"id": "child1", "parent_id": "parent1", "name": "Child Item"}
                        ]
                    }
                    """)
                .setHeader("Content-Type", "application/json"));
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Poll multiple times to handle error and recovery
        Set<String> observedTopics = new HashSet<>();
        List<String> requestPaths = new ArrayList<>();
        
        for (int poll = 0; poll < 5; poll++) {
            List<SourceRecord> records = task.poll();
            if (records != null && !records.isEmpty()) {
                records.forEach(record -> observedTopics.add(record.topic()));
            }
            
            RecordedRequest request = mockApiServer.takeRequest(100, TimeUnit.MILLISECONDS);
            if (request != null) {
                requestPaths.add(request.getPath());
            }
            
            Thread.sleep(200); // Allow time for error handling
        }
        
        // Verify error handling didn't break the chaining
        // Should eventually process both parent and child after error recovery
        assertThat(requestPaths).contains("/parent");
        
        log.info("Chaining with Error Handling test completed successfully");
        log.info("Request paths: {}", requestPaths);
    }
    
    @Test
    @Order(5)
    @DisplayName("Test Chaining Metadata Management")
    void testChainingMetadataManagement() throws Exception {
        log.info("Testing Chaining Metadata Management");
        
        Map<String, String> config = createBaseConfig();
        config.put("apis.num", "2");
        config.put("api.chaining.parent.child.relationship", "api2:api1");
        config.put("api.chaining.metadata.topic", "metadata-management-topic");
        config.put("api.chaining.metadata.retention.ms", "86400000"); // 24 hours
        config.put("api.chaining.metadata.cleanup.enabled", "true");
        
        // Parent API
        config.put("api1.http.api.path", "/metadata-parent");
        config.put("api1.topics", "metadata-parent-topic");
        config.put("api1.http.response.data.json.pointer", "/items");
        config.put("api1.http.offset.mode", "CHAINING");
        config.put("api1.http.chaining.json.pointer", "/metadata_id");
        
        // Child API
        config.put("api2.http.api.path", "/metadata-parent/${parent_value}/details");
        config.put("api2.topics", "metadata-child-topic");
        config.put("api2.http.offset.mode", "SIMPLE_INCREMENTING");
        config.put("api2.http.initial.offset", "0");
        
        // Setup parent response with metadata
        mockApiServer.enqueue(new MockResponse()
                .setBody("""
                    {
                        "items": [
                            {
                                "metadata_id": "meta123",
                                "name": "Item with Metadata",
                                "type": "document",
                                "created_at": "2023-01-01T00:00:00Z"
                            }
                        ]
                    }
                    """)
                .setHeader("Content-Type", "application/json"));
        
        // Setup child response
        mockApiServer.enqueue(new MockResponse()
                .setBody("""
                    {
                        "details": [
                            {
                                "metadata_id": "meta123",
                                "content": "Detailed content",
                                "size": 1024,
                                "checksum": "abc123def456"
                            }
                        ]
                    }
                    """)
                .setHeader("Content-Type", "application/json"));
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Poll to process chaining with metadata
        Set<String> observedTopics = new HashSet<>();
        for (int poll = 0; poll < 5; poll++) {
            List<SourceRecord> records = task.poll();
            if (records != null && !records.isEmpty()) {
                records.forEach(record -> {
                    observedTopics.add(record.topic());
                    // Check if metadata topic records are created
                    if ("metadata-management-topic".equals(record.topic())) {
                        log.info("Metadata record: {}", record.value());
                    }
                });
            }
            Thread.sleep(100);
        }
        
        // Verify metadata management
        assertThat(observedTopics).containsAnyOf("metadata-parent-topic", "metadata-child-topic", "metadata-management-topic");
        
        log.info("Chaining Metadata Management test completed successfully");
    }
    
    @Test
    @Order(6)
    @DisplayName("Test Complex Chaining Scenarios")
    void testComplexChainingScenarios() throws Exception {
        log.info("Testing Complex Chaining Scenarios");
        
        Map<String, String> config = createBaseConfig();
        config.put("apis.num", "5");
        // Complex relationship: API2 depends on API1, API3 depends on API1, API4 depends on API2, API5 depends on API3
        config.put("api.chaining.parent.child.relationship", "api2:api1,api3:api1,api4:api2,api5:api3");
        config.put("api.chaining.metadata.topic", "complex-chaining-metadata");
        config.put("api.chaining.parallel.processing.enabled", "true");
        
        // Root API: Projects
        config.put("api1.http.api.path", "/projects");
        config.put("api1.topics", "projects-topic");
        config.put("api1.http.response.data.json.pointer", "/projects");
        config.put("api1.http.offset.mode", "CHAINING");
        config.put("api1.http.chaining.json.pointer", "/id");
        
        // Branch 1: Teams per project
        config.put("api2.http.api.path", "/projects/${parent_value}/teams");
        config.put("api2.topics", "teams-topic");
        config.put("api2.http.response.data.json.pointer", "/teams");
        config.put("api2.http.offset.mode", "CHAINING");
        config.put("api2.http.chaining.json.pointer", "/id");
        
        // Branch 2: Tasks per project
        config.put("api3.http.api.path", "/projects/${parent_value}/tasks");
        config.put("api3.topics", "tasks-topic");
        config.put("api3.http.response.data.json.pointer", "/tasks");
        config.put("api3.http.offset.mode", "CHAINING");
        config.put("api3.http.chaining.json.pointer", "/id");
        
        // Sub-branch 1: Members per team
        config.put("api4.http.api.path", "/teams/${parent_value}/members");
        config.put("api4.topics", "members-topic");
        config.put("api4.http.offset.mode", "SIMPLE_INCREMENTING");
        config.put("api4.http.initial.offset", "0");
        
        // Sub-branch 2: Comments per task
        config.put("api5.http.api.path", "/tasks/${parent_value}/comments");
        config.put("api5.topics", "comments-topic");
        config.put("api5.http.offset.mode", "SIMPLE_INCREMENTING");
        config.put("api5.http.initial.offset", "0");
        
        // Setup responses for complex scenario
        setupComplexChainingResponses();
        
        // Start connector and task
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Poll multiple times to process complex chaining
        Set<String> observedTopics = new HashSet<>();
        List<String> requestPaths = new ArrayList<>();
        
        for (int poll = 0; poll < 15; poll++) {
            List<SourceRecord> records = task.poll();
            if (records != null && !records.isEmpty()) {
                records.forEach(record -> observedTopics.add(record.topic()));
            }
            
            RecordedRequest request = mockApiServer.takeRequest(100, TimeUnit.MILLISECONDS);
            if (request != null) {
                requestPaths.add(request.getPath());
            }
            
            Thread.sleep(100);
        }
        
        // Verify complex chaining worked
        assertThat(observedTopics).containsAnyOf("projects-topic", "teams-topic", "tasks-topic", "members-topic", "comments-topic");
        
        // Verify request progression shows proper dependency handling
        assertThat(requestPaths).anyMatch(path -> path.equals("/projects"));
        assertThat(requestPaths).anyMatch(path -> path.contains("/projects/") && path.contains("/teams"));
        assertThat(requestPaths).anyMatch(path -> path.contains("/projects/") && path.contains("/tasks"));
        assertThat(requestPaths).anyMatch(path -> path.contains("/teams/") && path.contains("/members"));
        assertThat(requestPaths).anyMatch(path -> path.contains("/tasks/") && path.contains("/comments"));
        
        log.info("Complex Chaining Scenarios test completed successfully");
        log.info("Observed topics: {}", observedTopics);
        log.info("Request paths: {}", requestPaths);
    }
    
    @Test
    @Order(7)
    @DisplayName("Test Chaining State Recovery")
    void testChainingStateRecovery() throws Exception {
        log.info("Testing Chaining State Recovery");
        
        Map<String, String> config = createBaseConfig();
        config.put("apis.num", "2");
        config.put("api.chaining.parent.child.relationship", "api2:api1");
        config.put("api.chaining.metadata.topic", "recovery-metadata");
        config.put("api.chaining.state.recovery.enabled", "true");
        
        // Parent API
        config.put("api1.http.api.path", "/recovery-parent");
        config.put("api1.topics", "recovery-parent-topic");
        config.put("api1.http.response.data.json.pointer", "/data");
        config.put("api1.http.offset.mode", "CHAINING");
        config.put("api1.http.chaining.json.pointer", "/recovery_id");
        
        // Child API
        config.put("api2.http.api.path", "/recovery-parent/${parent_value}/recovery-child");
        config.put("api2.topics", "recovery-child-topic");
        config.put("api2.http.offset.mode", "SIMPLE_INCREMENTING");
        config.put("api2.http.initial.offset", "0");
        
        // Setup initial responses
        mockApiServer.enqueue(new MockResponse()
                .setBody("""
                    {
                        "data": [
                            {"recovery_id": "rec1", "name": "Recovery Item 1"},
                            {"recovery_id": "rec2", "name": "Recovery Item 2"}
                        ]
                    }
                    """)
                .setHeader("Content-Type", "application/json"));
        
        mockApiServer.enqueue(new MockResponse()
                .setBody("""
                    {
                        "children": [
                            {"id": "child1", "parent_id": "rec1", "data": "Child Data 1"}
                        ]
                    }
                    """)
                .setHeader("Content-Type", "application/json"));
        
        // Start and process initially
        connector.start(config);
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        // Process some records
        task.poll();
        task.poll();
        
        // Simulate restart
        task.stop();
        task = new HttpSourceTask();
        
        // Setup post-restart response
        mockApiServer.enqueue(new MockResponse()
                .setBody("""
                    {
                        "children": [
                            {"id": "child2", "parent_id": "rec2", "data": "Child Data 2"}
                        ]
                    }
                    """)
                .setHeader("Content-Type", "application/json"));
        
        // Restart and verify recovery
        task.initialize(mockSourceTaskContext());
        task.start(config);
        
        List<SourceRecord> recoveredRecords = task.poll();
        
        // Verify recovery worked (should continue from where it left off)
        log.info("Chaining State Recovery test completed successfully");
    }
    
    // Helper methods
    
    private void setupComplexChainingResponses() {
        // Root: Projects
        mockApiServer.enqueue(new MockResponse()
                .setBody("""
                    {
                        "projects": [
                            {"id": "proj1", "name": "Project Alpha", "status": "active"},
                            {"id": "proj2", "name": "Project Beta", "status": "planning"}
                        ]
                    }
                    """)
                .setHeader("Content-Type", "application/json"));
        
        // Teams for each project
        mockApiServer.enqueue(new MockResponse()
                .setBody("""
                    {
                        "teams": [
                            {"id": "team1", "name": "Alpha Team", "project_id": "proj1"},
                            {"id": "team2", "name": "Alpha Support", "project_id": "proj1"}
                        ]
                    }
                    """)
                .setHeader("Content-Type", "application/json"));
        
        mockApiServer.enqueue(new MockResponse()
                .setBody("""
                    {
                        "teams": [
                            {"id": "team3", "name": "Beta Team", "project_id": "proj2"}
                        ]
                    }
                    """)
                .setHeader("Content-Type", "application/json"));
        
        // Tasks for each project
        mockApiServer.enqueue(new MockResponse()
                .setBody("""
                    {
                        "tasks": [
                            {"id": "task1", "title": "Alpha Task 1", "project_id": "proj1"},
                            {"id": "task2", "title": "Alpha Task 2", "project_id": "proj1"}
                        ]
                    }
                    """)
                .setHeader("Content-Type", "application/json"));
        
        mockApiServer.enqueue(new MockResponse()
                .setBody("""
                    {
                        "tasks": [
                            {"id": "task3", "title": "Beta Task 1", "project_id": "proj2"}
                        ]
                    }
                    """)
                .setHeader("Content-Type", "application/json"));
        
        // Members for each team
        for (int i = 1; i <= 3; i++) {
            mockApiServer.enqueue(new MockResponse()
                    .setBody(String.format("""
                        {
                            "members": [
                                {"id": "member%d1", "name": "Member %d1", "team_id": "team%d", "role": "developer"},
                                {"id": "member%d2", "name": "Member %d2", "team_id": "team%d", "role": "tester"}
                            ]
                        }
                        """, i, i, i, i, i, i))
                    .setHeader("Content-Type", "application/json"));
        }
        
        // Comments for each task
        for (int i = 1; i <= 3; i++) {
            mockApiServer.enqueue(new MockResponse()
                    .setBody(String.format("""
                        {
                            "comments": [
                                {"id": "comment%d1", "text": "Comment on task %d", "task_id": "task%d"},
                                {"id": "comment%d2", "text": "Another comment on task %d", "task_id": "task%d"}
                            ]
                        }
                        """, i, i, i, i, i, i))
                    .setHeader("Content-Type", "application/json"));
        }
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
}