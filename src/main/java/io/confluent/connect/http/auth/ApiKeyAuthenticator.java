package io.confluent.connect.http.auth;

import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Authenticator for API key authentication.
 * Supports both header-based and query parameter-based API keys.
 */
public class ApiKeyAuthenticator implements HttpAuthenticator {
    
    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticator.class);
    
    private final String keyName;
    private final String keyValue;
    private final HttpSourceConnectorConfig.ApiKeyLocation location;
    
    public ApiKeyAuthenticator(String keyName, String keyValue, HttpSourceConnectorConfig.ApiKeyLocation location) {
        if (keyName == null || keyName.trim().isEmpty()) {
            throw new IllegalArgumentException("API key name must not be null or empty");
        }
        if (keyValue == null || keyValue.trim().isEmpty()) {
            throw new IllegalArgumentException("API key value must not be null or empty");
        }
        if (location == null) {
            throw new IllegalArgumentException("API key location must not be null");
        }
        
        this.keyName = keyName.trim();
        this.keyValue = keyValue.trim();
        this.location = location;
        
        log.debug("Initialized ApiKeyAuthenticator with location: {} and key name: {}", location, keyName);
    }
    
    @Override
    public void authenticate(Request.Builder requestBuilder) {
        switch (location) {
            case HEADER:
                requestBuilder.header(keyName, keyValue);
                log.trace("Applied API key authentication as header: {}", keyName);
                break;
            
            case QUERY:
                // For query parameters, we need to modify the URL
                // This is handled in the HTTP client when building the request
                // Here we just log that query parameter auth will be applied
                log.trace("API key authentication will be applied as query parameter: {}", keyName);
                break;
                
            default:
                throw new IllegalArgumentException("Unsupported API key location: " + location);
        }
    }
    
    /**
     * Gets the API key name
     */
    public String getKeyName() {
        return keyName;
    }
    
    /**
     * Gets the API key value
     */
    public String getKeyValue() {
        return keyValue;
    }
    
    /**
     * Gets the API key location
     */
    public HttpSourceConnectorConfig.ApiKeyLocation getLocation() {
        return location;
    }
    
    @Override
    public void refreshToken() throws Exception {
        // API keys are typically static and don't need refreshing
        log.trace("No token refresh needed for API key authentication");
    }
    
    @Override
    public boolean supportsTokenRefresh() {
        return false;
    }
    
    @Override
    public void close() {
        // Nothing to close for API key auth
        log.debug("ApiKeyAuthenticator closed");
    }
}