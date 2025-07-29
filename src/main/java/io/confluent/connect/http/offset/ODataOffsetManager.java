package io.confluent.connect.http.offset;

import io.confluent.connect.http.config.ApiConfig;
import org.apache.kafka.connect.source.SourceTaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offset manager for OData pagination mode.
 * 
 * This manager handles OData-specific pagination patterns where:
 * 1. Initial requests use the configured path with query parameters
 * 2. Subsequent requests use either @odata.nextLink (for paging) or @odata.deltaLink (for delta changes)
 * 3. Links contain full URLs that need to be parsed to extract just the path and query parameters
 * 
 * Supports configurable extraction of:
 * - nextLink field name (default: "@odata.nextLink")  
 * - deltaLink field name (default: "@odata.deltaLink")
 * - Token extraction mode (FULL_URL or TOKEN_ONLY)
 * 
 * Example OData response:
 * {
 *   "value": [...],
 *   "@odata.nextLink": "[Org]/api/data/v9.0/accounts?$select=name&$skiptoken=...",
 *   "@odata.deltaLink": "[Org]/api/data/v9.0/accounts?$select=name&$deltatoken=..."
 * }
 */
public class ODataOffsetManager implements OffsetManager {
    
    private static final Logger log = LoggerFactory.getLogger(ODataOffsetManager.class);
    
    // Default OData link field names
    private static final String DEFAULT_NEXTLINK_FIELD = "@odata.nextLink";
    private static final String DEFAULT_DELTALINK_FIELD = "@odata.deltaLink";
    
    // Token extraction patterns
    // Thread-safe: Pattern is immutable and used in a read-only context.
    private static final Pattern SKIPTOKEN_PATTERN = Pattern.compile("\\$skiptoken=([^&]+)");
    // Thread-safe: Pattern is immutable and used in a read-only context.
    private static final Pattern DELTATOKEN_PATTERN = Pattern.compile("\\$deltatoken=([^&]+)");
    
    private final ApiConfig apiConfig;
    private final SourceTaskContext context;
    private final Map<String, String> sourcePartition;
    
    private volatile String currentOffset;
    private final String initialOffset;
    private final String nextLinkField;
    private final String deltaLinkField;
    private final ODataTokenMode tokenMode;
    private String lastExtractedTokenType; // Track whether last token was from skiptoken or deltatoken
    
    public enum ODataTokenMode {
        FULL_URL,    // Store the complete URL from @odata.nextLink/@odata.deltaLink
        TOKEN_ONLY   // Extract and store only the $skiptoken/$deltatoken value
    }
    
    public ODataOffsetManager(ApiConfig apiConfig, SourceTaskContext context) {
        this.apiConfig = apiConfig;
        this.context = context;
        
        // Create source partition for Kafka Connect offset storage
        this.sourcePartition = new HashMap<>();
        this.sourcePartition.put("url", apiConfig.getFullUrl());
        
        // Get initial offset
        this.initialOffset = apiConfig.getHttpInitialOffset();
        
        // Get configurable field names
        this.nextLinkField = apiConfig.getODataNextLinkField();
        this.deltaLinkField = apiConfig.getODataDeltaLinkField();
        this.tokenMode = apiConfig.getODataTokenMode();
        
        // Validate required configuration
        if (nextLinkField == null || nextLinkField.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "odata.nextlink.field must be configured for ODATA_PAGINATION mode in API: " + apiConfig.getId());
        }
        
        // Load current offset from Kafka Connect's offset storage
        loadCurrentOffset();
        
        log.debug("Initialized ODataOffsetManager for API: {} with nextLink field: {}, deltaLink field: {}, token mode: {}, current offset: {}",
            apiConfig.getId(), nextLinkField, deltaLinkField, tokenMode, currentOffset);
    }
    
    @Override
    public String getCurrentOffset() {
        return currentOffset;
    }
    
    @Override
    public void updateOffset(String newOffset) {
        if (newOffset != null && !newOffset.trim().isEmpty()) {
            String processedOffset = processOffset(newOffset.trim());
            this.currentOffset = processedOffset;
            log.trace("Updated offset for API {} to: {}", apiConfig.getId(), currentOffset);
        } else {
            log.debug("Received null or empty offset for API {}, this might indicate end of pagination", 
                apiConfig.getId());
            // In OData pagination, a null offset means we've reached the end or should use deltaLink
            this.currentOffset = null;
        }
    }
    
    @Override
    public void resetOffset() {
        currentOffset = initialOffset;
        log.info("Reset offset for API {} to initial value: {}", apiConfig.getId(), initialOffset);
    }
    
    @Override
    public ApiConfig.HttpOffsetMode getOffsetMode() {
        return ApiConfig.HttpOffsetMode.ODATA_PAGINATION;
    }
    
    @Override
    public void close() {
        log.debug("Closing ODataOffsetManager for API: {}", apiConfig.getId());
        // Nothing specific to close for this implementation
    }
    
    /**
     * Checks if there are more pages to fetch
     * 
     * @return true if there are more pages, false if pagination is complete
     */
    public boolean hasMorePages() {
        return currentOffset != null && !currentOffset.trim().isEmpty();
    }
    
    /**
     * Gets the source partition for Kafka Connect offset storage
     */
    public Map<String, String> getSourcePartition() {
        return sourcePartition;
    }
    
    /**
     * Gets the configured nextLink field name
     */
    public String getNextLinkField() {
        return nextLinkField;
    }
    
    /**
     * Gets the configured deltaLink field name  
     */
    public String getDeltaLinkField() {
        return deltaLinkField;
    }
    
    /**
     * Gets the token extraction mode
     */
    public ODataTokenMode getTokenMode() {
        return tokenMode;
    }
    
    /**
     * Processes the offset based on the configured token mode
     */
    private String processOffset(String rawOffset) {
        if (tokenMode == ODataTokenMode.FULL_URL) {
            return extractPathAndQuery(rawOffset);
        } else if (tokenMode == ODataTokenMode.TOKEN_ONLY) {
            return extractTokenOnly(rawOffset);
        }
        return rawOffset;
    }
    
    /**
     * Extracts the path and query parameters from a full OData URL
     * 
     * Example:
     * Input: "[Organization URI]/api/data/v9.0/accounts?$select=name&$skiptoken=..."
     * Output: "/api/data/v9.0/accounts?$select=name&$skiptoken=..."
     */
    private String extractPathAndQuery(String fullUrl) {
        try {
            URL url = new URL(fullUrl);
            String path = url.getPath();
            String query = url.getQuery();
            
            if (query != null && !query.isEmpty()) {
                return path + "?" + query;
            } else {
                return path;
            }
        } catch (MalformedURLException e) {
            log.warn("Failed to parse OData URL: {}, using as-is", fullUrl);
            return fullUrl;
        }
    }
    
    /**
     * Extracts only the token value from an OData URL
     * 
     * Looks for $skiptoken or $deltatoken parameters and extracts their values
     */
    private String extractTokenOnly(String fullUrl) {
        // Try to extract skiptoken first
        Matcher skipMatcher = SKIPTOKEN_PATTERN.matcher(fullUrl);
        if (skipMatcher.find()) {
            this.lastExtractedTokenType = "skiptoken";
            try {
                return URLDecoder.decode(skipMatcher.group(1), StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.warn("Failed to decode skiptoken from: {}", fullUrl, e);
                return skipMatcher.group(1);
            }
        }
        
        // Try to extract deltatoken
        Matcher deltaMatcher = DELTATOKEN_PATTERN.matcher(fullUrl);
        if (deltaMatcher.find()) {
            this.lastExtractedTokenType = "deltatoken";
            try {
                return URLDecoder.decode(deltaMatcher.group(1), StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.warn("Failed to decode deltatoken from: {}", fullUrl, e);
                return deltaMatcher.group(1);
            }
        }
        
        // If no token found, return the original URL
        log.debug("No skiptoken or deltatoken found in URL: {}, using full URL", fullUrl);
        this.lastExtractedTokenType = null;
        return extractPathAndQuery(fullUrl);
    }
    
    /**
     * Builds the URL for the next request based on current offset and token mode
     */
    public String buildNextRequestUrl() {
        log.trace("Building next request URL for API {} with currentOffset: {}, tokenMode: {}", 
            apiConfig.getId(), currentOffset, tokenMode);
            
        if (currentOffset == null || currentOffset.trim().isEmpty()) {
            // Use the initial API path
            return apiConfig.getHttpApiPath();
        }
        
        if (tokenMode == ODataTokenMode.FULL_URL) {
            // Current offset contains the path and query parameters
            return currentOffset;
        } else if (tokenMode == ODataTokenMode.TOKEN_ONLY) {
            // Build URL by appending token to base path
            String basePath = apiConfig.getHttpApiPath();
            String separator = basePath.contains("?") ? "&" : "?";
            
            // Determine if this is a skiptoken or deltatoken based on content and context
            if (isDeltaToken(currentOffset)) {
                return basePath + separator + "$deltatoken=" + currentOffset;
            } else {
                return basePath + separator + "$skiptoken=" + currentOffset;
            }
        }
        return currentOffset;
    }
    
    /**
     * Heuristic to determine if a token is likely a delta token
     */
    private boolean isDeltaToken(String token) {
        // Check if token came from a deltatoken URL parameter extraction
        // This is stored during the token extraction process
        if (lastExtractedTokenType != null) {
            return lastExtractedTokenType.equals("deltatoken");
        }
        
        // Fallback heuristic: Delta tokens often contain timestamps or specific patterns
        // This is a simple heuristic - customize based on your OData implementation
        return token.contains("%2f") || token.contains("%3a") || token.matches(".*\\d{4}.*\\d{2}.*\\d{2}.*");
    }
    
    /**
     * Loads the current offset from Kafka Connect's offset storage
     */
    private void loadCurrentOffset() {
        if (context != null && context.offsetStorageReader() != null) {
            try {
                Map<String, Object> offset = context.offsetStorageReader().offset(sourcePartition);
                
                if (offset != null && offset.containsKey("offset")) {
                    Object offsetValue = offset.get("offset");
                    if (offsetValue != null) {
                        this.currentOffset = offsetValue.toString();
                        log.debug("Loaded existing offset for API {}: {}", apiConfig.getId(), currentOffset);
                        return;
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to load existing offset for API {}, using initial offset: {}", 
                    apiConfig.getId(), e.getMessage());
            }
        }
        
        // Use initial offset if no stored offset is found
        this.currentOffset = initialOffset;
        log.debug("Using initial offset for API {}: {}", apiConfig.getId(), currentOffset);
    }
}