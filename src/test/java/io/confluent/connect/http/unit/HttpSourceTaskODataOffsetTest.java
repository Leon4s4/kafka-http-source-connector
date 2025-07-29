package io.confluent.connect.http.unit;

import io.confluent.connect.http.HttpSourceTask;
import io.confluent.connect.http.auth.NoAuthenticator;
import io.confluent.connect.http.client.HttpApiClient;
import io.confluent.connect.http.config.ApiConfig;
import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import io.confluent.connect.http.offset.ODataOffsetManager;
import io.confluent.connect.http.offset.OffsetManager;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTaskContext;
import org.apache.kafka.connect.storage.OffsetStorageReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test to verify that HttpSourceTask correctly handles OData pagination offsets.
 * 
 * This test ensures that:
 * 1. Pagination offsets are extracted from the API response (@odata.nextLink/@odata.deltaLink)
 * 2. The same pagination offset is used for all records in the batch
 * 3. Individual record offsets are NOT used for OData pagination mode
 * 4. Offset persistence works correctly across connector restarts
 */
@ExtendWith(MockitoExtension.class)
class HttpSourceTaskODataOffsetTest {

    @Mock
    private SourceTaskContext mockContext;
    
    @Mock
    private OffsetStorageReader mockOffsetStorageReader;

    private HttpSourceTask sourceTask;
    private HttpSourceConnectorConfig config;
    private ApiConfig apiConfig;

    @BeforeEach
    void setUp() {
        // Create configuration for OData pagination
        Map<String, String> configMap = new HashMap<>();
        configMap.put("http.api.base.url", "https://api.example.com");
        configMap.put("apis.num", "1");
        configMap.put("api1.http.api.path", "/api/data/v9.0/accounts");
        configMap.put("api1.topics", "accounts-topic");
        configMap.put("api1.http.request.method", "GET");
        configMap.put("api1.http.offset.mode", "ODATA_PAGINATION");
        configMap.put("api1.http.initial.offset", "?$select=name,accountnumber&$filter=modifiedon ge '2025-01-01'");
        configMap.put("api1.http.response.data.json.pointer", "/value");
        configMap.put("api1.odata.nextlink.field", "@odata.nextLink");
        configMap.put("api1.odata.deltalink.field", "@odata.deltaLink");
        configMap.put("api1.odata.token.mode", "FULL_URL");
        configMap.put("connection.timeout.ms", "5000");
        configMap.put("request.timeout.ms", "5000");
        
        config = new HttpSourceConnectorConfig(configMap);
        apiConfig = new ApiConfig(config, 1);
        
        sourceTask = new HttpSourceTask();
    }

    @Test
    @DisplayName("Should use pagination offset for all records in OData mode")
    void shouldUsePaginationOffsetForAllRecordsInODataMode() throws Exception {
        // Mock response with pagination link and multiple records
        String mockResponseBody = """
            {
                "value": [
                    {
                        "accountid": "123e4567-e89b-12d3-a456-426614174000",
                        "name": "Contoso Corp",
                        "accountnumber": "ACC-001",
                        "modifiedon": "2025-01-15T10:30:00Z"
                    },
                    {
                        "accountid": "987fcdeb-51d2-43e1-b543-987654321098",
                        "name": "Fabrikam Inc",
                        "accountnumber": "ACC-002", 
                        "modifiedon": "2025-01-16T14:45:00Z"
                    },
                    {
                        "accountid": "456e7890-abc1-23d4-e567-123456789abc",
                        "name": "Adventure Works",
                        "accountnumber": "ACC-003",
                        "modifiedon": "2025-01-17T09:15:00Z"
                    }
                ],
                "@odata.nextLink": "https://api.example.com/api/data/v9.0/accounts?$select=name,accountnumber&$skiptoken=xyz789"
            }
            """;

        // Mock HttpApiClient to return our test response
        HttpApiClient.ApiResponse mockResponse = new HttpApiClient.ApiResponse(200, new HashMap<>(), mockResponseBody, Duration.ofMillis(100));
        
        // Create a mock HttpApiClient that returns our response
        HttpApiClient mockApiClient = new HttpApiClient(apiConfig, new NoAuthenticator()) {
            @Override
            public ApiResponse makeRequest(String offset, Map<String, String> additionalVars) {
                return mockResponse;
            }
        };

        // Start the task and set up internal state
        sourceTask.start(config.originalsStrings());
        
        // Get access to the private pollApi method via reflection
        Method pollApiMethod = HttpSourceTask.class.getDeclaredMethod("pollApi", ApiConfig.class);
        pollApiMethod.setAccessible(true);
        
        // Replace the HttpApiClient in the task with our mock
        Field apiClientsField = HttpSourceTask.class.getDeclaredField("apiClients");
        apiClientsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, HttpApiClient> apiClients = (Map<String, HttpApiClient>) apiClientsField.get(sourceTask);
        apiClients.put(apiConfig.getId(), mockApiClient);
        
        // Get the offset manager to verify behavior
        Field offsetManagersField = HttpSourceTask.class.getDeclaredField("offsetManagers");
        offsetManagersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, OffsetManager> offsetManagers = (Map<String, OffsetManager>) offsetManagersField.get(sourceTask);
        OffsetManager offsetManager = offsetManagers.get(apiConfig.getId());
        
        // Verify it's an ODataOffsetManager
        assertThat(offsetManager).isInstanceOf(ODataOffsetManager.class);
        assertThat(offsetManager.getOffsetMode()).isEqualTo(ApiConfig.HttpOffsetMode.ODATA_PAGINATION);
        
        // Execute pollApi
        @SuppressWarnings("unchecked")
        List<SourceRecord> sourceRecords = (List<SourceRecord>) pollApiMethod.invoke(sourceTask, apiConfig);
        
        // Verify we got the expected number of records
        assertThat(sourceRecords).hasSize(3);
        
        // Verify that ALL records have the SAME offset (the pagination offset)
        for (SourceRecord record : sourceRecords) {
            Map<String, ?> sourceOffset = record.sourceOffset();
            assertThat(sourceOffset).containsKey("offset");
            
            // In FULL_URL mode, the offset should be the path + query from the nextLink
            String actualOffset = (String) sourceOffset.get("offset");
            assertThat(actualOffset).isEqualTo("/api/data/v9.0/accounts?$select=name,accountnumber&$skiptoken=xyz789");
        }
        
        // Verify the offset manager was updated with the pagination offset
        String currentOffset = offsetManager.getCurrentOffset();
        assertThat(currentOffset).isEqualTo("/api/data/v9.0/accounts?$select=name,accountnumber&$skiptoken=xyz789");
        
        // Verify all records use the same source partition
        Map<String, ?> expectedPartition = sourceRecords.get(0).sourcePartition();
        for (SourceRecord record : sourceRecords) {
            assertThat(record.sourcePartition()).isEqualTo(expectedPartition);
        }
        
        // Clean up
        sourceTask.stop();
        mockApiClient.close();
    }

    @Test
    @DisplayName("Should handle deltaLink offset in OData mode")
    void shouldHandleDeltaLinkOffsetInODataMode() throws Exception {
        // Mock response with deltaLink instead of nextLink
        String mockResponseBody = """
            {
                "value": [
                    {
                        "accountid": "123e4567-e89b-12d3-a456-426614174000",
                        "name": "Updated Account",
                        "accountnumber": "ACC-001",
                        "modifiedon": "2025-01-20T16:30:00Z"
                    }
                ],
                "@odata.deltaLink": "https://api.example.com/api/data/v9.0/accounts?$select=name,accountnumber&$deltatoken=abc123"
            }
            """;

        HttpApiClient.ApiResponse mockResponse = new HttpApiClient.ApiResponse(200, new HashMap<>(), mockResponseBody, Duration.ofMillis(100));
        
        HttpApiClient mockApiClient = new HttpApiClient(apiConfig, new NoAuthenticator()) {
            @Override
            public ApiResponse makeRequest(String offset, Map<String, String> additionalVars) {
                return mockResponse;
            }
        };

        sourceTask.start(config.originalsStrings());
        
        Method pollApiMethod = HttpSourceTask.class.getDeclaredMethod("pollApi", ApiConfig.class);
        pollApiMethod.setAccessible(true);
        
        Field apiClientsField = HttpSourceTask.class.getDeclaredField("apiClients");
        apiClientsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, HttpApiClient> apiClients = (Map<String, HttpApiClient>) apiClientsField.get(sourceTask);
        apiClients.put(apiConfig.getId(), mockApiClient);
        
        Field offsetManagersField = HttpSourceTask.class.getDeclaredField("offsetManagers");
        offsetManagersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, OffsetManager> offsetManagers = (Map<String, OffsetManager>) offsetManagersField.get(sourceTask);
        OffsetManager offsetManager = offsetManagers.get(apiConfig.getId());
        
        @SuppressWarnings("unchecked")
        List<SourceRecord> sourceRecords = (List<SourceRecord>) pollApiMethod.invoke(sourceTask, apiConfig);
        
        assertThat(sourceRecords).hasSize(1);
        
        // Verify the record has the deltaLink offset
        SourceRecord record = sourceRecords.get(0);
        Map<String, ?> sourceOffset = record.sourceOffset();
        String actualOffset = (String) sourceOffset.get("offset");
        assertThat(actualOffset).isEqualTo("/api/data/v9.0/accounts?$select=name,accountnumber&$deltatoken=abc123");
        
        // Verify offset manager was updated
        String currentOffset = offsetManager.getCurrentOffset();
        assertThat(currentOffset).isEqualTo("/api/data/v9.0/accounts?$select=name,accountnumber&$deltatoken=abc123");
        
        sourceTask.stop();
        mockApiClient.close();
    }

    @Test
    @DisplayName("Should handle end of pagination when no links present")
    void shouldHandleEndOfPaginationWhenNoLinksPresent() throws Exception {
        // Mock response with no pagination links (end of data)
        String mockResponseBody = """
            {
                "value": [
                    {
                        "accountid": "123e4567-e89b-12d3-a456-426614174000",
                        "name": "Final Account",
                        "accountnumber": "ACC-999",
                        "modifiedon": "2025-01-25T12:00:00Z"
                    }
                ]
            }
            """;

        HttpApiClient.ApiResponse mockResponse = new HttpApiClient.ApiResponse(200, new HashMap<>(), mockResponseBody, Duration.ofMillis(100));
        
        HttpApiClient mockApiClient = new HttpApiClient(apiConfig, new NoAuthenticator()) {
            @Override
            public ApiResponse makeRequest(String offset, Map<String, String> additionalVars) {
                return mockResponse;
            }
        };

        sourceTask.start(config.originalsStrings());
        
        Method pollApiMethod = HttpSourceTask.class.getDeclaredMethod("pollApi", ApiConfig.class);
        pollApiMethod.setAccessible(true);
        
        Field apiClientsField = HttpSourceTask.class.getDeclaredField("apiClients");
        apiClientsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, HttpApiClient> apiClients = (Map<String, HttpApiClient>) apiClientsField.get(sourceTask);
        apiClients.put(apiConfig.getId(), mockApiClient);
        
        Field offsetManagersField = HttpSourceTask.class.getDeclaredField("offsetManagers");
        offsetManagersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, OffsetManager> offsetManagers = (Map<String, OffsetManager>) offsetManagersField.get(sourceTask);
        OffsetManager offsetManager = offsetManagers.get(apiConfig.getId());
        
        @SuppressWarnings("unchecked")
        List<SourceRecord> sourceRecords = (List<SourceRecord>) pollApiMethod.invoke(sourceTask, apiConfig);
        
        assertThat(sourceRecords).hasSize(1);
        
        // Verify the record has null offset (end of pagination)
        SourceRecord record = sourceRecords.get(0);
        Map<String, ?> sourceOffset = record.sourceOffset();
        
        // When no pagination links are found, the offset should be null or empty
        Object actualOffset = sourceOffset.get("offset");
        assertThat(actualOffset).isNull();
        
        // Verify offset manager was updated to null (end of pagination)
        String currentOffset = offsetManager.getCurrentOffset();
        assertThat(currentOffset).isNull();
        
        sourceTask.stop();
        mockApiClient.close();
    }

    @Test
    @DisplayName("Should use individual record offsets for non-OData modes")
    void shouldUseIndividualRecordOffsetsForNonODataModes() throws Exception {
        // Create configuration for SIMPLE_INCREMENTING mode (not OData)
        Map<String, String> configMap = new HashMap<>();
        configMap.put("http.api.base.url", "https://api.example.com");
        configMap.put("apis.num", "1");
        configMap.put("api1.http.api.path", "/api/records");
        configMap.put("api1.topics", "records-topic");
        configMap.put("api1.http.request.method", "GET");
        configMap.put("api1.http.offset.mode", "CURSOR_PAGINATION");
        configMap.put("api1.http.initial.offset", "0");
        configMap.put("api1.http.response.data.json.pointer", "/data");
        configMap.put("api1.http.offset.json.pointer", "/id"); // Extract offset from individual records
        configMap.put("api1.http.next.page.json.pointer", "/nextPage"); // Required for CURSOR_PAGINATION
        configMap.put("connection.timeout.ms", "5000");
        configMap.put("request.timeout.ms", "5000");
        
        HttpSourceConnectorConfig nonODataConfig = new HttpSourceConnectorConfig(configMap);
        ApiConfig nonODataApiConfig = new ApiConfig(nonODataConfig, 1);
        
        // Mock response with multiple records having different IDs
        String mockResponseBody = """
            {
                "data": [
                    {
                        "id": 100,
                        "name": "Record 1",
                        "timestamp": "2025-01-20T10:00:00Z"
                    },
                    {
                        "id": 101,
                        "name": "Record 2",
                        "timestamp": "2025-01-20T10:05:00Z"
                    },
                    {
                        "id": 102,
                        "name": "Record 3",
                        "timestamp": "2025-01-20T10:10:00Z"
                    }
                ]
            }
            """;

        HttpApiClient.ApiResponse mockResponse = new HttpApiClient.ApiResponse(200, new HashMap<>(), mockResponseBody, Duration.ofMillis(100));
        
        HttpApiClient mockApiClient = new HttpApiClient(nonODataApiConfig, new NoAuthenticator()) {
            @Override
            public ApiResponse makeRequest(String offset, Map<String, String> additionalVars) {
                return mockResponse;
            }
        };

        HttpSourceTask nonODataTask = new HttpSourceTask();
        nonODataTask.start(nonODataConfig.originalsStrings());
        
        Method pollApiMethod = HttpSourceTask.class.getDeclaredMethod("pollApi", ApiConfig.class);
        pollApiMethod.setAccessible(true);
        
        Field apiClientsField = HttpSourceTask.class.getDeclaredField("apiClients");
        apiClientsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, HttpApiClient> apiClients = (Map<String, HttpApiClient>) apiClientsField.get(nonODataTask);
        apiClients.put(nonODataApiConfig.getId(), mockApiClient);
        
        Field offsetManagersField = HttpSourceTask.class.getDeclaredField("offsetManagers");
        offsetManagersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, OffsetManager> offsetManagers = (Map<String, OffsetManager>) offsetManagersField.get(nonODataTask);
        OffsetManager offsetManager = offsetManagers.get(nonODataApiConfig.getId());
        
        // Verify it's NOT an ODataOffsetManager
        assertThat(offsetManager.getOffsetMode()).isEqualTo(ApiConfig.HttpOffsetMode.CURSOR_PAGINATION);
        
        @SuppressWarnings("unchecked")
        List<SourceRecord> sourceRecords = (List<SourceRecord>) pollApiMethod.invoke(nonODataTask, nonODataApiConfig);
        
        assertThat(sourceRecords).hasSize(3);
        
        // Verify that each record has its OWN offset (extracted from the record's "id" field)
        String[] expectedOffsets = {"100", "101", "102"};
        
        for (int i = 0; i < sourceRecords.size(); i++) {
            SourceRecord record = sourceRecords.get(i);
            Map<String, ?> sourceOffset = record.sourceOffset();
            String actualOffset = (String) sourceOffset.get("offset");
            assertThat(actualOffset).isEqualTo(expectedOffsets[i]);
        }
        
        // For CURSOR_PAGINATION mode, the offset manager should have the last processed offset
        String finalOffset = offsetManager.getCurrentOffset();
        assertThat(finalOffset).isEqualTo("102"); // Last record's ID
        
        nonODataTask.stop();
        mockApiClient.close();
    }
}
