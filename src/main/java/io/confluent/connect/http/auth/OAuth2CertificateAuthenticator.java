package io.confluent.connect.http.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Authenticator for OAuth 2.0 Client Credentials grant flow using certificate-based authentication.
 * This implementation uses a PFX certificate for client authentication instead of client secret.
 */
public class OAuth2CertificateAuthenticator implements HttpAuthenticator {
    
    private static final Logger log = LoggerFactory.getLogger(OAuth2CertificateAuthenticator.class);
    
    private final String tokenUrl;
    private final String clientId;
    private final String certificatePath;
    private final String certificatePassword;
    private final String tokenProperty;
    private final String scope;
    
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ReentrantLock tokenLock;
    
    private volatile String accessToken;
    private volatile Instant tokenExpiryTime;
    private volatile boolean tokenRefreshInProgress;
    
    // Default token expiry buffer (refresh 5 minutes before expiry)
    private static final long TOKEN_EXPIRY_BUFFER_SECONDS = 300;
    
    public OAuth2CertificateAuthenticator(String tokenUrl, String clientId, String certificatePath, 
                                         String certificatePassword, String tokenProperty, String scope) {
        
        if (tokenUrl == null || tokenUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("OAuth2 token URL must not be null or empty");
        }
        if (clientId == null || clientId.trim().isEmpty()) {
            throw new IllegalArgumentException("OAuth2 client ID must not be null or empty");
        }
        if (certificatePath == null || certificatePath.trim().isEmpty()) {
            throw new IllegalArgumentException("OAuth2 certificate path must not be null or empty");
        }
        
        this.tokenUrl = tokenUrl.trim();
        this.clientId = clientId.trim();
        this.certificatePath = certificatePath.trim();
        this.certificatePassword = certificatePassword; // Can be null for password-less certificates
        this.tokenProperty = tokenProperty != null ? tokenProperty.trim() : "access_token";
        this.scope = scope;
        
        this.objectMapper = new ObjectMapper();
        this.tokenLock = new ReentrantLock();
        this.tokenRefreshInProgress = false;
        
        // Initialize HTTP client with certificate-based SSL
        this.httpClient = createHttpClientWithCertificate();
        
        log.debug("Initialized OAuth2CertificateAuthenticator for token URL: {} with certificate: {}", 
                 tokenUrl, certificatePath);
        
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
            
            // Build token request with client credentials grant
            FormBody.Builder formBuilder = new FormBody.Builder()
                .add("grant_type", "client_credentials")
                .add("client_id", clientId);
            
            if (scope != null && !scope.trim().isEmpty()) {
                formBuilder.add("scope", scope.trim());
            }
            
            Request.Builder requestBuilder = new Request.Builder()
                .url(tokenUrl)
                .post(formBuilder.build())
                .header("Content-Type", "application/x-www-form-urlencoded");
            
            // Execute token request (certificate authentication happens at TLS level)
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
                
                log.info("OAuth2 certificate-based token refreshed successfully");
                
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
        log.debug("Closing OAuth2CertificateAuthenticator");
        
        // Close HTTP client
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }
        
        // Clear token data
        accessToken = null;
        tokenExpiryTime = null;
        
        log.debug("OAuth2CertificateAuthenticator closed");
    }
    
    /**
     * Creates an HTTP client configured with the PFX certificate for mTLS authentication
     */
    private OkHttpClient createHttpClientWithCertificate() {
        try {
            // Load the PFX certificate
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            char[] password = certificatePassword != null ? certificatePassword.toCharArray() : new char[0];
            
            try (FileInputStream fis = new FileInputStream(certificatePath)) {
                keyStore.load(fis, password);
            }
            
            // Initialize KeyManagerFactory with the certificate
            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, password);
            KeyManager[] keyManagers = keyManagerFactory.getKeyManagers();
            
            // Create a trust manager that accepts all certificates (for testing)
            // In production, you should use proper certificate validation
            TrustManager[] trustManagers = new TrustManager[] {
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                        // Accept all client certificates
                    }
                    
                    @Override
                    public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                        // Accept all server certificates (for testing)
                        // In production, implement proper validation
                    }
                    
                    @Override
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }
                }
            };
            
            // Initialize SSL context
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagers, trustManagers, new SecureRandom());
            
            return new OkHttpClient.Builder()
                .sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustManagers[0])
                .hostnameVerifier((hostname, session) -> true) // Accept all hostnames for testing
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
                
        } catch (Exception e) {
            log.error("Failed to create HTTP client with certificate authentication", e);
            throw new RuntimeException("Failed to initialize certificate-based authentication", e);
        }
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
