package io.confluent.connect.http.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import okhttp3.Credentials;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Authenticator for OAuth 2.0 Client Credentials grant flow.
 * Handles token acquisition, refresh, and automatic expiration management.
 */
public class OAuth2Authenticator implements HttpAuthenticator {
    
    private static final Logger log = LoggerFactory.getLogger(OAuth2Authenticator.class);
    
    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;
    private final String tokenProperty;
    private final String scope;
    private final HttpSourceConnectorConfig.OAuth2ClientAuthMode authMode;
    
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ReentrantLock tokenLock;
    
    private volatile String accessToken;
    private volatile Instant tokenExpiryTime;
    private volatile boolean tokenRefreshInProgress;
    
    // Default token expiry buffer (refresh 5 minutes before expiry)
    private static final long TOKEN_EXPIRY_BUFFER_SECONDS = 300;
    
    public OAuth2Authenticator(String tokenUrl, String clientId, String clientSecret, 
                              String tokenProperty, String scope, 
                              HttpSourceConnectorConfig.OAuth2ClientAuthMode authMode) {
        
        if (tokenUrl == null || tokenUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("OAuth2 token URL must not be null or empty");
        }
        if (clientId == null || clientId.trim().isEmpty()) {
            throw new IllegalArgumentException("OAuth2 client ID must not be null or empty");
        }
        if (clientSecret == null || clientSecret.trim().isEmpty()) {
            throw new IllegalArgumentException("OAuth2 client secret must not be null or empty");
        }
        
        this.tokenUrl = tokenUrl.trim();
        this.clientId = clientId.trim();
        this.clientSecret = clientSecret.trim();
        this.tokenProperty = tokenProperty != null ? tokenProperty.trim() : "access_token";
        this.scope = scope;
        this.authMode = authMode != null ? authMode : HttpSourceConnectorConfig.OAuth2ClientAuthMode.HEADER;
        
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
        
        this.objectMapper = new ObjectMapper();
        this.tokenLock = new ReentrantLock();
        this.tokenRefreshInProgress = false;
        
        log.debug("Initialized OAuth2Authenticator for token URL: {}", tokenUrl);
        
        // Acquire initial token
        try {
            refreshToken();
        } catch (Exception e) {
            log.error("Failed to acquire initial OAuth2 token", e);
            throw new RuntimeException("Failed to acquire initial OAuth2 token", e);
        }
    }
    
    @Override
    public void authenticate(Request.Builder requestBuilder) {
        // Check if token needs refresh
        if (shouldRefreshToken()) {
            try {
                refreshToken();
            } catch (Exception e) {
                log.error("Failed to refresh OAuth2 token during authentication", e);
                // Continue with existing token if refresh fails
            }
        }
        
        if (accessToken != null) {
            requestBuilder.header("Authorization", "Bearer " + accessToken);
            log.trace("Applied OAuth2 Bearer token authentication");
        } else {
            log.warn("No OAuth2 access token available for authentication");
        }
    }
    
    @Override
    public void refreshToken() throws Exception {
        tokenLock.lock();
        try {
            // Double-check if refresh is still needed after acquiring lock
            if (tokenRefreshInProgress) {
                log.debug("Token refresh already in progress, waiting...");
                return;
            }
            
            tokenRefreshInProgress = true;
            log.debug("Refreshing OAuth2 token from: {}", tokenUrl);
            
            // Build token request with auth mode-specific form body
            FormBody.Builder formBuilder = new FormBody.Builder()
                .add("grant_type", "client_credentials");
            
            if (scope != null && !scope.trim().isEmpty()) {
                formBuilder.add("scope", scope.trim());
            }
            
            // Add client credentials to form body if using URL auth mode
            if (authMode == HttpSourceConnectorConfig.OAuth2ClientAuthMode.URL) {
                formBuilder.add("client_id", clientId);
                formBuilder.add("client_secret", clientSecret);
            }
            
            Request.Builder requestBuilder = new Request.Builder()
                .url(tokenUrl)
                .post(formBuilder.build());
            
            // Add header-based authentication if using HEADER auth mode
            if (authMode == HttpSourceConnectorConfig.OAuth2ClientAuthMode.HEADER) {
                String credentials = Credentials.basic(clientId, clientSecret);
                requestBuilder.header("Authorization", credentials);
            }
            
            // Execute token request
            try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
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
                
                log.info("OAuth2 token refreshed successfully");
                
            } catch (IOException e) {
                log.error("Failed to refresh OAuth2 token", e);
                throw new Exception("Failed to refresh OAuth2 token: " + e.getMessage(), e);
            }
            
        } finally {
            tokenRefreshInProgress = false;
            tokenLock.unlock();
        }
    }
    
    @Override
    public boolean supportsTokenRefresh() {
        return true;
    }
    
    @Override
    public void close() {
        log.debug("Closing OAuth2Authenticator");
        
        // Close HTTP client
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }
        
        // Clear token data
        accessToken = null;
        tokenExpiryTime = null;
        
        log.debug("OAuth2Authenticator closed");
    }
    
    /**
     * Checks if the current token should be refreshed based on expiry time
     */
    private boolean shouldRefreshToken() {
        if (accessToken == null) {
            return true;
        }
        
        if (tokenExpiryTime == null) {
            // No expiry info, assume token is still valid
            return false;
        }
        
        // Refresh if token will expire within the buffer time
        Instant refreshTime = tokenExpiryTime.minusSeconds(TOKEN_EXPIRY_BUFFER_SECONDS);
        return Instant.now().isAfter(refreshTime);
    }
    
    /**
     * Gets the current access token (for testing purposes)
     */
    public String getAccessToken() {
        return accessToken;
    }
    
    /**
     * Gets the token expiry time (for testing purposes)
     */
    public Instant getTokenExpiryTime() {
        return tokenExpiryTime;
    }
}