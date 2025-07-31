package io.confluent.connect.http.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;

/**
 * Abstract base class for OAuth2 authenticators that provides common authentication logic.
 * Eliminates code duplication between different OAuth2 authentication implementations.
 */
public abstract class AbstractOAuth2Authenticator implements HttpAuthenticator {
    
    private static final Logger log = LoggerFactory.getLogger(AbstractOAuth2Authenticator.class);
    
    protected volatile String accessToken;
    protected volatile Instant tokenExpiryTime;
    
    // Default token expiry buffer (refresh 5 minutes before expiry)
    protected static final long TOKEN_EXPIRY_BUFFER_SECONDS = 300;
    
    @Override
    public final void authenticate(Request.Builder requestBuilder) {
        // Check if token needs refresh
        if (shouldRefreshToken()) {
            try {
                refreshToken();
            } catch (Exception e) {
                log.error("Failed to refresh OAuth2 token during authentication", e);
            }
        }
        
        if (accessToken != null) {
            requestBuilder.header("Authorization", "Bearer " + accessToken);
            log.trace("Applied OAuth2 Bearer token authentication");
        } else {
            log.warn("No OAuth2 access token available for authentication");
        }
    }
    
    /**
     * Checks if the current token should be refreshed based on expiry time
     */
    protected boolean shouldRefreshToken() {
        if (accessToken == null) {
            return true;
        }
        
        if (tokenIsStillValid()) {
            return false;
        }
        
        Instant refreshTime = tokenExpiryTime.minusSeconds(TOKEN_EXPIRY_BUFFER_SECONDS);
        return Instant.now().isAfter(refreshTime);
    }

    private boolean tokenIsStillValid() {
        return tokenExpiryTime == null;
    }

    
    @Override
    public boolean supportsTokenRefresh() {
        return true;
    }
    
    /**
     * Processes the token response from the OAuth2 authorization server.
     * Extracts the access token and expiry information from the JSON response.
     * 
     * @param response The HTTP response from the token endpoint
     * @param tokenProperty The JSON property name containing the access token
     * @param objectMapper Jackson ObjectMapper for JSON parsing
     * @throws IOException If the response processing fails
     */
    protected void processTokenResponse(Response response, String tokenProperty, ObjectMapper objectMapper) throws IOException {
        if (!response.isSuccessful()) {
            String errorBody = response.body() != null ? response.body().string() : "No response body";
            throw new IOException("Token request failed with status " + response.code() + ": " + errorBody);
        }
        
        String responseBody = response.body().string();
        JsonNode tokenResponse = objectMapper.readTree(responseBody);
        
        // Extract access token
        JsonNode tokenNode = tokenResponse.get(tokenProperty);
        if (tokenNode == null || tokenNode.isNull()) {
            throw new IOException("Access token not found in response. Expected property: " + tokenProperty);
        }
        
        String newAccessToken = tokenNode.asText();
        if (newAccessToken.isEmpty()) {
            throw new IOException("Access token is empty");
        }
        
        // Extract token expiry (optional)
        Instant newExpiryTime = null;
        JsonNode expiresInNode = tokenResponse.get("expires_in");
        if (expiresInNode != null && !expiresInNode.isNull()) {
            long expiresInSeconds = expiresInNode.asLong();
            newExpiryTime = Instant.now().plusSeconds(expiresInSeconds);
            log.debug("Token expires in {} seconds", expiresInSeconds);
        }
        
        // Update token atomically
        this.accessToken = newAccessToken;
        this.tokenExpiryTime = newExpiryTime;
    }
    
    /**
     * Clears token data - should be called by subclasses in their close() method
     */
    protected void clearTokenData() {
        accessToken = null;
        tokenExpiryTime = null;
    }
}