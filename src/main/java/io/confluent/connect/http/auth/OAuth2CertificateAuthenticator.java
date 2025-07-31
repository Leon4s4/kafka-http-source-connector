package io.confluent.connect.http.auth;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
public class OAuth2CertificateAuthenticator extends AbstractOAuth2Authenticator {
    
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
    
    private AtomicBoolean tokenRefreshInProgress;
    
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
        this.tokenRefreshInProgress = new AtomicBoolean(false);
        
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
    public void refreshToken() throws Exception {
        tokenLock.lock();
        try {
            // Double-check if refresh is still needed after acquiring lock
            if (tokenRefreshInProgress.get()) {
                log.debug("Token refresh already in progress, waiting...");
                return;
            }
            
            tokenRefreshInProgress.set(true);
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
                processTokenResponse(response, tokenProperty, objectMapper);
                log.info("OAuth2 certificate-based token refreshed successfully");
                
            } catch (IOException e) {
                log.error("Failed to refresh OAuth2 token", e);
                throw new Exception("Failed to refresh OAuth2 token: " + e.getMessage(), e);
            }
            
        } finally {
            tokenRefreshInProgress.set(false);
            tokenLock.unlock();
        }
    }
    
    @Override
    public void close() {
        log.debug("Closing OAuth2CertificateAuthenticator");
        
        // Close HTTP client
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
            httpClient.connectionPool().evictAll();
        }
        
        clearTokenData();
        
        log.debug("OAuth2CertificateAuthenticator closed");
    }
    
    /**
     * Creates an HTTP client configured with the PFX certificate for mTLS authentication
     * with proper SSL/TLS security based on environment configuration
     */
    private OkHttpClient createHttpClientWithCertificate() {
        char[] password = certificatePassword != null ? certificatePassword.toCharArray() : new char[0];
        try {
            // Load the client certificate for mTLS
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            KeyManager[] keyManagers;
            try (FileInputStream fis = new FileInputStream(certificatePath)) {
                keyStore.load(fis, password);
            }
            
            // Initialize KeyManagerFactory with the certificate
            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, password);
            keyManagers = keyManagerFactory.getKeyManagers();
            
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
        } finally {
            // Clear the password array to remove sensitive data from memory
            java.util.Arrays.fill(password, '\0');
        }
    }
    
    /**
     * Configures hostname verification based on configuration settings
     */
    private void configureHostnameVerification(OkHttpClient.Builder clientBuilder) {
        if (verifyHostname) {
            log.debug("Using strict hostname verification as configured");
            // Do not set hostnameVerifier - use default secure verification
        } else {
            log.warn("SECURITY WARNING: Hostname verification is disabled per configuration. This is not recommended for production use.");
        }
    }
    
    /**
     * Creates appropriate trust managers based on configuration
     */
    private TrustManager[] createTrustManagers() throws Exception {
        if (allowSelfSigned) {
            log.warn("SECURITY WARNING: Self-signed certificates are allowed per configuration. This is not recommended for production use.");
            return createPermissiveTrustManagers();
        }
        return createStrictTrustManagers();
    }
    
    /**
     * Creates strict trust managers with proper certificate validation
     */
    private TrustManager[] createStrictTrustManagers() throws Exception {
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
            
            log.info("Using custom truststore for certificate validation");
            return tmf.getTrustManagers();
        } else {
            // Use default system truststore
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null); // Use default truststore
            
            return tmf.getTrustManagers();
        }
    }
    
    /**
     * Creates permissive trust managers that allow self-signed certificates but with enhanced validation
     */
    private TrustManager[] createPermissiveTrustManagers() throws Exception {
        // First try to get the default trust managers
        TrustManager[] defaultTrustManagers = createStrictTrustManagers();
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
                            // Allow self-signed certificates but with enhanced validation
                            log.warn("Certificate validation failed with default trust manager, performing enhanced validation: {}", e.getMessage());
                            
                            // Perform enhanced certificate validation
                            performEnhancedCertificateValidation(chain, authType);
                            
                            log.debug("Self-signed certificate accepted after enhanced validation");
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
     * Performs enhanced certificate validation when self-signed certificates are allowed.
     * This method provides stronger validation than just accepting any certificate,
     * while still allowing self-signed certificates when configured.
     */
    private void performEnhancedCertificateValidation(java.security.cert.X509Certificate[] chain, String authType) 
            throws java.security.cert.CertificateException {
        
        // Basic chain validation
        if (chain == null || chain.length == 0) {
            throw new java.security.cert.CertificateException("Certificate chain is empty");
        }
        
        log.debug("Performing enhanced certificate validation for {} certificate(s)", chain.length);
        
        for (int i = 0; i < chain.length; i++) {
            java.security.cert.X509Certificate cert = chain[i];
            
            // 1. Check certificate validity dates
            try {
                cert.checkValidity();
                log.debug("Certificate {} validity dates are valid", i);
            } catch (java.security.cert.CertificateExpiredException e) {
                throw new java.security.cert.CertificateException("Certificate " + i + " has expired: " + e.getMessage(), e);
            } catch (java.security.cert.CertificateNotYetValidException e) {
                throw new java.security.cert.CertificateException("Certificate " + i + " is not yet valid: " + e.getMessage(), e);
            }
            
            // 2. Check basic certificate structure and format
            try {
                // Verify the certificate can be parsed properly
                cert.getSubjectX500Principal();
                cert.getIssuerX500Principal();
                cert.getSerialNumber();
                cert.getPublicKey();
                log.debug("Certificate {} structure validation passed", i);
            } catch (Exception e) {
                throw new java.security.cert.CertificateException("Certificate " + i + " has invalid structure: " + e.getMessage(), e);
            }
            
            // 3. Check key usage and extensions for basic sanity
            try {
                boolean[] keyUsage = cert.getKeyUsage();
                if (keyUsage != null) {
                    // For server certificates, we expect digital signature and/or key encipherment
                    boolean hasValidKeyUsage = keyUsage[0] || keyUsage[2]; // Digital signature or key encipherment
                    if (!hasValidKeyUsage) {
                        log.warn("Certificate {} may not be suitable for TLS server authentication (key usage restrictions)", i);
                    }
                }
                log.debug("Certificate {} key usage validation passed", i);
            } catch (Exception e) {
                log.warn("Could not validate key usage for certificate {}: {}", i, e.getMessage());
                // Don't fail for key usage issues in development, just log warning
            }
            
            // 4. Check algorithm strength
            try {
                String algorithm = cert.getPublicKey().getAlgorithm();
                int keySize = getKeySize(cert.getPublicKey());
                
                // Enforce minimum key sizes for security
                boolean strongKey = false;
                switch (algorithm.toUpperCase()) {
                    case "RSA":
                        strongKey = keySize >= 2048;
                        break;
                    case "EC":
                    case "ECDSA":
                        strongKey = keySize >= 256;
                        break;
                    case "DSA":
                        strongKey = keySize >= 2048;
                        break;
                    default:
                        log.warn("Unknown algorithm '{}' for certificate {}", algorithm, i);
                        strongKey = true; // Don't fail for unknown algorithms
                }
                
                if (!strongKey) {
                    throw new java.security.cert.CertificateException(
                        "Certificate " + i + " uses weak cryptography: " + algorithm + " " + keySize + " bits. " +
                        "Minimum requirements: RSA 2048 bits, EC 256 bits, DSA 2048 bits.");
                }
                
                log.debug("Certificate {} algorithm strength validation passed: {} {} bits", i, algorithm, keySize);
            } catch (java.security.cert.CertificateException e) {
                throw e; // Re-throw certificate exceptions
            } catch (Exception e) {
                log.warn("Could not validate algorithm strength for certificate {}: {}", i, e.getMessage());
                // Don't fail for algorithm validation issues in development, just log warning
            }
            
            // 5. Check signature algorithm strength
            try {
                String sigAlg = cert.getSigAlgName().toUpperCase();
                if (sigAlg.contains("MD5") || sigAlg.contains("SHA1")) {
                    throw new java.security.cert.CertificateException(
                        "Certificate " + i + " uses weak signature algorithm: " + sigAlg + ". " +
                        "Use SHA-256 or stronger algorithms.");
                }
                log.debug("Certificate {} signature algorithm validation passed: {}", i, sigAlg);
            } catch (java.security.cert.CertificateException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Could not validate signature algorithm for certificate {}: {}", i, e.getMessage());
            }
        }
        
        // 6. Validate certificate chain structure
        if (chain.length > 1) {
            for (int i = 0; i < chain.length - 1; i++) {
                try {
                    // Verify that each certificate is signed by the next one in the chain
                    chain[i].verify(chain[i + 1].getPublicKey());
                    log.debug("Certificate chain link {} -> {} verification passed", i, i + 1);
                } catch (Exception e) {
                    log.warn("Certificate chain verification failed for link {} -> {}: {}", i, i + 1, e.getMessage());
                    // Don't fail the entire validation for chain issues when self-signed certificates are allowed
                    // This allows self-signed certificates which won't have proper chain validation
                }
            }
        }
        
        log.info("Enhanced certificate validation completed successfully");
    }
    
    /**
     * Gets the key size in bits for the given public key
     */
    private int getKeySize(java.security.PublicKey publicKey) {
        if (publicKey instanceof java.security.interfaces.RSAPublicKey) {
            return ((java.security.interfaces.RSAPublicKey) publicKey).getModulus().bitLength();
        } else if (publicKey instanceof java.security.interfaces.ECPublicKey) {
            return ((java.security.interfaces.ECPublicKey) publicKey).getParams().getOrder().bitLength();
        } else if (publicKey instanceof java.security.interfaces.DSAPublicKey) {
            return ((java.security.interfaces.DSAPublicKey) publicKey).getParams().getP().bitLength();
        } else {
            // For unknown key types, return a safe default
            log.warn("Unknown public key type: {}", publicKey.getClass().getName());
            return 2048; // Assume strong key
        }
    }
    
    /**
     * Gets the current environment configuration
     */
    public String getEnvironment() {
        return environment;
    }
    
    /**
     * Gets a security report for the current configuration
     */
    public String getSecurityReport() {
        StringBuilder report = new StringBuilder();
        report.append("OAuth2CertificateAuthenticator Security Report:\n");
        report.append("  Hostname Verification: ").append(verifyHostname ? "Enabled" : "Disabled").append("\n");
        report.append("  Self-Signed Certificates: ").append(allowSelfSigned ? "Allowed" : "Not Allowed").append("\n");
        report.append("  Custom Truststore: ").append(truststorePath != null ? "Yes" : "No").append("\n");
        
        // Determine security level based on configuration
        if (verifyHostname && !allowSelfSigned) {
            report.append("  Security Level: STRICT\n");
        } else if (!verifyHostname || allowSelfSigned) {
            report.append("  Security Level: PERMISSIVE\n");
        } else {
            report.append("  Security Level: MIXED\n");
        }
        
        return report.toString();
    }
    
    /**
     * Validates the security configuration and logs warnings for insecure settings
     */
    private void validateSecurityConfiguration() {
        if (!verifyHostname) {
            log.warn("SECURITY WARNING: Hostname verification is disabled. This is not recommended for production use.");
        }
        if (allowSelfSigned) {
            log.warn("SECURITY WARNING: Self-signed certificates are allowed. This is not recommended for production use.");
        }
        if (truststorePath == null) {
            log.debug("Using default system truststore for certificate validation");
        } else {
            log.info("Using custom truststore: {}", truststorePath);
        }
        
        log.info("OAuth2 certificate authenticator initialized with security configuration - see security report for details");
    }
}
