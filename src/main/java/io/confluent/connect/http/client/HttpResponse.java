package io.confluent.connect.http.client;

import java.util.HashMap;
import java.util.Map;

/**
 * HTTP response representation.
 */
public class HttpResponse {
    
    private final int statusCode;
    private final Map<String, String> headers;
    private final String body;
    private final String contentType;
    private final long contentLength;
    
    public HttpResponse(int statusCode, Map<String, String> headers, String body, 
                       String contentType, long contentLength) {
        this.statusCode = statusCode;
        this.headers = headers != null ? headers : new HashMap<>();
        this.body = body;
        this.contentType = contentType;
        this.contentLength = contentLength;
    }
    
    public int getStatusCode() {
        return statusCode;
    }
    
    public Map<String, String> getHeaders() {
        return headers;
    }
    
    public String getHeader(String name) {
        return headers.get(name);
    }
    
    public String getBody() {
        return body;
    }
    
    public String getContentType() {
        return contentType;
    }
    
    public long getContentLength() {
        return contentLength;
    }
    
    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }
    
    public boolean isRedirect() {
        return statusCode >= 300 && statusCode < 400;
    }
    
    public boolean isClientError() {
        return statusCode >= 400 && statusCode < 500;
    }
    
    public boolean isServerError() {
        return statusCode >= 500;
    }
    
    @Override
    public String toString() {
        return String.format("HttpResponse{status=%d, contentType='%s', length=%d}", 
                           statusCode, contentType, contentLength);
    }
}
