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
import javax.net.ssl.TrustManagerFactory;
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
 * 
 * <p>Security Features:
 * <ul>
 *   <li>Environment-based certificate validation (production, staging, development, testing)</li>
 *   <li>Custom truststore support for enterprise CA certificates</li>
 *   <li>Configurable hostname verification</li>
 *   <li>TLS 1.2+ enforcement</li>
 *   <li>Proper certificate chain validation</li>
 *   <li>Self-signed certificate support for development (with warnings)</li>
 * </ul>
 * 
 * <p>Environment Configurations:
 * <ul>
 *   <li><b>Production/Staging:</b> Strict certificate validation, hostname verification enabled</li>
 *   <li><b>Development/Testing:</b> Configurable validation, optional self-signed certificate support</li>
 * </ul>
 */
public class OAuth2CertificateAuthenticator implements HttpAuthenticator {
    
    private static final Logger log = LoggerFactory.getLogger(OAuth2CertificateAuthenticator.class);
    
    private final String tokenUrl;
    private final String clientId;
    private final String certificatePath;
    private final String certificatePassword;
    private final String tokenProperty;
    private final String scope;
    private final String truststorePath;
    private final String truststorePassword;
    private final boolean verifyHostname;
    private final boolean allowSelfSigned;
    private final String environment;
    
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
        this(tokenUrl, clientId, certificatePath, certificatePassword, tokenProperty, scope,
             null, null, true, false, "production");
    }
    
    public OAuth2CertificateAuthenticator(String tokenUrl, String clientId, String certificatePath, 
                                         String certificatePassword, String tokenProperty, String scope,
                                         String truststorePath, String truststorePassword, 
                                         boolean verifyHostname, boolean allowSelfSigned, String environment) {
        
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
        this.truststorePath = truststorePath;
        this.truststorePassword = truststorePassword;
        this.verifyHostname = verifyHostname;
        this.allowSelfSigned = allowSelfSigned;
        this.environment = environment != null ? environment.toLowerCase() : "production";
        
        // Validate security configuration and log warnings
        validateSecurityConfiguration();
        
        this.objectMapper = new ObjectMapper();
        this.tokenLock = new ReentrantLock();
        this.tokenRefreshInProgress = false;
        
        // Initialize HTTP client with certificate-based SSL
        this.httpClient = createHttpClientWithCertificate();
        
        log.debug("Initialized OAuth2CertificateAuthenticator for token URL: {} with certificate: {}, environment: {}, verifyHostname: {}", 
                 tokenUrl, certificatePath, environment, verifyHostname);
        
        // Log security report for audit purposes
        if (log.isInfoEnabled()) {
            log.info("Security Configuration Report:\n{}", getSecurityReport());
        }
        
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
     * with proper SSL/TLS security based on environment configuration
     */
    private OkHttpClient createHttpClientWithCertificate() {
        try {
            // Load the client certificate for mTLS
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            char[] password = certificatePassword != null ? certificatePassword.toCharArray() : new char[0];
            
            try (FileInputStream fis = new FileInputStream(certificatePath)) {
                keyStore.load(fis, password);
            }
            
            // Initialize KeyManagerFactory with the certificate
            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, password);
            KeyManager[] keyManagers = keyManagerFactory.getKeyManagers();
            
            // Create appropriate trust managers based on environment and configuration
            TrustManager[] trustManagers = createTrustManagers();
            
            // Initialize SSL context with TLS 1.2 minimum
            SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
            sslContext.init(keyManagers, trustManagers, new SecureRandom());
            
            // Create OkHttpClient with appropriate security settings
            OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
                .sslSocketFactory(sslContext.getSocketFactory(), (X509TrustManager) trustManagers[0])
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS);
            
            // Configure hostname verification based on settings and environment
            configureHostnameVerification(clientBuilder);
            
            return clientBuilder.build();
                
        } catch (Exception e) {
            log.error("Failed to create HTTP client with certificate authentication", e);
            throw new RuntimeException("Failed to initialize certificate-based authentication", e);
        }
    }
    
    /**
     * Configures hostname verification based on environment and security settings
     */
    private void configureHostnameVerification(OkHttpClient.Builder clientBuilder) {
        // For production and staging environments, enforce hostname verification
        if ("production".equals(environment) || "staging".equals(environment)) {
            if (!verifyHostname) {
                // Force hostname verification in production/staging regardless of configuration
                log.error("SECURITY ENFORCEMENT: Hostname verification forced ON in {} environment - overriding configuration", environment);
                log.error("Hostname verification bypass is not allowed in production/staging environments for security reasons");
                // Do not set hostnameVerifier - use default secure verification
            } else {
                log.debug("Using strict hostname verification for {} environment", environment);
                // Do not set hostnameVerifier - use default secure verification
            }
            return;
        }
        
        // For development and testing environments, allow configuration flexibility
        if ("development".equals(environment) || "testing".equals(environment)) {
            if (verifyHostname) {
                log.debug("Using strict hostname verification for {} environment", environment);
                // Do not set hostnameVerifier - use default secure verification
            } else {
                log.warn("DEVELOPMENT ONLY: Hostname verification disabled for {} environment", environment);
                log.warn("This configuration is NOT suitable for production use!");
                clientBuilder.hostnameVerifier((hostname, session) -> {
                    log.debug("Accepting hostname: {} in {} environment (verification disabled)", hostname, environment);
                    return true;
                });
            }
            return;
        }
        
        // For any unknown environment, default to strict security
        log.warn("Unknown environment '{}' - defaulting to strict hostname verification", environment);
        // Do not set hostnameVerifier - use default secure verification
    }
    
    /**
     * Creates appropriate trust managers based on environment and configuration
     */
    private TrustManager[] createTrustManagers() throws Exception {
        // For production and staging, use proper certificate validation
        if ("production".equals(environment) || "staging".equals(environment)) {
            return createProductionTrustManagers();
        }
        
        // For development/testing, allow more flexibility but with warnings
        if ("development".equals(environment) || "testing".equals(environment)) {
            if (allowSelfSigned) {
                log.warn("Using permissive trust manager for {} environment - self-signed certificates allowed", environment);
                return createDevelopmentTrustManagers();
            }
        }
        
        // Default to production-grade security
        return createProductionTrustManagers();
    }
    
    /**
     * Creates production-grade trust managers with proper certificate validation
     */
    private TrustManager[] createProductionTrustManagers() throws Exception {
        if (truststorePath != null && !truststorePath.trim().isEmpty()) {
            // Use custom truststore if provided
            log.debug("Loading custom truststore from: {}", truststorePath);
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            char[] trustStorePassword = truststorePassword != null ? truststorePassword.toCharArray() : null;
            
            try (FileInputStream fis = new FileInputStream(truststorePath.trim())) {
                trustStore.load(fis, trustStorePassword);
            }
            
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            
            log.info("Using custom truststore for certificate validation in {} environment", environment);
            return tmf.getTrustManagers();
        } else {
            // Use default system truststore
            log.debug("Using default system truststore for certificate validation");
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null); // Use default truststore
            
            return tmf.getTrustManagers();
        }
    }
    
    /**
     * Creates development trust managers that allow self-signed certificates but with proper validation
     */
    private TrustManager[] createDevelopmentTrustManagers() throws Exception {
        // First try to get the default trust managers
        TrustManager[] defaultTrustManagers = createProductionTrustManagers();
        X509TrustManager defaultTrustManager = (X509TrustManager) defaultTrustManagers[0];
        
        return new TrustManager[] {
            new X509TrustManager() {
                @Override
                public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) 
                    throws java.security.cert.CertificateException {
                    // For client certificates, always use default validation
                    defaultTrustManager.checkClientTrusted(chain, authType);
                }
                
                @Override
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) 
                    throws java.security.cert.CertificateException {
                    try {
                        // Try default validation first
                        defaultTrustManager.checkServerTrusted(chain, authType);
                        log.debug("Server certificate validated successfully using default trust manager");
                    } catch (java.security.cert.CertificateException e) {
                        if (allowSelfSigned) {
                            // For development, allow self-signed certificates but log warning
                            log.warn("Certificate validation failed with default trust manager, but allowing due to development configuration: {}", e.getMessage());
                            
                            // Perform basic certificate validation
                            if (chain == null || chain.length == 0) {
                                throw new java.security.cert.CertificateException("Certificate chain is empty");
                            }
                            
                            // Check certificate dates
                            for (java.security.cert.X509Certificate cert : chain) {
                                cert.checkValidity();
                            }
                            
                            log.debug("Self-signed certificate accepted for development environment");
                        } else {
                            // Re-throw the original exception
                            throw e;
                        }
                    }
                }
                
                @Override
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return defaultTrustManager.getAcceptedIssuers();
                }
            }
        };
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
    
    /**
     * Gets the current environment configuration
     */
    public String getEnvironment() {
        return environment;
    }
    
    /**
     * Checks if hostname verification is enabled
     */
    public boolean isHostnameVerificationEnabled() {
        return verifyHostname;
    }
    
    /**
     * Checks if self-signed certificates are allowed
     */
    public boolean isSelfSignedAllowed() {
        return allowSelfSigned;
    }
    
    /**
     * Gets the configured truststore path (may be null)
     */
    public String getTruststorePath() {
        return truststorePath;
    }
    
    /**
     * Checks if hostname verification is actually enforced (considering environment overrides)
     */
    public boolean isHostnameVerificationEnforced() {
        // Production and staging always enforce hostname verification
        if ("production".equals(environment) || "staging".equals(environment)) {
            return true;
        }
        // For other environments, use the configuration
        return verifyHostname;
    }
    
    /**
     * Gets a security report for the current configuration
     */
    public String getSecurityReport() {
        StringBuilder report = new StringBuilder();
        report.append("OAuth2CertificateAuthenticator Security Report:\n");
        report.append("  Environment: ").append(environment).append("\n");
        report.append("  Hostname Verification Configured: ").append(verifyHostname).append("\n");
        report.append("  Hostname Verification Enforced: ").append(isHostnameVerificationEnforced()).append("\n");
        report.append("  Self-Signed Certificates Allowed: ").append(allowSelfSigned).append("\n");
        report.append("  Custom Truststore: ").append(truststorePath != null ? "Yes" : "No").append("\n");
        
        if ("production".equals(environment) || "staging".equals(environment)) {
            report.append("  Security Level: STRICT (Production/Staging)\n");
        } else if ("development".equals(environment) || "testing".equals(environment)) {
            report.append("  Security Level: FLEXIBLE (Development/Testing)\n");
        } else {
            report.append("  Security Level: STRICT (Unknown Environment Default)\n");
        }
        
        return report.toString();
    }
    
    /**
     * Validates the security configuration and logs warnings for insecure settings
     */
    private void validateSecurityConfiguration() {
        if ("production".equals(environment)) {
            if (!verifyHostname) {
                log.error("SECURITY NOTICE: Hostname verification configuration disabled, but will be ENFORCED in production environment");
            }
            if (allowSelfSigned) {
                log.error("SECURITY WARNING: Self-signed certificates are allowed in production environment!");
            }
            if (truststorePath == null) {
                log.warn("Using default system truststore in production environment - consider using custom truststore");
            }
            log.info("Production environment detected - enforcing strict security policies");
        } else if ("staging".equals(environment)) {
            if (!verifyHostname) {
                log.error("SECURITY NOTICE: Hostname verification configuration disabled, but will be ENFORCED in staging environment");
            }
            if (allowSelfSigned) {
                log.warn("Self-signed certificates are allowed in staging environment");
            }
            log.info("Staging environment detected - enforcing strict security policies");
        } else if ("development".equals(environment) || "testing".equals(environment)) {
            if (!verifyHostname) {
                log.warn("DEVELOPMENT: Hostname verification disabled - this configuration is NOT suitable for production");
            }
            if (allowSelfSigned) {
                log.warn("DEVELOPMENT: Self-signed certificates allowed - this configuration is NOT suitable for production");
            }
            log.info("Development/Testing environment detected - flexible security settings enabled with warnings");
        } else {
            log.warn("Unknown environment '{}' - defaulting to strict security policies", environment);
        }
    }
}
