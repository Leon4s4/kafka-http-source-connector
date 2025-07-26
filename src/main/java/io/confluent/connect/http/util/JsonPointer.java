package io.confluent.connect.http.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for working with JSON pointers to extract data from JSON responses.
 * Supports RFC 6901 JSON Pointer syntax.
 */
public class JsonPointer {
    
    private static final Logger log = LoggerFactory.getLogger(JsonPointer.class);
    
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * Extracts data from a JSON string using a JSON pointer
     * 
     * @param jsonString The JSON string to extract from
     * @param pointer The JSON pointer path (e.g., "/data/items/0/id")
     * @return The extracted value, or null if not found
     */
    public static Object extract(String jsonString, String pointer) {
        if (jsonString == null || jsonString.trim().isEmpty()) {
            log.debug("Empty JSON string provided for extraction");
            return null;
        }
        
        if (pointer == null || pointer.trim().isEmpty()) {
            log.debug("Empty JSON pointer provided, returning parsed JSON");
            return parse(jsonString);
        }
        
        try {
            JsonNode rootNode = objectMapper.readTree(jsonString);
            return extract(rootNode, pointer);
        } catch (Exception e) {
            log.error("Failed to extract data using JSON pointer '{}': {}", pointer, e.getMessage());
            return null;
        }
    }
    
    /**
     * Extracts data from a JSON object using a JSON pointer
     * 
     * @param data The JSON object to extract from
     * @param pointer The JSON pointer path
     * @return The extracted value, or null if not found
     */
    public static Object extract(Object data, String pointer) {
        if (data == null) {
            log.debug("Null data provided for extraction");
            return null;
        }
        
        if (pointer == null || pointer.trim().isEmpty()) {
            return data;
        }
        
        try {
            JsonNode rootNode;
            if (data instanceof JsonNode) {
                rootNode = (JsonNode) data;
            } else if (data instanceof String) {
                rootNode = objectMapper.readTree((String) data);
            } else {
                rootNode = objectMapper.valueToTree(data);
            }
            
            return extractFromNode(rootNode, pointer);
        } catch (Exception e) {
            log.error("Failed to extract data using JSON pointer '{}': {}", pointer, e.getMessage());
            return null;
        }
    }
    
    /**
     * Parses a JSON string into a Java object
     * 
     * @param jsonString The JSON string to parse
     * @return The parsed object (Map, List, or primitive), or null if parsing fails
     */
    public static Object parse(String jsonString) {
        if (jsonString == null || jsonString.trim().isEmpty()) {
            return null;
        }
        
        try {
            JsonNode node = objectMapper.readTree(jsonString);
            return convertJsonNodeToJavaObject(node);
        } catch (Exception e) {
            log.error("Failed to parse JSON string: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Extracts data from a JsonNode using a JSON pointer
     */
    private static Object extractFromNode(JsonNode rootNode, String pointer) {
        // Normalize pointer (ensure it starts with /)
        if (!pointer.startsWith("/")) {
            pointer = "/" + pointer;
        }
        
        // Use Jackson's built-in JSON pointer support
        JsonNode resultNode = rootNode.at(pointer);
        
        if (resultNode.isMissingNode()) {
            log.debug("JSON pointer '{}' not found in data", pointer);
            return null;
        }
        
        return convertJsonNodeToJavaObject(resultNode);
    }
    
    /**
     * Converts a Jackson JsonNode to a plain Java object
     */
    private static Object convertJsonNodeToJavaObject(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        
        if (node.isTextual()) {
            return node.asText();
        }
        
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        
        if (node.isInt()) {
            return node.asInt();
        }
        
        if (node.isLong()) {
            return node.asLong();
        }
        
        if (node.isDouble()) {
            return node.asDouble();
        }
        
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode element : node) {
                list.add(convertJsonNodeToJavaObject(element));
            }
            return list;
        }
        
        if (node.isObject()) {
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            node.fields().forEachRemaining(entry -> {
                map.put(entry.getKey(), convertJsonNodeToJavaObject(entry.getValue()));
            });
            return map;
        }
        
        // Fallback: return as string
        return node.asText();
    }
    
    /**
     * Validates if a JSON pointer is syntactically correct
     * 
     * @param pointer The JSON pointer to validate
     * @return true if valid, false otherwise
     */
    public static boolean isValidPointer(String pointer) {
        if (pointer == null) {
            return false;
        }
        
        if (pointer.isEmpty()) {
            return true; // Empty string is a valid pointer (points to root)
        }
        
        if (!pointer.startsWith("/")) {
            return false;
        }
        
        // Check for properly escaped characters
        // JSON pointer uses ~0 for ~ and ~1 for /
        return !pointer.matches(".*~[^01].*");
    }
    
    /**
     * Escapes a string for use in a JSON pointer
     * 
     * @param str The string to escape
     * @return The escaped string
     */
    public static String escape(String str) {
        if (str == null) {
            return null;
        }
        
        return str.replace("~", "~0").replace("/", "~1");
    }
    
    /**
     * Unescapes a JSON pointer component
     * 
     * @param str The string to unescape
     * @return The unescaped string
     */
    public static String unescape(String str) {
        if (str == null) {
            return null;
        }
        
        return str.replace("~1", "/").replace("~0", "~");
    }
}