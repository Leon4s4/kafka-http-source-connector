package io.confluent.connect.http.auth;

import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Authenticator for Bearer token authentication.
 */
public class BearerTokenAuthenticator implements HttpAuthenticator {
    
    private static final Logger log = LoggerFactory.getLogger(BearerTokenAuthenticator.class);
    
    private final String token;
    
    public BearerTokenAuthenticator(String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("Bearer token must not be null or empty");
        }
        
        this.token = token.trim();
        
        log.debug("Initialized BearerTokenAuthenticator");
    }
    
    @Override
    public void authenticate(Request.Builder requestBuilder) {
        requestBuilder.header("Authorization", "Bearer " + token);
        log.trace("Applied Bearer token authentication");
    }
    
    @Override
    public void refreshToken() throws Exception {
        // Static bearer tokens can't be refreshed
        log.trace("No token refresh available for static Bearer token");
    }
    
    @Override
    public boolean supportsTokenRefresh() {
        return false;
    }
    
    @Override
    public void close() {
        // Nothing to close for Bearer token auth
        log.debug("BearerTokenAuthenticator closed");
    }
}