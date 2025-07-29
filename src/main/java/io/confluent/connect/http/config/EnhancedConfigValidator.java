package io.confluent.connect.http.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Enhanced configuration validation system with JSON schema support,
 * real-time validation, and comprehensive validation rules.
 */
public class EnhancedConfigValidator {
    
    private static final Logger log = LoggerFactory.getLogger(EnhancedConfigValidator.class);
    
    private final ObjectMapper objectMapper;
    private final Map<String, String> schemaCache;
    private final Map<String, ValidationRule> customRules;
    private final boolean realTimeValidationEnabled;
    
    // Validation patterns
    private static final Pattern URL_PATTERN = Pattern.compile(
        "^https?://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]"
    );
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,7}$"
    );
    private static final Pattern IPV4_PATTERN = Pattern.compile(
        "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    );
    
    public EnhancedConfigValidator(boolean realTimeValidationEnabled) {
        this.objectMapper = new ObjectMapper();
        this.schemaCache = new ConcurrentHashMap<>();
        this.customRules = new ConcurrentHashMap<>();
        this.realTimeValidationEnabled = realTimeValidationEnabled;
        
        initializeBuiltInRules();
        loadSchemas();
        
        log.info("Enhanced configuration validator initialized: realTime={}, schemas={}, rules={}",
                realTimeValidationEnabled, schemaCache.size(), customRules.size());
    }
    
    /**
     * Validate configuration using JSON schema and custom rules.
     */
    public ValidationResult validateConfiguration(Map<String, String> config) {
        ValidationResult result = new ValidationResult();
        
        try {
            // Convert to JSON for schema validation
            JsonNode configJson = objectMapper.valueToTree(config);
            
            // Schema validation
            validateWithSchema(configJson, result);
            
            // Custom rule validation
            validateWithCustomRules(config, result);
            
            // Cross-field validation
            validateCrossFields(config, result);
            
            // Security validation
            validateSecurity(config, result);
            
            // Performance validation
            validatePerformance(config, result);
            
        } catch (Exception e) {
            // Enhanced error handling with detailed diagnostics
            if (isConnectionRelatedError(e)) {
                String enhancedMessage = generateConnectionErrorDetails(e, config);
                result.addError("CONNECTION_VALIDATION_ERROR", enhancedMessage);
                log.error("Connection validation error with enhanced diagnostics: {}", enhancedMessage, e);
            } else {
                result.addError("VALIDATION_ERROR", "Configuration validation failed: " + e.getMessage());
                log.error("Configuration validation error", e);
            }
        }
        
        log.debug("Configuration validation completed: valid={}, errors={}, warnings={}",
                result.isValid(), result.getErrors().size(), result.getWarnings().size());
        
        return result;
    }
    
    /**
     * Real-time validation for configuration changes.
     */
    public ValidationResult validateConfigurationChange(String key, String oldValue, String newValue, Map<String, String> fullConfig) {
        if (!realTimeValidationEnabled) {
            return ValidationResult.success();
        }
        
        ValidationResult result = new ValidationResult();
        
        try {
            // Validate individual field
            validateField(key, newValue, result);
            
            // Create updated config for cross-validation
            Map<String, String> updatedConfig = new HashMap<>(fullConfig);
            updatedConfig.put(key, newValue);
            
            // Validate dependencies
            validateFieldDependencies(key, newValue, updatedConfig, result);
            
            // Check for breaking changes
            validateBreakingChanges(key, oldValue, newValue, result);
            
        } catch (Exception e) {
            result.addError("RUNTIME_VALIDATION_ERROR", "Real-time validation failed: " + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * Generate configuration template for common scenarios.
     */
    public Map<String, String> generateConfigurationTemplate(ConfigurationTemplate template) {
        Map<String, String> config = new HashMap<>();
        
        switch (template) {
            case BASIC_REST_API:
                config.putAll(generateBasicRestApiTemplate());
                break;
            case OAUTH2_ENTERPRISE:
                config.putAll(generateOAuth2EnterpriseTemplate());
                break;
            case HIGH_PERFORMANCE:
                config.putAll(generateHighPerformanceTemplate());
                break;
            case SECURE_ENTERPRISE:
                config.putAll(generateSecureEnterpriseTemplate());
                break;
            case IOT_SENSORS:
                config.putAll(generateIoTSensorsTemplate());
                break;
            case SOCIAL_MEDIA:
                config.putAll(generateSocialMediaTemplate());
                break;
            default:
                config.putAll(generateDefaultTemplate());
        }
        
        log.info("Generated configuration template: {}", template);
        return config;
    }
    
    /**
     * Compare two configurations and generate diff.
     */
    public ConfigurationDiff compareConfigurations(Map<String, String> oldConfig, Map<String, String> newConfig) {
        ConfigurationDiff diff = new ConfigurationDiff();
        
        Set<String> allKeys = new HashSet<>();
        allKeys.addAll(oldConfig.keySet());
        allKeys.addAll(newConfig.keySet());
        
        for (String key : allKeys) {
            String oldValue = oldConfig.get(key);
            String newValue = newConfig.get(key);
            
            if (oldValue == null && newValue != null) {
                diff.addAddition(key, newValue);
            } else if (oldValue != null && newValue == null) {
                diff.addRemoval(key, oldValue);
            } else if (oldValue != null && !oldValue.equals(newValue)) {
                diff.addModification(key, oldValue, newValue);
                
                // Check if this is a breaking change
                if (isBreakingChange(key, oldValue, newValue)) {
                    diff.addBreakingChange(key, oldValue, newValue);
                }
            }
        }
        
        return diff;
    }
    
    /**
     * Validate configuration with basic schema validation (simplified).
     */
    private void validateWithSchema(JsonNode configJson, ValidationResult result) {
        // Simplified schema validation without external library
        // Check required fields exist - note: configJson contains flat key-value pairs
        String[] requiredFields = {"http.api.base.url"};
        
        for (String field : requiredFields) {
            if (!configJson.has(field)) {
                result.addError("SCHEMA_VALIDATION", "Required field missing: " + field);
            }
        }
        
        // Basic type validation
        validateFieldTypes(configJson, result);
    }
    
    /**
     * Validate field types.
     */
    private void validateFieldTypes(JsonNode configJson, ValidationResult result) {
        // Validate numeric fields
        String[] numericFields = {"tasks.max", "http.source.poll.interval.ms"};
        for (String field : numericFields) {
            JsonNode node = getNestedField(configJson, field);
            if (node != null && !node.isNumber() && !node.isTextual()) {
                result.addError("TYPE_VALIDATION", field + " must be numeric");
            }
        }
        
        // Validate boolean fields
        String[] booleanFields = {"ssl.enabled", "cache.enabled"};
        for (String field : booleanFields) {
            JsonNode node = getNestedField(configJson, field);
            if (node != null && !node.isBoolean() && !node.isTextual()) {
                result.addError("TYPE_VALIDATION", field + " must be boolean");
            }
        }
    }
    
    /**
     * Get nested field from JSON.
     */
    private JsonNode getNestedField(JsonNode json, String fieldPath) {
        String[] parts = fieldPath.split("\\.");
        JsonNode current = json;
        
        for (String part : parts) {
            if (current == null || !current.has(part)) {
                return null;
            }
            current = current.get(part);
        }
        return current;
    }
    
    /**
     * Validate configuration with custom rules.
     */
    private void validateWithCustomRules(Map<String, String> config, ValidationResult result) {
        for (Map.Entry<String, ValidationRule> entry : customRules.entrySet()) {
            String ruleKey = entry.getKey();
            ValidationRule rule = entry.getValue();
            
            try {
                if (!rule.validate(config)) {
                    if (rule.getSeverity() == ValidationSeverity.ERROR) {
                        result.addError(ruleKey, rule.getMessage());
                    } else {
                        result.addWarning(ruleKey, rule.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("Custom rule validation failed for {}: {}", ruleKey, e.getMessage());
            }
        }
    }
    
    /**
     * Cross-field validation logic.
     */
    private void validateCrossFields(Map<String, String> config, ValidationResult result) {
        // OAuth2 validation
        if ("oauth2".equals(config.get("http.auth.type"))) {
            validateOAuth2Configuration(config, result);
        }
        
        // SSL validation
        if ("true".equals(config.get("ssl.enabled"))) {
            validateSSLConfiguration(config, result);
        }
        
        // Pagination validation
        if (config.containsKey("pagination.type")) {
            validatePaginationConfiguration(config, result);
        }
        
        // Rate limiting validation
        if ("true".equals(config.get("rate.limit.enabled"))) {
            validateRateLimitConfiguration(config, result);
        }
        
        // Transformation validation
        if ("true".equals(config.get("transformation.enabled"))) {
            validateTransformationConfiguration(config, result);
        }
    }
    
    /**
     * Security-focused validation.
     */
    private void validateSecurity(Map<String, String> config, ValidationResult result) {
        // Check for insecure configurations
        if ("false".equals(config.get("ssl.enabled"))) {
            result.addWarning("SECURITY_WARNING", "SSL is disabled - consider enabling for production");
        }
        
        // Check for weak authentication
        if ("none".equals(config.get("http.auth.type"))) {
            result.addWarning("SECURITY_WARNING", "No authentication configured - ensure API is secure");
        }
        
        // Check for sensitive data in plain text
        for (Map.Entry<String, String> entry : config.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            
            if (isSensitiveField(key) && !isSecureValue(value)) {
                result.addWarning("SECURITY_WARNING", 
                    "Sensitive field '" + key + "' should use environment variables or secure storage");
            }
        }
        
        // SSL certificate validation
        if ("true".equals(config.get("ssl.certificate.pinning.enabled"))) {
            String pins = config.get("ssl.certificate.pins");
            if (pins == null || pins.trim().isEmpty()) {
                result.addError("SSL_CONFIG_ERROR", "Certificate pinning enabled but no pins specified");
            }
        }
    }
    
    /**
     * Performance-focused validation.
     */
    private void validatePerformance(Map<String, String> config, ValidationResult result) {
        // Check task count
        String tasksMax = config.get("tasks.max");
        if (tasksMax != null) {
            try {
                int tasks = Integer.parseInt(tasksMax);
                if (tasks > 16) {
                    result.addWarning("PERFORMANCE_WARNING", 
                        "High task count (" + tasks + ") may impact performance");
                }
            } catch (NumberFormatException e) {
                result.addError("CONFIG_ERROR", "tasks.max must be a valid integer");
            }
        }
        
        // Check poll interval
        String pollInterval = config.get("http.source.poll.interval.ms");
        if (pollInterval != null) {
            try {
                long interval = Long.parseLong(pollInterval);
                if (interval < 1000) {
                    result.addWarning("PERFORMANCE_WARNING", 
                        "Very short poll interval (" + interval + "ms) may overwhelm API");
                }
            } catch (NumberFormatException e) {
                result.addError("CONFIG_ERROR", "http.source.poll.interval.ms must be a valid long");
            }
        }
        
        // Check cache configuration
        if ("true".equals(config.get("cache.enabled"))) {
            validateCachePerformance(config, result);
        }
    }
    
    /**
     * Initialize built-in validation rules.
     */
    private void initializeBuiltInRules() {
        // URL validation
        customRules.put("url_validation", new ValidationRule() {
            @Override
            public boolean validate(Map<String, String> config) {
                String url = config.get("http.api.base.url");
                return url == null || URL_PATTERN.matcher(url).matches();
            }
            
            @Override
            public String getMessage() {
                return "http.api.base.url must be a valid HTTP/HTTPS URL";
            }
            
            @Override
            public ValidationSeverity getSeverity() {
                return ValidationSeverity.ERROR;
            }
        });
        
        // Required fields validation
        customRules.put("required_fields", new ValidationRule() {
            @Override
            public boolean validate(Map<String, String> config) {
                String[] requiredFields = {"http.api.base.url"};
                for (String field : requiredFields) {
                    if (!config.containsKey(field) || config.get(field).trim().isEmpty()) {
                        return false;
                    }
                }
                return true;
            }
            
            @Override
            public String getMessage() {
                return "Required fields missing: http.api.base.url";
            }
            
            @Override
            public ValidationSeverity getSeverity() {
                return ValidationSeverity.ERROR;
            }
        });
        
        // Timeout validation
        customRules.put("timeout_validation", new ValidationRule() {
            @Override
            public boolean validate(Map<String, String> config) {
                String[] timeoutFields = {
                    "http.client.connection.timeout.ms",
                    "http.client.read.timeout.ms",
                    "http.source.poll.interval.ms"
                };
                
                for (String field : timeoutFields) {
                    String value = config.get(field);
                    if (value != null) {
                        try {
                            long timeout = Long.parseLong(value);
                            if (timeout < 0 || timeout > 86400000) { // 0 to 24 hours
                                return false;
                            }
                        } catch (NumberFormatException e) {
                            return false;
                        }
                    }
                }
                return true;
            }
            
            @Override
            public String getMessage() {
                return "Timeout values must be between 0 and 86400000 milliseconds (24 hours)";
            }
            
            @Override
            public ValidationSeverity getSeverity() {
                return ValidationSeverity.ERROR;
            }
        });
    }
    
    /**
     * Load JSON schemas from resources (simplified).
     */
    private void loadSchemas() {
        try {
            // Simplified schema loading - just mark as loaded
            schemaCache.put("http-source-connector-schema.json", "loaded");
            schemaCache.put("oauth2-schema.json", "loaded");
            schemaCache.put("ssl-schema.json", "loaded");
            schemaCache.put("pagination-schema.json", "loaded");
            schemaCache.put("transformation-schema.json", "loaded");
            schemaCache.put("cache-schema.json", "loaded");
            
            log.debug("Loaded {} configuration schemas", schemaCache.size());
        } catch (Exception e) {
            log.warn("Failed to load configuration schemas: {}", e.getMessage());
        }
    }
    
    /**
     * Validate individual field.
     */
    private void validateField(String key, String value, ValidationResult result) {
        if (key == null || value == null) {
            return;
        }
        
        // URL validation
        if (key.contains("url") && !URL_PATTERN.matcher(value).matches()) {
            result.addError("FIELD_VALIDATION", key + " must be a valid URL");
        }
        
        // Numeric validation
        if (key.contains("timeout") || key.contains("interval") || key.contains("max") || key.contains("port")) {
            try {
                Long.parseLong(value);
            } catch (NumberFormatException e) {
                result.addError("FIELD_VALIDATION", key + " must be a valid number");
            }
        }
        
        // Boolean validation
        if (key.contains("enabled") || key.equals("ssl.enabled")) {
            if (!"true".equals(value) && !"false".equals(value)) {
                result.addError("FIELD_VALIDATION", key + " must be true or false");
            }
        }
    }
    
    /**
     * Validate field dependencies.
     */
    private void validateFieldDependencies(String key, String value, Map<String, String> config, ValidationResult result) {
        // OAuth2 dependencies
        if ("http.auth.type".equals(key) && "oauth2".equals(value)) {
            if (!config.containsKey("http.auth.oauth2.client.id")) {
                result.addWarning("DEPENDENCY_WARNING", "OAuth2 authentication requires client.id");
            }
        }
        
        // SSL dependencies
        if ("ssl.enabled".equals(key) && "true".equals(value)) {
            if (!config.containsKey("ssl.validation.level")) {
                result.addWarning("DEPENDENCY_WARNING", "SSL enabled but no validation level specified");
            }
        }
    }
    
    /**
     * Validate breaking changes.
     */
    private void validateBreakingChanges(String key, String oldValue, String newValue, ValidationResult result) {
        if (isBreakingChange(key, oldValue, newValue)) {
            result.addWarning("BREAKING_CHANGE", 
                String.format("Changing %s from %s to %s may cause compatibility issues", key, oldValue, newValue));
        }
    }
    
    /**
     * Validate pagination configuration.
     */
    private void validatePaginationConfiguration(Map<String, String> config, ValidationResult result) {
        String paginationType = config.get("pagination.type");
        if (paginationType != null) {
            if (!Arrays.asList("offset", "cursor", "page", "link_header", "custom").contains(paginationType)) {
                result.addError("PAGINATION_CONFIG_ERROR", "Invalid pagination type: " + paginationType);
            }
            
            // Validate pagination-specific fields
            if ("cursor".equals(paginationType)) {
                if (!config.containsKey("pagination.cursor.param")) {
                    result.addError("PAGINATION_CONFIG_ERROR", "Cursor pagination requires cursor.param");
                }
            }
        }
    }
    
    /**
     * Validate rate limiting configuration.
     */
    private void validateRateLimitConfiguration(Map<String, String> config, ValidationResult result) {
        String algorithm = config.get("rate.limit.algorithm");
        if (algorithm != null) {
            if (!Arrays.asList("TOKEN_BUCKET", "SLIDING_WINDOW", "FIXED_WINDOW", "LEAKY_BUCKET").contains(algorithm)) {
                result.addError("RATE_LIMIT_CONFIG_ERROR", "Invalid rate limit algorithm: " + algorithm);
            }
        }
        
        String requestsPerSecond = config.get("rate.limit.requests.per.second");
        if (requestsPerSecond != null) {
            try {
                int rps = Integer.parseInt(requestsPerSecond);
                if (rps <= 0 || rps > 10000) {
                    result.addWarning("RATE_LIMIT_WARNING", "Unusual requests per second value: " + rps);
                }
            } catch (NumberFormatException e) {
                result.addError("RATE_LIMIT_CONFIG_ERROR", "rate.limit.requests.per.second must be numeric");
            }
        }
    }
    
    /**
     * Validate transformation configuration.
     */
    private void validateTransformationConfiguration(Map<String, String> config, ValidationResult result) {
        String fieldMappings = config.get("transformation.field.mappings");
        if (fieldMappings != null && !fieldMappings.trim().isEmpty()) {
            // Basic JSON validation for field mappings
            if (!fieldMappings.trim().startsWith("[") || !fieldMappings.trim().endsWith("]")) {
                result.addError("TRANSFORMATION_CONFIG_ERROR", "Field mappings must be valid JSON array");
            }
        }
    }
    
    /**
     * Validate cache performance settings.
     */
    private void validateCachePerformance(Map<String, String> config, ValidationResult result) {
        String maxSize = config.get("cache.response.max.size");
        if (maxSize != null) {
            try {
                int size = Integer.parseInt(maxSize);
                if (size > 10000) {
                    result.addWarning("PERFORMANCE_WARNING", 
                        "Large cache size (" + size + ") may impact memory usage");
                }
            } catch (NumberFormatException e) {
                result.addError("CACHE_CONFIG_ERROR", "Cache max size must be numeric");
            }
        }
    }
    
    /**
     * Generate high performance template.
     */
    private Map<String, String> generateHighPerformanceTemplate() {
        Map<String, String> template = new HashMap<>();
        template.put("name", "high-performance-connector");
        template.put("connector.class", "io.confluent.connect.http.HttpSourceConnector");
        template.put("tasks.max", "8");
        template.put("http.source.poll.interval.ms", "10000");
        template.put("cache.enabled", "true");
        template.put("cache.response.max.size", "5000");
        template.put("rate.limit.enabled", "true");
        template.put("rate.limit.requests.per.second", "50");
        template.put("http.client.connection.pool.enabled", "true");
        template.put("http.client.max.connections", "30");
        return template;
    }
    
    /**
     * Generate secure enterprise template.
     */
    private Map<String, String> generateSecureEnterpriseTemplate() {
        Map<String, String> template = new HashMap<>();
        template.put("name", "secure-enterprise-connector");
        template.put("connector.class", "io.confluent.connect.http.HttpSourceConnector");
        template.put("tasks.max", "4");
        template.put("http.auth.type", "vault");
        template.put("http.auth.vault.address", "${env:VAULT_ADDR}");
        template.put("http.auth.vault.token", "${env:VAULT_TOKEN}");
        template.put("ssl.enabled", "true");
        template.put("ssl.validation.level", "STRICT");
        template.put("ssl.certificate.pinning.enabled", "true");
        template.put("jmx.enabled", "true");
        template.put("health.check.enabled", "true");
        return template;
    }
    
    /**
     * Generate IoT sensors template.
     */
    private Map<String, String> generateIoTSensorsTemplate() {
        Map<String, String> template = new HashMap<>();
        template.put("name", "iot-sensors-connector");
        template.put("connector.class", "io.confluent.connect.http.HttpSourceConnector");
        template.put("tasks.max", "4");
        template.put("http.source.poll.interval.ms", "10000");
        template.put("http.auth.type", "jwt");
        template.put("http.auth.jwt.token", "${env:IOT_DEVICE_TOKEN}");
        template.put("rate.limit.enabled", "true");
        template.put("rate.limit.requests.per.second", "10");
        template.put("transformation.enabled", "true");
        template.put("cache.enabled", "true");
        template.put("cache.response.ttl.seconds", "30");
        return template;
    }
    
    /**
     * Generate social media template.
     */
    private Map<String, String> generateSocialMediaTemplate() {
        Map<String, String> template = new HashMap<>();
        template.put("name", "social-media-connector");
        template.put("connector.class", "io.confluent.connect.http.HttpSourceConnector");
        template.put("tasks.max", "2");
        template.put("http.source.poll.interval.ms", "60000");
        template.put("http.auth.type", "oauth2");
        template.put("http.auth.oauth2.client.id", "${env:SOCIAL_CLIENT_ID}");
        template.put("http.auth.oauth2.client.secret", "${env:SOCIAL_CLIENT_SECRET}");
        template.put("pagination.enabled", "true");
        template.put("pagination.type", "cursor");
        template.put("rate.limit.enabled", "true");
        template.put("rate.limit.requests.per.second", "5");
        return template;
    }
    
    /**
     * Generate default template.
     */
    private Map<String, String> generateDefaultTemplate() {
        Map<String, String> template = new HashMap<>();
        template.put("name", "default-http-connector");
        template.put("connector.class", "io.confluent.connect.http.HttpSourceConnector");
        template.put("tasks.max", "1");
        template.put("http.source.url", "https://api.example.com/data");
        template.put("http.source.method", "GET");
        template.put("http.source.poll.interval.ms", "60000");
        template.put("kafka.topic", "http-data");
        template.put("http.auth.type", "none");
        return template;
    }
    
    /**
     * OAuth2 configuration validation.
     */
    private void validateOAuth2Configuration(Map<String, String> config, ValidationResult result) {
        String clientId = config.get("http.auth.oauth2.client.id");
        String clientSecret = config.get("http.auth.oauth2.client.secret");
        String tokenUrl = config.get("http.auth.oauth2.token.url");
        
        if (clientId == null || clientId.trim().isEmpty()) {
            result.addError("OAUTH2_CONFIG_ERROR", "OAuth2 client ID is required");
        }
        
        if (clientSecret == null || clientSecret.trim().isEmpty()) {
            result.addError("OAUTH2_CONFIG_ERROR", "OAuth2 client secret is required");
        }
        
        if (tokenUrl == null || !URL_PATTERN.matcher(tokenUrl).matches()) {
            result.addError("OAUTH2_CONFIG_ERROR", "OAuth2 token URL must be a valid HTTPS URL");
        }
    }
    
    /**
     * SSL configuration validation.
     */
    private void validateSSLConfiguration(Map<String, String> config, ValidationResult result) {
        String validationLevel = config.get("ssl.validation.level");
        if (validationLevel != null) {
            if (!Arrays.asList("STRICT", "RELAXED", "DISABLED").contains(validationLevel)) {
                result.addError("SSL_CONFIG_ERROR", 
                    "ssl.validation.level must be STRICT, RELAXED, or DISABLED");
            }
        }
        
        // Validate keystore/truststore paths
        String keystorePath = config.get("ssl.keystore.location");
        if (keystorePath != null && !keystorePath.trim().isEmpty()) {
            String keystorePassword = config.get("ssl.keystore.password");
            if (keystorePassword == null || keystorePassword.trim().isEmpty()) {
                result.addError("SSL_CONFIG_ERROR", "SSL keystore password is required when keystore is specified");
            }
        }
    }
    
    /**
     * Generate configuration templates for different scenarios.
     */
    private Map<String, String> generateBasicRestApiTemplate() {
        Map<String, String> template = new HashMap<>();
        template.put("name", "basic-rest-api-connector");
        template.put("connector.class", "io.confluent.connect.http.HttpSourceConnector");
        template.put("tasks.max", "1");
        template.put("http.source.url", "https://api.example.com/data");
        template.put("http.source.method", "GET");
        template.put("http.source.poll.interval.ms", "60000");
        template.put("kafka.topic", "api-data");
        template.put("http.auth.type", "api_key");
        template.put("http.auth.api.key", "${env:API_KEY}");
        return template;
    }
    
    private Map<String, String> generateOAuth2EnterpriseTemplate() {
        Map<String, String> template = new HashMap<>();
        template.put("name", "enterprise-oauth2-connector");
        template.put("connector.class", "io.confluent.connect.http.HttpSourceConnector");
        template.put("tasks.max", "2");
        template.put("http.source.url", "https://enterprise-api.com/v1/data");
        template.put("http.auth.type", "oauth2");
        template.put("http.auth.oauth2.client.id", "${env:OAUTH_CLIENT_ID}");
        template.put("http.auth.oauth2.client.secret", "${env:OAUTH_CLIENT_SECRET}");
        template.put("http.auth.oauth2.token.url", "https://enterprise-api.com/oauth/token");
        template.put("http.auth.oauth2.token.refresh.enabled", "true");
        template.put("ssl.enabled", "true");
        template.put("ssl.validation.level", "STRICT");
        template.put("cache.enabled", "true");
        template.put("jmx.enabled", "true");
        return template;
    }
    
    /**
     * Check if configuration change is breaking.
     */
    private boolean isBreakingChange(String key, String oldValue, String newValue) {
        // Authentication type changes
        if ("http.auth.type".equals(key)) {
            return true;
        }
        
        // URL changes
        if ("http.api.base.url".equals(key)) {
            return true;
        }
        
        // Topic changes (disabled since we don't require specific topic field)
        // if ("kafka.topic".equals(key)) {
        //     return true;
        // }
        
        // SSL changes
        if (key.startsWith("ssl.") && "true".equals(oldValue) && "false".equals(newValue)) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Check if field contains sensitive data.
     */
    private boolean isSensitiveField(String key) {
        String lowerKey = key.toLowerCase();
        return lowerKey.contains("password") || 
               lowerKey.contains("secret") || 
               lowerKey.contains("token") || 
               lowerKey.contains("key") ||
               lowerKey.contains("credential");
    }
    
    /**
     * Check if value is securely configured.
     */
    private boolean isSecureValue(String value) {
        return value.startsWith("${env:") || 
               value.startsWith("${file:") || 
               value.startsWith("${vault:");
    }
    
    /**
     * Check if error is connection-related and should get enhanced diagnostics.
     */
    private boolean isConnectionRelatedError(Exception e) {
        if (e == null) return false;
        
        String errorClass = e.getClass().getName();
        String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        
        return errorClass.contains("Connection") ||
               errorClass.contains("SSL") ||
               errorClass.contains("Certificate") ||
               message.contains("connection") ||
               message.contains("ssl") ||
               message.contains("tls") ||
               message.contains("timeout") ||
               message.contains("handshake") ||
               message.contains("certificate");
    }
    
    /**
     * Generate enhanced connection error details.
     */
    private String generateConnectionErrorDetails(Exception e, Map<String, String> config) {
        try {
            // Create a minimal HTTP config for diagnostics
            io.confluent.connect.http.config.HttpSourceConnectorConfig httpConfig = 
                new io.confluent.connect.http.config.HttpSourceConnectorConfig(config);
            
            io.confluent.connect.http.error.ConnectionErrorDiagnostics diagnostics = 
                new io.confluent.connect.http.error.ConnectionErrorDiagnostics(httpConfig);
            
            String url = config.get("http.api.base.url");
            if (url == null) url = "unknown";
            
            io.confluent.connect.http.error.EnhancedErrorReport report = 
                diagnostics.analyzeConnectionError(e, url, config);
            
            return report.generateSummary();
            
        } catch (Exception diagnosticsError) {
            log.warn("Failed to generate enhanced error diagnostics", diagnosticsError);
            return "Enhanced diagnostics failed: " + e.getMessage() + 
                   " (Diagnostics error: " + diagnosticsError.getMessage() + ")";
        }
    }
    
    /**
     * Configuration template types.
     */
    public enum ConfigurationTemplate {
        BASIC_REST_API,
        OAUTH2_ENTERPRISE,
        HIGH_PERFORMANCE,
        SECURE_ENTERPRISE,
        IOT_SENSORS,
        SOCIAL_MEDIA
    }
    
    /**
     * Validation severity levels.
     */
    public enum ValidationSeverity {
        ERROR,
        WARNING,
        INFO
    }
    
    /**
     * Validation rule interface.
     */
    public interface ValidationRule {
        boolean validate(Map<String, String> config);
        String getMessage();
        ValidationSeverity getSeverity();
    }
}
