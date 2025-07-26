package io.confluent.connect.http.chaining;

import io.confluent.connect.http.config.ApiConfig;
import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import io.confluent.connect.http.util.JsonPointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages API chaining relationships where parent APIs provide data for child API requests.
 * Supports one level of parent-child relationships as specified in the PRD.
 */
public class ApiChainingManager {
    
    private static final Logger log = LoggerFactory.getLogger(ApiChainingManager.class);
    
    private final Map<String, String> parentChildRelationships;
    private final Map<String, String> parentResponseData;
    private final Map<String, List<Object>> parentRecords;
    
    public ApiChainingManager(HttpSourceConnectorConfig config) {
        this.parentChildRelationships = parseParentChildRelationships(config.getApiChainingParentChildRelationship());
        this.parentResponseData = new ConcurrentHashMap<>();
        this.parentRecords = new ConcurrentHashMap<>();
        
        log.info("Initialized API chaining with {} parent-child relationships", parentChildRelationships.size());
        if (!parentChildRelationships.isEmpty()) {
            log.debug("Parent-child relationships: {}", parentChildRelationships);
        }
    }
    
    /**
     * Checks if the given API is a parent API that has child APIs depending on it
     */
    public boolean isParentApi(String apiId) {
        return parentChildRelationships.containsValue(apiId);
    }
    
    /**
     * Checks if the given API is a child API that depends on a parent API
     */
    public boolean isChildApi(String apiId) {
        return parentChildRelationships.containsKey(apiId);
    }
    
    /**
     * Gets the parent API ID for the given child API
     */
    public String getParentApiId(String childApiId) {
        return parentChildRelationships.get(childApiId);
    }
    
    /**
     * Stores the response data from a parent API for use by child APIs
     */
    public void storeParentResponse(String parentApiId, String responseData, List<Object> records) {
        if (isParentApi(parentApiId)) {
            parentResponseData.put(parentApiId, responseData);
            parentRecords.put(parentApiId, records);
            log.debug("Stored response data for parent API: {} (records: {})", parentApiId, records.size());
        }
    }
    
    /**
     * Gets template variables for a child API based on its parent's response data
     */
    public Map<String, String> getChildApiTemplateVariables(ApiConfig childApiConfig) {
        Map<String, String> templateVars = new HashMap<>();
        
        String childApiId = childApiConfig.getId();
        String parentApiId = getParentApiId(childApiId);
        
        if (parentApiId == null) {
            return templateVars; // Not a child API
        }
        
        String parentResponseData = this.parentResponseData.get(parentApiId);
        if (parentResponseData == null) {
            log.warn("No parent response data available for child API: {} (parent: {})", childApiId, parentApiId);
            return templateVars;
        }
        
        // Extract chaining variables from parent response
        String chainingJsonPointer = childApiConfig.getHttpChainingJsonPointer();
        if (chainingJsonPointer != null && !chainingJsonPointer.trim().isEmpty()) {
            try {
                Object chainValue = JsonPointer.extract(parentResponseData, chainingJsonPointer);
                if (chainValue != null) {
                    templateVars.put("parent_value", chainValue.toString());
                    log.trace("Extracted chaining value for child API {}: {}", childApiId, chainValue);
                }
            } catch (Exception e) {
                log.error("Failed to extract chaining value from parent response for API {}: {}", 
                    childApiId, e.getMessage());
            }
        }
        
        // Add parent API ID for reference
        templateVars.put("parent_api_id", parentApiId);
        
        return templateVars;
    }
    
    /**
     * Checks if a child API should be executed based on its parent's state
     */
    public boolean shouldExecuteChildApi(String childApiId) {
        String parentApiId = getParentApiId(childApiId);
        if (parentApiId == null) {
            return true; // Not a child API, can execute normally
        }
        
        // Child API can only execute if parent has provided data
        boolean hasParentData = parentResponseData.containsKey(parentApiId);
        
        if (!hasParentData) {
            log.debug("Child API {} waiting for parent API {} to provide data", childApiId, parentApiId);
        }
        
        return hasParentData;
    }
    
    /**
     * Gets the records from a parent API for processing by child APIs
     */
    public List<Object> getParentRecords(String parentApiId) {
        return parentRecords.get(parentApiId);
    }
    
    /**
     * Clears stored parent data (called when parent API processes new data)
     */
    public void clearParentData(String parentApiId) {
        parentResponseData.remove(parentApiId);
        parentRecords.remove(parentApiId);
        log.debug("Cleared cached data for parent API: {}", parentApiId);
    }
    
    /**
     * Parses the parent-child relationship configuration string
     * Format: "child1:parent1,child2:parent1,child3:parent2"
     */
    private Map<String, String> parseParentChildRelationships(String relationshipConfig) {
        Map<String, String> relationships = new HashMap<>();
        
        if (relationshipConfig == null || relationshipConfig.trim().isEmpty()) {
            return relationships;
        }
        
        String[] pairs = relationshipConfig.split(",");
        for (String pair : pairs) {
            pair = pair.trim();
            if (pair.isEmpty()) continue;
            
            String[] parts = pair.split(":", 2);
            if (parts.length == 2) {
                String childApi = parts[0].trim();
                String parentApi = parts[1].trim();
                relationships.put(childApi, parentApi);
                log.debug("Configured chaining: {} -> {}", childApi, parentApi);
            } else {
                log.warn("Invalid parent-child relationship format: {}. Expected 'child:parent'", pair);
            }
        }
        
        return relationships;
    }
    
    /**
     * Gets all child APIs for a given parent API
     */
    public Set<String> getChildApis(String parentApiId) {
        return parentChildRelationships.entrySet().stream()
            .filter(entry -> parentApiId.equals(entry.getValue()))
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toSet());
    }
    
    /**
     * Validates the chaining configuration to ensure no circular dependencies
     */
    public void validateChainingConfiguration(List<ApiConfig> apiConfigs) {
        // Check for circular dependencies
        for (String childApi : parentChildRelationships.keySet()) {
            if (hasCircularDependency(childApi, new HashSet<>())) {
                throw new IllegalArgumentException("Circular dependency detected in API chaining configuration involving: " + childApi);
            }
        }
        
        // Ensure parent APIs exist in the configuration
        for (Map.Entry<String, String> entry : parentChildRelationships.entrySet()) {
            String childApi = entry.getKey();
            String parentApi = entry.getValue();
            
            boolean parentExists = apiConfigs.stream().anyMatch(config -> parentApi.equals(config.getId()));
            boolean childExists = apiConfigs.stream().anyMatch(config -> childApi.equals(config.getId()));
            
            if (!parentExists) {
                throw new IllegalArgumentException("Parent API '" + parentApi + "' referenced by child API '" + childApi + "' does not exist in configuration");
            }
            
            if (!childExists) {
                throw new IllegalArgumentException("Child API '" + childApi + "' in chaining configuration does not exist in API configuration");
            }
        }
        
        log.info("API chaining configuration validation completed successfully");
    }
    
    /**
     * Checks for circular dependencies in the chaining configuration
     */
    private boolean hasCircularDependency(String apiId, java.util.Set<String> visited) {
        if (visited.contains(apiId)) {
            return true; // Circular dependency found
        }
        
        visited.add(apiId);
        String parentApi = parentChildRelationships.get(apiId);
        
        if (parentApi != null) {
            return hasCircularDependency(parentApi, visited);
        }
        
        return false;
    }
}