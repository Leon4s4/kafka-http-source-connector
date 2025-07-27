package io.confluent.connect.http.client;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Prepared HTTP request ready for execution.
 */
public class PreparedRequest {
    
    private final HttpRequest originalRequest;
    private final String processedBody;
    private final ByteBuffer buffer;
    
    public PreparedRequest(HttpRequest originalRequest, String processedBody, ByteBuffer buffer) {
        this.originalRequest = originalRequest;
        this.processedBody = processedBody;
        this.buffer = buffer;
    }
    
    public String getMethod() {
        return originalRequest.getMethod();
    }
    
    public String getUrl() {
        return originalRequest.getUrl();
    }
    
    public Map<String, String> getHeaders() {
        return originalRequest.getHeaders() != null ? originalRequest.getHeaders() : new HashMap<>();
    }
    
    public String getContentType() {
        return originalRequest.getContentType();
    }
    
    public String getBody() {
        return processedBody;
    }
    
    public ByteBuffer getBuffer() {
        return buffer;
    }
    
    public HttpRequest getOriginalRequest() {
        return originalRequest;
    }
}
