package io.confluent.connect.http.auth.enhanced;

import io.confluent.connect.http.auth.HttpAuthenticator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Azure Active Directory authenticator supporting various authentication flows.
 * Handles OAuth2 flows including client credentials, device code, and managed identity.
 */
public class AzureAdAuthenticator implements HttpAuthenticator {
    
    private static final Logger log = LoggerFactory.getLogger(AzureAdAuthenticator.class);
    
    public enum AzureAuthFlow {
        CLIENT_CREDENTIALS,
        DEVICE_CODE,
        MANAGED_IDENTITY,
        USERNAME_PASSWORD
    }
    
    private final String tenantId;
    private final String clientId;
    private final String clientSecret;
    private final String scope;
    private final AzureAuthFlow authFlow;
    private final Map<String, Object> authConfig;
    
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ReentrantLock tokenLock;
    
    private volatile String accessToken;
    private volatile String refreshToken;
    private volatile Instant tokenExpiryTime;
    
    // Azure AD endpoints
    private static final String AZURE_AD_BASE_URL = "https://login.microsoftonline.com";
    private static final String MANAGED_IDENTITY_ENDPOINT = "http://169.254.169.254/metadata/identity/oauth2/token";
    
    // Token refresh settings
    private static final long TOKEN_REFRESH_BUFFER_SECONDS = 300; // 5 minutes
    
    public AzureAdAuthenticator(Map<String, Object> config) {
        this.authConfig = config;
        this.tenantId = (String) config.get("azure.tenant.id");
        this.clientId = (String) config.get("azure.client.id");
        this.clientSecret = (String) config.get("azure.client.secret");
        this.scope = (String) config.getOrDefault("azure.scope", "https://graph.microsoft.com/.default");
        this.authFlow = AzureAuthFlow.valueOf(
            config.getOrDefault("azure.auth.flow", "CLIENT_CREDENTIALS").toString().toUpperCase()
        );
        
        // Validate required configuration based on auth flow
        validateConfiguration();
        
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build();
        
        this.objectMapper = new ObjectMapper();
        this.tokenLock = new ReentrantLock();
        
        // Initialize Azure AD authentication
        try {
            authenticateWithAzureAd();
        } catch (Exception e) {
            log.error("Failed to initialize Azure AD authentication: {}", e.getMessage(), e);
            throw new RuntimeException("Azure AD authentication initialization failed", e);
        }
        
        log.info("Azure AD authenticator initialized: tenant={}, clientId={}, flow={}",
                tenantId, clientId, authFlow);
    }
    
    @Override
    public void authenticate(Request.Builder requestBuilder) {
        try {
            // Ensure we have a valid token
            if (shouldRefreshToken()) {
                refreshToken();
            }
            
            if (accessToken != null) {
                requestBuilder.header("Authorization", "Bearer " + accessToken);
                log.trace("Applied Azure AD Bearer token authentication");
            } else {
                log.warn("No Azure AD access token available for authentication");
            }
        } catch (Exception e) {
            log.error("Failed to apply Azure AD authentication: {}", e.getMessage(), e);
            throw new RuntimeException("Azure AD authentication failed", e);
        }
    }
    
    @Override
    public void refreshToken() {
        try {
            if (refreshToken != null && authFlow != AzureAuthFlow.MANAGED_IDENTITY) {
                refreshAccessToken();
            } else {
                authenticateWithAzureAd();
            }
        } catch (Exception e) {
            log.error("Failed to refresh Azure AD token: {}", e.getMessage(), e);
            throw new RuntimeException("Azure AD token refresh failed", e);
        }
    }
    
    @Override
    public boolean supportsTokenRefresh() {
        return true;
    }
    
    @Override
    public void close() {
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }
        
        // Clear sensitive data
        accessToken = null;
        refreshToken = null;
        tokenExpiryTime = null;
        
        log.debug("Azure AD authenticator closed");
    }
    
    /**
     * Rotate credentials by re-authenticating with Azure AD.
     */
    public void rotateCredentials() throws Exception {
        log.info("Rotating Azure AD credentials");
        authenticateWithAzureAd();
        log.info("Azure AD credential rotation completed");
    }
    
    /**
     * Authenticate with Azure AD using the configured flow.
     */
    private void authenticateWithAzureAd() throws Exception {
        tokenLock.lock();
        try {
            log.debug("Authenticating with Azure AD using flow: {}", authFlow);
            
            switch (authFlow) {
                case CLIENT_CREDENTIALS:
                    authenticateWithClientCredentials();
                    break;
                case DEVICE_CODE:
                    authenticateWithDeviceCode();
                    break;
                case MANAGED_IDENTITY:
                    authenticateWithManagedIdentity();
                    break;
                case USERNAME_PASSWORD:
                    authenticateWithUsernamePassword();
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported Azure auth flow: " + authFlow);
            }
            
            log.debug("Azure AD authentication successful");
            
        } finally {
            tokenLock.unlock();
        }
    }
    
    /**
     * Authenticate using client credentials flow.
     */
    private void authenticateWithClientCredentials() throws Exception {
        String tokenUrl = AZURE_AD_BASE_URL + "/" + tenantId + "/oauth2/v2.0/token";
        
        FormBody.Builder formBuilder = new FormBody.Builder()
            .add("grant_type", "client_credentials")
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("scope", scope);
        
        Request request = new Request.Builder()
            .url(tokenUrl)
            .post(formBuilder.build())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "No response body";
                throw new IOException("Azure AD client credentials authentication failed: " + 
                                    response.code() + " - " + errorBody);
            }
            
            parseTokenResponse(response);
        }
    }
    
    /**
     * Authenticate using device code flow.
     */
    private void authenticateWithDeviceCode() throws Exception {
        // Step 1: Get device code
        String deviceCodeUrl = AZURE_AD_BASE_URL + "/" + tenantId + "/oauth2/v2.0/devicecode";
        
        FormBody deviceCodeForm = new FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", scope)
            .build();
        
        Request deviceCodeRequest = new Request.Builder()
            .url(deviceCodeUrl)
            .post(deviceCodeForm)
            .build();
        
        JsonNode deviceCodeResponse;
        try (Response response = httpClient.newCall(deviceCodeRequest).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to get device code: " + response.code());
            }
            deviceCodeResponse = objectMapper.readTree(response.body().string());
        }
        
        String deviceCode = deviceCodeResponse.get("device_code").asText();
        String userCode = deviceCodeResponse.get("user_code").asText();
        String verificationUrl = deviceCodeResponse.get("verification_uri").asText();
        int interval = deviceCodeResponse.get("interval").asInt();
        int expiresIn = deviceCodeResponse.get("expires_in").asInt();
        
        log.info("Azure AD Device Code Authentication Required:");
        log.info("1. Go to: {}", verificationUrl);
        log.info("2. Enter code: {}", userCode);
        log.info("Waiting for user to complete authentication...");
        
        // Step 2: Poll for token
        String tokenUrl = AZURE_AD_BASE_URL + "/" + tenantId + "/oauth2/v2.0/token";
        
        FormBody.Builder tokenFormBuilder = new FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .add("client_id", clientId)
            .add("device_code", deviceCode);
        
        long startTime = System.currentTimeMillis();
        long timeoutMs = expiresIn * 1000L;
        
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            Thread.sleep(interval * 1000L);
            
            Request tokenRequest = new Request.Builder()
                .url(tokenUrl)
                .post(tokenFormBuilder.build())
                .build();
            
            try (Response response = httpClient.newCall(tokenRequest).execute()) {
                if (response.isSuccessful()) {
                    parseTokenResponse(response);
                    log.info("Azure AD device code authentication completed successfully");
                    return;
                } else {
                    String errorBody = response.body() != null ? response.body().string() : "";
                    if (errorBody.contains("authorization_pending")) {
                        continue; // Keep polling
                    } else {
                        throw new IOException("Device code authentication failed: " + errorBody);
                    }
                }
            }
        }
        
        throw new IOException("Device code authentication timed out");
    }
    
    /**
     * Authenticate using managed identity.
     */
    private void authenticateWithManagedIdentity() throws Exception {
        String resource = (String) authConfig.getOrDefault("azure.resource", "https://management.azure.com/");
        
        String url = MANAGED_IDENTITY_ENDPOINT + 
                    "?api-version=2018-02-01&resource=" + java.net.URLEncoder.encode(resource, "UTF-8");
        
        if (clientId != null) {
            url += "&client_id=" + java.net.URLEncoder.encode(clientId, "UTF-8");
        }
        
        Request request = new Request.Builder()
            .url(url)
            .header("Metadata", "true")
            .get()
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "No response body";
                throw new IOException("Managed identity authentication failed: " + 
                                    response.code() + " - " + errorBody);
            }
            
            parseTokenResponse(response);
        }
    }
    
    /**
     * Authenticate using username/password flow.
     */
    private void authenticateWithUsernamePassword() throws Exception {
        String username = (String) authConfig.get("azure.username");
        String password = (String) authConfig.get("azure.password");
        
        if (username == null || password == null) {
            throw new IllegalArgumentException("Username and password must be provided for username/password flow");
        }
        
        String tokenUrl = AZURE_AD_BASE_URL + "/" + tenantId + "/oauth2/v2.0/token";
        
        FormBody.Builder formBuilder = new FormBody.Builder()
            .add("grant_type", "password")
            .add("client_id", clientId)
            .add("username", username)
            .add("password", password)
            .add("scope", scope);
        
        if (clientSecret != null) {
            formBuilder.add("client_secret", clientSecret);
        }
        
        Request request = new Request.Builder()
            .url(tokenUrl)
            .post(formBuilder.build())
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "No response body";
                throw new IOException("Username/password authentication failed: " + 
                                    response.code() + " - " + errorBody);
            }
            
            parseTokenResponse(response);
        }
    }
    
    /**
     * Refresh access token using refresh token.
     */
    private void refreshAccessToken() throws Exception {
        if (refreshToken == null) {
            throw new IllegalStateException("No refresh token available");
        }
        
        String tokenUrl = AZURE_AD_BASE_URL + "/" + tenantId + "/oauth2/v2.0/token";
        
        FormBody.Builder formBuilder = new FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", clientId)
            .add("refresh_token", refreshToken)
            .add("scope", scope);
        
        if (clientSecret != null) {
            formBuilder.add("client_secret", clientSecret);
        }
        
        Request request = new Request.Builder()
            .url(tokenUrl)
            .post(formBuilder.build())
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "No response body";
                throw new IOException("Token refresh failed: " + response.code() + " - " + errorBody);
            }
            
            parseTokenResponse(response);
            log.debug("Azure AD token refreshed successfully");
        }
    }
    
    /**
     * Parse token response from Azure AD.
     */
    private void parseTokenResponse(Response response) throws Exception {
        String responseBody = response.body().string();
        JsonNode rootNode = objectMapper.readTree(responseBody);
        
        this.accessToken = rootNode.get("access_token").asText();
        
        // Parse refresh token if available
        JsonNode refreshTokenNode = rootNode.get("refresh_token");
        if (refreshTokenNode != null) {
            this.refreshToken = refreshTokenNode.asText();
        }
        
        // Parse expiry time
        JsonNode expiresInNode = rootNode.get("expires_in");
        if (expiresInNode != null) {
            long expiresIn = expiresInNode.asLong();
            this.tokenExpiryTime = Instant.now().plusSeconds(expiresIn);
        } else {
            // Default to 1 hour if no expiry provided
            this.tokenExpiryTime = Instant.now().plusSeconds(3600);
        }
        
        log.debug("Azure AD token acquired, expires at: {}", tokenExpiryTime);
    }
    
    /**
     * Check if the token should be refreshed.
     */
    private boolean shouldRefreshToken() {
        if (accessToken == null) {
            return true;
        }
        
        if (tokenExpiryTime == null) {
            return false; // No expiry info, assume valid
        }
        
        Instant refreshTime = tokenExpiryTime.minusSeconds(TOKEN_REFRESH_BUFFER_SECONDS);
        return Instant.now().isAfter(refreshTime);
    }
    
    /**
     * Validate configuration based on authentication flow.
     */
    private void validateConfiguration() {
        switch (authFlow) {
            case CLIENT_CREDENTIALS:
                if (tenantId == null || clientId == null || clientSecret == null) {
                    throw new IllegalArgumentException(
                        "Tenant ID, client ID, and client secret must be provided for client credentials flow");
                }
                break;
            case DEVICE_CODE:
            case USERNAME_PASSWORD:
                if (tenantId == null || clientId == null) {
                    throw new IllegalArgumentException(
                        "Tenant ID and client ID must be provided for " + authFlow + " flow");
                }
                break;
            case MANAGED_IDENTITY:
                // No validation needed for managed identity
                break;
            default:
                throw new IllegalArgumentException("Unsupported Azure auth flow: " + authFlow);
        }
    }
}
