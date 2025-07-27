package io.confluent.connect.http.client;

import java.util.HashMap;
import java.util.Map;

/**
 * HTTP request representation.
 */
public class HttpRequest {
    
    private String method = "GET";
    private String url;
    private Map<String, String> headers = new HashMap<>();
    private String contentType;
    private String body;
    
    public HttpRequest(String url) {
        this.url = url;
    }
    
    public HttpRequest(String method, String url) {
        this.method = method;
        this.url = url;
    }
    
    public String getMethod() {
        return method;
    }
    
    public void setMethod(String method) {
        this.method = method;
    }
    
    public String getUrl() {
        return url;
    }
    
    public void setUrl(String url) {
        this.url = url;
    }
    
    public Map<String, String> getHeaders() {
        return headers;
    }
    
    public void setHeaders(Map<String, String> headers) {
        this.headers = headers != null ? headers : new HashMap<>();
    }
    
    public void addHeader(String name, String value) {
        this.headers.put(name, value);
    }
    
    public String getContentType() {
        return contentType;
    }
    
    public void setContentType(String contentType) {
        this.contentType = contentType;
    }
    
    public String getBody() {
        return body;
    }
    
    public void setBody(String body) {
        this.body = body;
    }
}
