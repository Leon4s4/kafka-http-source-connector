package io.confluent.connect.http.error;

/**
 * Context information for DLQ operations, containing HTTP request/response details
 * and error metadata needed for comprehensive error handling.
 */
public class DLQContext {
    
    private final String apiEndpoint;
    private final Integer httpStatusCode;
    private final String requestHeaders;
    private final String responseHeaders;
    private final String requestPayload;
    private final String responsePayload;
    private final int retryCount;
    private final int taskId;
    private final String errorCategory;
    private final long timestamp;
    
    private DLQContext(Builder builder) {
        this.apiEndpoint = builder.apiEndpoint;
        this.httpStatusCode = builder.httpStatusCode;
        this.requestHeaders = builder.requestHeaders;
        this.responseHeaders = builder.responseHeaders;
        this.requestPayload = builder.requestPayload;
        this.responsePayload = builder.responsePayload;
        this.retryCount = builder.retryCount;
        this.taskId = builder.taskId;
        this.errorCategory = builder.errorCategory;
        this.timestamp = builder.timestamp;
    }
    
    public String getApiEndpoint() {
        return apiEndpoint;
    }
    
    public Integer getHttpStatusCode() {
        return httpStatusCode;
    }
    
    public String getRequestHeaders() {
        return requestHeaders;
    }
    
    public String getResponseHeaders() {
        return responseHeaders;
    }
    
    public String getRequestPayload() {
        return requestPayload;
    }
    
    public String getResponsePayload() {
        return responsePayload;
    }
    
    public int getRetryCount() {
        return retryCount;
    }
    
    public int getTaskId() {
        return taskId;
    }
    
    public String getErrorCategory() {
        return errorCategory;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private String apiEndpoint;
        private Integer httpStatusCode;
        private String requestHeaders;
        private String responseHeaders;
        private String requestPayload;
        private String responsePayload;
        private int retryCount = 0;
        private int taskId = 0;
        private String errorCategory;
        private long timestamp = System.currentTimeMillis();
        
        public Builder apiEndpoint(String apiEndpoint) {
            this.apiEndpoint = apiEndpoint;
            return this;
        }
        
        public Builder httpStatusCode(Integer httpStatusCode) {
            this.httpStatusCode = httpStatusCode;
            return this;
        }
        
        public Builder requestHeaders(String requestHeaders) {
            this.requestHeaders = requestHeaders;
            return this;
        }
        
        public Builder responseHeaders(String responseHeaders) {
            this.responseHeaders = responseHeaders;
            return this;
        }
        
        public Builder requestPayload(String requestPayload) {
            this.requestPayload = requestPayload;
            return this;
        }
        
        public Builder responsePayload(String responsePayload) {
            this.responsePayload = responsePayload;
            return this;
        }
        
        public Builder retryCount(int retryCount) {
            this.retryCount = retryCount;
            return this;
        }
        
        public Builder taskId(int taskId) {
            this.taskId = taskId;
            return this;
        }
        
        public Builder errorCategory(String errorCategory) {
            this.errorCategory = errorCategory;
            return this;
        }
        
        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }
        
        public DLQContext build() {
            return new DLQContext(this);
        }
    }
    
    @Override
    public String toString() {
        return String.format("DLQContext{endpoint='%s', status=%d, retries=%d, task=%d, category='%s'}",
                           apiEndpoint, httpStatusCode, retryCount, taskId, errorCategory);
    }
}
