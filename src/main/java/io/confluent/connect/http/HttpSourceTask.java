package io.confluent.connect.http;

import io.confluent.connect.http.auth.HttpAuthenticator;
import io.confluent.connect.http.chaining.ApiChainingManager;
import io.confluent.connect.http.auth.HttpAuthenticatorFactory;
import io.confluent.connect.http.client.HttpApiClient;
import io.confluent.connect.http.config.ApiConfig;
import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import io.confluent.connect.http.converter.RecordConverter;
import io.confluent.connect.http.converter.RecordConverterFactory;
import io.confluent.connect.http.error.ErrorHandler;
import io.confluent.connect.http.offset.OffsetManager;
import io.confluent.connect.http.offset.OffsetManagerFactory;
import io.confluent.connect.http.util.JsonPointer;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * HttpSourceTask is responsible for polling HTTP APIs and producing records to Kafka.
 * Each task can handle multiple API endpoints and manages their individual offsets and schedules.
 */
public class HttpSourceTask extends SourceTask {
    
    private static final Logger log = LoggerFactory.getLogger(HttpSourceTask.class);
    
    private HttpSourceConnectorConfig config;
    private List<ApiConfig> apiConfigs;
    private Map<String, HttpApiClient> apiClients;
    private Map<String, OffsetManager> offsetManagers;
    private Map<String, RecordConverter> recordConverters;
    private Map<String, Long> lastPollTimes;
    private ErrorHandler errorHandler;
    private ScheduledExecutorService scheduler;
    private HttpAuthenticator authenticator;
    private ApiChainingManager chainingManager;
    
    @Override
    public String version() {
        return HttpSourceConnector.VERSION;
    }
    
    @Override
    public void start(Map<String, String> props) {
        log.info("Starting HttpSourceTask");
        
        try {
            config = new HttpSourceConnectorConfig(props);
            
            // Initialize collections
            apiConfigs = new ArrayList<>();
            apiClients = new ConcurrentHashMap<>();
            offsetManagers = new ConcurrentHashMap<>();
            recordConverters = new ConcurrentHashMap<>();
            lastPollTimes = new ConcurrentHashMap<>();
            
            // Create authenticator
            authenticator = HttpAuthenticatorFactory.create(config);
            
            // Initialize API chaining manager
            chainingManager = new ApiChainingManager(config);
            
            // Initialize error handler
            errorHandler = new ErrorHandler(config);
            
            // Parse task-specific API indices
            parseTaskApiIndices(props);
            
            // Initialize API clients and related components
            initializeApiComponents();
            
            // Validate API chaining configuration
            chainingManager.validateChainingConfiguration(apiConfigs);
            
            // Start scheduler for polling
            startScheduler();
            
            log.info("HttpSourceTask started successfully with {} APIs", apiConfigs.size());
            
        } catch (Exception e) {
            log.error("Failed to start HttpSourceTask", e);
            throw new RuntimeException("Failed to start task: " + e.getMessage(), e);
        }
    }
    
    @Override
    public List<SourceRecord> poll() throws InterruptedException {
        List<SourceRecord> records = new ArrayList<>();
        
        for (ApiConfig apiConfig : apiConfigs) {
            try {
                // Check if it's time to poll this API
                if (shouldPollApi(apiConfig)) {
                    List<SourceRecord> apiRecords = pollApi(apiConfig);
                    records.addAll(apiRecords);
                    
                    // Update last poll time
                    lastPollTimes.put(apiConfig.getId(), System.currentTimeMillis());
                }
            } catch (Exception e) {
                log.error("Error polling API {}: {}", apiConfig.getId(), e.getMessage(), e);
                
                // Handle error based on configuration
                if (config.getBehaviorOnError() == HttpSourceConnectorConfig.BehaviorOnError.FAIL) {
                    throw new RuntimeException("API polling failed: " + e.getMessage(), e);
                } else {
                    // Log and continue with other APIs
                    errorHandler.handleError(apiConfig, e, null);
                }
            }
        }
        
        if (!records.isEmpty()) {
            log.debug("Returning {} records from {} APIs", records.size(), apiConfigs.size());
        }
        
        return records;
    }
    
    @Override
    public void stop() {
        log.info("Stopping HttpSourceTask");
        
        try {
            // Stop scheduler
            if (scheduler != null && !scheduler.isShutdown()) {
                scheduler.shutdown();
                if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            }
            
            // Close API clients
            for (HttpApiClient client : apiClients.values()) {
                try {
                    client.close();
                } catch (Exception e) {
                    log.warn("Error closing API client", e);
                }
            }
            
            // Close authenticator
            if (authenticator != null) {
                try {
                    authenticator.close();
                } catch (Exception e) {
                    log.warn("Error closing authenticator", e);
                }
            }
            
            log.info("HttpSourceTask stopped successfully");
            
        } catch (Exception e) {
            log.error("Error stopping HttpSourceTask", e);
        }
    }
    
    /**
     * Parses the task-specific API indices from the configuration
     */
    private void parseTaskApiIndices(Map<String, String> props) {
        String taskApiIndices = props.get("task.api.indices");
        if (taskApiIndices == null) {
            log.warn("No task.api.indices specified, task will handle all APIs");
            // Handle all APIs if not specified
            for (int i = 1; i <= config.getApisNum(); i++) {
                apiConfigs.add(new ApiConfig(config, i));
            }
        } else {
            // Parse the API indices list [1, 3, 5] format
            String indices = taskApiIndices.replaceAll("[\\[\\]\\s]", "");
            if (!indices.isEmpty()) {
                for (String index : indices.split(",")) {
                    try {
                        int apiIndex = Integer.parseInt(index.trim());
                        apiConfigs.add(new ApiConfig(config, apiIndex));
                    } catch (NumberFormatException e) {
                        log.error("Invalid API index: {}", index);
                    }
                }
            }
        }
        
        log.info("Task will handle {} API configurations", apiConfigs.size());
    }
    
    /**
     * Initializes API clients, offset managers, and record converters for each API
     */
    private void initializeApiComponents() {
        for (ApiConfig apiConfig : apiConfigs) {
            String apiId = apiConfig.getId();
            
            // Create HTTP API client
            HttpApiClient apiClient = new HttpApiClient(apiConfig, authenticator);
            apiClients.put(apiId, apiClient);
            
            // Create offset manager
            OffsetManager offsetManager = OffsetManagerFactory.create(apiConfig, context);
            offsetManagers.put(apiId, offsetManager);
            
            // Create record converter
            RecordConverter recordConverter = RecordConverterFactory.create(config);
            recordConverters.put(apiId, recordConverter);
            
            // Initialize last poll time
            lastPollTimes.put(apiId, 0L);
            
            log.debug("Initialized components for API: {}", apiId);
        }
    }
    
    /**
     * Starts the scheduler for periodic tasks like token refresh
     */
    private void startScheduler() {
        scheduler = Executors.newScheduledThreadPool(1);
        
        // Schedule token refresh for OAuth2
        if (config.getAuthType() == HttpSourceConnectorConfig.AuthType.OAUTH2) {
            int refreshIntervalMinutes = config.getOauth2TokenRefreshIntervalMinutes();
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    authenticator.refreshToken();
                } catch (Exception e) {
                    log.error("Failed to refresh OAuth2 token", e);
                }
            }, 0, refreshIntervalMinutes, TimeUnit.MINUTES);
            
            log.info("OAuth2 token refresh scheduled every {} minutes", refreshIntervalMinutes);
        }
    }
    
    /**
     * Checks if it's time to poll the specified API based on its configured interval and chaining dependencies
     */
    private boolean shouldPollApi(ApiConfig apiConfig) {
        long lastPollTime = lastPollTimes.get(apiConfig.getId());
        long currentTime = System.currentTimeMillis();
        long interval = apiConfig.getRequestIntervalMs();
        
        // Check time-based condition
        boolean timeCondition = (currentTime - lastPollTime) >= interval;
        
        // Check chaining condition for child APIs
        boolean chainingCondition = chainingManager.shouldExecuteChildApi(apiConfig.getId());
        
        return timeCondition && chainingCondition;
    }
    
    /**
     * Polls a specific API and returns the resulting source records
     */
    private List<SourceRecord> pollApi(ApiConfig apiConfig) throws Exception {
        String apiId = apiConfig.getId();
        HttpApiClient apiClient = apiClients.get(apiId);
        OffsetManager offsetManager = offsetManagers.get(apiId);
        RecordConverter recordConverter = recordConverters.get(apiId);
        
        log.debug("Polling API: {}", apiId);
        
        // Get current offset
        String currentOffset = offsetManager.getCurrentOffset();
        
        // Get chaining template variables for child APIs
        Map<String, String> chainingVars = chainingManager.getChildApiTemplateVariables(apiConfig);
        
        // Make HTTP request with chaining variables
        HttpApiClient.ApiResponse response = apiClient.makeRequest(currentOffset, chainingVars);
        
        if (response == null || response.getBody() == null) {
            log.debug("No data received from API: {}", apiId);
            return Collections.emptyList();
        }
        
        // Extract data from response
        List<Object> dataRecords = extractDataFromResponse(apiConfig, response.getBody());
        
        if (dataRecords.isEmpty()) {
            log.debug("No records extracted from API response: {}", apiId);
            return Collections.emptyList();
        }
        
        // Store parent API response data for chaining
        if (chainingManager.isParentApi(apiId)) {
            chainingManager.storeParentResponse(apiId, response.getBody(), dataRecords);
            log.debug("Stored parent API response for chaining: {}", apiId);
        }
        
        // Convert to source records
        List<SourceRecord> sourceRecords = new ArrayList<>();
        
        for (Object dataRecord : dataRecords) {
            try {
                // Extract offset from record if needed
                String recordOffset = extractOffsetFromRecord(apiConfig, dataRecord);
                
                // Create source partition and offset
                Map<String, String> sourcePartition = createSourcePartition(apiConfig);
                Map<String, String> sourceOffset = createSourceOffset(recordOffset);
                
                // Convert data to source record
                SourceRecord sourceRecord = recordConverter.convert(
                    sourcePartition,
                    sourceOffset,
                    apiConfig.getTopic(),
                    null, // key schema
                    null, // key
                    null, // value schema (will be determined by converter)
                    dataRecord,
                    Instant.now().toEpochMilli()
                );
                
                sourceRecords.add(sourceRecord);
                
                // Update offset manager
                offsetManager.updateOffset(recordOffset);
                
            } catch (Exception e) {
                log.error("Error converting record from API {}: {}", apiId, e.getMessage(), e);
                errorHandler.handleError(apiConfig, e, dataRecord);
            }
        }
        
        log.debug("Converted {} records from API: {}", sourceRecords.size(), apiId);
        return sourceRecords;
    }
    
    /**
     * Extracts data records from the HTTP response based on the configured JSON pointer
     */
    private List<Object> extractDataFromResponse(ApiConfig apiConfig, String responseBody) {
        try {
            String dataJsonPointer = apiConfig.getHttpResponseDataJsonPointer();
            
            if (dataJsonPointer != null && !dataJsonPointer.isEmpty()) {
                Object extractedData = JsonPointer.extract(responseBody, dataJsonPointer);
                
                if (extractedData instanceof List) {
                    return (List<Object>) extractedData;
                } else if (extractedData != null) {
                    return Collections.singletonList(extractedData);
                }
            } else {
                // If no JSON pointer specified, parse the entire response
                Object parsedResponse = JsonPointer.parse(responseBody);
                if (parsedResponse instanceof List) {
                    return (List<Object>) parsedResponse;
                } else if (parsedResponse != null) {
                    return Collections.singletonList(parsedResponse);
                }
            }
        } catch (Exception e) {
            log.error("Error extracting data from response: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to extract data from API response", e);
        }
        
        return Collections.emptyList();
    }
    
    /**
     * Extracts offset value from a data record based on the configured JSON pointer
     */
    private String extractOffsetFromRecord(ApiConfig apiConfig, Object record) {
        try {
            String offsetJsonPointer = apiConfig.getHttpOffsetJsonPointer();
            
            if (offsetJsonPointer != null && !offsetJsonPointer.isEmpty()) {
                Object offsetValue = JsonPointer.extract(record, offsetJsonPointer);
                return offsetValue != null ? offsetValue.toString() : null;
            }
        } catch (Exception e) {
            log.warn("Error extracting offset from record: {}", e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Creates the source partition map for Kafka Connect
     */
    private Map<String, String> createSourcePartition(ApiConfig apiConfig) {
        Map<String, String> partition = new HashMap<>();
        partition.put("url", config.getHttpApiBaseUrl() + apiConfig.getHttpApiPath());
        return partition;
    }
    
    /**
     * Creates the source offset map for Kafka Connect
     */
    private Map<String, String> createSourceOffset(String offset) {
        Map<String, String> offsetMap = new HashMap<>();
        if (offset != null) {
            offsetMap.put("offset", offset);
        }
        return offsetMap;
    }
}