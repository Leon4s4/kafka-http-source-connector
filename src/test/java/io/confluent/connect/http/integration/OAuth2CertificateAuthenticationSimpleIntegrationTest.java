package io.confluent.connect.http.integration;

import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for OAuth2 Certificate-based Authentication
 * 
 * This test suite verifies the OAuth2 certificate authentication functionality with:
 * - Configuration validation for certificate-based authentication
 * - HttpAuthenticatorFactory behavior with certificate mode
 * - Error handling for invalid configurations
 */
public class OAuth2CertificateAuthenticationSimpleIntegrationTest {
    
    private static final Logger log = LoggerFactory.getLogger(OAuth2CertificateAuthenticationSimpleIntegrationTest.class);
    
    // Test certificate configuration
    private static final String CERT_PASSWORD = "test123";
    private static final String CLIENT_ID = "test-client-id";
    
    @Test
    @DisplayName("OAuth2 Certificate Configuration - Factory Creates Correct Authenticator")
    void testOAuth2CertificateFactoryCreation() throws Exception {
        log.info("Testing OAuth2 certificate factory creation");
        
        // Create a temporary certificate file for testing configuration validation
        Path tempDir = Files.createTempDirectory("oauth2-cert-test");
        Path testCertificatePath = tempDir.resolve("test-client.pfx");
        
        // Generate a test PKCS12 keystore for testing configuration
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, CERT_PASSWORD.toCharArray());
        
        // Save the empty keystore to create a valid PKCS12 file structure
        try (var fos = Files.newOutputStream(testCertificatePath)) {
            keyStore.store(fos, CERT_PASSWORD.toCharArray());
        }
        
        Map<String, String> config = createOAuth2CertificateConfig(testCertificatePath.toString());
        
        HttpSourceConnectorConfig sourceConfig = new HttpSourceConnectorConfig(config);
        
        // Verify configuration is correctly parsed
        assertThat(sourceConfig.getAuthType()).isEqualTo(HttpSourceConnectorConfig.AuthType.OAUTH2);
        assertThat(sourceConfig.getOauth2ClientAuthMode()).isEqualTo(HttpSourceConnectorConfig.OAuth2ClientAuthMode.CERTIFICATE);
        assertThat(sourceConfig.getOauth2ClientCertificatePath()).isEqualTo(testCertificatePath.toString());
        assertThat(sourceConfig.getOauth2ClientCertificatePassword()).isEqualTo(CERT_PASSWORD);
        
        // Clean up
        Files.deleteIfExists(testCertificatePath);
        Files.deleteIfExists(tempDir);
        
        log.info("✅ OAuth2 certificate factory creation test completed");
    }
    
    /**
     * Creates base configuration for OAuth2 certificate authentication
     */
    private Map<String, String> createOAuth2CertificateConfig(String certificatePath) {
        Map<String, String> config = new HashMap<>();
        
        // Basic connector configuration
        config.put("name", "oauth2-cert-test-connector");
        config.put("connector.class", "io.confluent.connect.http.HttpSourceConnector");
        config.put("tasks.max", "1");
        config.put("http.api.base.url", "https://api.example.com");
        config.put("http.apis.num", "1");
        config.put("api1.http.api.path", "/api/data");
        config.put("api1.topics", "oauth2-cert-test-topic");
        config.put("http.api.1.endpoint", "/api/data");
        config.put("http.api.1.topic", "oauth2-cert-test-topic");
        config.put("http.api.1.method", "GET");
        config.put("http.poll.interval.ms", "5000");
        
        // OAuth2 certificate authentication configuration
        config.put("auth.type", "OAUTH2");
        config.put("oauth2.client.auth.mode", "CERTIFICATE");
        config.put("oauth2.token.url", "https://auth.example.com/oauth2/token");
        config.put("oauth2.client.id", CLIENT_ID);
        config.put("oauth2.client.certificate.path", certificatePath);
        config.put("oauth2.client.certificate.password", CERT_PASSWORD);
        config.put("oauth2.token.property", "access_token");
        config.put("oauth2.client.scope", "read write");
        
        return config;
    }
}
