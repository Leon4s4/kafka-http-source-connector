package io.confluent.connect.http.auth;

import okhttp3.Request;

/**
 * Interface for HTTP authentication mechanisms.
 * Implementations handle different authentication types (Basic, Bearer, OAuth2, API Key).
 */
public interface HttpAuthenticator {
    
    /**
     * Applies authentication to the HTTP request
     * 
     * @param requestBuilder The OkHttp request builder to add authentication to
     */
    void authenticate(Request.Builder requestBuilder);
    
    /**
     * Refreshes authentication credentials if applicable (e.g., OAuth2 tokens)
     * 
     * @throws Exception if token refresh fails
     */
    void refreshToken() throws Exception;
    
    /**
     * Checks if the authenticator supports token refresh
     * 
     * @return true if token refresh is supported, false otherwise
     */
    boolean supportsTokenRefresh();
    
    /**
     * Closes any resources used by the authenticator
     */
    void close();
}