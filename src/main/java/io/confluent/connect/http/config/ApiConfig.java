package io.confluent.connect.http.config;

import org.apache.kafka.common.config.ConfigException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration class for individual API endpoints.
 * Each connector can handle multiple APIs, and each API has its own configuration.
 */
public class ApiConfig {
    
    private static final Logger log = LoggerFactory.getLogger(ApiConfig.class);
    
    private final HttpSourceConnectorConfig globalConfig;
    private final int apiIndex;
    private final String apiPrefix;
    
    // API-specific configuration keys
    public static final String HTTP_API_PATH = ".http.api.path";
    public static final String TOPICS = ".topics";
    public static final String HTTP_REQUEST_METHOD = ".http.request.method";
    public static final String HTTP_REQUEST_HEADERS = ".http.request.headers";
    public static final String HTTP_REQUEST_PARAMETERS = ".http.request.parameters";
    public static final String HTTP_CONNECT_TIMEOUT_MS = ".http.connect.timeout.ms";
    public static final String HTTP_REQUEST_BODY = ".http.request.body";
    public static final String HTTP_REQUEST_TIMEOUT_MS = ".http.request.timeout.ms";
    public static final String HTTP_OFFSET_MODE = ".http.offset.mode";
    public static final String MAX_RETRIES = ".max.retries";
    public static final String HTTP_INITIAL_OFFSET = ".http.initial.offset";
    public static final String RETRY_BACKOFF_POLICY = ".retry.backoff.policy";
    public static final String HTTP_RESPONSE_DATA_JSON_POINTER = ".http.response.data.json.pointer";
    public static final String RETRY_BACKOFF_MS = ".retry.backoff.ms";
    public static final String HTTP_OFFSET_JSON_POINTER = ".http.offset.json.pointer";
    public static final String RETRY_ON_STATUS_CODES = ".retry.on.status.codes";
    public static final String HTTP_NEXT_PAGE_JSON_POINTER = ".http.next.page.json.pointer";
    public static final String REQUEST_INTERVAL_MS = ".request.interval.ms";
    public static final String HTTP_REQUEST_HEADERS_SEPARATOR = ".http.request.headers.separator";
    public static final String HTTP_REQUEST_PARAMETERS_SEPARATOR = ".http.request.parameters.separator";
    public static final String HTTP_PATH_PARAMETERS_SEPARATOR = ".http.path.parameters.separator";
    public static final String HTTP_RESPONSE_SCHEMA_ENFORCE = ".http.response.schema.enforce";
    
    // Enums
    public enum HttpRequestMethod {
        GET, POST
    }
    
    public enum HttpOffsetMode {
        SIMPLE_INCREMENTING, CHAINING, CURSOR_PAGINATION, SNAPSHOT_PAGINATION
    }
    
    public enum RetryBackoffPolicy {
        CONSTANT_VALUE, EXPONENTIAL_WITH_JITTER
    }
    
    public enum HttpResponseSchemaEnforce {
        STRICT, NONE
    }
    
    public ApiConfig(HttpSourceConnectorConfig globalConfig, int apiIndex) {
        this.globalConfig = globalConfig;
        this.apiIndex = apiIndex;
        this.apiPrefix = "api" + apiIndex;
        
        validateConfiguration();
    }
    
    /**
     * Gets the unique identifier for this API configuration
     */
    public String getId() {
        return apiPrefix;
    }
    
    /**
     * Gets the API index (1-based)
     */
    public int getIndex() {
        return apiIndex;
    }
    
    /**
     * Gets the HTTP API path for this API
     */
    public String getHttpApiPath() {
        return getStringProperty(HTTP_API_PATH);
    }
    
    /**
     * Gets the Kafka topic name for this API
     */
    public String getTopic() {
        return getStringProperty(TOPICS);
    }
    
    /**
     * Gets the HTTP request method (GET or POST)
     */
    public HttpRequestMethod getHttpRequestMethod() {
        String method = getStringProperty(HTTP_REQUEST_METHOD, HttpRequestMethod.GET.name());
        return HttpRequestMethod.valueOf(method);
    }
    
    /**
     * Gets the HTTP request headers as a string
     */
    public String getHttpRequestHeaders() {
        return getStringProperty(HTTP_REQUEST_HEADERS);
    }
    
    /**
     * Gets the HTTP request parameters as a string
     */
    public String getHttpRequestParameters() {
        return getStringProperty(HTTP_REQUEST_PARAMETERS);
    }
    
    /**
     * Gets the HTTP connection timeout in milliseconds
     */
    public int getHttpConnectTimeoutMs() {
        return getIntProperty(HTTP_CONNECT_TIMEOUT_MS, 30000);
    }
    
    /**
     * Gets the HTTP request body
     */
    public String getHttpRequestBody() {
        return getStringProperty(HTTP_REQUEST_BODY);
    }
    
    /**
     * Gets the HTTP request timeout in milliseconds
     */
    public int getHttpRequestTimeoutMs() {
        return getIntProperty(HTTP_REQUEST_TIMEOUT_MS, 30000);
    }
    
    /**
     * Gets the HTTP offset mode
     */
    public HttpOffsetMode getHttpOffsetMode() {
        String mode = getStringProperty(HTTP_OFFSET_MODE, HttpOffsetMode.SIMPLE_INCREMENTING.name());
        return HttpOffsetMode.valueOf(mode);
    }
    
    /**
     * Gets the maximum number of retries
     */
    public int getMaxRetries() {
        return getIntProperty(MAX_RETRIES, 5);
    }
    
    /**
     * Gets the initial offset value
     */
    public String getHttpInitialOffset() {
        return getStringProperty(HTTP_INITIAL_OFFSET, "");
    }
    
    /**
     * Gets the retry backoff policy
     */
    public RetryBackoffPolicy getRetryBackoffPolicy() {
        String policy = getStringProperty(RETRY_BACKOFF_POLICY, RetryBackoffPolicy.EXPONENTIAL_WITH_JITTER.name());
        return RetryBackoffPolicy.valueOf(policy);
    }
    
    /**
     * Gets the JSON pointer for extracting response data
     */
    public String getHttpResponseDataJsonPointer() {
        return getStringProperty(HTTP_RESPONSE_DATA_JSON_POINTER);
    }
    
    /**
     * Gets the retry backoff time in milliseconds
     */
    public int getRetryBackoffMs() {
        return getIntProperty(RETRY_BACKOFF_MS, 3000);
    }
    
    /**
     * Gets the JSON pointer for extracting offset from records
     */
    public String getHttpOffsetJsonPointer() {
        return getStringProperty(HTTP_OFFSET_JSON_POINTER);
    }
    
    /**
     * Gets the HTTP status codes to retry on
     */
    public String getRetryOnStatusCodes() {
        return getStringProperty(RETRY_ON_STATUS_CODES, "400-");
    }
    
    /**
     * Gets the JSON pointer for extracting next page information
     */
    public String getHttpNextPageJsonPointer() {
        return getStringProperty(HTTP_NEXT_PAGE_JSON_POINTER);
    }
    
    /**
     * Gets the request interval in milliseconds
     */
    public long getRequestIntervalMs() {
        return getLongProperty(REQUEST_INTERVAL_MS, 60000L);
    }
    
    /**
     * Gets the separator character for HTTP request headers
     */
    public String getHttpRequestHeadersSeparator() {
        return getStringProperty(HTTP_REQUEST_HEADERS_SEPARATOR, "|");
    }
    
    /**
     * Gets the separator character for HTTP request parameters
     */
    public String getHttpRequestParametersSeparator() {
        return getStringProperty(HTTP_REQUEST_PARAMETERS_SEPARATOR, "&");
    }
    
    /**
     * Gets the separator character for HTTP path parameters
     */
    public String getHttpPathParametersSeparator() {
        return getStringProperty(HTTP_PATH_PARAMETERS_SEPARATOR, "|");
    }
    
    /**
     * Gets the HTTP response schema enforcement mode
     */
    public HttpResponseSchemaEnforce getHttpResponseSchemaEnforce() {
        String mode = getStringProperty(HTTP_RESPONSE_SCHEMA_ENFORCE, HttpResponseSchemaEnforce.NONE.name());
        return HttpResponseSchemaEnforce.valueOf(mode);
    }
    
    /**
     * Gets the full URL for this API by combining base URL with API path
     */
    public String getFullUrl() {
        String baseUrl = globalConfig.getHttpApiBaseUrl();
        String apiPath = getHttpApiPath();
        
        if (baseUrl.endsWith("/") && apiPath.startsWith("/")) {
            return baseUrl + apiPath.substring(1);
        } else if (!baseUrl.endsWith("/") && !apiPath.startsWith("/")) {
            return baseUrl + "/" + apiPath;
        } else {
            return baseUrl + apiPath;
        }
    }
    
    /**
     * Gets the global configuration object
     */
    public HttpSourceConnectorConfig getGlobalConfig() {
        return globalConfig;
    }
    
    private void validateConfiguration() {
        log.debug("Validating configuration for API: {}", apiPrefix);
        
        // Validate required properties
        if (getHttpApiPath() == null || getHttpApiPath().trim().isEmpty()) {
            throw new ConfigException("API path must be specified for " + apiPrefix);
        }
        
        if (getTopic() == null || getTopic().trim().isEmpty()) {
            throw new ConfigException("Topic must be specified for " + apiPrefix);
        }
        
        // Validate offset mode specific requirements
        HttpOffsetMode offsetMode = getHttpOffsetMode();
        switch (offsetMode) {
            case SIMPLE_INCREMENTING:
                // Initial offset should be numeric for simple incrementing
                String initialOffset = getHttpInitialOffset();
                if (initialOffset != null && !initialOffset.isEmpty()) {
                    try {
                        Integer.parseInt(initialOffset);
                    } catch (NumberFormatException e) {
                        log.warn("Initial offset '{}' is not numeric for SIMPLE_INCREMENTING mode in {}", 
                                initialOffset, apiPrefix);
                    }
                }
                break;
            case CHAINING:
                if (getHttpOffsetJsonPointer() == null || getHttpOffsetJsonPointer().isEmpty()) {
                    throw new ConfigException("http.offset.json.pointer must be set for CHAINING mode in " + apiPrefix);
                }
                break;
            case CURSOR_PAGINATION:
                if (getHttpNextPageJsonPointer() == null || getHttpNextPageJsonPointer().isEmpty()) {
                    throw new ConfigException("http.next.page.json.pointer must be set for CURSOR_PAGINATION mode in " + apiPrefix);
                }
                break;
            case SNAPSHOT_PAGINATION:
                if (getHttpOffsetJsonPointer() == null || getHttpOffsetJsonPointer().isEmpty()) {
                    throw new ConfigException("http.offset.json.pointer must be set for SNAPSHOT_PAGINATION mode in " + apiPrefix);
                }
                break;
        }
        
        // Validate timeouts
        if (getHttpConnectTimeoutMs() <= 0) {
            throw new ConfigException("HTTP connect timeout must be positive for " + apiPrefix);
        }
        
        if (getHttpRequestTimeoutMs() <= 0) {
            throw new ConfigException("HTTP request timeout must be positive for " + apiPrefix);
        }
        
        // Validate retry configuration
        if (getMaxRetries() < 0) {
            throw new ConfigException("Max retries must be non-negative for " + apiPrefix);
        }
        
        if (getRetryBackoffMs() <= 0) {
            throw new ConfigException("Retry backoff time must be positive for " + apiPrefix);
        }
        
        if (getRequestIntervalMs() <= 0) {
            throw new ConfigException("Request interval must be positive for " + apiPrefix);
        }
        
        log.debug("Configuration validation completed for API: {}", apiPrefix);
    }
    
    private String getStringProperty(String suffix) {
        return globalConfig.getString(apiPrefix + suffix);
    }
    
    private String getStringProperty(String suffix, String defaultValue) {
        String value = globalConfig.getString(apiPrefix + suffix);
        return value != null ? value : defaultValue;
    }
    
    private int getIntProperty(String suffix, int defaultValue) {
        String key = apiPrefix + suffix;
        return globalConfig.originals().containsKey(key) ? 
               globalConfig.getInt(key) : defaultValue;
    }
    
    private long getLongProperty(String suffix, long defaultValue) {
        String key = apiPrefix + suffix;
        return globalConfig.originals().containsKey(key) ? 
               globalConfig.getLong(key) : defaultValue;
    }
}