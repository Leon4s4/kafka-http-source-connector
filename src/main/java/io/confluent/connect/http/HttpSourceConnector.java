package io.confluent.connect.http;

import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.connect.connector.Task;
import org.apache.kafka.connect.source.SourceConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HttpSourceConnector is a Kafka Connect source connector that fetches data from HTTP/HTTPS APIs
 * and publishes it to Kafka topics. It supports multiple authentication methods, offset management,
 * schema registry integration, and advanced features like API chaining.
 */
public class HttpSourceConnector extends SourceConnector {
    
    private static final Logger log = LoggerFactory.getLogger(HttpSourceConnector.class);
    
    public static final String VERSION = "1.0.0";
    
    private Map<String, String> configProps;
    private HttpSourceConnectorConfig config;
    
    @Override
    public String version() {
        return VERSION;
    }
    
    @Override
    public void start(Map<String, String> props) {
        log.info("Starting HttpSourceConnector with {} properties", props.size());
        
        try {
            configProps = props;
            config = new HttpSourceConnectorConfig(props);
            
            // Validate configuration
            validateConfiguration();
            
            log.info("HttpSourceConnector started successfully");
            log.info("Base URL: {}", config.getHttpApiBaseUrl());
            log.info("Authentication Type: {}", config.getAuthType());
            log.info("Number of APIs configured: {}", config.getApisNum());
            
        } catch (Exception e) {
            log.error("Failed to start HttpSourceConnector", e);
            throw new ConfigException("Failed to start connector: " + e.getMessage(), e);
        }
    }
    
    @Override
    public Class<? extends Task> taskClass() {
        return HttpSourceTask.class;
    }
    
    @Override
    public List<Map<String, String>> taskConfigs(int maxTasks) {
        log.info("Creating task configurations for {} max tasks", maxTasks);
        
        List<Map<String, String>> taskConfigs = new ArrayList<>();
        
        // For now, we'll create one task per API endpoint, up to maxTasks
        int numApis = config.getApisNum();
        int numTasks = Math.min(maxTasks, numApis);
        
        if (numTasks == 0) {
            log.warn("No tasks will be created - either maxTasks is 0 or no APIs configured");
            return taskConfigs;
        }
        
        // Distribute APIs across tasks
        for (int i = 0; i < numTasks; i++) {
            Map<String, String> taskConfig = new HashMap<>(configProps);
            
            // Calculate which APIs this task should handle
            List<Integer> apiIndices = new ArrayList<>();
            for (int apiIndex = i; apiIndex < numApis; apiIndex += numTasks) {
                apiIndices.add(apiIndex + 1); // API indices are 1-based
            }
            
            // Add task-specific configuration
            taskConfig.put("task.id", String.valueOf(i));
            taskConfig.put("task.api.indices", apiIndices.toString());
            
            taskConfigs.add(taskConfig);
            
            log.info("Task {} will handle APIs: {}", i, apiIndices);
        }
        
        log.info("Created {} task configurations", taskConfigs.size());
        return taskConfigs;
    }
    
    @Override
    public void stop() {
        log.info("Stopping HttpSourceConnector");
        // Cleanup any resources if needed
        log.info("HttpSourceConnector stopped successfully");
    }
    
    @Override
    public ConfigDef config() {
        return HttpSourceConnectorConfig.configDef();
    }
    
    /**
     * Validates the connector configuration to ensure all required properties are set
     * and that the configuration is internally consistent.
     */
    private void validateConfiguration() {
        log.debug("Validating connector configuration");
        
        // Validate base URL is set
        if (config.getHttpApiBaseUrl() == null || config.getHttpApiBaseUrl().trim().isEmpty()) {
            throw new ConfigException("http.api.base.url must be specified");
        }
        
        // Validate authentication configuration
        validateAuthenticationConfig();
        
        // Validate API configurations
        validateApiConfigurations();
        
        // Validate output format configuration
        validateOutputFormatConfig();
        
        log.debug("Configuration validation completed successfully");
    }
    
    private void validateAuthenticationConfig() {
        HttpSourceConnectorConfig.AuthType authType = config.getAuthType();
        
        switch (authType) {
            case BASIC:
                if (config.getConnectionUser() == null || config.getConnectionPassword() == null) {
                    throw new ConfigException("connection.user and connection.password must be set for BASIC auth");
                }
                break;
            case BEARER:
                if (config.getBearerToken() == null) {
                    throw new ConfigException("bearer.token must be set for BEARER auth");
                }
                break;
            case OAUTH2:
                if (config.getOauth2TokenUrl() == null || 
                    config.getOauth2ClientId() == null || 
                    config.getOauth2ClientSecret() == null) {
                    throw new ConfigException("oauth2.token.url, oauth2.client.id, and oauth2.client.secret must be set for OAUTH2 auth");
                }
                break;
            case API_KEY:
                if (config.getApiKeyValue() == null) {
                    throw new ConfigException("api.key.value must be set for API_KEY auth");
                }
                break;
            case NONE:
                // No validation needed
                break;
        }
    }
    
    private void validateApiConfigurations() {
        int numApis = config.getApisNum();
        
        if (numApis <= 0 || numApis > 15) {
            throw new ConfigException("apis.num must be between 1 and 15, got: " + numApis);
        }
        
        for (int i = 1; i <= numApis; i++) {
            String pathKey = "api" + i + ".http.api.path";
            String topicKey = "api" + i + ".topics";
            
            if (!configProps.containsKey(pathKey) || configProps.get(pathKey).trim().isEmpty()) {
                throw new ConfigException("API " + i + " path must be specified: " + pathKey);
            }
            
            if (!configProps.containsKey(topicKey) || configProps.get(topicKey).trim().isEmpty()) {
                throw new ConfigException("API " + i + " topic must be specified: " + topicKey);
            }
        }
    }
    
    private void validateOutputFormatConfig() {
        HttpSourceConnectorConfig.OutputDataFormat format = config.getOutputDataFormat();
        
        // For schema-based formats, we should validate Schema Registry configuration
        if (format != HttpSourceConnectorConfig.OutputDataFormat.JSON_SR) {
            log.warn("Schema Registry integration recommended for format: {}", format);
        }
    }
}