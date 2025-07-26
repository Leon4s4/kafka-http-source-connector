package io.confluent.connect.http.auth;

import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * No-op authenticator for endpoints that don't require authentication.
 */
public class NoAuthenticator implements HttpAuthenticator {
    
    private static final Logger log = LoggerFactory.getLogger(NoAuthenticator.class);
    
    public NoAuthenticator() {
        log.debug("Initialized NoAuthenticator");
    }
    
    @Override
    public void authenticate(Request.Builder requestBuilder) {
        // No authentication needed
        log.trace("No authentication applied to request");
    }
    
    @Override
    public void refreshToken() throws Exception {
        // No token to refresh
        log.trace("No token refresh needed for NoAuthenticator");
    }
    
    @Override
    public boolean supportsTokenRefresh() {
        return false;
    }
    
    @Override
    public void close() {
        // Nothing to close
        log.debug("NoAuthenticator closed");
    }
}