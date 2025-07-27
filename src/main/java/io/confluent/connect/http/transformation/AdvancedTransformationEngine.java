package io.confluent.connect.http.transformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Advanced transformation engine for HTTP API responses.
 * Supports field mapping, filtering, data type conversion, template expressions,
 * conditional logic, and mathematical operations.
 */
public class AdvancedTransformationEngine {
    
    private static final Logger log = LoggerFactory.getLogger(AdvancedTransformationEngine.class);
    
    // Template expression patterns
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");
    private static final Pattern FUNCTION_PATTERN = Pattern.compile("(\\w+)\\(([^)]*)\\)");
    private static final Pattern MATH_PATTERN = Pattern.compile("([\\d.]+)\\s*([+\\-*/])\\s*([\\d.]+)");
    private static final Pattern CONDITION_PATTERN = Pattern.compile("if\\s*\\(([^)]+)\\)\\s*\\{([^}]+)\\}(?:\\s*else\\s*\\{([^}]+)\\})?");
    
    private final ObjectMapper objectMapper;
    private final Map<String, Object> transformationContext;
    private final Map<String, TransformationRule> transformationRules;
    private final boolean enableTemplateExpressions;
    private final boolean enableConditionalLogic;
    private final boolean enableMathOperations;
    private final boolean enableEnvironmentVariables;
    
    // Performance tracking
    private volatile long transformationCount;
    private volatile long transformationTime;
    private volatile long transformationErrors;
    
    public AdvancedTransformationEngine(Map<String, Object> config) {
        this.objectMapper = new ObjectMapper();
        this.transformationContext = new ConcurrentHashMap<>();
        this.transformationRules = new ConcurrentHashMap<>();
        
        // Extract configuration
        this.enableTemplateExpressions = getBooleanConfig(config, "transformation.template.enabled", true);
        this.enableConditionalLogic = getBooleanConfig(config, "transformation.conditional.enabled", true);
        this.enableMathOperations = getBooleanConfig(config, "transformation.math.enabled", true);
        this.enableEnvironmentVariables = getBooleanConfig(config, "transformation.env.enabled", true);
        
        // Initialize transformation context
        initializeContext();
        
        // Load transformation rules from configuration
        loadTransformationRules(config);
        
        log.info("Advanced transformation engine initialized: rules={}, template={}, conditional={}, math={}, env={}",
                transformationRules.size(), enableTemplateExpressions, enableConditionalLogic, 
                enableMathOperations, enableEnvironmentVariables);
    }
    
    /**
     * Transform JSON data using configured rules and expressions.
     */
    public JsonNode transform(JsonNode inputData, String transformationName) {
        long startTime = System.currentTimeMillis();
        
        try {
            transformationCount++;
            
            if (inputData == null) {
                return null;
            }
            
            // Get transformation rule
            TransformationRule rule = transformationRules.get(transformationName);
            if (rule == null) {
                log.debug("No transformation rule found for: {}", transformationName);
                return inputData; // Return original data
            }
            
            // Apply transformation
            JsonNode transformedData = applyTransformationRule(inputData, rule);
            
            transformationTime += (System.currentTimeMillis() - startTime);
            log.trace("Transformation '{}' completed in {}ms", transformationName, 
                     System.currentTimeMillis() - startTime);
            
            return transformedData;
            
        } catch (Exception e) {
            transformationErrors++;
            log.error("Transformation '{}' failed: {}", transformationName, e.getMessage(), e);
            return inputData; // Return original data on error
        }
    }
    
    /**
     * Transform a simple value using template expressions.
     */
    public String transformValue(String template, Map<String, Object> context) {
        if (template == null || !enableTemplateExpressions) {
            return template;
        }
        
        try {
            // Merge transformation context with provided context
            Map<String, Object> combinedContext = new HashMap<>(transformationContext);
            if (context != null) {
                combinedContext.putAll(context);
            }
            
            return processTemplateExpressions(template, combinedContext);
            
        } catch (Exception e) {
            log.error("Template transformation failed: {}", e.getMessage(), e);
            return template; // Return original on error
        }
    }
    
    /**
     * Add a transformation rule dynamically.
     */
    public void addTransformationRule(String name, TransformationRule rule) {
        transformationRules.put(name, rule);
        log.debug("Added transformation rule: {}", name);
    }
    
    /**
     * Get transformation statistics.
     */
    public TransformationStats getStats() {
        return new TransformationStats(
            transformationCount,
            transformationErrors,
            transformationCount > 0 ? (double) transformationTime / transformationCount : 0.0,
            transformationRules.size()
        );
    }
    
    /**
     * Apply a transformation rule to JSON data.
     */
    private JsonNode applyTransformationRule(JsonNode inputData, TransformationRule rule) throws Exception {
        ObjectNode result = objectMapper.createObjectNode();
        
        // Apply field mappings
        for (FieldMapping mapping : rule.getFieldMappings()) {
            applyFieldMapping(inputData, result, mapping);
        }
        
        // Apply filters
        if (rule.getFilter() != null && !evaluateFilter(inputData, rule.getFilter())) {
            return null; // Filter rejected the record
        }
        
        // Apply enrichments
        for (FieldEnrichment enrichment : rule.getEnrichments()) {
            applyFieldEnrichment(result, enrichment);
        }
        
        return result;
    }
    
    /**
     * Apply a field mapping transformation.
     */
    private void applyFieldMapping(JsonNode input, ObjectNode output, FieldMapping mapping) throws Exception {
        JsonNode sourceValue = extractValue(input, mapping.getSourcePath());
        
        if (sourceValue != null) {
            Object transformedValue = transformFieldValue(sourceValue, mapping);
            setNestedValue(output, mapping.getTargetPath(), transformedValue);
        } else if (mapping.getDefaultValue() != null) {
            setNestedValue(output, mapping.getTargetPath(), mapping.getDefaultValue());
        }
    }
    
    /**
     * Apply field enrichment.
     */
    private void applyFieldEnrichment(ObjectNode output, FieldEnrichment enrichment) {
        try {
            Object enrichedValue = calculateEnrichmentValue(enrichment);
            setNestedValue(output, enrichment.getTargetPath(), enrichedValue);
        } catch (Exception e) {
            log.warn("Field enrichment failed for path '{}': {}", enrichment.getTargetPath(), e.getMessage());
        }
    }
    
    /**
     * Transform a field value based on mapping configuration.
     */
    private Object transformFieldValue(JsonNode sourceValue, FieldMapping mapping) {
        Object value = extractJsonValue(sourceValue);
        
        // Apply data type conversion
        if (mapping.getTargetType() != null) {
            value = convertDataType(value, mapping.getTargetType());
        }
        
        // Apply template transformation
        if (mapping.getTemplate() != null) {
            Map<String, Object> context = Map.of("value", value);
            value = processTemplateExpressions(mapping.getTemplate(), context);
        }
        
        // Apply validation
        if (mapping.getValidation() != null && !validateValue(value, mapping.getValidation())) {
            log.warn("Value validation failed for mapping: {}", mapping.getSourcePath());
            return mapping.getDefaultValue();
        }
        
        return value;
    }
    
    /**
     * Process template expressions in a string.
     */
    private String processTemplateExpressions(String template, Map<String, Object> context) {
        if (template == null) {
            return null;
        }
        
        String result = template;
        Matcher matcher = TEMPLATE_PATTERN.matcher(template);
        
        while (matcher.find()) {
            String expression = matcher.group(1);
            String value = evaluateExpression(expression, context);
            result = result.replace(matcher.group(0), value != null ? value : "");
        }
        
        return result;
    }
    
    /**
     * Evaluate a template expression.
     */
    private String evaluateExpression(String expression, Map<String, Object> context) {
        try {
            // Check for functions
            Matcher functionMatcher = FUNCTION_PATTERN.matcher(expression);
            if (functionMatcher.matches()) {
                return evaluateFunction(functionMatcher.group(1), functionMatcher.group(2), context);
            }
            
            // Check for mathematical operations
            if (enableMathOperations) {
                Matcher mathMatcher = MATH_PATTERN.matcher(expression);
                if (mathMatcher.matches()) {
                    return evaluateMathOperation(mathMatcher, context);
                }
            }
            
            // Check for conditional logic
            if (enableConditionalLogic) {
                Matcher conditionMatcher = CONDITION_PATTERN.matcher(expression);
                if (conditionMatcher.matches()) {
                    return evaluateConditional(conditionMatcher, context);
                }
            }
            
            // Check for environment variables
            if (enableEnvironmentVariables && expression.startsWith("env.")) {
                String envVar = expression.substring(4);
                return System.getenv(envVar);
            }
            
            // Simple variable lookup
            Object value = context.get(expression);
            return value != null ? value.toString() : "";
            
        } catch (Exception e) {
            log.warn("Expression evaluation failed for '{}': {}", expression, e.getMessage());
            return "";
        }
    }
    
    /**
     * Evaluate a function call.
     */
    private String evaluateFunction(String functionName, String args, Map<String, Object> context) {
        switch (functionName.toLowerCase()) {
            case "now":
                return formatTimestamp(Instant.now(), args);
            case "today":
                return formatDate(LocalDateTime.now(), args);
            case "yesterday":
                return formatDate(LocalDateTime.now().minusDays(1), args);
            case "tomorrow":
                return formatDate(LocalDateTime.now().plusDays(1), args);
            case "uuid":
                return UUID.randomUUID().toString();
            case "upper":
                Object upperValue = context.get(args.trim());
                return upperValue != null ? upperValue.toString().toUpperCase() : "";
            case "lower":
                Object lowerValue = context.get(args.trim());
                return lowerValue != null ? lowerValue.toString().toLowerCase() : "";
            case "length":
                Object lengthValue = context.get(args.trim());
                return lengthValue != null ? String.valueOf(lengthValue.toString().length()) : "0";
            default:
                log.warn("Unknown function: {}", functionName);
                return "";
        }
    }
    
    /**
     * Evaluate mathematical operations.
     */
    private String evaluateMathOperation(Matcher mathMatcher, Map<String, Object> context) {
        try {
            double left = Double.parseDouble(mathMatcher.group(1));
            String operator = mathMatcher.group(2);
            double right = Double.parseDouble(mathMatcher.group(3));
            
            double result;
            switch (operator) {
                case "+":
                    result = left + right;
                    break;
                case "-":
                    result = left - right;
                    break;
                case "*":
                    result = left * right;
                    break;
                case "/":
                    result = left / right;
                    break;
                default:
                    return "";
            }
            
            // Return as integer if it's a whole number
            if (result == Math.floor(result)) {
                return String.valueOf((long) result);
            } else {
                return String.valueOf(result);
            }
            
        } catch (Exception e) {
            log.warn("Math operation evaluation failed: {}", e.getMessage());
            return "";
        }
    }
    
    /**
     * Evaluate conditional expressions.
     */
    private String evaluateConditional(Matcher conditionMatcher, Map<String, Object> context) {
        try {
            String condition = conditionMatcher.group(1);
            String trueValue = conditionMatcher.group(2);
            String falseValue = conditionMatcher.group(3);
            
            boolean conditionResult = evaluateCondition(condition, context);
            
            if (conditionResult) {
                return processTemplateExpressions(trueValue, context);
            } else if (falseValue != null) {
                return processTemplateExpressions(falseValue, context);
            } else {
                return "";
            }
            
        } catch (Exception e) {
            log.warn("Conditional evaluation failed: {}", e.getMessage());
            return "";
        }
    }
    
    /**
     * Evaluate a boolean condition.
     */
    private boolean evaluateCondition(String condition, Map<String, Object> context) {
        // Simple condition evaluation (can be enhanced)
        if (condition.contains("==")) {
            String[] parts = condition.split("==");
            if (parts.length == 2) {
                String left = processTemplateExpressions(parts[0].trim(), context);
                String right = processTemplateExpressions(parts[1].trim(), context);
                return Objects.equals(left, right);
            }
        } else if (condition.contains("!=")) {
            String[] parts = condition.split("!=");
            if (parts.length == 2) {
                String left = processTemplateExpressions(parts[0].trim(), context);
                String right = processTemplateExpressions(parts[1].trim(), context);
                return !Objects.equals(left, right);
            }
        }
        
        // Default: check if variable exists and is truthy
        Object value = context.get(condition.trim());
        return value != null && !value.toString().isEmpty() && !"false".equalsIgnoreCase(value.toString());
    }
    
    /**
     * Extract a value from JSON using JSONPath-like syntax.
     */
    private JsonNode extractValue(JsonNode node, String path) {
        if (path == null || path.isEmpty()) {
            return node;
        }
        
        String[] pathParts = path.split("\\.");
        JsonNode current = node;
        
        for (String part : pathParts) {
            if (current == null) {
                return null;
            }
            
            if (part.contains("[") && part.contains("]")) {
                // Array access
                String fieldName = part.substring(0, part.indexOf('['));
                String indexStr = part.substring(part.indexOf('[') + 1, part.indexOf(']'));
                
                if (!fieldName.isEmpty()) {
                    current = current.get(fieldName);
                }
                
                if (current != null && current.isArray()) {
                    try {
                        int index = Integer.parseInt(indexStr);
                        current = current.get(index);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
            } else {
                current = current.get(part);
            }
        }
        
        return current;
    }
    
    /**
     * Set a nested value in an ObjectNode.
     */
    private void setNestedValue(ObjectNode node, String path, Object value) {
        String[] pathParts = path.split("\\.");
        ObjectNode current = node;
        
        for (int i = 0; i < pathParts.length - 1; i++) {
            String part = pathParts[i];
            if (!current.has(part)) {
                current.set(part, objectMapper.createObjectNode());
            }
            current = (ObjectNode) current.get(part);
        }
        
        String finalKey = pathParts[pathParts.length - 1];
        if (value instanceof String) {
            current.put(finalKey, (String) value);
        } else if (value instanceof Number) {
            if (value instanceof Integer) {
                current.put(finalKey, (Integer) value);
            } else if (value instanceof Long) {
                current.put(finalKey, (Long) value);
            } else if (value instanceof Double) {
                current.put(finalKey, (Double) value);
            }
        } else if (value instanceof Boolean) {
            current.put(finalKey, (Boolean) value);
        } else if (value != null) {
            current.put(finalKey, value.toString());
        }
    }
    
    /**
     * Convert JsonNode to Java object.
     */
    private Object extractJsonValue(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        } else if (node.isNumber()) {
            if (node.isInt()) {
                return node.asInt();
            } else if (node.isLong()) {
                return node.asLong();
            } else {
                return node.asDouble();
            }
        } else if (node.isBoolean()) {
            return node.asBoolean();
        } else {
            return node.toString();
        }
    }
    
    /**
     * Convert data type.
     */
    private Object convertDataType(Object value, String targetType) {
        if (value == null) {
            return null;
        }
        
        try {
            switch (targetType.toLowerCase()) {
                case "string":
                    return value.toString();
                case "int":
                case "integer":
                    return value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(value.toString());
                case "long":
                    return value instanceof Number ? ((Number) value).longValue() : Long.parseLong(value.toString());
                case "double":
                    return value instanceof Number ? ((Number) value).doubleValue() : Double.parseDouble(value.toString());
                case "boolean":
                    return value instanceof Boolean ? value : Boolean.parseBoolean(value.toString());
                default:
                    return value;
            }
        } catch (Exception e) {
            log.warn("Data type conversion failed from {} to {}: {}", value.getClass().getSimpleName(), targetType, e.getMessage());
            return value;
        }
    }
    
    /**
     * Validate a value against validation rules.
     */
    private boolean validateValue(Object value, ValueValidation validation) {
        if (value == null) {
            return !validation.isRequired();
        }
        
        String strValue = value.toString();
        
        // Check required
        if (validation.isRequired() && strValue.isEmpty()) {
            return false;
        }
        
        // Check pattern
        if (validation.getPattern() != null && !strValue.matches(validation.getPattern())) {
            return false;
        }
        
        // Check min/max length
        if (validation.getMinLength() > 0 && strValue.length() < validation.getMinLength()) {
            return false;
        }
        if (validation.getMaxLength() > 0 && strValue.length() > validation.getMaxLength()) {
            return false;
        }
        
        return true;
    }
    
    /**
     * Evaluate a filter expression.
     */
    private boolean evaluateFilter(JsonNode data, FilterExpression filter) {
        // Simple filter evaluation - can be enhanced
        JsonNode value = extractValue(data, filter.getFieldPath());
        
        if (value == null) {
            return false;
        }
        
        Object actualValue = extractJsonValue(value);
        Object expectedValue = filter.getExpectedValue();
        
        switch (filter.getOperator()) {
            case EQUALS:
                return Objects.equals(actualValue.toString(), expectedValue.toString());
            case NOT_EQUALS:
                return !Objects.equals(actualValue.toString(), expectedValue.toString());
            case CONTAINS:
                return actualValue.toString().contains(expectedValue.toString());
            case NOT_CONTAINS:
                return !actualValue.toString().contains(expectedValue.toString());
            case EXISTS:
                return true; // Field exists if we got here
            case NOT_EXISTS:
                return false; // Field exists if we got here
            default:
                return true;
        }
    }
    
    /**
     * Calculate enrichment value.
     */
    private Object calculateEnrichmentValue(FieldEnrichment enrichment) {
        switch (enrichment.getType()) {
            case TIMESTAMP:
                return System.currentTimeMillis();
            case UUID:
                return UUID.randomUUID().toString();
            case CONSTANT:
                return enrichment.getValue();
            case TEMPLATE:
                return processTemplateExpressions(enrichment.getTemplate(), transformationContext);
            default:
                return null;
        }
    }
    
    /**
     * Format timestamp with optional format.
     */
    private String formatTimestamp(Instant instant, String format) {
        if (format == null || format.trim().isEmpty()) {
            return instant.toString();
        }
        
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format.trim());
            return formatter.format(instant.atZone(ZoneId.systemDefault()));
        } catch (Exception e) {
            return instant.toString();
        }
    }
    
    /**
     * Format date with optional format.
     */
    private String formatDate(LocalDateTime dateTime, String format) {
        if (format == null || format.trim().isEmpty()) {
            return dateTime.toString();
        }
        
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format.trim());
            return formatter.format(dateTime);
        } catch (Exception e) {
            return dateTime.toString();
        }
    }
    
    /**
     * Initialize transformation context with system variables.
     */
    private void initializeContext() {
        transformationContext.put("timestamp", System.currentTimeMillis());
        transformationContext.put("hostname", getHostname());
        transformationContext.put("random", Math.random());
    }
    
    /**
     * Load transformation rules from configuration.
     */
    private void loadTransformationRules(Map<String, Object> config) {
        // This would load transformation rules from configuration
        // For now, we'll add a default rule
        transformationRules.put("default", createDefaultTransformationRule());
    }
    
    /**
     * Create a default transformation rule.
     */
    private TransformationRule createDefaultTransformationRule() {
        return TransformationRule.builder()
            .addFieldMapping(FieldMapping.builder()
                .sourcePath("data")
                .targetPath("value")
                .targetType("string")
                .build())
            .addEnrichment(FieldEnrichment.builder()
                .targetPath("processed_timestamp")
                .type(FieldEnrichment.EnrichmentType.TIMESTAMP)
                .build())
            .build();
    }
    
    /**
     * Get system hostname.
     */
    private static final String cachedHostname;

    static {
        String hostname;
        try {
            hostname = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            hostname = "unknown";
        }
        cachedHostname = hostname;
    }

    private String getHostname() {
        return cachedHostname;
    }
    
    /**
     * Get boolean configuration value.
     */
    private boolean getBooleanConfig(Map<String, Object> config, String key, boolean defaultValue) {
        Object value = config.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        } else if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return defaultValue;
    }
    
    /**
     * Transformation statistics.
     */
    public static class TransformationStats {
        public final long transformationCount;
        public final long transformationErrors;
        public final double averageTransformationTime;
        public final int ruleCount;
        
        public TransformationStats(long transformationCount, long transformationErrors,
                                 double averageTransformationTime, int ruleCount) {
            this.transformationCount = transformationCount;
            this.transformationErrors = transformationErrors;
            this.averageTransformationTime = averageTransformationTime;
            this.ruleCount = ruleCount;
        }
        
        public double getSuccessRate() {
            if (transformationCount == 0) {
                return 100.0;
            }
            return (double) (transformationCount - transformationErrors) / transformationCount * 100.0;
        }
        
        @Override
        public String toString() {
            return String.format("TransformationStats{count=%d, errors=%d, successRate=%.1f%%, " +
                               "avgTime=%.1fms, rules=%d}",
                               transformationCount, transformationErrors, getSuccessRate(),
                               averageTransformationTime, ruleCount);
        }
    }
}
