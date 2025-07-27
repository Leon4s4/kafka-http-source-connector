package io.confluent.connect.http.pagination;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a paginated request with all necessary parameters.
 * Used to build the next request URL based on pagination strategy.
 */
public class PaginationRequest {
    
    private final String baseUrl;
    private final Map<String, String> parameters;
    private final Map<String, String> headers;
    private final String cursor;
    private final int page;
    private final int offset;
    private final int limit;
    private final String nextUrl;
    
    private PaginationRequest(Builder builder) {
        this.baseUrl = builder.baseUrl;
        this.parameters = new HashMap<>(builder.parameters);
        this.headers = new HashMap<>(builder.headers);
        this.cursor = builder.cursor;
        this.page = builder.page;
        this.offset = builder.offset;
        this.limit = builder.limit;
        this.nextUrl = builder.nextUrl;
    }
    
    public String getBaseUrl() {
        return baseUrl;
    }
    
    public Map<String, String> getParameters() {
        return new HashMap<>(parameters);
    }
    
    public Map<String, String> getHeaders() {
        return new HashMap<>(headers);
    }
    
    public String getCursor() {
        return cursor;
    }
    
    public int getPage() {
        return page;
    }
    
    public int getOffset() {
        return offset;
    }
    
    public int getLimit() {
        return limit;
    }
    
    public String getNextUrl() {
        return nextUrl;
    }
    
    public String getParameter(String key) {
        return parameters.get(key);
    }
    
    public String getHeader(String key) {
        return headers.get(key);
    }
    
    public boolean hasParameter(String key) {
        return parameters.containsKey(key);
    }
    
    public boolean hasHeader(String key) {
        return headers.containsKey(key);
    }
    
    /**
     * Check if this is a cursor-based request.
     */
    public boolean isCursorBased() {
        return cursor != null && !cursor.isEmpty();
    }
    
    /**
     * Check if this is a page-based request.
     */
    public boolean isPageBased() {
        return page > 0;
    }
    
    /**
     * Check if this is an offset-based request.
     */
    public boolean isOffsetBased() {
        return offset >= 0 && limit > 0;
    }
    
    /**
     * Check if this is a link-based request.
     */
    public boolean isLinkBased() {
        return nextUrl != null && !nextUrl.isEmpty();
    }
    
    /**
     * Build the complete URL for this request.
     */
    public String buildUrl() {
        if (isLinkBased()) {
            return nextUrl; // Use provided URL directly
        }
        
        StringBuilder url = new StringBuilder(baseUrl);
        
        if (!parameters.isEmpty()) {
            url.append(baseUrl.contains("?") ? "&" : "?");
            
            boolean first = !baseUrl.contains("?");
            for (Map.Entry<String, String> param : parameters.entrySet()) {
                if (!first) {
                    url.append("&");
                }
                url.append(param.getKey()).append("=").append(param.getValue());
                first = false;
            }
        }
        
        return url.toString();
    }
    
    /**
     * Create a new builder from this request.
     */
    public Builder toBuilder() {
        return new Builder(baseUrl)
                .parameters(parameters)
                .headers(headers)
                .cursor(cursor)
                .page(page)
                .offset(offset)
                .limit(limit)
                .nextUrl(nextUrl);
    }
    
    @Override
    public String toString() {
        return String.format("PaginationRequest{url='%s', cursor='%s', page=%d, offset=%d, limit=%d}",
                           buildUrl(), cursor, page, offset, limit);
    }
    
    /**
     * Builder for creating PaginationRequest instances.
     */
    public static class Builder {
        private final String baseUrl;
        private final Map<String, String> parameters = new HashMap<>();
        private final Map<String, String> headers = new HashMap<>();
        private String cursor;
        private int page = 0;
        private int offset = 0;
        private int limit = 0;
        private String nextUrl;
        
        public Builder(String baseUrl) {
            this.baseUrl = baseUrl;
        }
        
        public Builder parameter(String key, String value) {
            if (value != null) {
                this.parameters.put(key, value);
            }
            return this;
        }
        
        public Builder parameter(String key, int value) {
            this.parameters.put(key, String.valueOf(value));
            return this;
        }
        
        public Builder parameter(String key, long value) {
            this.parameters.put(key, String.valueOf(value));
            return this;
        }
        
        public Builder parameters(Map<String, String> parameters) {
            if (parameters != null) {
                this.parameters.putAll(parameters);
            }
            return this;
        }
        
        public Builder header(String key, String value) {
            if (value != null) {
                this.headers.put(key, value);
            }
            return this;
        }
        
        public Builder headers(Map<String, String> headers) {
            if (headers != null) {
                this.headers.putAll(headers);
            }
            return this;
        }
        
        public Builder cursor(String cursor) {
            this.cursor = cursor;
            return this;
        }
        
        public Builder page(int page) {
            this.page = page;
            return this;
        }
        
        public Builder offset(int offset) {
            this.offset = offset;
            return this;
        }
        
        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }
        
        public Builder nextUrl(String nextUrl) {
            this.nextUrl = nextUrl;
            return this;
        }
        
        /**
         * Configure for offset-based pagination.
         */
        public Builder offsetBased(int offset, int limit) {
            this.offset = offset;
            this.limit = limit;
            parameter("offset", offset);
            parameter("limit", limit);
            return this;
        }
        
        /**
         * Configure for page-based pagination.
         */
        public Builder pageBased(int page, int pageSize) {
            this.page = page;
            this.limit = pageSize;
            parameter("page", page);
            parameter("size", pageSize);
            return this;
        }
        
        /**
         * Configure for cursor-based pagination.
         */
        public Builder cursorBased(String cursor, int limit) {
            this.cursor = cursor;
            this.limit = limit;
            if (cursor != null) {
                parameter("cursor", cursor);
            }
            parameter("limit", limit);
            return this;
        }
        
        /**
         * Configure for link-based pagination.
         */
        public Builder linkBased(String nextUrl) {
            this.nextUrl = nextUrl;
            return this;
        }
        
        public PaginationRequest build() {
            return new PaginationRequest(this);
        }
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        
        PaginationRequest that = (PaginationRequest) o;
        
        if (page != that.page) return false;
        if (offset != that.offset) return false;
        if (limit != that.limit) return false;
        if (!baseUrl.equals(that.baseUrl)) return false;
        if (!parameters.equals(that.parameters)) return false;
        if (!headers.equals(that.headers)) return false;
        if (cursor != null ? !cursor.equals(that.cursor) : that.cursor != null) return false;
        return nextUrl != null ? nextUrl.equals(that.nextUrl) : that.nextUrl == null;
    }
    
    @Override
    public int hashCode() {
        int result = baseUrl.hashCode();
        result = 31 * result + parameters.hashCode();
        result = 31 * result + headers.hashCode();
        result = 31 * result + (cursor != null ? cursor.hashCode() : 0);
        result = 31 * result + page;
        result = 31 * result + offset;
        result = 31 * result + limit;
        result = 31 * result + (nextUrl != null ? nextUrl.hashCode() : 0);
        return result;
    }
}
