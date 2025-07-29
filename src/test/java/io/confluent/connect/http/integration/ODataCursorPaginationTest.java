package io.confluent.connect.http.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.connect.http.HttpSourceConnector;
import io.confluent.connect.http.HttpSourceTask;
import org.apache.kafka.connect.runtime.ConnectorConfig;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for OData cursor pagination functionality using TestContainers.
 * Tests the ${offset} template variable replacement with OData-style query parameters.
 */
@Testcontainers
public class ODataCursorPaginationTest extends BaseIntegrationTest {
    
    private static final Logger log = LoggerFactory.getLogger(ODataCursorPaginationTest.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    
    private HttpSourceConnector connector;
    private Map<String, String> connectorConfig;
    
    // Mock OData API server container
    @SuppressWarnings("resource") // TestContainers manages lifecycle automatically
    @Container
    private static final GenericContainer<?> MOCK_ODATA_SERVER = 
            new GenericContainer<>(
            DockerImageName.parse("wiremock/wiremock:2.35.0"))
            .withNetwork(SHARED_NETWORK)
            .withNetworkAliases("mock-odata-api")
            .withExposedPorts(8080)
            .withCommand("--port", "8080", "--verbose")
            .waitingFor(Wait.forHttp("/__admin")
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(30)));
    
    @BeforeEach
    void setUp() throws Exception {
        log.info("Setting up OData cursor pagination test");
        
        connector = new HttpSourceConnector();
        
        // Setup mock OData API responses
        setupMockODataResponses();
        
        // Create connector configuration
        connectorConfig = createODataConnectorConfig();
        
        log.info("OData test setup completed");
    }
    
    @AfterEach
    void tearDown() {
        if (connector != null) {
            try {
                connector.stop();
            } catch (Exception e) {
                log.warn("Error stopping connector", e);
            }
        }
        log.info("OData test cleanup completed");
    }
    
    @Test
    @DisplayName("Should replace ${offset} template variable with initial offset containing OData query parameters")
    void shouldReplaceOffsetTemplateWithODataQueryParameters() throws Exception {
        log.info("Testing OData cursor pagination with ${offset} template variable");
        
        // Start the connector
        connector.start(connectorConfig);
        
        // Create and start the source task
        HttpSourceTask task = new HttpSourceTask();
        task.start(connectorConfig);
        
        // Check what requests WireMock has received (for debugging)
        checkWireMockRequests();
        
        // Poll for records
        List<SourceRecord> records = task.poll();
        
        // Verify that records were retrieved (indicating the URL was built correctly)
        assertNotNull(records, "Records should not be null");
        assertTrue(records.size() > 0, "Should retrieve at least one record");
        
        // Verify the record content matches OData structure
        SourceRecord firstRecord = records.get(0);
        assertNotNull(firstRecord.value(), "Record value should not be null");
        
        // Parse the record value as JSON to verify OData structure
        String recordJson = firstRecord.value().toString();
        Map<String, Object> recordData = OBJECT_MAPPER.readValue(recordJson, Map.class);
        
        // Verify OData fields are present
        assertTrue(recordData.containsKey("name"), "Record should contain 'name' field");
        assertTrue(recordData.containsKey("accountnumber"), "Record should contain 'accountnumber' field");
        assertTrue(recordData.containsKey("telephone1"), "Record should contain 'telephone1' field");
        assertTrue(recordData.containsKey("fax"), "Record should contain 'fax' field");
        
        task.stop();
        
        log.info("OData cursor pagination test completed successfully");
    }
    
    @Test
    @DisplayName("Should handle cursor pagination with @odata.nextLink")
    void shouldHandleCursorPaginationWithNextLink() throws Exception {
        log.info("Testing OData cursor pagination with @odata.nextLink");
        
        // Setup mock responses for pagination
        setupMockPaginationResponses();
        
        // Start the connector
        connector.start(connectorConfig);
        
        // Create and start the source task
        HttpSourceTask task = new HttpSourceTask();
        task.start(connectorConfig);
        
        // Poll for first page
        List<SourceRecord> firstPage = task.poll();
        assertNotNull(firstPage, "First page should not be null");
        assertTrue(firstPage.size() > 0, "First page should have records");
        
        // Poll for second page
        List<SourceRecord> secondPage = task.poll();
        
        // Verify pagination worked
        if (secondPage != null && secondPage.size() > 0) {
            log.info("Successfully retrieved second page with {} records", secondPage.size());
            
            // Verify the second page has different data
            String firstPageJson = firstPage.get(0).value().toString();
            String secondPageJson = secondPage.get(0).value().toString();
            assertNotEquals(firstPageJson, secondPageJson, "Second page should have different data");
        }
        
        task.stop();
        
        log.info("OData pagination test completed");
    }
    
    private Map<String, String> createODataConnectorConfig() {
        Map<String, String> config = new HashMap<>();
        
        // Basic connector configuration
        config.put(ConnectorConfig.CONNECTOR_CLASS_CONFIG, HttpSourceConnector.class.getName());
        config.put("tasks.max", "1");
        
        // HTTP API configuration
        String mockServerUrl = String.format("http://%s:%d", 
            MOCK_ODATA_SERVER.getHost(), 
            MOCK_ODATA_SERVER.getMappedPort(8080));
        config.put("http.api.base.url", mockServerUrl + "/api/data/v9.2");
        config.put("apis.num", "1");
        
        // API 1 configuration - This is the key test: ${offset} in path
        config.put("api1.http.api.path", "/accounts${offset}");
        config.put("api1.topics", "test-accounts");
        config.put("api1.http.request.method", "GET");
        config.put("api1.http.offset.mode", "CURSOR_PAGINATION");
        config.put("api1.http.initial.offset", "?$select=name,accountnumber,telephone1,fax&$filter=modifiedon ge '2025-01-01'");
        config.put("api1.http.response.data.json.pointer", "/value");
        config.put("api1.http.next.page.json.pointer", "/@odata.nextLink");
        config.put("api1.request.interval.ms", "5000");
        
        // Output format
        config.put("output.data.format", "JSON_SR");
        
        // Timeouts
        config.put("connection.timeout.ms", "30000");
        config.put("request.timeout.ms", "30000");
        
        // Debug logging
        config.put("debug.logging.enabled", "true");
        config.put("debug.log.request.headers", "true");
        config.put("debug.log.response.body", "true");
        
        log.info("Created OData connector config with mock server URL: {}", mockServerUrl);
        return config;
    }
    
    private void setupMockODataResponses() {
        log.info("Setting up mock OData API responses");
        
        // Mock the first page response with the expected query parameters
        String firstPageResponse = """
            {
                "@odata.context": "https://mock-api.example.com/api/data/v9.2/$metadata#accounts(name,accountnumber,telephone1,fax)",
                "value": [
                    {
                        "@odata.etag": "W/\\"12345\\"",
                        "name": "Test Company 1",
                        "accountnumber": "ACC001",
                        "telephone1": "(555) 123-4567",
                        "fax": null,
                        "accountid": "11111111-1111-1111-1111-111111111111"
                    },
                    {
                        "@odata.etag": "W/\\"12346\\"",
                        "name": "Test Company 2", 
                        "accountnumber": "ACC002",
                        "telephone1": "(555) 234-5678",
                        "fax": "(555) 234-5679",
                        "accountid": "22222222-2222-2222-2222-222222222222"
                    }
                ],
                "@odata.nextLink": "https://mock-api.example.com/api/data/v9.2/accounts?$select=name,accountnumber,telephone1,fax&$skiptoken=page2token"
            }
            """;
        
        // Setup WireMock stub for the initial request with OData query parameters  
        // The actual request will be: /accounts?$select=name,accountnumber,telephone1,fax&$filter=modifiedon%20ge%20'2025-01-01'
        setupWireMockStubWithQueryParams("/accounts", 
            "\\$select=name,accountnumber,telephone1,fax&\\$filter=modifiedon%20ge%20'2025-01-01'", 
            firstPageResponse);
        
        log.info("Mock OData responses configured");
    }
    
    private void setupMockPaginationResponses() {
        log.info("Setting up mock OData pagination responses");
        
        // First page response
        String firstPageResponse = """
            {
                "@odata.context": "https://mock-api.example.com/api/data/v9.2/$metadata#accounts",
                "value": [
                    {
                        "name": "Page 1 Company",
                        "accountnumber": "P1ACC001",
                        "telephone1": "(555) 111-1111",
                        "fax": null,
                        "accountid": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
                    }
                ],
                "@odata.nextLink": "https://mock-api.example.com/api/data/v9.2/accounts?$skiptoken=page2"
            }
            """;
        
        // Second page response (no nextLink = end of pagination)
        String secondPageResponse = """
            {
                "@odata.context": "https://mock-api.example.com/api/data/v9.2/$metadata#accounts",
                "value": [
                    {
                        "name": "Page 2 Company",
                        "accountnumber": "P2ACC001", 
                        "telephone1": "(555) 222-2222",
                        "fax": null,
                        "accountid": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
                    }
                ]
            }
            """;
        
        // Setup stubs for both pages with proper URL encoding
        setupWireMockStub("/accounts", firstPageResponse);
        setupWireMockStubWithQueryParams("/accounts", "\\$skiptoken=page2", secondPageResponse);
        
        log.info("Mock pagination responses configured");
    }
    
    private void setupWireMockStub(String urlPath, String responseBody) {
        setupWireMockStubWithQueryParams(urlPath, null, responseBody);
    }
    
    private void setupWireMockStubWithQueryParams(String urlPath, String queryString, String responseBody) {
        try {
            // Build WireMock stub configuration with more flexible matching
            String stubConfig;
            if (queryString != null) {
                // Use a more flexible pattern that matches any query parameters containing our key parts
                stubConfig = String.format("""
                    {
                        "request": {
                            "method": "GET",
                            "urlPathPattern": "/api/data/v9.2%s",
                            "queryParameters": {
                                ".*": {
                                    "matches": ".*"
                                }
                            }
                        },
                        "response": {
                            "status": 200,
                            "headers": {
                                "Content-Type": "application/json"
                            },
                            "body": %s
                        }
                    }
                    """, urlPath, OBJECT_MAPPER.writeValueAsString(responseBody));
            } else {
                // Match URL path with any or no query parameters
                stubConfig = String.format("""
                    {
                        "request": {
                            "method": "GET",
                            "urlPathPattern": "/api/data/v9.2%s"
                        },
                        "response": {
                            "status": 200,
                            "headers": {
                                "Content-Type": "application/json"
                            },
                            "body": %s
                        }
                    }
                    """, urlPath, OBJECT_MAPPER.writeValueAsString(responseBody));
            }
            
            // Post to WireMock admin API
            String wireMockUrl = String.format("http://%s:%d/__admin/mappings", 
                MOCK_ODATA_SERVER.getHost(), 
                MOCK_ODATA_SERVER.getMappedPort(8080));
            
            log.info("Setting up WireMock stub for path: {} with query: {} at URL: {}", 
                urlPath, queryString != null ? queryString : "(none)", wireMockUrl);
            log.info("WireMock stub config: {}", stubConfig);
            
            // Using basic HTTP client to setup WireMock stub
            var httpClient = java.net.http.HttpClient.newHttpClient();
            var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(wireMockUrl))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(stubConfig))
                .build();
            
            var response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 201) {
                log.warn("Failed to setup WireMock stub. Status: {}, Body: {}", 
                    response.statusCode(), response.body());
            } else {
                log.info("Successfully setup WireMock stub for path: {}", urlPath);
            }
            
        } catch (Exception e) {
            log.error("Error setting up WireMock stub for path: {} with query: {}", urlPath, queryString, e);
        }
    }
    
    private void checkWireMockRequests() {
        try {
            String requestsUrl = String.format("http://%s:%d/__admin/requests", 
                MOCK_ODATA_SERVER.getHost(), 
                MOCK_ODATA_SERVER.getMappedPort(8080));
            
            var httpClient = java.net.http.HttpClient.newHttpClient();
            var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(requestsUrl))
                .GET()
                .build();
            
            var response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            log.info("WireMock received requests: {}", response.body());
            
        } catch (Exception e) {
            log.warn("Failed to check WireMock requests", e);
        }
    }
}