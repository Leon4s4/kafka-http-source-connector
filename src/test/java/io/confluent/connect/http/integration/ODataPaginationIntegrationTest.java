package io.confluent.connect.http.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.connect.http.HttpSourceConnector;
import io.confluent.connect.http.HttpSourceTask;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for OData pagination functionality using TestContainers
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ODataPaginationIntegrationTest extends BaseIntegrationTest {

    @Container
    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"))
            .withEmbeddedZookeeper()
            .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
            .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1");

    private MockWebServer mockWebServer;
    private HttpSourceConnector connector;
    private HttpSourceTask task;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        objectMapper = new ObjectMapper();
        
        connector = new HttpSourceConnector();
        task = new HttpSourceTask();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (task != null) {
            task.stop();
        }
        if (connector != null) {
            connector.stop();
        }
        if (mockWebServer != null) {
            mockWebServer.shutdown();
        }
    }

    @Test
    @Order(1)
    void testODataPaginationWithNextLink() throws Exception {
        // Mock OData API responses with nextLink pagination
        String initialResponse = """
            {
              "value": [
                {
                  "accountid": "account1",
                  "name": "Company A",
                  "accountnumber": "ACC001"
                },
                {
                  "accountid": "account2", 
                  "name": "Company B",
                  "accountnumber": "ACC002"
                }
              ],
              "@odata.nextLink": "%BASEURL%/api/data/v9.0/accounts?$select=name,accountnumber&$skiptoken=%%3Ccookie%%20pagenumber=%%222%%22%%20pagingcookie=%%22%%253ccookie%%2520page%%253d%%25221%%2522%%253e%%253caccountid%%2520last%%253d%%2522%%257bAAA19CDD-88DF-E311-B8E5-6C3BE5A8B200%%257d%%2522%%2520first%%253d%%2522%%257b475B158C-541C-E511-80D3-3863BB347BA8%%257d%%2522%%2520%%252f%%253e%%253c%%252fcookie%%253e%%22%%20istracking=%%22False%%22%%20/%%3E"
            }
            """.replace("%BASEURL%", mockWebServer.url("").toString().replaceAll("/$", ""));

        String secondResponse = """
            {
              "value": [
                {
                  "accountid": "account3",
                  "name": "Company C", 
                  "accountnumber": "ACC003"
                }
              ],
              "@odata.deltaLink": "%BASEURL%/api/data/v9.0/accounts?$select=name,accountnumber&$deltatoken=919042%%2108%%2f22%%2f2017%%2008%%3a10%%3a44"
            }
            """.replace("%BASEURL%", mockWebServer.url("").toString().replaceAll("/$", ""));

        // Queue responses
        mockWebServer.enqueue(new MockResponse()
                .setBody(initialResponse)
                .setHeader("Content-Type", "application/json"));
                
        mockWebServer.enqueue(new MockResponse()
                .setBody(secondResponse) 
                .setHeader("Content-Type", "application/json"));

        // Configure connector for OData pagination
        Map<String, String> connectorConfig = createODataConnectorConfig("FULL_URL");
        
        // Start connector and tasks
        connector.start(connectorConfig);
        List<Map<String, String>> taskConfigs = connector.taskConfigs(1);
        assertThat(taskConfigs).hasSize(1);
        
        task.start(taskConfigs.get(0));

        // First poll should get initial page
        List<SourceRecord> firstBatch = task.poll();
        assertThat(firstBatch).hasSize(2);
        
        // Verify first batch content
        SourceRecord firstRecord = firstBatch.get(0);
        JsonNode firstValue = objectMapper.readTree(firstRecord.value().toString());
        assertThat(firstValue.get("accountid").asText()).isEqualTo("account1");
        assertThat(firstValue.get("name").asText()).isEqualTo("Company A");

        // Verify second batch content  
        SourceRecord secondRecord = firstBatch.get(1);
        JsonNode secondValue = objectMapper.readTree(secondRecord.value().toString());
        assertThat(secondValue.get("accountid").asText()).isEqualTo("account2");
        assertThat(secondValue.get("name").asText()).isEqualTo("Company B");

        // Second poll should use nextLink URL
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<SourceRecord> secondBatch = task.poll();
            assertThat(secondBatch).hasSize(1);
            
            JsonNode thirdValue = objectMapper.readTree(secondBatch.get(0).value().toString());
            assertThat(thirdValue.get("accountid").asText()).isEqualTo("account3");
            assertThat(thirdValue.get("name").asText()).isEqualTo("Company C");
        });

        // Verify the second request used the nextLink URL path
        RecordedRequest secondRequest = mockWebServer.takeRequest(); // First request
        RecordedRequest thirdRequest = mockWebServer.takeRequest();  // Second request with nextLink
        
        assertThat(thirdRequest.getPath()).contains("$skiptoken=");
        assertThat(thirdRequest.getPath()).contains("pagenumber=%%222%%22");
    }

    @Test
    @Order(2) 
    void testODataPaginationWithTokenOnlyMode() throws Exception {
        // Mock OData API responses
        String initialResponse = """
            {
              "value": [
                {
                  "accountid": "account1",
                  "name": "Company A"
                }
              ],
              "@odata.nextLink": "%BASEURL%/api/data/v9.0/accounts?$select=name&$skiptoken=simple-token-123"
            }
            """.replace("%BASEURL%", mockWebServer.url("").toString().replaceAll("/$", ""));

        String secondResponse = """
            {
              "value": [
                {
                  "accountid": "account2",
                  "name": "Company B"
                }
              ]
            }
            """;

        mockWebServer.enqueue(new MockResponse()
                .setBody(initialResponse)
                .setHeader("Content-Type", "application/json"));
                
        mockWebServer.enqueue(new MockResponse()
                .setBody(secondResponse)
                .setHeader("Content-Type", "application/json"));

        // Configure connector for TOKEN_ONLY mode
        Map<String, String> connectorConfig = createODataConnectorConfig("TOKEN_ONLY");
        
        connector.start(connectorConfig);
        List<Map<String, String>> taskConfigs = connector.taskConfigs(1);
        
        task.start(taskConfigs.get(0));

        // First poll
        List<SourceRecord> firstBatch = task.poll();
        assertThat(firstBatch).hasSize(1);

        // Second poll should use extracted token
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<SourceRecord> secondBatch = task.poll();
            assertThat(secondBatch).hasSize(1);
        });

        // Verify requests
        RecordedRequest firstRequest = mockWebServer.takeRequest();
        RecordedRequest secondRequest = mockWebServer.takeRequest();
        
        // First request should be the initial API path
        assertThat(firstRequest.getPath()).isEqualTo("/api/data/v9.0/accounts?$select=name,accountnumber");
        
        // Second request should append the extracted token
        assertThat(secondRequest.getPath()).contains("$skiptoken=simple-token-123");
    }

    @Test
    @Order(3)
    void testODataPaginationWithDeltaLink() throws Exception {
        String responseWithDeltaLink = """
            {
              "value": [
                {
                  "accountid": "account1",
                  "name": "Updated Company A",
                  "modifiedon": "2025-01-15T10:30:00Z"
                }
              ],
              "@odata.deltaLink": "%BASEURL%/api/data/v9.0/accounts?$select=name,accountnumber&$deltatoken=919042%%2108%%2f15%%2f2025%%2010%%3a30%%3a00"
            }
            """.replace("%BASEURL%", mockWebServer.url("").toString().replaceAll("/$", ""));

        mockWebServer.enqueue(new MockResponse()
                .setBody(responseWithDeltaLink)
                .setHeader("Content-Type", "application/json"));

        Map<String, String> connectorConfig = createODataConnectorConfig("FULL_URL");
        
        connector.start(connectorConfig);
        List<Map<String, String>> taskConfigs = connector.taskConfigs(1);
        
        task.start(taskConfigs.get(0));

        List<SourceRecord> records = task.poll();
        assertThat(records).hasSize(1);
        
        JsonNode value = objectMapper.readTree(records.get(0).value().toString());
        assertThat(value.get("name").asText()).isEqualTo("Updated Company A");

        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getPath()).isEqualTo("/api/data/v9.0/accounts?$select=name,accountnumber");
    }

    @Test
    @Order(4)
    void testODataPaginationWithCustomFieldNames() throws Exception {
        String responseWithCustomFields = """
            {
              "data": [
                {
                  "id": "custom1",
                  "title": "Custom Entity A"
                }
              ],
              "pagination": {
                "next": "%BASEURL%/api/custom?cursor=custom-next-token"
              }
            }
            """.replace("%BASEURL%", mockWebServer.url("").toString().replaceAll("/$", ""));

        mockWebServer.enqueue(new MockResponse()
                .setBody(responseWithCustomFields)
                .setHeader("Content-Type", "application/json"));

        // Configure with custom field names
        Map<String, String> connectorConfig = createODataConnectorConfig("FULL_URL");
        connectorConfig.put("api1.odata.nextlink.field", "pagination.next");
        connectorConfig.put("api1.odata.deltalink.field", "pagination.delta");
        connectorConfig.put("api1.http.response.data.json.pointer", "/data");
        
        connector.start(connectorConfig);
        List<Map<String, String>> taskConfigs = connector.taskConfigs(1);
        
        task.start(taskConfigs.get(0));

        List<SourceRecord> records = task.poll();
        assertThat(records).hasSize(1);
        
        JsonNode value = objectMapper.readTree(records.get(0).value().toString());
        assertThat(value.get("id").asText()).isEqualTo("custom1");
        assertThat(value.get("title").asText()).isEqualTo("Custom Entity A");
    }

    @Test
    @Order(5)
    void testODataPaginationEndOfData() throws Exception {
        // Response without nextLink or deltaLink indicates end of pagination
        String finalResponse = """
            {
              "value": [
                {
                  "accountid": "final-account",
                  "name": "Final Company"
                }
              ]
            }
            """;

        mockWebServer.enqueue(new MockResponse()
                .setBody(finalResponse)
                .setHeader("Content-Type", "application/json"));

        Map<String, String> connectorConfig = createODataConnectorConfig("FULL_URL");
        
        connector.start(connectorConfig);
        List<Map<String, String>> taskConfigs = connector.taskConfigs(1);
        
        task.start(taskConfigs.get(0));

        List<SourceRecord> records = task.poll();
        assertThat(records).hasSize(1);

        // Second poll should return empty since no more pages
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            List<SourceRecord> emptyBatch = task.poll();
            assertThat(emptyBatch).isEmpty();
        });
    }

    @Test
    @Order(6)
    void testODataPaginationWithMalformedUrl() throws Exception {
        String responseWithMalformedNextLink = """
            {
              "value": [
                {
                  "accountid": "account1",
                  "name": "Company A"
                }
              ],
              "@odata.nextLink": "not-a-valid-url"
            }
            """;

        mockWebServer.enqueue(new MockResponse()
                .setBody(responseWithMalformedNextLink)
                .setHeader("Content-Type", "application/json"));

        Map<String, String> connectorConfig = createODataConnectorConfig("FULL_URL");
        
        connector.start(connectorConfig);
        List<Map<String, String>> taskConfigs = connector.taskConfigs(1);
        
        task.start(taskConfigs.get(0));

        // Should handle malformed URL gracefully
        List<SourceRecord> records = task.poll();
        assertThat(records).hasSize(1);
        
        JsonNode value = objectMapper.readTree(records.get(0).value().toString());
        assertThat(value.get("name").asText()).isEqualTo("Company A");
    }

    private Map<String, String> createODataConnectorConfig(String tokenMode) {
        Map<String, String> config = new HashMap<>();
        
        // Basic connector configuration
        config.put("name", "odata-test-connector");
        config.put("connector.class", "io.confluent.connect.http.HttpSourceConnector");
        config.put("tasks.max", "1");
        
        // HTTP API configuration
        config.put("http.api.base.url", mockWebServer.url("").toString().replaceAll("/$", ""));
        config.put("apis.num", "1");
        
        // API 1 configuration for OData
        config.put("api1.http.api.path", "/api/data/v9.0/accounts?$select=name,accountnumber");
        config.put("api1.topics", "odata-accounts");
        config.put("api1.http.request.method", "GET");
        config.put("api1.http.offset.mode", "ODATA_PAGINATION");
        config.put("api1.http.initial.offset", "");
        config.put("api1.request.interval.ms", "1000");
        
        // OData-specific configuration
        config.put("api1.odata.nextlink.field", "@odata.nextLink");
        config.put("api1.odata.deltalink.field", "@odata.deltaLink");
        config.put("api1.odata.token.mode", tokenMode);
        config.put("api1.http.response.data.json.pointer", "/value");
        
        // Data format
        config.put("output.data.format", "JSON_SR");
        
        // Error handling
        config.put("behavior.on.error", "FAIL");
        
        return config;
    }
}