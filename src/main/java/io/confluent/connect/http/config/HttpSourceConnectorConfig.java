package io.confluent.connect.http.config;

import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Configuration class for the HTTP Source Connector.
 * Defines all configuration properties and their validation rules.
 */
public class HttpSourceConnectorConfig extends AbstractConfig {
    
    private static final Logger log = LoggerFactory.getLogger(HttpSourceConnectorConfig.class);
    
    // Connection Configuration
    public static final String HTTP_API_BASE_URL = "http.api.base.url";
    public static final String AUTH_TYPE = "auth.type";
    public static final String CONNECTION_USER = "connection.user";
    public static final String CONNECTION_PASSWORD = "connection.password";
    public static final String BEARER_TOKEN = "bearer.token";
    public static final String OAUTH2_TOKEN_URL = "oauth2.token.url";
    public static final String OAUTH2_CLIENT_ID = "oauth2.client.id";
    public static final String OAUTH2_CLIENT_SECRET = "oauth2.client.secret";
    public static final String OAUTH2_TOKEN_PROPERTY = "oauth2.token.property";
    public static final String OAUTH2_CLIENT_SCOPE = "oauth2.client.scope";
    public static final String OAUTH2_CLIENT_AUTH_MODE = "oauth2.client.auth.mode";
    public static final String OAUTH2_TOKEN_REFRESH_INTERVAL_MINUTES = "oauth2.token.refresh.interval.minutes";
    public static final String API_KEY_LOCATION = "api.key.location";
    public static final String API_KEY_NAME = "api.key.name";
    public static final String API_KEY_VALUE = "api.key.value";
    
    // SSL Configuration
    public static final String HTTPS_SSL_ENABLED = "https.ssl.enabled";
    public static final String HTTPS_SSL_PROTOCOL = "https.ssl.protocol";
    public static final String HTTPS_SSL_KEYSTORE_FILE = "https.ssl.keystorefile";
    public static final String HTTPS_SSL_KEYSTORE_PASSWORD = "https.ssl.keystore.password";
    public static final String HTTPS_SSL_KEY_PASSWORD = "https.ssl.key.password";
    public static final String HTTPS_SSL_TRUSTSTORE_FILE = "https.ssl.truststorefile";
    public static final String HTTPS_SSL_TRUSTSTORE_PASSWORD = "https.ssl.truststore.password";
    
    // Proxy Configuration
    public static final String HTTP_PROXY_HOST = "http.proxy.host";
    public static final String HTTP_PROXY_PORT = "http.proxy.port";
    public static final String HTTP_PROXY_USER = "http.proxy.user";
    public static final String HTTP_PROXY_PASSWORD = "http.proxy.password";
    
    // API Configuration
    public static final String APIS_NUM = "apis.num";
    public static final String API_CHAINING_PARENT_CHILD_RELATIONSHIP = "api.chaining.parent.child.relationship";
    
    // Output Configuration
    public static final String OUTPUT_DATA_FORMAT = "output.data.format";
    public static final String SCHEMA_CONTEXT_NAME = "schema.context.name";
    public static final String VALUE_SUBJECT_NAME_STRATEGY = "value.subject.name.strategy";
    
    // Task Configuration
    public static final String TASKS_MAX = "tasks.max";
    
    // Error Handling
    public static final String BEHAVIOR_ON_ERROR = "behavior.on.error";
    public static final String REPORTER_ERROR_TOPIC_NAME = "reporter.error.topic.name";
    public static final String REPORT_ERRORS_AS = "report.errors.as";
    
    // Field-Level Encryption
    public static final String FIELD_ENCRYPTION_ENABLED = "field.encryption.enabled";
    public static final String FIELD_ENCRYPTION_KEY = "field.encryption.key";
    public static final String FIELD_ENCRYPTION_RULES = "field.encryption.rules";
    
    // Circuit Breaker Configuration
    public static final String CIRCUIT_BREAKER_FAILURE_THRESHOLD = "circuit.breaker.failure.threshold";
    public static final String CIRCUIT_BREAKER_TIMEOUT_MS = "circuit.breaker.timeout.ms";
    public static final String CIRCUIT_BREAKER_RECOVERY_TIME_MS = "circuit.breaker.recovery.time.ms";
    
    // Performance Optimization
    public static final String RESPONSE_CACHING_ENABLED = "response.caching.enabled";
    public static final String RESPONSE_CACHE_TTL_MS = "response.cache.ttl.ms";
    public static final String MAX_CACHE_SIZE = "max.cache.size";
    public static final String ADAPTIVE_POLLING_ENABLED = "adaptive.polling.enabled";
    
    // Debug Logging Configuration
    public static final String DEBUG_LOGGING_ENABLED = "debug.logging.enabled";
    public static final String DEBUG_LOG_REQUEST_HEADERS = "debug.log.request.headers";
    public static final String DEBUG_LOG_RESPONSE_BODY = "debug.log.response.body";
    public static final String DEBUG_LOG_RESPONSE_HEADERS = "debug.log.response.headers";
    public static final String DEBUG_LOG_REQUEST_BODY = "debug.log.request.body";
    
    // Enums
    public enum AuthType {
        NONE, BASIC, BEARER, OAUTH2, API_KEY
    }
    
    public enum OutputDataFormat {
        AVRO, JSON_SR, PROTOBUF
    }
    
    public enum BehaviorOnError {
        FAIL, IGNORE
    }
    
    public enum ApiKeyLocation {
        HEADER, QUERY
    }
    
    public enum OAuth2ClientAuthMode {
        HEADER, URL
    }
    
    public enum ReportErrorsAs {
        ERROR_STRING("Error string"),
        HTTP_RESPONSE("http_response");
        
        private final String value;
        
        ReportErrorsAs(String value) {
            this.value = value;
        }
        
        public String getValue() {
            return value;
        }
    }
    
    private static final ConfigDef CONFIG_DEF = createConfigDef();
    
    public HttpSourceConnectorConfig(Map<String, String> props) {
        super(CONFIG_DEF, props);
        validateConfig();
    }
    
    public static ConfigDef configDef() {
        return CONFIG_DEF;
    }
    
    private static ConfigDef createConfigDef() {
        ConfigDef configDef = new ConfigDef();
        
        // Connection Configuration
        configDef.define(
            HTTP_API_BASE_URL,
            ConfigDef.Type.STRING,
            ConfigDef.NO_DEFAULT_VALUE,
            ConfigDef.Importance.HIGH,
            "The HTTP API Base URL. For example: http://example.com/absenceManagement/v1"
        );
        
        configDef.define(
            AUTH_TYPE,
            ConfigDef.Type.STRING,
            AuthType.NONE.name(),
            ConfigDef.ValidString.in(AuthType.NONE.name(), AuthType.BASIC.name(), 
                                     AuthType.BEARER.name(), AuthType.OAUTH2.name(), AuthType.API_KEY.name()),
            ConfigDef.Importance.HIGH,
            "Authentication type of the endpoint"
        );
        
        configDef.define(
            CONNECTION_USER,
            ConfigDef.Type.STRING,
            null,
            ConfigDef.Importance.MEDIUM,
            "The username to be used with an endpoint requiring basic authentication"
        );
        
        configDef.define(
            CONNECTION_PASSWORD,
            ConfigDef.Type.PASSWORD,
            null,
            ConfigDef.Importance.MEDIUM,
            "The password to be used with an endpoint requiring basic authentication"
        );
        
        configDef.define(
            BEARER_TOKEN,
            ConfigDef.Type.PASSWORD,
            null,
            ConfigDef.Importance.MEDIUM,
            "The bearer authentication token to be used with an endpoint requiring bearer token based authentication"
        );
        
        configDef.define(
            OAUTH2_TOKEN_URL,
            ConfigDef.Type.STRING,
            null,
            ConfigDef.Importance.MEDIUM,
            "The URL to be used for fetching the OAuth2 token. Client Credentials is the only supported grant type"
        );
        
        configDef.define(
            OAUTH2_CLIENT_ID,
            ConfigDef.Type.STRING,
            null,
            ConfigDef.Importance.MEDIUM,
            "The client id used when fetching the OAuth2 token"
        );
        
        configDef.define(
            OAUTH2_CLIENT_SECRET,
            ConfigDef.Type.PASSWORD,
            null,
            ConfigDef.Importance.MEDIUM,
            "The client secret used when fetching the OAuth2 token"
        );
        
        configDef.define(
            OAUTH2_TOKEN_PROPERTY,
            ConfigDef.Type.STRING,
            "access_token",
            ConfigDef.Importance.MEDIUM,
            "The name of the property containing the OAuth2 token returned by the OAuth2 token URL"
        );
        
        configDef.define(
            OAUTH2_CLIENT_SCOPE,
            ConfigDef.Type.STRING,
            "any",
            ConfigDef.Importance.MEDIUM,
            "The scope parameter sent to the service when fetching the OAuth2 token"
        );
        
        configDef.define(
            OAUTH2_CLIENT_AUTH_MODE,
            ConfigDef.Type.STRING,
            OAuth2ClientAuthMode.HEADER.name(),
            ConfigDef.ValidString.in(OAuth2ClientAuthMode.HEADER.name(), OAuth2ClientAuthMode.URL.name()),
            ConfigDef.Importance.MEDIUM,
            "Specifies how to encode client_id and client_secret in the OAuth2 authorization request"
        );
        
        configDef.define(
            OAUTH2_TOKEN_REFRESH_INTERVAL_MINUTES,
            ConfigDef.Type.INT,
            30,
            ConfigDef.Range.between(1, 1440), // 1 minute to 24 hours
            ConfigDef.Importance.MEDIUM,
            "The interval in minutes for refreshing OAuth2 tokens. Defaults to 30 minutes"
        );
        
        configDef.define(
            API_KEY_LOCATION,
            ConfigDef.Type.STRING,
            ApiKeyLocation.HEADER.name(),
            ConfigDef.ValidString.in(ApiKeyLocation.HEADER.name(), ApiKeyLocation.QUERY.name()),
            ConfigDef.Importance.MEDIUM,
            "Specifies where the API key is included in the HTTP request"
        );
        
        configDef.define(
            API_KEY_NAME,
            ConfigDef.Type.STRING,
            "X-API-KEY",
            ConfigDef.Importance.MEDIUM,
            "The identifier for the API key used in authentication"
        );
        
        configDef.define(
            API_KEY_VALUE,
            ConfigDef.Type.PASSWORD,
            null,
            ConfigDef.Importance.MEDIUM,
            "The API key to be used with an endpoint that requires API key-based authentication"
        );
        
        // SSL Configuration
        configDef.define(
            HTTPS_SSL_ENABLED,
            ConfigDef.Type.BOOLEAN,
            false,
            ConfigDef.Importance.MEDIUM,
            "Controls whether to enforce TLSv1.3 for HTTPS connections"
        );
        
        configDef.define(
            HTTPS_SSL_PROTOCOL,
            ConfigDef.Type.STRING,
            "TLSv1.3",
            ConfigDef.Importance.MEDIUM,
            "The protocol to use for SSL connections"
        );
        
        // Proxy Configuration
        configDef.define(
            HTTP_PROXY_HOST,
            ConfigDef.Type.STRING,
            null,
            ConfigDef.Importance.MEDIUM,
            "The host or IP address of the HTTP proxy"
        );
        
        configDef.define(
            HTTP_PROXY_PORT,
            ConfigDef.Type.INT,
            null,
            new ConfigDef.Validator() {
                @Override
                public void ensureValid(String name, Object value) {
                    if (value != null) {
                        int port = (Integer) value;
                        if (port < 0 || port > 65535) {
                            throw new ConfigException(name, value, "Port must be between 0 and 65535");
                        }
                    }
                }
            },
            ConfigDef.Importance.MEDIUM,
            "The port number of the HTTP proxy"
        );
        
        configDef.define(
            HTTP_PROXY_USER,
            ConfigDef.Type.STRING,
            null,
            ConfigDef.Importance.MEDIUM,
            "The username to be used for proxy authentication"
        );
        
        configDef.define(
            HTTP_PROXY_PASSWORD,
            ConfigDef.Type.PASSWORD,
            null,
            ConfigDef.Importance.MEDIUM,
            "The password to be used for proxy authentication"
        );
        
        // API Configuration
        configDef.define(
            APIS_NUM,
            ConfigDef.Type.INT,
            1,
            ConfigDef.Range.between(1, 15),
            ConfigDef.Importance.HIGH,
            "The number of http(s) APIs to configure. This value should be less than or equal to 15"
        );
        
        configDef.define(
            API_CHAINING_PARENT_CHILD_RELATIONSHIP,
            ConfigDef.Type.STRING,
            "",
            ConfigDef.Importance.HIGH,
            "Comma separated list of parent-child relationship in case of API Chaining"
        );
        
        // Output Configuration
        configDef.define(
            OUTPUT_DATA_FORMAT,
            ConfigDef.Type.STRING,
            OutputDataFormat.JSON_SR.name(),
            ConfigDef.ValidString.in(OutputDataFormat.AVRO.name(), 
                                   OutputDataFormat.JSON_SR.name(), 
                                   OutputDataFormat.PROTOBUF.name()),
            ConfigDef.Importance.HIGH,
            "Sets the output Kafka record value format. Valid entries are AVRO, JSON_SR, or PROTOBUF"
        );
        
        configDef.define(
            SCHEMA_CONTEXT_NAME,
            ConfigDef.Type.STRING,
            "default",
            ConfigDef.Importance.MEDIUM,
            "Add a schema context name"
        );
        
        configDef.define(
            VALUE_SUBJECT_NAME_STRATEGY,
            ConfigDef.Type.STRING,
            "TopicNameStrategy",
            ConfigDef.Importance.LOW,
            "Determines how to construct the subject name under which the value schema is registered with Schema Registry"
        );
        
        // Task Configuration
        configDef.define(
            TASKS_MAX,
            ConfigDef.Type.INT,
            1,
            ConfigDef.Range.atLeast(1),
            ConfigDef.Importance.HIGH,
            "Maximum number of tasks for the connector"
        );
        
        // Error Handling
        configDef.define(
            BEHAVIOR_ON_ERROR,
            ConfigDef.Type.STRING,
            BehaviorOnError.FAIL.name(),
            ConfigDef.ValidString.in(BehaviorOnError.FAIL.name(), BehaviorOnError.IGNORE.name()),
            ConfigDef.Importance.LOW,
            "Error handling behavior setting for handling error response from HTTP requests"
        );
        
        configDef.define(
            REPORTER_ERROR_TOPIC_NAME,
            ConfigDef.Type.STRING,
            "error-${connector}",
            ConfigDef.Importance.LOW,
            "The name of the topic to produce errant records after each unsuccessful API operation"
        );
        
        configDef.define(
            REPORT_ERRORS_AS,
            ConfigDef.Type.STRING,
            ReportErrorsAs.ERROR_STRING.getValue(),
            ConfigDef.ValidString.in(ReportErrorsAs.ERROR_STRING.getValue(), ReportErrorsAs.HTTP_RESPONSE.getValue()),
            ConfigDef.Importance.LOW,
            "Dictates the content of records produced to the error topic"
        );
        
        // Field-Level Encryption Configuration
        configDef.define(
            FIELD_ENCRYPTION_ENABLED,
            ConfigDef.Type.BOOLEAN,
            false,
            ConfigDef.Importance.MEDIUM,
            "Enable field-level encryption for sensitive data"
        );
        
        configDef.define(
            FIELD_ENCRYPTION_KEY,
            ConfigDef.Type.PASSWORD,
            null,
            ConfigDef.Importance.HIGH,
            "Base64-encoded AES-256 key for field encryption. If not provided, a new key will be generated"
        );
        
        configDef.define(
            FIELD_ENCRYPTION_RULES,
            ConfigDef.Type.STRING,
            "",
            ConfigDef.Importance.MEDIUM,
            "Comma-separated list of field encryption rules in format 'field:type'. Types: AES_GCM, DETERMINISTIC, RANDOM"
        );
        
        // Circuit Breaker Configuration
        configDef.define(
            CIRCUIT_BREAKER_FAILURE_THRESHOLD,
            ConfigDef.Type.INT,
            5,
            ConfigDef.Range.between(1, 100),
            ConfigDef.Importance.MEDIUM,
            "Number of consecutive failures before opening circuit breaker. Defaults to 5"
        );
        
        configDef.define(
            CIRCUIT_BREAKER_TIMEOUT_MS,
            ConfigDef.Type.LONG,
            60000L,
            ConfigDef.Range.between(1000L, 600000L),
            ConfigDef.Importance.MEDIUM,
            "Timeout in milliseconds for circuit breaker operations. Defaults to 60 seconds"
        );
        
        configDef.define(
            CIRCUIT_BREAKER_RECOVERY_TIME_MS,
            ConfigDef.Type.LONG,
            30000L,
            ConfigDef.Range.between(5000L, 300000L),
            ConfigDef.Importance.MEDIUM,
            "Recovery time in milliseconds before attempting to close circuit breaker. Defaults to 30 seconds"
        );
        
        // Performance Optimization Configuration
        configDef.define(
            RESPONSE_CACHING_ENABLED,
            ConfigDef.Type.BOOLEAN,
            true,
            ConfigDef.Importance.MEDIUM,
            "Enable response caching to improve performance. Defaults to true"
        );
        
        configDef.define(
            RESPONSE_CACHE_TTL_MS,
            ConfigDef.Type.LONG,
            300000L, // 5 minutes
            ConfigDef.Range.between(10000L, 3600000L),
            ConfigDef.Importance.MEDIUM,
            "Time-to-live for cached responses in milliseconds. Defaults to 5 minutes"
        );
        
        configDef.define(
            MAX_CACHE_SIZE,
            ConfigDef.Type.INT,
            1000,
            ConfigDef.Range.between(10, 10000),
            ConfigDef.Importance.MEDIUM,
            "Maximum number of responses to cache. Defaults to 1000"
        );
        
        configDef.define(
            ADAPTIVE_POLLING_ENABLED,
            ConfigDef.Type.BOOLEAN,
            true,
            ConfigDef.Importance.MEDIUM,
            "Enable adaptive polling intervals based on API response patterns. Defaults to true"
        );
        
        // Debug Logging Configuration
        configDef.define(
            DEBUG_LOGGING_ENABLED,
            ConfigDef.Type.BOOLEAN,
            false,
            ConfigDef.Importance.LOW,
            "Enable debug logging for HTTP requests and responses. Defaults to false"
        );
        
        configDef.define(
            DEBUG_LOG_REQUEST_HEADERS,
            ConfigDef.Type.BOOLEAN,
            false,
            ConfigDef.Importance.LOW,
            "Log HTTP request headers when debug logging is enabled. Defaults to false"
        );
        
        configDef.define(
            DEBUG_LOG_RESPONSE_BODY,
            ConfigDef.Type.BOOLEAN,
            false,
            ConfigDef.Importance.LOW,
            "Log HTTP response body when debug logging is enabled. Defaults to false"
        );
        
        configDef.define(
            DEBUG_LOG_RESPONSE_HEADERS,
            ConfigDef.Type.BOOLEAN,
            false,
            ConfigDef.Importance.LOW,
            "Log HTTP response headers when debug logging is enabled. Defaults to false"
        );
        
        configDef.define(
            DEBUG_LOG_REQUEST_BODY,
            ConfigDef.Type.BOOLEAN,
            false,
            ConfigDef.Importance.LOW,
            "Log HTTP request body when debug logging is enabled. Defaults to false"
        );
        
        return configDef;
    }
    
    private void validateConfig() {
        // Additional validation logic beyond what ConfigDef provides
        log.debug("Validating HttpSourceConnectorConfig");
        
        // Custom validation can be added here
        validateAuthenticationConfig();
        validateOutputFormatConfig();
        
        log.debug("HttpSourceConnectorConfig validation completed");
    }
    
    private void validateAuthenticationConfig() {
        AuthType authType = getAuthType();
        
        switch (authType) {
            case BASIC:
                if (getConnectionUser() == null || getConnectionPassword() == null) {
                    throw new ConfigException("connection.user and connection.password must be set for BASIC auth");
                }
                break;
            case BEARER:
                if (getBearerToken() == null) {
                    throw new ConfigException("bearer.token must be set for BEARER auth");
                }
                break;
            case OAUTH2:
                if (getOauth2TokenUrl() == null || getOauth2ClientId() == null || getOauth2ClientSecret() == null) {
                    throw new ConfigException("oauth2.token.url, oauth2.client.id, and oauth2.client.secret must be set for OAUTH2 auth");
                }
                break;
            case API_KEY:
                if (getApiKeyValue() == null) {
                    throw new ConfigException("api.key.value must be set for API_KEY auth");
                }
                break;
            case NONE:
                // No validation needed
                break;
        }
    }
    
    private void validateOutputFormatConfig() {
        OutputDataFormat format = getOutputDataFormat();
        
        // Schema-based formats should have Schema Registry configured
        if (format == OutputDataFormat.AVRO || format == OutputDataFormat.PROTOBUF) {
            log.info("Using schema-based format: {}. Ensure Schema Registry is configured.", format);
        }
    }
    
    // Getter methods
    public String getHttpApiBaseUrl() {
        return getString(HTTP_API_BASE_URL);
    }
    
    public AuthType getAuthType() {
        return AuthType.valueOf(getString(AUTH_TYPE));
    }
    
    public String getConnectionUser() {
        return getString(CONNECTION_USER);
    }
    
    public String getConnectionPassword() {
        return getPassword(CONNECTION_PASSWORD) != null ? getPassword(CONNECTION_PASSWORD).value() : null;
    }
    
    public String getBearerToken() {
        return getPassword(BEARER_TOKEN) != null ? getPassword(BEARER_TOKEN).value() : null;
    }
    
    public String getOauth2TokenUrl() {
        return getString(OAUTH2_TOKEN_URL);
    }
    
    public String getOauth2ClientId() {
        return getString(OAUTH2_CLIENT_ID);
    }
    
    public String getOauth2ClientSecret() {
        return getPassword(OAUTH2_CLIENT_SECRET) != null ? getPassword(OAUTH2_CLIENT_SECRET).value() : null;
    }
    
    public String getOauth2TokenProperty() {
        return getString(OAUTH2_TOKEN_PROPERTY);
    }
    
    public String getOauth2ClientScope() {
        return getString(OAUTH2_CLIENT_SCOPE);
    }
    
    public OAuth2ClientAuthMode getOauth2ClientAuthMode() {
        return OAuth2ClientAuthMode.valueOf(getString(OAUTH2_CLIENT_AUTH_MODE));
    }
    
    public int getOauth2TokenRefreshIntervalMinutes() {
        return getInt(OAUTH2_TOKEN_REFRESH_INTERVAL_MINUTES);
    }
    
    public ApiKeyLocation getApiKeyLocation() {
        return ApiKeyLocation.valueOf(getString(API_KEY_LOCATION));
    }
    
    public String getApiKeyName() {
        return getString(API_KEY_NAME);
    }
    
    public String getApiKeyValue() {
        return getPassword(API_KEY_VALUE) != null ? getPassword(API_KEY_VALUE).value() : null;
    }
    
    public boolean isHttpsSslEnabled() {
        return getBoolean(HTTPS_SSL_ENABLED);
    }
    
    public String getHttpsSslProtocol() {
        return getString(HTTPS_SSL_PROTOCOL);
    }
    
    public String getHttpProxyHost() {
        return getString(HTTP_PROXY_HOST);
    }
    
    public Integer getHttpProxyPort() {
        return getInt(HTTP_PROXY_PORT);
    }
    
    public String getHttpProxyUser() {
        return getString(HTTP_PROXY_USER);
    }
    
    public String getHttpProxyPassword() {
        return getPassword(HTTP_PROXY_PASSWORD) != null ? getPassword(HTTP_PROXY_PASSWORD).value() : null;
    }
    
    public int getApisNum() {
        return getInt(APIS_NUM);
    }
    
    public String getApiChainingParentChildRelationship() {
        return getString(API_CHAINING_PARENT_CHILD_RELATIONSHIP);
    }
    
    public OutputDataFormat getOutputDataFormat() {
        return OutputDataFormat.valueOf(getString(OUTPUT_DATA_FORMAT));
    }
    
    public String getSchemaContextName() {
        return getString(SCHEMA_CONTEXT_NAME);
    }
    
    public String getValueSubjectNameStrategy() {
        return getString(VALUE_SUBJECT_NAME_STRATEGY);
    }
    
    public int getTasksMax() {
        return getInt(TASKS_MAX);
    }
    
    public BehaviorOnError getBehaviorOnError() {
        return BehaviorOnError.valueOf(getString(BEHAVIOR_ON_ERROR));
    }
    
    public String getReporterErrorTopicName() {
        return getString(REPORTER_ERROR_TOPIC_NAME);
    }
    
    public ReportErrorsAs getReportErrorsAs() {
        String value = getString(REPORT_ERRORS_AS);
        for (ReportErrorsAs errorAs : ReportErrorsAs.values()) {
            if (errorAs.getValue().equals(value)) {
                return errorAs;
            }
        }
        return ReportErrorsAs.ERROR_STRING;
    }
    
    public boolean isFieldEncryptionEnabled() {
        return getBoolean(FIELD_ENCRYPTION_ENABLED);
    }
    
    public String getFieldEncryptionKey() {
        return getPassword(FIELD_ENCRYPTION_KEY) != null ? getPassword(FIELD_ENCRYPTION_KEY).value() : null;
    }
    
    public String getFieldEncryptionRules() {
        return getString(FIELD_ENCRYPTION_RULES);
    }
    
    public int getCircuitBreakerFailureThreshold() {
        return getInt(CIRCUIT_BREAKER_FAILURE_THRESHOLD);
    }
    
    public long getCircuitBreakerTimeoutMs() {
        return getLong(CIRCUIT_BREAKER_TIMEOUT_MS);
    }
    
    public long getCircuitBreakerRecoveryTimeMs() {
        return getLong(CIRCUIT_BREAKER_RECOVERY_TIME_MS);
    }
    
    public boolean isResponseCachingEnabled() {
        return getBoolean(RESPONSE_CACHING_ENABLED);
    }
    
    public long getResponseCacheTtlMs() {
        return getLong(RESPONSE_CACHE_TTL_MS);
    }
    
    public int getMaxCacheSize() {
        return getInt(MAX_CACHE_SIZE);
    }
    
    public boolean isAdaptivePollingEnabled() {
        return getBoolean(ADAPTIVE_POLLING_ENABLED);
    }
    
    // Debug Logging Getters
    public boolean isDebugLoggingEnabled() {
        return getBoolean(DEBUG_LOGGING_ENABLED);
    }
    
    public boolean isDebugLogRequestHeaders() {
        return getBoolean(DEBUG_LOG_REQUEST_HEADERS);
    }
    
    public boolean isDebugLogResponseBody() {
        return getBoolean(DEBUG_LOG_RESPONSE_BODY);
    }
    
    public boolean isDebugLogResponseHeaders() {
        return getBoolean(DEBUG_LOG_RESPONSE_HEADERS);
    }
    
    public boolean isDebugLogRequestBody() {
        return getBoolean(DEBUG_LOG_REQUEST_BODY);
    }
}