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
import java.util.concurrent.ConcurrentHashMap;
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
    
    // Thread-safe cache for compiled patterns to avoid repeated compilation overhead
    private static final ConcurrentHashMap<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();
    
    // Token extraction patterns - will be retrieved from cache or compiled once
    private final Pattern skipTokenPattern;
    private final Pattern deltaTokenPattern;
    
    private final ApiConfig apiConfig;
    private final SourceTaskContext context;
    private final Map<String, String> sourcePartition;
    
    private volatile String currentOffset;
    private final String initialOffset;
    private final String nextLinkField;
    private final String deltaLinkField;
    private final ODataTokenMode tokenMode;
    private final String skipTokenParam;
    private final String deltaTokenParam;
    private volatile String lastExtractedTokenType; // Track whether last token was from skiptoken or deltatoken - volatile for thread safety
    
    public enum ODataTokenMode {
        FULL_URL,    // Store the complete URL from @odata.nextLink/@odata.deltaLink
        TOKEN_ONLY   // Extract and store only the $skiptoken/$deltatoken value
    }
    
    public enum ODataLinkType {
        NEXTLINK,    // Processing @odata.nextLink for pagination
        DELTALINK,   // Processing @odata.deltaLink for incremental updates
        UNKNOWN      // No active pagination link or initial state
    }
    
    public ODataOffsetManager(ApiConfig apiConfig, SourceTaskContext context) {
        this.apiConfig = apiConfig;
        this.context = context;
        
        // Create source partition for Kafka Connect offset storage
        this.sourcePartition = new HashMap<>();
        this.sourcePartition.put("url", apiConfig.getFullUrl());
        
        // Get initial offset
        this.initialOffset = apiConfig.getHttpInitialOffset();
        
        // Get configurable field names and token parameters
        this.nextLinkField = apiConfig.getODataNextLinkField();
        this.deltaLinkField = apiConfig.getODataDeltaLinkField();
        this.tokenMode = apiConfig.getODataTokenMode();
        this.skipTokenParam = apiConfig.getODataSkipTokenParam();
        this.deltaTokenParam = apiConfig.getODataDeltaTokenParam();
        
        // Get patterns from cache or compile once - thread-safe caching avoids repeated compilation
        this.skipTokenPattern = getOrCompilePattern(skipTokenParam);
        this.deltaTokenPattern = getOrCompilePattern(deltaTokenParam);
        
        // Validate required configuration
        if (nextLinkField == null || nextLinkField.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "odata.nextlink.field must be configured for ODATA_PAGINATION mode in API: " + apiConfig.getId());
        }
        
        // Load current offset from Kafka Connect's offset storage
        loadCurrentOffset();
        
        log.debug("Initialized ODataOffsetManager for API: {} with nextLink field: {}, deltaLink field: {}, token mode: {}, skiptoken param: {}, deltatoken param: {}, current offset: {}",
            apiConfig.getId(), nextLinkField, deltaLinkField, tokenMode, skipTokenParam, deltaTokenParam, currentOffset);
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
     * Gets the current OData link type being processed.
     * This can be used to determine appropriate polling intervals.
     * 
     * @return the current link type (NEXTLINK, DELTALINK, or UNKNOWN)
     */
    public ODataLinkType getCurrentLinkType() {
        if (lastExtractedTokenType != null) {
            if (lastExtractedTokenType.equals(skipTokenParam)) {
                return ODataLinkType.NEXTLINK;
            } else if (lastExtractedTokenType.equals(deltaTokenParam)) {
                return ODataLinkType.DELTALINK;
            }
        }
        return ODataLinkType.UNKNOWN;
    }
    
    /**
     * Gets the token extraction mode
     */
    public ODataTokenMode getTokenMode() {
        return tokenMode;
    }
    
    /**
     * Gets the configured skiptoken parameter name
     */
    public String getSkipTokenParam() {
        return skipTokenParam;
    }
    
    /**
     * Gets the configured deltatoken parameter name
     */
    public String getDeltaTokenParam() {
        return deltaTokenParam;
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
            
            // Detect token type from query parameters to set lastExtractedTokenType
            if (query != null && !query.isEmpty()) {
                if (query.contains(skipTokenParam)) {
                    this.lastExtractedTokenType = skipTokenParam;
                    log.debug("Detected {} token in URL: {}", skipTokenParam, fullUrl);
                } else if (query.contains(deltaTokenParam)) {
                    this.lastExtractedTokenType = deltaTokenParam;
                    log.debug("Detected {} token in URL: {}", deltaTokenParam, fullUrl);
                } else {
                    this.lastExtractedTokenType = null;
                    log.debug("No OData tokens detected in URL: {}", fullUrl);
                }
                return path + "?" + query;
            } else {
                this.lastExtractedTokenType = null;
                return path;
            }
        } catch (MalformedURLException e) {
            log.warn("Failed to parse OData URL: {}, using as-is", fullUrl);
            this.lastExtractedTokenType = null;
            return fullUrl;
        }
    }
    
    /**
     * Extracts only the token value from an OData URL
     * 
     * Looks for configured token parameters and extracts their values
     */
    private String extractTokenOnly(String fullUrl) {
        // Try to extract skiptoken first
        Matcher skipMatcher = skipTokenPattern.matcher(fullUrl);
        if (skipMatcher.find()) {
            this.lastExtractedTokenType = skipTokenParam;
            try {
                return URLDecoder.decode(skipMatcher.group(1), StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.warn("Failed to decode {} from: {}", skipTokenParam, fullUrl, e);
                return skipMatcher.group(1);
            }
        }
        
        // Try to extract deltatoken
        Matcher deltaMatcher = deltaTokenPattern.matcher(fullUrl);
        if (deltaMatcher.find()) {
            this.lastExtractedTokenType = deltaTokenParam;
            try {
                return URLDecoder.decode(deltaMatcher.group(1), StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.warn("Failed to decode {} from: {}", deltaTokenParam, fullUrl, e);
                return deltaMatcher.group(1);
            }
        }
        
        // If no token found, return the original URL
        log.debug("No {} or {} found in URL: {}, using full URL", skipTokenParam, deltaTokenParam, fullUrl);
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
                return basePath + separator + deltaTokenParam + "=" + currentOffset;
            } else {
                return basePath + separator + skipTokenParam + "=" + currentOffset;
            }
        }
        return currentOffset;
    }
    
    /**
     * Determines if a token is a delta token based on the extraction context
     * 
     * This method uses the token type that was determined during the extraction process,
     * which is more reliable than pattern-based heuristics.
     */
    private boolean isDeltaToken(String token) {
        // Check if token came from a deltatoken URL parameter extraction
        // This is stored during the token extraction process and is the most reliable method
        if (lastExtractedTokenType != null) {
            return lastExtractedTokenType.equals(deltaTokenParam);
        }
        
        // Fallback: If no extraction context is available, assume skiptoken
        // This can happen when loading from offset storage or in edge cases
        log.debug("No token extraction context available for token: {}, defaulting to skiptoken", token);
        return false;
    }
    
    /**
     * Thread-safe method to get a compiled pattern from cache or compile it once.
     * This avoids repeated pattern compilation overhead for the same token parameters.
     * 
     * @param tokenParam the token parameter name (e.g., "$skiptoken", "$deltatoken")
     * @return the compiled Pattern for extracting the token value
     */
    private static Pattern getOrCompilePattern(String tokenParam) {
        return PATTERN_CACHE.computeIfAbsent(tokenParam, 
            param -> Pattern.compile(Pattern.quote(param) + "=([^&]+)"));
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