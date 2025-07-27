package io.confluent.connect.http;

import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import io.confluent.connect.http.operational.OperationalFeaturesManager;
import io.confluent.connect.http.config.EnhancedConfigValidator;
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
 * schema registry integration, advanced features like API chaining, and comprehensive enterprise capabilities.
 * 
 * Enhanced with enterprise features:
 * - JMX monitoring and metrics
 * - Health check endpoints
 * - Enhanced DLQ handling
 * - Rate limiting
 * - OpenAPI documentation
 * - SSL/TLS enhancements
 * - Pagination support
 * - Enhanced authentication (Vault, AWS IAM, Azure AD)
 * - Request/response transformation
 * - Intelligent caching
 * - Configuration validation
 * - Performance optimizations
 * - Operational features
 */
public class HttpSourceConnector extends SourceConnector {
    
    private static final Logger log = LoggerFactory.getLogger(HttpSourceConnector.class);
    
    public static final String VERSION = "2.0.0-enterprise";
    
    private Map<String, String> configProps;
    private HttpSourceConnectorConfig config;
    private OperationalFeaturesManager operationalManager;
    private EnhancedConfigValidator configValidator;
    
    @Override
    public String version() {
        return VERSION;
    }
    
    @Override
    public void start(Map<String, String> props) {
        log.info("Starting Enhanced HttpSourceConnector v{} with {} properties", VERSION, props.size());
        
        try {
            configProps = props;
            
            // Enhanced configuration validation
            configValidator = new EnhancedConfigValidator(true);
            var validationResult = configValidator.validateConfiguration(props);
            
            if (validationResult.hasErrors()) {
                log.error("Configuration validation failed: {}", validationResult.getErrors());
                throw new ConfigException("Configuration validation failed: " + validationResult.getErrors());
            }
            
            if (validationResult.hasWarnings()) {
                log.warn("Configuration warnings: {}", validationResult.getWarnings());
            }
            
            config = new HttpSourceConnectorConfig(props);
            
            // Initialize enterprise features
            initializeEnterpriseFeatures();
            
            // Validate configuration
            validateConfiguration();
            
            log.info("Enhanced HttpSourceConnector started successfully with enterprise features");
            log.info("Base URL: {}", config.getHttpApiBaseUrl());
            log.info("Authentication Type: {}", config.getAuthType());
            log.info("Number of APIs configured: {}", config.getApisNum());
            log.info("Enterprise features: operational={}, validation={}",
                    operationalManager != null, configValidator != null);
            
        } catch (ConfigException e) {
            log.error("Configuration error during connector start", e);
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error during connector start", e);
            throw new ConfigException("Failed to start connector", e);
        }
    }
    
    /**
     * Initialize enterprise features.
     */
    private void initializeEnterpriseFeatures() {
        try {
            // Initialize operational features
            String operationalEnabled = configProps.get("operational.features.enabled");
            if (operationalEnabled == null || Boolean.parseBoolean(operationalEnabled)) {
                OperationalFeaturesManager.OperationalConfig operationalConfig = 
                    new OperationalFeaturesManager.OperationalConfig();
                operationalConfig.setHealthMonitoringEnabled(getBooleanProperty("operational.health.enabled", true));
                operationalConfig.setAlertingEnabled(getBooleanProperty("operational.alerting.enabled", true));
                operationalConfig.setCircuitBreakerEnabled(getBooleanProperty("operational.circuit-breaker.enabled", true));
                operationalConfig.setMetricsCollectionEnabled(getBooleanProperty("operational.metrics.enabled", true));
                
                operationalManager = new OperationalFeaturesManager(operationalConfig);
                operationalManager.start();
                log.info("Operational features initialized and started");
            }
            
        } catch (Exception e) {
            log.error("Failed to initialize enterprise features", e);
            // Continue without enterprise features
        }
    }
    
    /**
     * Helper method to get boolean properties with defaults.
     */
    private boolean getBooleanProperty(String key, boolean defaultValue) {
        String value = configProps.get(key);
        return value != null ? Boolean.parseBoolean(value) : defaultValue;
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
        log.info("Stopping Enhanced HttpSourceConnector");
        
        try {
            // Stop operational features
            if (operationalManager != null) {
                operationalManager.stop();
                log.info("Operational features stopped");
            }
            
        } catch (Exception e) {
            log.error("Error during connector shutdown", e);
        }
        
        log.info("Enhanced HttpSourceConnector stopped successfully");
    }
    
    @Override
    public ConfigDef config() {
        return HttpSourceConnectorConfig.configDef();
    }
    
    /**
     * Validate the connector configuration
     */
    private void validateConfiguration() {
        // Basic validation
        if (config.getHttpApiBaseUrl() == null || config.getHttpApiBaseUrl().trim().isEmpty()) {
            throw new ConfigException("HTTP API base URL is required");
        }
        
        if (config.getApisNum() <= 0) {
            throw new ConfigException("At least one API must be configured");
        }
        
        // Validate each API configuration
        for (int i = 1; i <= config.getApisNum(); i++) {
            String endpoint = config.originalsStrings().get(String.format("api%d.http.api.path", i));
            if (endpoint == null || endpoint.trim().isEmpty()) {
                throw new ConfigException(String.format("API %d path is required", i));
            }
        }
        
        log.debug("Configuration validation passed");
    }
}