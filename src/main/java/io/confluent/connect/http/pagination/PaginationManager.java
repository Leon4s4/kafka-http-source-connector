package io.confluent.connect.http.pagination;

import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Advanced pagination support for HTTP Source Connector.
 * Handles multiple pagination strategies including offset-based, cursor-based,
 * page-based, and link header pagination.
 */
public class PaginationManager {
    
    private static final Logger log = LoggerFactory.getLogger(PaginationManager.class);
    
    public enum PaginationType {
        NONE,           // No pagination
        OFFSET_LIMIT,   // ?offset=0&limit=100
        PAGE_SIZE,      // ?page=1&size=100
        CURSOR,         // ?cursor=abc123&limit=100
        LINK_HEADER,    // Link header: <url>; rel="next"
        CUSTOM          // Custom pagination logic
    }
    
    private final HttpSourceConnectorConfig config;
    private final ObjectMapper objectMapper;
    private final Map<String, PaginationState> paginationStates;
    private final Map<String, PaginationStats> paginationStats;
    
    // Pagination configuration
    private final boolean enabled;
    private final PaginationType defaultPaginationType;
    private final int defaultPageSize;
    private final int maxPages;
    private final String offsetParameter;
    private final String limitParameter;
    private final String pageParameter;
    private final String sizeParameter;
    private final String cursorParameter;
    private final boolean followRedirects;
    private final Pattern linkHeaderPattern;
    
    public PaginationManager(HttpSourceConnectorConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
        this.paginationStates = new ConcurrentHashMap<>();
        this.paginationStats = new ConcurrentHashMap<>();
        
        // Extract configuration
        this.enabled = isPaginationEnabled(config);
        this.defaultPaginationType = getDefaultPaginationType(config);
        this.defaultPageSize = getDefaultPageSize(config);
        this.maxPages = getMaxPages(config);
        this.offsetParameter = getOffsetParameter(config);
        this.limitParameter = getLimitParameter(config);
        this.pageParameter = getPageParameter(config);
        this.sizeParameter = getSizeParameter(config);
        this.cursorParameter = getCursorParameter(config);
        this.followRedirects = shouldFollowRedirects(config);
        
        // Compile link header pattern for parsing
        this.linkHeaderPattern = Pattern.compile("<([^>]+)>\\s*;\\s*rel\\s*=\\s*[\"']?([^\"';]+)[\"']?");
        
        if (enabled) {
            log.info("Pagination manager initialized: type={}, pageSize={}, maxPages={}",
                    defaultPaginationType, defaultPageSize, maxPages);
        } else {
            log.info("Pagination disabled");
        }
    }
    
    /**
     * Initialize pagination for an API endpoint.
     */
    public void initializePagination(String apiId, String baseUrl, PaginationType paginationType) {
        if (!enabled) {
            return;
        }
        
        PaginationType type = paginationType != null ? paginationType : defaultPaginationType;
        PaginationState state = new PaginationState(apiId, baseUrl, type);
        paginationStates.put(apiId, state);
        
        log.debug("Initialized pagination for API {}: type={}, baseUrl={}", apiId, type, baseUrl);
    }
    
    /**
     * Get the next URL to fetch based on pagination state.
     */
    public PaginationRequest getNextRequest(String apiId) {
        if (!enabled) {
            return null;
        }
        
        PaginationState state = paginationStates.get(apiId);
        if (state == null) {
            log.warn("No pagination state found for API: {}", apiId);
            return null;
        }
        
        if (state.isComplete()) {
            log.debug("Pagination complete for API: {}", apiId);
            return null;
        }
        
        if (state.getCurrentPage() >= maxPages) {
            log.warn("Max pages reached for API {}: {}", apiId, maxPages);
            state.markComplete();
            return null;
        }
        
        try {
            String nextUrl = buildNextUrl(state);
            PaginationRequest.Builder builder = new PaginationRequest.Builder(nextUrl);
            
            switch (state.getPaginationType()) {
                case OFFSET_LIMIT:
                    int offset = state.getCurrentPage() * state.getPageSize();
                    builder.offsetBased(offset, state.getPageSize());
                    break;
                case PAGE_SIZE:
                    builder.pageBased(state.getCurrentPage() + 1, state.getPageSize());
                    break;
                case CURSOR:
                    builder.cursorBased(state.getNextCursor(), state.getPageSize());
                    break;
                case LINK_HEADER:
                    builder.linkBased(state.getNextUrl());
                    break;
                default:
                    break;
            }
            
            PaginationRequest request = builder.build();
            
            state.incrementPage();
            
            log.debug("Generated next pagination request for API {}: page={}, url={}",
                     apiId, state.getCurrentPage() - 1, nextUrl);
            
            return request;
            
        } catch (Exception e) {
            log.error("Error generating next pagination request for API: " + apiId, e);
            state.markComplete();
            return null;
        }
    }
    
    /**
     * Process pagination response and update state.
     */
    public void processResponse(String apiId, String responseBody, Map<String, String> responseHeaders) {
        if (!enabled) {
            return;
        }
        
        PaginationState state = paginationStates.get(apiId);
        if (state == null) {
            return;
        }
        
        try {
            switch (state.getPaginationType()) {
                case LINK_HEADER:
                    processLinkHeaderPagination(state, responseHeaders);
                    break;
                case CURSOR:
                    processCursorPagination(state, responseBody);
                    break;
                case OFFSET_LIMIT:
                case PAGE_SIZE:
                    processCountBasedPagination(state, responseBody);
                    break;
                case CUSTOM:
                    processCustomPagination(state, responseBody, responseHeaders);
                    break;
                default:
                    // No processing needed for NONE
                    break;
            }
            
        } catch (Exception e) {
            log.error("Error processing pagination response for API: " + apiId, e);
            state.markComplete();
        }
    }
    
    /**
     * Check if there are more pages available for an API.
     */
    public boolean hasMorePages(String apiId) {
        if (!enabled) {
            return false;
        }
        
        PaginationState state = paginationStates.get(apiId);
        return state != null && !state.isComplete() && state.getCurrentPage() < maxPages;
    }
    
    /**
     * Reset pagination state for an API (useful for periodic full refreshes).
     */
    public void resetPagination(String apiId) {
        PaginationState state = paginationStates.get(apiId);
        if (state != null) {
            state.reset();
            log.debug("Reset pagination state for API: {}", apiId);
        }
    }
    
    /**
     * Get pagination statistics for monitoring.
     */
    public PaginationStats getStats(String apiId) {
        if (!enabled) {
            return new PaginationStats("disabled");
        }
        
        PaginationState state = paginationStates.get(apiId);
        if (state == null) {
            return new PaginationStats("not_initialized");
        }
        
        // Get or create stats instance
        PaginationStats stats = paginationStats.computeIfAbsent(apiId, k -> new PaginationStats(apiId));
        return stats;
    }
    
    private String buildNextUrl(PaginationState state) throws Exception {
        String baseUrl = state.getBaseUrl();
        
        switch (state.getPaginationType()) {
            case OFFSET_LIMIT:
                return buildOffsetLimitUrl(baseUrl, state);
            case PAGE_SIZE:
                return buildPageSizeUrl(baseUrl, state);
            case CURSOR:
                return buildCursorUrl(baseUrl, state);
            case LINK_HEADER:
                return state.getNextUrl() != null ? state.getNextUrl() : baseUrl;
            case CUSTOM:
                return buildCustomUrl(baseUrl, state);
            default:
                return baseUrl;
        }
    }
    
    private String buildOffsetLimitUrl(String baseUrl, PaginationState state) throws Exception {
        URL url = new URL(baseUrl);
        String query = url.getQuery();
        
        // Parse existing query parameters
        Map<String, String> params = parseQueryParams(query);
        
        // Add/update pagination parameters
        int offset = state.getCurrentPage() * state.getPageSize();
        params.put(offsetParameter, String.valueOf(offset));
        params.put(limitParameter, String.valueOf(state.getPageSize()));
        
        // Rebuild URL
        String newQuery = buildQueryString(params);
        return url.getProtocol() + "://" + url.getAuthority() + url.getPath() +
               (newQuery.isEmpty() ? "" : "?" + newQuery);
    }
    
    private String buildPageSizeUrl(String baseUrl, PaginationState state) throws Exception {
        URL url = new URL(baseUrl);
        String query = url.getQuery();
        
        // Parse existing query parameters
        Map<String, String> params = parseQueryParams(query);
        
        // Add/update pagination parameters
        params.put(pageParameter, String.valueOf(state.getCurrentPage() + 1)); // 1-based
        params.put(sizeParameter, String.valueOf(state.getPageSize()));
        
        // Rebuild URL
        String newQuery = buildQueryString(params);
        return url.getProtocol() + "://" + url.getAuthority() + url.getPath() +
               (newQuery.isEmpty() ? "" : "?" + newQuery);
    }
    
    private String buildCursorUrl(String baseUrl, PaginationState state) throws Exception {
        URL url = new URL(baseUrl);
        String query = url.getQuery();
        
        // Parse existing query parameters
        Map<String, String> params = parseQueryParams(query);
        
        // Add cursor and limit
        if (state.getNextCursor() != null) {
            params.put(cursorParameter, state.getNextCursor());
        }
        params.put(limitParameter, String.valueOf(state.getPageSize()));
        
        // Rebuild URL
        String newQuery = buildQueryString(params);
        return url.getProtocol() + "://" + url.getAuthority() + url.getPath() +
               (newQuery.isEmpty() ? "" : "?" + newQuery);
    }
    
    private String buildCustomUrl(String baseUrl, PaginationState state) {
        // Custom pagination logic can be implemented here
        // For now, fall back to page-based
        try {
            return buildPageSizeUrl(baseUrl, state);
        } catch (Exception e) {
            log.warn("Error in custom pagination, falling back to base URL", e);
            return baseUrl;
        }
    }
    
    private void processLinkHeaderPagination(PaginationState state, Map<String, String> headers) {
        String linkHeader = headers.get("Link");
        if (linkHeader == null) {
            linkHeader = headers.get("link"); // Case-insensitive
        }
        
        if (linkHeader != null) {
            Matcher matcher = linkHeaderPattern.matcher(linkHeader);
            while (matcher.find()) {
                String url = matcher.group(1);
                String rel = matcher.group(2);
                
                if ("next".equals(rel)) {
                    state.setNextUrl(url);
                    return;
                }
            }
        }
        
        // No next link found - pagination complete
        state.markComplete();
    }
    
    private void processCursorPagination(PaginationState state, String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        
        // Try common cursor field names
        String[] cursorFields = {"next_cursor", "nextCursor", "cursor", "next", "continuation_token"};
        String nextCursor = null;
        
        for (String field : cursorFields) {
            JsonNode cursorNode = root.get(field);
            if (cursorNode != null && !cursorNode.isNull()) {
                nextCursor = cursorNode.asText();
                break;
            }
        }
        
        if (nextCursor != null && !nextCursor.isEmpty()) {
            state.setNextCursor(nextCursor);
        } else {
            // No cursor found - pagination complete
            state.markComplete();
        }
        
        // Try to extract record count for stats
        updateRecordCount(state, root);
    }
    
    private void processCountBasedPagination(PaginationState state, String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        
        // Try to determine if there are more records
        boolean hasMore = checkHasMore(root, state);
        
        if (!hasMore) {
            state.markComplete();
        }
        
        // Update record count
        updateRecordCount(state, root);
    }
    
    private void processCustomPagination(PaginationState state, String responseBody, 
                                       Map<String, String> headers) throws Exception {
        // Custom pagination processing can be implemented here
        // For now, use simple count-based logic
        processCountBasedPagination(state, responseBody);
    }
    
    private boolean checkHasMore(JsonNode root, PaginationState state) {
        // Try common "has more" field names
        String[] hasMoreFields = {"has_more", "hasMore", "more", "has_next", "hasNext"};
        
        for (String field : hasMoreFields) {
            JsonNode hasMoreNode = root.get(field);
            if (hasMoreNode != null && hasMoreNode.isBoolean()) {
                return hasMoreNode.asBoolean();
            }
        }
        
        // Try to infer from data array size
        String[] dataFields = {"data", "items", "results", "records", "content"};
        
        for (String field : dataFields) {
            JsonNode dataNode = root.get(field);
            if (dataNode != null && dataNode.isArray()) {
                int size = dataNode.size();
                // If returned size is less than page size, probably no more data
                return size >= state.getPageSize();
            }
        }
        
        // Default: assume there might be more (will be limited by maxPages)
        return true;
    }
    
    private void updateRecordCount(PaginationState state, JsonNode root) {
        // Try to extract total record count
        String[] totalFields = {"total", "totalCount", "total_count", "totalElements", "total_elements"};
        
        for (String field : totalFields) {
            JsonNode totalNode = root.get(field);
            if (totalNode != null && totalNode.isNumber()) {
                state.setTotalRecords(totalNode.asLong());
                return;
            }
        }
        
        // Try to count records in current response
        String[] dataFields = {"data", "items", "results", "records", "content"};
        
        for (String field : dataFields) {
            JsonNode dataNode = root.get(field);
            if (dataNode != null && dataNode.isArray()) {
                state.addRecords(dataNode.size());
                return;
            }
        }
    }
    
    private Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new ConcurrentHashMap<>();
        
        if (query != null && !query.isEmpty()) {
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                String[] keyValue = pair.split("=", 2);
                if (keyValue.length == 2) {
                    try {
                        String key = URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8);
                        String value = URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8);
                        params.put(key, value);
                    } catch (Exception e) {
                        // Skip malformed parameters
                        log.warn("Failed to decode query parameter: {}", pair);
                    }
                }
            }
        }
        
        return params;
    }
    
    private String buildQueryString(Map<String, String> params) {
        if (params.isEmpty()) {
            return "";
        }
        
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (query.length() > 0) {
                query.append("&");
            }
            try {
                String key = URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8);
                String value = URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8);
                query.append(key).append("=").append(value);
            } catch (Exception e) {
                // Skip problematic parameters
                log.warn("Failed to encode query parameter: {}={}", entry.getKey(), entry.getValue());
            }
        }
        
        return query.toString();
    }
    
    // Configuration extraction methods
    
    private boolean isPaginationEnabled(HttpSourceConnectorConfig config) {
        return Boolean.parseBoolean(System.getProperty("pagination.enabled", "true"));
    }
    
    private PaginationType getDefaultPaginationType(HttpSourceConnectorConfig config) {
        String type = System.getProperty("pagination.type", "PAGE_SIZE");
        try {
            return PaginationType.valueOf(type);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid pagination type: {}, using PAGE_SIZE", type);
            return PaginationType.PAGE_SIZE;
        }
    }
    
    private int getDefaultPageSize(HttpSourceConnectorConfig config) {
        try {
            return Integer.parseInt(System.getProperty("pagination.page.size", "100"));
        } catch (NumberFormatException e) {
            return 100;
        }
    }
    
    private int getMaxPages(HttpSourceConnectorConfig config) {
        try {
            return Integer.parseInt(System.getProperty("pagination.max.pages", "1000"));
        } catch (NumberFormatException e) {
            return 1000;
        }
    }
    
    private String getOffsetParameter(HttpSourceConnectorConfig config) {
        return System.getProperty("pagination.offset.parameter", "offset");
    }
    
    private String getLimitParameter(HttpSourceConnectorConfig config) {
        return System.getProperty("pagination.limit.parameter", "limit");
    }
    
    private String getPageParameter(HttpSourceConnectorConfig config) {
        return System.getProperty("pagination.page.parameter", "page");
    }
    
    private String getSizeParameter(HttpSourceConnectorConfig config) {
        return System.getProperty("pagination.size.parameter", "size");
    }
    
    private String getCursorParameter(HttpSourceConnectorConfig config) {
        return System.getProperty("pagination.cursor.parameter", "cursor");
    }
    
    private boolean shouldFollowRedirects(HttpSourceConnectorConfig config) {
        return Boolean.parseBoolean(System.getProperty("pagination.follow.redirects", "true"));
    }
    
    public void close() {
        log.info("Pagination manager shutting down");
        paginationStates.clear();
    }
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public PaginationType getDefaultPaginationType() {
        return defaultPaginationType;
    }
    
    public int getDefaultPageSize() {
        return defaultPageSize;
    }
}
