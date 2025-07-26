package io.confluent.connect.http.auth;

import okhttp3.Credentials;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Authenticator for HTTP Basic authentication.
 */
public class BasicAuthenticator implements HttpAuthenticator {
    
    private static final Logger log = LoggerFactory.getLogger(BasicAuthenticator.class);
    
    private final String username;
    private final String password;
    private final String credentials;
    
    public BasicAuthenticator(String username, String password) {
        if (username == null || password == null) {
            throw new IllegalArgumentException("Username and password must not be null for Basic authentication");
        }
        
        this.username = username;
        this.password = password;
        this.credentials = Credentials.basic(username, password);
        
        log.debug("Initialized BasicAuthenticator for user: {}", username);
    }
    
    @Override
    public void authenticate(Request.Builder requestBuilder) {
        requestBuilder.header("Authorization", credentials);
        log.trace("Applied Basic authentication for user: {}", username);
    }
    
    @Override
    public void refreshToken() throws Exception {
        // Basic auth doesn't have tokens to refresh
        log.trace("No token refresh needed for Basic authentication");
    }
    
    @Override
    public boolean supportsTokenRefresh() {
        return false;
    }
    
    @Override
    public void close() {
        // Nothing to close for Basic auth
        log.debug("BasicAuthenticator closed");
    }
}