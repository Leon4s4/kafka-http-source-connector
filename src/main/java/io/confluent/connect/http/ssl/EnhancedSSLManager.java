package io.confluent.connect.http.ssl;

import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

/**
 * Enhanced SSL/TLS management for HTTP Source Connector.
 * Provides comprehensive SSL configuration, certificate management,
 * and security enhancements including certificate pinning and validation.
 */
public class EnhancedSSLManager {
    
    private static final Logger log = LoggerFactory.getLogger(EnhancedSSLManager.class);
    
    public enum SSLVersion {
        TLS_1_2("TLSv1.2"),
        TLS_1_3("TLSv1.3"),
        TLS_1_2_1_3("TLSv1.2,TLSv1.3");
        
        private final String protocols;
        
        SSLVersion(String protocols) {
            this.protocols = protocols;
        }
        
        public String[] getProtocols() {
            return protocols.split(",");
        }
    }
    
    public enum CertificateValidation {
        STRICT,     // Full certificate validation
        RELAXED,    // Allow self-signed certificates  
        DISABLED,   // Disable validation (not recommended)
        PINNED      // Certificate pinning enabled
    }
    
    private final HttpSourceConnectorConfig config;
    private final Map<String, SSLContext> sslContextCache;
    private final Map<String, List<String>> certificatePins;
    
    // SSL Configuration
    private final boolean sslEnabled;
    private final SSLVersion sslVersion;
    private final CertificateValidation certificateValidation;
    private final String keyStorePath;
    private final String keyStorePassword;
    private final String keyStoreType;
    private final String trustStorePath;
    private final String trustStorePassword;
    private final String trustStoreType;
    private final String[] enabledCipherSuites;
    private final boolean hostnameVerification;
    private final int certificateRefreshIntervalMs;
    
    public EnhancedSSLManager(HttpSourceConnectorConfig config) {
        this.config = config;
        this.sslContextCache = new ConcurrentHashMap<>();
        this.certificatePins = new ConcurrentHashMap<>();
        
        // Extract SSL configuration
        this.sslEnabled = isSSLEnabled(config);
        this.sslVersion = getSSLVersion(config);
        this.certificateValidation = getCertificateValidation(config);
        this.keyStorePath = getKeyStorePath(config);
        this.keyStorePassword = getKeyStorePassword(config);
        this.keyStoreType = getKeyStoreType(config);
        this.trustStorePath = getTrustStorePath(config);
        this.trustStorePassword = getTrustStorePassword(config);
        this.trustStoreType = getTrustStoreType(config);
        this.enabledCipherSuites = getEnabledCipherSuites(config);
        this.hostnameVerification = isHostnameVerificationEnabled(config);
        this.certificateRefreshIntervalMs = getCertificateRefreshInterval(config);
        
        if (sslEnabled) {
            log.info("Enhanced SSL manager initialized: version={}, validation={}, hostname_verification={}",
                    sslVersion, certificateValidation, hostnameVerification);
            
            // Load certificate pins if configured
            loadCertificatePins(config);
        } else {
            log.info("SSL disabled for HTTP Source Connector");
        }
    }
    
    /**
     * Get or create an SSL context for a specific API endpoint.
     */
    public SSLContext getSSLContext(String apiId) throws SSLException {
        if (!sslEnabled) {
            return null;
        }
        
        return sslContextCache.computeIfAbsent(apiId, id -> {
            try {
                return createSSLContext(id);
            } catch (Exception e) {
                log.error("Failed to create SSL context for API: " + id, e);
                throw new RuntimeException("SSL context creation failed", e);
            }
        });
    }
    
    /**
     * Create a custom hostname verifier based on configuration.
     */
    public HostnameVerifier getHostnameVerifier(String apiId) {
        if (!sslEnabled || !hostnameVerification) {
            return (hostname, session) -> true; // Accept all hostnames
        }
        
        switch (certificateValidation) {
            case STRICT:
                return HttpsURLConnection.getDefaultHostnameVerifier();
            case RELAXED:
                return new RelaxedHostnameVerifier();
            case DISABLED:
                return (hostname, session) -> true;
            case PINNED:
                return new PinnedHostnameVerifier(apiId, certificatePins.get(apiId));
            default:
                return HttpsURLConnection.getDefaultHostnameVerifier();
        }
    }
    
    /**
     * Create a custom trust manager based on configuration.
     */
    public X509TrustManager getTrustManager(String apiId) throws SSLException {
        switch (certificateValidation) {
            case STRICT:
                return createStrictTrustManager();
            case RELAXED:
                return new RelaxedX509TrustManager();
            case DISABLED:
                return new DisabledX509TrustManager();
            case PINNED:
                List<String> pins = certificatePins.get(apiId);
                return new PinnedX509TrustManager(pins);
            default:
                return createStrictTrustManager();
        }
    }
    
    /**
     * Add certificate pin for an API endpoint.
     */
    public void addCertificatePin(String apiId, String pin) {
        certificatePins.computeIfAbsent(apiId, id -> new ArrayList<>()).add(pin);
        
        // Invalidate cached SSL context to force recreation with new pin
        sslContextCache.remove(apiId);
        
        log.info("Added certificate pin for API {}: {}", apiId, pin);
    }
    
    /**
     * Remove all certificate pins for an API.
     */
    public void removeCertificatePins(String apiId) {
        certificatePins.remove(apiId);
        sslContextCache.remove(apiId);
        log.info("Removed certificate pins for API: {}", apiId);
    }
    
    /**
     * Validate SSL configuration and certificates.
     */
    public SSLValidationResult validateSSLConfiguration() {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        
        if (!sslEnabled) {
            return new SSLValidationResult(true, "SSL disabled", errors, warnings);
        }
        
        // Validate keystore
        if (keyStorePath != null && !keyStorePath.isEmpty()) {
            try {
                loadKeyStore();
            } catch (Exception e) {
                errors.add("Keystore validation failed: " + e.getMessage());
            }
        }
        
        // Validate truststore
        if (trustStorePath != null && !trustStorePath.isEmpty()) {
            try {
                loadTrustStore();
            } catch (Exception e) {
                errors.add("Truststore validation failed: " + e.getMessage());
            }
        }
        
        // Check SSL version support
        try {
            SSLContext.getInstance(sslVersion.getProtocols()[0]);
        } catch (Exception e) {
            errors.add("SSL version not supported: " + sslVersion);
        }
        
        // Validate cipher suites
        if (enabledCipherSuites != null && enabledCipherSuites.length > 0) {
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            String[] supportedCiphers = factory.getSupportedCipherSuites();
            
            for (String cipher : enabledCipherSuites) {
                boolean supported = false;
                for (String supportedCipher : supportedCiphers) {
                    if (supportedCipher.equals(cipher)) {
                        supported = true;
                        break;
                    }
                }
                if (!supported) {
                    warnings.add("Cipher suite not supported: " + cipher);
                }
            }
        }
        
        // Security warnings
        if (certificateValidation == CertificateValidation.DISABLED) {
            warnings.add("Certificate validation is disabled - not recommended for production");
        }
        
        if (!hostnameVerification) {
            warnings.add("Hostname verification is disabled - potential security risk");
        }
        
        boolean valid = errors.isEmpty();
        String message = valid ? "SSL configuration is valid" : "SSL configuration has errors";
        
        return new SSLValidationResult(valid, message, errors, warnings);
    }
    
    /**
     * Refresh SSL contexts (useful for certificate rotation).
     */
    public void refreshSSLContexts() {
        log.info("Refreshing SSL contexts...");
        sslContextCache.clear();
        
        // Pre-load contexts for known APIs
        for (String apiId : certificatePins.keySet()) {
            try {
                getSSLContext(apiId);
                log.debug("Refreshed SSL context for API: {}", apiId);
            } catch (Exception e) {
                log.warn("Failed to refresh SSL context for API: " + apiId, e);
            }
        }
    }
    
    private SSLContext createSSLContext(String apiId) throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        
        // Initialize key manager
        KeyManager[] keyManagers = null;
        if (keyStorePath != null && !keyStorePath.isEmpty()) {
            KeyStore keyStore = loadKeyStore();
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, keyStorePassword != null ? keyStorePassword.toCharArray() : null);
            keyManagers = kmf.getKeyManagers();
        }
        
        // Initialize trust manager
        TrustManager[] trustManagers = new TrustManager[]{ getTrustManager(apiId) };
        
        // Initialize SSL context
        sslContext.init(keyManagers, trustManagers, new java.security.SecureRandom());
        
        return sslContext;
    }
    
    private KeyStore loadKeyStore() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(keyStoreType);
        try (InputStream is = new FileInputStream(keyStorePath)) {
            keyStore.load(is, keyStorePassword != null ? keyStorePassword.toCharArray() : null);
        }
        return keyStore;
    }
    
    private KeyStore loadTrustStore() throws Exception {
        KeyStore trustStore = KeyStore.getInstance(trustStoreType);
        try (InputStream is = new FileInputStream(trustStorePath)) {
            trustStore.load(is, trustStorePassword != null ? trustStorePassword.toCharArray() : null);
        }
        return trustStore;
    }
    
    private X509TrustManager createStrictTrustManager() throws SSLException {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            
            if (trustStorePath != null && !trustStorePath.isEmpty()) {
                KeyStore trustStore = loadTrustStore();
                tmf.init(trustStore);
            } else {
                tmf.init((KeyStore) null); // Use default trust store
            }
            
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    return (X509TrustManager) tm;
                }
            }
            
            throw new SSLException("No X509TrustManager found");
            
        } catch (Exception e) {
            throw new SSLException("Failed to create trust manager", e);
        }
    }
    
    private void loadCertificatePins(HttpSourceConnectorConfig config) {
        // Load certificate pins from configuration
        // This would typically be done from connector properties
        String pinsConfig = config.originalsStrings().get("ssl.certificate.pins");
        if (pinsConfig != null && !pinsConfig.trim().isEmpty()) {
            String[] pinEntries = pinsConfig.split(";");
            for (String entry : pinEntries) {
                String[] parts = entry.split("=", 2);
                if (parts.length == 2) {
                    String apiId = parts[0].trim();
                    String[] pins = parts[1].split(",");
                    List<String> pinList = new ArrayList<>();
                    for (String pin : pins) {
                        pinList.add(pin.trim());
                    }
                    certificatePins.put(apiId, pinList);
                    log.info("Loaded {} certificate pins for API: {}", pinList.size(), apiId);
                }
            }
        }
    }
    
    // Configuration extraction methods
    
    private boolean isSSLEnabled(HttpSourceConnectorConfig config) {
        String value = config.originalsStrings().get("ssl.enabled");
        return value != null ? Boolean.parseBoolean(value) : true;
    }
    
    private SSLVersion getSSLVersion(HttpSourceConnectorConfig config) {
        String version = config.originalsStrings().getOrDefault("ssl.version", "TLS_1_2_1_3");
        try {
            return SSLVersion.valueOf(version);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid SSL version: {}, using TLS_1_2_1_3", version);
            return SSLVersion.TLS_1_2_1_3;
        }
    }
    
    private CertificateValidation getCertificateValidation(HttpSourceConnectorConfig config) {
        String validation = config.originalsStrings().getOrDefault("ssl.certificate.validation", "STRICT");
        try {
            return CertificateValidation.valueOf(validation);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid certificate validation: {}, using STRICT", validation);
            return CertificateValidation.STRICT;
        }
    }
    
    private String getKeyStorePath(HttpSourceConnectorConfig config) {
        return config.originalsStrings().get("ssl.keystore.path");
    }
    
    private String getKeyStorePassword(HttpSourceConnectorConfig config) {
        return config.originalsStrings().get("ssl.keystore.password");
    }
    
    private String getKeyStoreType(HttpSourceConnectorConfig config) {
        return config.originalsStrings().getOrDefault("ssl.keystore.type", "JKS");
    }
    
    private String getTrustStorePath(HttpSourceConnectorConfig config) {
        return config.originalsStrings().get("ssl.truststore.path");
    }
    
    private String getTrustStorePassword(HttpSourceConnectorConfig config) {
        return config.originalsStrings().get("ssl.truststore.password");
    }
    
    private String getTrustStoreType(HttpSourceConnectorConfig config) {
        return config.originalsStrings().getOrDefault("ssl.truststore.type", "JKS");
    }
    
    private String[] getEnabledCipherSuites(HttpSourceConnectorConfig config) {
        String ciphers = config.originalsStrings().get("ssl.enabled.cipher.suites");
        if (ciphers != null && !ciphers.trim().isEmpty()) {
            return ciphers.split(",");
        }
        return null;
    }
    
    private boolean isHostnameVerificationEnabled(HttpSourceConnectorConfig config) {
        String value = config.originalsStrings().get("ssl.hostname.verification");
        return value != null ? Boolean.parseBoolean(value) : true;
    }
    
    private int getCertificateRefreshInterval(HttpSourceConnectorConfig config) {
        String value = config.originalsStrings().get("ssl.certificate.refresh.interval.ms");
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                log.warn("Invalid certificate refresh interval: {}, using default 3600000ms", value);
            }
        }
        return 3600000; // 1 hour default
    }
    
    public void close() {
        log.info("SSL manager shutting down");
        sslContextCache.clear();
        certificatePins.clear();
    }
    
    // Getters
    public boolean isSSLEnabled() {
        return sslEnabled;
    }
    
    public SSLVersion getSSLVersion() {
        return sslVersion;
    }
    
    public CertificateValidation getCertificateValidation() {
        return certificateValidation;
    }
    
    public boolean isHostnameVerificationEnabled() {
        return hostnameVerification;
    }
}
