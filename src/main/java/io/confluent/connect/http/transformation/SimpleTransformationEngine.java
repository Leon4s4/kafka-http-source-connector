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
public class SimpleTransformationEngine {
    
    private static final Logger log = LoggerFactory.getLogger(SimpleTransformationEngine.class);
    
    // Template expression patterns
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");
    private static final Pattern FUNCTION_PATTERN = Pattern.compile("(\\w+)\\(([^)]*)\\)");
    private static final Pattern MATH_PATTERN = Pattern.compile("([\\d.]+)\\s*([+\\-*/])\\s*([\\d.]+)");
    
    private final ObjectMapper objectMapper;
    private final Map<String, Object> transformationContext;
    private final boolean enableTemplateExpressions;
    private final boolean enableMathOperations;
    private final boolean enableEnvironmentVariables;
    
    // Performance tracking
    private volatile long transformationCount;
    private volatile long transformationTime;
    private volatile long transformationErrors;
    
    public SimpleTransformationEngine(Map<String, Object> config) {
        this.objectMapper = new ObjectMapper();
        this.transformationContext = new ConcurrentHashMap<>();
        
        // Extract configuration
        this.enableTemplateExpressions = getBooleanConfig(config, "transformation.template.enabled", true);
        this.enableMathOperations = getBooleanConfig(config, "transformation.math.enabled", true);
        this.enableEnvironmentVariables = getBooleanConfig(config, "transformation.env.enabled", true);
        
        // Initialize transformation context
        initializeContext();
        
        log.info("Simple transformation engine initialized: template={}, math={}, env={}",
                enableTemplateExpressions, enableMathOperations, enableEnvironmentVariables);
    }
    
    /**
     * Transform JSON data using basic field transformations.
     */
    public JsonNode transform(JsonNode inputData, Map<String, String> fieldMappings) {
        long startTime = System.currentTimeMillis();
        
        try {
            transformationCount++;
            
            if (inputData == null) {
                return null;
            }
            
            // Apply basic field mappings
            ObjectNode result = objectMapper.createObjectNode();
            
            if (fieldMappings != null) {
                for (Map.Entry<String, String> mapping : fieldMappings.entrySet()) {
                    String sourcePath = mapping.getKey();
                    String targetPath = mapping.getValue();
                    
                    JsonNode sourceValue = extractValue(inputData, sourcePath);
                    if (sourceValue != null) {
                        setNestedValue(result, targetPath, extractJsonValue(sourceValue));
                    }
                }
            } else {
                // Copy all fields if no mappings specified
                return inputData;
            }
            
            // Add enrichment fields
            addEnrichmentFields(result);
            
            transformationTime += (System.currentTimeMillis() - startTime);
            log.trace("Transformation completed in {}ms", System.currentTimeMillis() - startTime);
            
            return result;
            
        } catch (Exception e) {
            transformationErrors++;
            log.error("Transformation failed: {}", e.getMessage(), e);
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
     * Get transformation statistics.
     */
    public TransformationStats getStats() {
        return new TransformationStats(
            transformationCount,
            transformationErrors,
            transformationCount > 0 ? (double) transformationTime / transformationCount : 0.0
        );
    }
    
    /**
     * Add enrichment fields to the result.
     */
    private void addEnrichmentFields(ObjectNode result) {
        result.put("processed_timestamp", System.currentTimeMillis());
        result.put("processed_id", UUID.randomUUID().toString());
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
     * Get system hostname.
     */
    private String getHostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
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
        
        public TransformationStats(long transformationCount, long transformationErrors,
                                 double averageTransformationTime) {
            this.transformationCount = transformationCount;
            this.transformationErrors = transformationErrors;
            this.averageTransformationTime = averageTransformationTime;
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
                               "avgTime=%.1fms}",
                               transformationCount, transformationErrors, getSuccessRate(),
                               averageTransformationTime);
        }
    }
}
