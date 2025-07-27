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
 * HashiCorp Vault authenticator for secure credential management.
 * Supports various Vault authentication methods and automatic token refresh.
 */
public class VaultAuthenticator implements HttpAuthenticator {
    
    private static final Logger log = LoggerFactory.getLogger(VaultAuthenticator.class);
    
    public enum VaultAuthMethod {
        TOKEN,
        APPROLE,
        AWS,
        KUBERNETES,
        LDAP,
        USERPASS
    }
    
    private final String vaultUrl;
    private final String secretPath;
    private final VaultAuthMethod authMethod;
    private final Map<String, Object> authConfig;
    
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ReentrantLock tokenLock;
    
    private volatile String vaultToken;
    private volatile Instant tokenExpiryTime;
    private volatile String cachedSecret;
    private volatile Instant secretExpiryTime;
    
    // Token refresh settings
    private static final long TOKEN_REFRESH_BUFFER_SECONDS = 300; // 5 minutes
    private static final long SECRET_REFRESH_BUFFER_SECONDS = 600; // 10 minutes
    
    public VaultAuthenticator(Map<String, Object> config) {
        this.authConfig = config;
        this.vaultUrl = (String) config.get("vault.url");
        this.secretPath = (String) config.get("vault.secret.path");
        this.authMethod = VaultAuthMethod.valueOf(
            config.getOrDefault("vault.auth.method", "TOKEN").toString().toUpperCase()
        );
        
        // Validate required configuration
        if (vaultUrl == null || vaultUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("Vault URL must be provided");
        }
        if (secretPath == null || secretPath.trim().isEmpty()) {
            throw new IllegalArgumentException("Vault secret path must be provided");
        }
        
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build();
        
        this.objectMapper = new ObjectMapper();
        this.tokenLock = new ReentrantLock();
        
        // Initialize Vault authentication
        try {
            authenticateWithVault();
        } catch (Exception e) {
            log.error("Failed to initialize Vault authentication: {}", e.getMessage(), e);
            throw new RuntimeException("Vault authentication initialization failed", e);
        }
        
        log.info("Vault authenticator initialized: url={}, method={}, path={}",
                vaultUrl, authMethod, secretPath);
    }
    
    @Override
    public void authenticate(Request.Builder requestBuilder) {
        try {
            // Ensure we have a valid secret
            String secret = getSecret();
            if (secret != null) {
                // Apply secret as Bearer token (most common use case)
                requestBuilder.header("Authorization", "Bearer " + secret);
                log.trace("Applied Vault-retrieved secret for authentication");
            } else {
                log.warn("No secret available from Vault for authentication");
            }
        } catch (Exception e) {
            log.error("Failed to retrieve secret from Vault: {}", e.getMessage(), e);
            throw new RuntimeException("Vault authentication failed", e);
        }
    }
    
    @Override
    public void refreshToken() {
        try {
            authenticateWithVault();
            refreshSecret();
        } catch (Exception e) {
            log.error("Failed to refresh Vault token: {}", e.getMessage(), e);
            throw new RuntimeException("Vault token refresh failed", e);
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
        vaultToken = null;
        cachedSecret = null;
        tokenExpiryTime = null;
        secretExpiryTime = null;
        
        log.debug("Vault authenticator closed");
    }
    
    /**
     * Rotate credentials by re-authenticating with Vault and refreshing secrets.
     */
    public void rotateCredentials() throws Exception {
        log.info("Rotating Vault credentials");
        authenticateWithVault();
        refreshSecret();
        log.info("Vault credential rotation completed");
    }
    
    /**
     * Get the current secret from Vault, refreshing if necessary.
     */
    private String getSecret() throws Exception {
        if (shouldRefreshSecret()) {
            refreshSecret();
        }
        return cachedSecret;
    }
    
    /**
     * Authenticate with Vault using the configured method.
     */
    private void authenticateWithVault() throws Exception {
        tokenLock.lock();
        try {
            log.debug("Authenticating with Vault using method: {}", authMethod);
            
            switch (authMethod) {
                case TOKEN:
                    authenticateWithToken();
                    break;
                case APPROLE:
                    authenticateWithAppRole();
                    break;
                case AWS:
                    authenticateWithAws();
                    break;
                case KUBERNETES:
                    authenticateWithKubernetes();
                    break;
                case LDAP:
                    authenticateWithLdap();
                    break;
                case USERPASS:
                    authenticateWithUserPass();
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported Vault auth method: " + authMethod);
            }
            
            log.debug("Vault authentication successful");
            
        } finally {
            tokenLock.unlock();
        }
    }
    
    /**
     * Authenticate using a static token.
     */
    private void authenticateWithToken() {
        String token = (String) authConfig.get("vault.token");
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("Vault token must be provided for TOKEN auth method");
        }
        
        this.vaultToken = token.trim();
        // For static tokens, we don't know the expiry, so assume long-lived
        this.tokenExpiryTime = Instant.now().plusSeconds(3600 * 24); // 24 hours
    }
    
    /**
     * Authenticate using AppRole method.
     */
    private void authenticateWithAppRole() throws Exception {
        String roleId = (String) authConfig.get("vault.role.id");
        String secretId = (String) authConfig.get("vault.secret.id");
        
        if (roleId == null || secretId == null) {
            throw new IllegalArgumentException("Role ID and Secret ID must be provided for AppRole auth");
        }
        
        String authUrl = vaultUrl + "/v1/auth/approle/login";
        
        RequestBody requestBody = RequestBody.create(
            objectMapper.writeValueAsString(Map.of(
                "role_id", roleId,
                "secret_id", secretId
            )),
            MediaType.get("application/json")
        );
        
        Request request = new Request.Builder()
            .url(authUrl)
            .post(requestBody)
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Vault AppRole authentication failed: " + response.code());
            }
            
            parseAuthResponse(response);
        }
    }
    
    /**
     * Authenticate using AWS method.
     */
    private void authenticateWithAws() throws Exception {
        // AWS authentication would integrate with AWS SDK to get IAM credentials
        // This is a placeholder for the full implementation
        throw new UnsupportedOperationException("AWS Vault authentication not yet implemented");
    }
    
    /**
     * Authenticate using Kubernetes method.
     */
    private void authenticateWithKubernetes() throws Exception {
        String role = (String) authConfig.get("vault.kubernetes.role");
        String jwtPath = authConfig.getOrDefault("vault.kubernetes.jwt.path", 
                                               "/var/run/secrets/kubernetes.io/serviceaccount/token").toString();
        
        if (role == null) {
            throw new IllegalArgumentException("Kubernetes role must be provided");
        }
        
        // Read JWT token from file system
        String jwt = readFileContent(jwtPath);
        
        String authUrl = vaultUrl + "/v1/auth/kubernetes/login";
        
        RequestBody requestBody = RequestBody.create(
            objectMapper.writeValueAsString(Map.of(
                "role", role,
                "jwt", jwt
            )),
            MediaType.get("application/json")
        );
        
        Request request = new Request.Builder()
            .url(authUrl)
            .post(requestBody)
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Vault Kubernetes authentication failed: " + response.code());
            }
            
            parseAuthResponse(response);
        }
    }
    
    /**
     * Authenticate using LDAP method.
     */
    private void authenticateWithLdap() throws Exception {
        String username = (String) authConfig.get("vault.ldap.username");
        String password = (String) authConfig.get("vault.ldap.password");
        
        if (username == null || password == null) {
            throw new IllegalArgumentException("Username and password must be provided for LDAP auth");
        }
        
        String authUrl = vaultUrl + "/v1/auth/ldap/login/" + username;
        
        RequestBody requestBody = RequestBody.create(
            objectMapper.writeValueAsString(Map.of("password", password)),
            MediaType.get("application/json")
        );
        
        Request request = new Request.Builder()
            .url(authUrl)
            .post(requestBody)
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Vault LDAP authentication failed: " + response.code());
            }
            
            parseAuthResponse(response);
        }
    }
    
    /**
     * Authenticate using username/password method.
     */
    private void authenticateWithUserPass() throws Exception {
        String username = (String) authConfig.get("vault.username");
        String password = (String) authConfig.get("vault.password");
        
        if (username == null || password == null) {
            throw new IllegalArgumentException("Username and password must be provided for userpass auth");
        }
        
        String authUrl = vaultUrl + "/v1/auth/userpass/login/" + username;
        
        RequestBody requestBody = RequestBody.create(
            objectMapper.writeValueAsString(Map.of("password", password)),
            MediaType.get("application/json")
        );
        
        Request request = new Request.Builder()
            .url(authUrl)
            .post(requestBody)
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Vault userpass authentication failed: " + response.code());
            }
            
            parseAuthResponse(response);
        }
    }
    
    /**
     * Parse authentication response from Vault.
     */
    private void parseAuthResponse(Response response) throws Exception {
        String responseBody = response.body().string();
        JsonNode rootNode = objectMapper.readTree(responseBody);
        JsonNode authNode = rootNode.get("auth");
        
        if (authNode == null) {
            throw new IOException("Invalid Vault authentication response: missing auth section");
        }
        
        this.vaultToken = authNode.get("client_token").asText();
        
        // Parse lease duration for token expiry
        long leaseDuration = authNode.get("lease_duration").asLong();
        this.tokenExpiryTime = Instant.now().plusSeconds(leaseDuration);
        
        log.debug("Vault token acquired, expires at: {}", tokenExpiryTime);
    }
    
    /**
     * Refresh the secret from Vault.
     */
    private void refreshSecret() throws Exception {
        if (vaultToken == null) {
            throw new IllegalStateException("No Vault token available");
        }
        
        String secretUrl = vaultUrl + "/v1/" + secretPath;
        
        Request request = new Request.Builder()
            .url(secretUrl)
            .header("X-Vault-Token", vaultToken)
            .get()
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to retrieve secret from Vault: " + response.code());
            }
            
            String responseBody = response.body().string();
            JsonNode rootNode = objectMapper.readTree(responseBody);
            JsonNode dataNode = rootNode.get("data");
            
            if (dataNode == null) {
                throw new IOException("Invalid Vault secret response: missing data section");
            }
            
            // Extract the secret value (assuming it's in a "value" field)
            JsonNode secretNode = dataNode.get("value");
            if (secretNode == null) {
                // Try "password" field as fallback
                secretNode = dataNode.get("password");
            }
            if (secretNode == null) {
                // Try "token" field as fallback
                secretNode = dataNode.get("token");
            }
            
            if (secretNode != null) {
                this.cachedSecret = secretNode.asText();
                this.secretExpiryTime = Instant.now().plusSeconds(3600); // 1 hour default
                log.debug("Secret refreshed from Vault path: {}", secretPath);
            } else {
                throw new IOException("No usable secret found in Vault response");
            }
        }
    }
    
    /**
     * Check if the Vault token should be refreshed.
     */
    private boolean shouldRefreshToken() {
        if (vaultToken == null || tokenExpiryTime == null) {
            return true;
        }
        
        Instant refreshTime = tokenExpiryTime.minusSeconds(TOKEN_REFRESH_BUFFER_SECONDS);
        return Instant.now().isAfter(refreshTime);
    }
    
    /**
     * Check if the secret should be refreshed.
     */
    private boolean shouldRefreshSecret() {
        if (cachedSecret == null || secretExpiryTime == null) {
            return true;
        }
        
        Instant refreshTime = secretExpiryTime.minusSeconds(SECRET_REFRESH_BUFFER_SECONDS);
        return Instant.now().isAfter(refreshTime);
    }
    
    /**
     * Read content from file (for Kubernetes JWT token).
     */
    private String readFileContent(String filePath) throws Exception {
        try {
            return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath)));
        } catch (Exception e) {
            throw new Exception("Failed to read file: " + filePath, e);
        }
    }
}
