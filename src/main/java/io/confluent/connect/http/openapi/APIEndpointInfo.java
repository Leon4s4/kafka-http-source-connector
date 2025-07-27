package io.confluent.connect.http.openapi;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Information about a discovered API endpoint for OpenAPI documentation.
 * Contains metadata, schema information, and examples collected at runtime.
 */
public class APIEndpointInfo {
    
    private final String url;
    private final String method;
    private final List<APIParameter> parameters;
    private final Map<Integer, String> responseExamples;
    private volatile String responseSchema;
    private volatile String description;
    private volatile String summary;
    private final long discoveredAt;
    private volatile long lastSeen;
    
    public APIEndpointInfo(String url, String method) {
        this.url = url;
        this.method = method.toUpperCase();
        this.parameters = new CopyOnWriteArrayList<>();
        this.responseExamples = new ConcurrentHashMap<>();
        this.discoveredAt = System.currentTimeMillis();
        this.lastSeen = this.discoveredAt;
    }
    
    public String getUrl() {
        return url;
    }
    
    public String getMethod() {
        return method;
    }
    
    public List<APIParameter> getParameters() {
        return parameters;
    }
    
    public Map<Integer, String> getResponseExamples() {
        return responseExamples;
    }
    
    public String getResponseSchema() {
        return responseSchema;
    }
    
    public String getDescription() {
        return description != null ? description : generateDefaultDescription();
    }
    
    public String getSummary() {
        return summary != null ? summary : generateDefaultSummary();
    }
    
    public long getDiscoveredAt() {
        return discoveredAt;
    }
    
    public long getLastSeen() {
        return lastSeen;
    }
    
    /**
     * Add a parameter to this endpoint.
     */
    public void addParameter(APIParameter parameter) {
        // Check if parameter already exists
        parameters.removeIf(p -> p.getName().equals(parameter.getName()) && 
                              p.getLocation().equals(parameter.getLocation()));
        parameters.add(parameter);
        updateLastSeen();
    }
    
    /**
     * Add or update a response example for a specific status code.
     */
    public void addResponseExample(int statusCode, String example) {
        if (example != null && !example.trim().isEmpty()) {
            responseExamples.put(statusCode, example);
            updateLastSeen();
        }
    }
    
    /**
     * Update the response schema information.
     */
    public void updateResponseSchema(String schema) {
        if (schema != null && !schema.trim().isEmpty()) {
            this.responseSchema = schema;
            updateLastSeen();
        }
    }
    
    /**
     * Set a custom description for this endpoint.
     */
    public void setDescription(String description) {
        this.description = description;
        updateLastSeen();
    }
    
    /**
     * Set a custom summary for this endpoint.
     */
    public void setSummary(String summary) {
        this.summary = summary;
        updateLastSeen();
    }
    
    /**
     * Check if this endpoint has been seen recently.
     */
    public boolean isActive(long timeWindowMs) {
        return (System.currentTimeMillis() - lastSeen) < timeWindowMs;
    }
    
    /**
     * Get the number of different response status codes seen.
     */
    public int getResponseStatusCount() {
        return responseExamples.size();
    }
    
    /**
     * Check if this endpoint has error responses.
     */
    public boolean hasErrorResponses() {
        return responseExamples.keySet().stream().anyMatch(status -> status >= 400);
    }
    
    /**
     * Get the most common success status code.
     */
    public Integer getPrimarySuccessStatus() {
        return responseExamples.keySet().stream()
            .filter(status -> status >= 200 && status < 300)
            .findFirst()
            .orElse(null);
    }
    
    private void updateLastSeen() {
        this.lastSeen = System.currentTimeMillis();
    }
    
    private String generateDefaultDescription() {
        StringBuilder desc = new StringBuilder();
        desc.append("HTTP ").append(method).append(" endpoint");
        
        if (responseExamples.size() > 0) {
            desc.append(" with ").append(responseExamples.size()).append(" response type(s)");
        }
        
        if (hasErrorResponses()) {
            desc.append(" (includes error responses)");
        }
        
        return desc.toString();
    }
    
    private String generateDefaultSummary() {
        return method + " " + extractEndpointName(url);
    }
    
    private String extractEndpointName(String url) {
        try {
            // Extract meaningful name from URL
            String path = url;
            
            // Remove protocol and domain if present
            if (path.contains("://")) {
                path = path.substring(path.indexOf("://") + 3);
                if (path.contains("/")) {
                    path = path.substring(path.indexOf("/"));
                }
            }
            
            // Remove query parameters
            if (path.contains("?")) {
                path = path.substring(0, path.indexOf("?"));
            }
            
            // Clean up path
            path = path.replaceAll("^/+", "").replaceAll("/+$", "");
            
            if (path.isEmpty()) {
                return "root";
            }
            
            // Return last segment or full path if short
            String[] segments = path.split("/");
            if (segments.length > 0) {
                String lastSegment = segments[segments.length - 1];
                if (!lastSegment.isEmpty()) {
                    return lastSegment;
                }
            }
            
            return path.replaceAll("/", "_");
            
        } catch (Exception e) {
            return "endpoint";
        }
    }
    
    @Override
    public String toString() {
        return String.format("APIEndpointInfo{method=%s, url='%s', parameters=%d, responses=%d, lastSeen=%d}",
                           method, url, parameters.size(), responseExamples.size(), lastSeen);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        
        APIEndpointInfo that = (APIEndpointInfo) o;
        
        if (!method.equals(that.method)) return false;
        return url.equals(that.url);
    }
    
    @Override
    public int hashCode() {
        int result = url.hashCode();
        result = 31 * result + method.hashCode();
        return result;
    }
}
