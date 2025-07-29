package io.confluent.connect.http.unit;

import io.confluent.connect.http.HttpSourceTask;
import io.confluent.connect.http.config.ApiConfig;
import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import io.confluent.connect.http.offset.ODataOffsetManager;
import io.confluent.connect.http.offset.OffsetManager;
import org.apache.kafka.connect.source.SourceTaskContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test class for verifying configurable OData poll intervals functionality.
 * Tests that different poll intervals are used for nextLink vs deltaLink processing.
 */
class HttpSourceTaskODataPollIntervalTest {

    @Mock
    private SourceTaskContext context;

    private HttpSourceTask task;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        task = new HttpSourceTask();
    }

    @Test
    void shouldUseNextLinkPollIntervalForPagination() throws Exception {
        // Given: Configuration with different OData poll intervals
        Map<String, String> config = createODataConfig();
        config.put("api1.request.interval.ms", "60000");  // Standard: 1 minute
        config.put("api1.odata.nextlink.poll.interval.ms", "5000");   // NextLink: 5 seconds
        config.put("api1.odata.deltalink.poll.interval.ms", "300000"); // DeltaLink: 5 minutes

        HttpSourceConnectorConfig sourceConfig = new HttpSourceConnectorConfig(config);
        ApiConfig apiConfig = new ApiConfig(sourceConfig, 1);

        // Create offset manager with nextLink state
        ODataOffsetManager offsetManager = new ODataOffsetManager(apiConfig, context);
        
        // Simulate nextLink extraction (this sets the link type to NEXTLINK)
        offsetManager.updateOffset("https://api.example.com/api/data/v9.0/accounts?$skiptoken=abc123");

        // Initialize task with mocked offset managers
        initializeTaskWithOffsetManager(task, apiConfig, offsetManager);

        // When: Calculate poll interval
        long pollInterval = calculatePollInterval(task, apiConfig);

        // Then: Should use nextLink poll interval
        assertThat(pollInterval).isEqualTo(5000L);
        assertThat(offsetManager.getCurrentLinkType()).isEqualTo(ODataOffsetManager.ODataLinkType.NEXTLINK);
    }

    @Test
    void shouldUseDeltaLinkPollIntervalForIncrementalUpdates() throws Exception {
        // Given: Configuration with different OData poll intervals
        Map<String, String> config = createODataConfig();
        config.put("api1.request.interval.ms", "60000");  // Standard: 1 minute
        config.put("api1.odata.nextlink.poll.interval.ms", "5000");   // NextLink: 5 seconds
        config.put("api1.odata.deltalink.poll.interval.ms", "300000"); // DeltaLink: 5 minutes

        HttpSourceConnectorConfig sourceConfig = new HttpSourceConnectorConfig(config);
        ApiConfig apiConfig = new ApiConfig(sourceConfig, 1);

        // Create offset manager with deltaLink state
        ODataOffsetManager offsetManager = new ODataOffsetManager(apiConfig, context);
        
        // Simulate deltaLink extraction (this sets the link type to DELTALINK)
        offsetManager.updateOffset("https://api.example.com/api/data/v9.0/accounts?$deltatoken=xyz789");

        // Initialize task with mocked offset managers
        initializeTaskWithOffsetManager(task, apiConfig, offsetManager);

        // When: Calculate poll interval
        long pollInterval = calculatePollInterval(task, apiConfig);

        // Then: Should use deltaLink poll interval
        assertThat(pollInterval).isEqualTo(300000L);
        assertThat(offsetManager.getCurrentLinkType()).isEqualTo(ODataOffsetManager.ODataLinkType.DELTALINK);
    }

    @Test
    void shouldUseStandardIntervalForUnknownLinkType() throws Exception {
        // Given: Configuration with different OData poll intervals
        Map<String, String> config = createODataConfig();
        config.put("api1.request.interval.ms", "60000");  // Standard: 1 minute
        config.put("api1.odata.nextlink.poll.interval.ms", "5000");   // NextLink: 5 seconds
        config.put("api1.odata.deltalink.poll.interval.ms", "300000"); // DeltaLink: 5 minutes

        HttpSourceConnectorConfig sourceConfig = new HttpSourceConnectorConfig(config);
        ApiConfig apiConfig = new ApiConfig(sourceConfig, 1);

        // Create offset manager without any link extraction (UNKNOWN state)
        ODataOffsetManager offsetManager = new ODataOffsetManager(apiConfig, context);

        // Initialize task with mocked offset managers
        initializeTaskWithOffsetManager(task, apiConfig, offsetManager);

        // When: Calculate poll interval
        long pollInterval = calculatePollInterval(task, apiConfig);

        // Then: Should use standard poll interval
        assertThat(pollInterval).isEqualTo(60000L);
        assertThat(offsetManager.getCurrentLinkType()).isEqualTo(ODataOffsetManager.ODataLinkType.UNKNOWN);
    }

    @Test
    void shouldDefaultToStandardIntervalWhenODataIntervalsNotConfigured() throws Exception {
        // Given: Configuration without specific OData poll intervals
        Map<String, String> config = createODataConfig();
        config.put("api1.request.interval.ms", "45000");  // Standard: 45 seconds
        // No odata.nextlink.poll.interval.ms or odata.deltalink.poll.interval.ms

        HttpSourceConnectorConfig sourceConfig = new HttpSourceConnectorConfig(config);
        ApiConfig apiConfig = new ApiConfig(sourceConfig, 1);

        // Create offset manager with nextLink state
        ODataOffsetManager offsetManager = new ODataOffsetManager(apiConfig, context);
        offsetManager.updateOffset("https://api.example.com/api/data/v9.0/accounts?$skiptoken=abc123");

        // Initialize task with mocked offset managers
        initializeTaskWithOffsetManager(task, apiConfig, offsetManager);

        // When: Calculate poll interval
        long pollInterval = calculatePollInterval(task, apiConfig);

        // Then: Should default to standard interval for both link types
        assertThat(pollInterval).isEqualTo(45000L);
        assertThat(apiConfig.getODataNextLinkPollIntervalMs()).isEqualTo(45000L);
        assertThat(apiConfig.getODataDeltaLinkPollIntervalMs()).isEqualTo(45000L);
    }

    @Test
    void shouldUseStandardIntervalForNonODataMode() throws Exception {
        // Given: Configuration with non-OData offset mode
        Map<String, String> config = createBasicConfig();
        config.put("api1.http.offset.mode", "SIMPLE_INCREMENTING");
        config.put("api1.request.interval.ms", "30000");  // Standard: 30 seconds
        config.put("api1.odata.nextlink.poll.interval.ms", "5000");   // Should be ignored
        config.put("api1.odata.deltalink.poll.interval.ms", "300000"); // Should be ignored

        HttpSourceConnectorConfig sourceConfig = new HttpSourceConnectorConfig(config);
        ApiConfig apiConfig = new ApiConfig(sourceConfig, 1);

        // Initialize task (offset manager will not be ODataOffsetManager)
        initializeTaskWithBasicOffsetManager(task, apiConfig);

        // When: Calculate poll interval
        long pollInterval = calculatePollInterval(task, apiConfig);

        // Then: Should use standard poll interval, ignoring OData-specific settings
        assertThat(pollInterval).isEqualTo(30000L);
    }

    // Helper methods

    private Map<String, String> createODataConfig() {
        Map<String, String> configMap = new HashMap<>();
        configMap.put("name", "test-connector");
        configMap.put("connector.class", "io.confluent.connect.http.HttpSourceConnector");
        configMap.put("http.api.base.url", "https://api.example.com");
        configMap.put("apis.num", "1");
        configMap.put("api1.http.api.path", "/api/data/v9.0/accounts");
        configMap.put("api1.topics", "accounts-topic");
        configMap.put("api1.http.request.method", "GET");
        configMap.put("api1.http.offset.mode", "ODATA_PAGINATION");
        configMap.put("api1.http.initial.offset", "");
        configMap.put("api1.http.response.data.json.pointer", "/value");
        configMap.put("api1.odata.nextlink.field", "@odata.nextLink");
        configMap.put("api1.odata.deltalink.field", "@odata.deltaLink");
        configMap.put("api1.odata.token.mode", "FULL_URL");
        configMap.put("api1.odata.skiptoken.param", "$skiptoken");
        configMap.put("api1.odata.deltatoken.param", "$deltatoken");
        configMap.put("connection.timeout.ms", "5000");
        configMap.put("request.timeout.ms", "5000");
        return configMap;
    }

    private Map<String, String> createBasicConfig() {
        Map<String, String> configMap = new HashMap<>();
        configMap.put("name", "test-connector");
        configMap.put("connector.class", "io.confluent.connect.http.HttpSourceConnector");
        configMap.put("http.api.base.url", "https://api.example.com");
        configMap.put("apis.num", "1");
        configMap.put("api1.http.api.path", "/api/records");
        configMap.put("api1.topics", "records-topic");
        configMap.put("api1.http.request.method", "GET");
        configMap.put("connection.timeout.ms", "5000");
        configMap.put("request.timeout.ms", "5000");
        return configMap;
    }

    private void initializeTaskWithOffsetManager(HttpSourceTask task, ApiConfig apiConfig, OffsetManager offsetManager) throws Exception {
        // Use reflection to set the offsetManagers field
        Field offsetManagersField = HttpSourceTask.class.getDeclaredField("offsetManagers");
        offsetManagersField.setAccessible(true);
        
        Map<String, OffsetManager> offsetManagers = new ConcurrentHashMap<>();
        offsetManagers.put(apiConfig.getId(), offsetManager);
        offsetManagersField.set(task, offsetManagers);
    }

    private void initializeTaskWithBasicOffsetManager(HttpSourceTask task, ApiConfig apiConfig) throws Exception {
        // Use reflection to set an empty offsetManagers map (non-OData scenario)
        Field offsetManagersField = HttpSourceTask.class.getDeclaredField("offsetManagers");
        offsetManagersField.setAccessible(true);
        
        Map<String, OffsetManager> offsetManagers = new ConcurrentHashMap<>();
        // Don't add any offset manager to simulate non-OData case
        offsetManagersField.set(task, offsetManagers);
    }

    private long calculatePollInterval(HttpSourceTask task, ApiConfig apiConfig) throws Exception {
        // Use reflection to call the private calculatePollInterval method
        Method calculatePollIntervalMethod = HttpSourceTask.class.getDeclaredMethod("calculatePollInterval", ApiConfig.class);
        calculatePollIntervalMethod.setAccessible(true);
        return (Long) calculatePollIntervalMethod.invoke(task, apiConfig);
    }
}
