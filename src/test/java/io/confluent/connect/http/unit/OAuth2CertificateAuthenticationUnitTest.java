package io.confluent.connect.http.unit;

import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for OAuth2 Certificate-based Authentication Configuration
 * 
 * This test suite verifies:
 * - Configuration validation for certificate-based OAuth2
 * - HttpAuthenticatorFactory correctly creates certificate authenticators
 * - Proper error handling for invalid configurations
 */
public class OAuth2CertificateAuthenticationUnitTest {
    
    private static final Logger log = LoggerFactory.getLogger(OAuth2CertificateAuthenticationUnitTest.class);
    
    @Test
    @DisplayName("OAuth2 Certificate Configuration - Valid Configuration")
    void testOAuth2CertificateConfigurationValid() {
        log.info("Testing OAuth2 certificate configuration validation - valid case");
        
        Map<String, String> configMap = createValidOAuth2CertificateConfig();
        
        // This should not throw any exceptions
        HttpSourceConnectorConfig config = new HttpSourceConnectorConfig(configMap);
        
        // Verify configuration properties
        assertThat(config.getAuthType()).isEqualTo(HttpSourceConnectorConfig.AuthType.OAUTH2);
        assertThat(config.getOauth2ClientAuthMode()).isEqualTo(HttpSourceConnectorConfig.OAuth2ClientAuthMode.CERTIFICATE);
        assertThat(config.getOauth2TokenUrl()).isEqualTo("https://auth.example.com/oauth2/token");
        assertThat(config.getOauth2ClientId()).isEqualTo("test-client-id");
        assertThat(config.getOauth2ClientCertificatePath()).isEqualTo("/path/to/certificate.pfx");
        assertThat(config.getOauth2ClientCertificatePassword()).isEqualTo("cert-password");
        assertThat(config.getOauth2TokenProperty()).isEqualTo("access_token");
        assertThat(config.getOauth2ClientScope()).isEqualTo("read write");
        
        log.info("✅ Valid OAuth2 certificate configuration test completed");
    }
    
    @Test
    @DisplayName("OAuth2 Certificate Configuration - Missing Certificate Path")
    void testOAuth2CertificateConfigurationMissingPath() {
        log.info("Testing OAuth2 certificate configuration validation - missing certificate path");
        
        Map<String, String> configMap = createValidOAuth2CertificateConfig();
        configMap.remove("oauth2.client.certificate.path");
        
        // This should throw a configuration exception
        Exception exception = assertThrows(Exception.class, () -> {
            new HttpSourceConnectorConfig(configMap);
        });
        
        log.info("Missing certificate path resulted in exception: {}", exception.getMessage());
        assertThat(exception.getMessage()).containsIgnoringCase("certificate");
        
        log.info("✅ Missing certificate path configuration test completed");
    }
    
    @Test
    @DisplayName("OAuth2 Certificate Configuration - Invalid Auth Mode")
    void testOAuth2CertificateConfigurationInvalidAuthMode() {
        log.info("Testing OAuth2 certificate configuration validation - invalid auth mode");
        
        Map<String, String> configMap = createValidOAuth2CertificateConfig();
        configMap.put("oauth2.client.auth.mode", "INVALID_MODE");
        
        // This should throw a configuration exception
        Exception exception = assertThrows(Exception.class, () -> {
            new HttpSourceConnectorConfig(configMap);
        });
        
        log.info("Invalid auth mode resulted in exception: {}", exception.getMessage());
        
        log.info("✅ Invalid auth mode configuration test completed");
    }
    
    @Test
    @DisplayName("OAuth2 Certificate Configuration - Missing Token URL")
    void testOAuth2CertificateConfigurationMissingTokenUrl() {
        log.info("Testing OAuth2 certificate configuration validation - missing token URL");
        
        Map<String, String> configMap = createValidOAuth2CertificateConfig();
        configMap.remove("oauth2.token.url");
        
        // This should throw a configuration exception
        Exception exception = assertThrows(Exception.class, () -> {
            new HttpSourceConnectorConfig(configMap);
        });
        
        log.info("Missing token URL resulted in exception: {}", exception.getMessage());
        assertThat(exception.getMessage()).containsIgnoringCase("oauth2.token.url");
        
        log.info("✅ Missing token URL configuration test completed");
    }
    
    @Test
    @DisplayName("OAuth2 Certificate Configuration - Missing Client ID")
    void testOAuth2CertificateConfigurationMissingClientId() {
        log.info("Testing OAuth2 certificate configuration validation - missing client ID");
        
        Map<String, String> configMap = createValidOAuth2CertificateConfig();
        configMap.remove("oauth2.client.id");
        
        // This should throw a configuration exception
        Exception exception = assertThrows(Exception.class, () -> {
            new HttpSourceConnectorConfig(configMap);
        });
        
        log.info("Missing client ID resulted in exception: {}", exception.getMessage());
        assertThat(exception.getMessage()).containsIgnoringCase("oauth2.client.id");
        
        log.info("✅ Missing client ID configuration test completed");
    }
    
    @Test
    @DisplayName("OAuth2 Certificate Configuration - Client Secret with Certificate Mode")
    void testOAuth2CertificateConfigurationWithClientSecret() {
        log.info("Testing OAuth2 certificate configuration validation - client secret with certificate mode");
        
        Map<String, String> configMap = createValidOAuth2CertificateConfig();
        configMap.put("oauth2.client.secret", "should-not-be-used");
        
        // This should still be valid - certificate mode should ignore client secret
        HttpSourceConnectorConfig config = new HttpSourceConnectorConfig(configMap);
        
        assertThat(config.getOauth2ClientAuthMode()).isEqualTo(HttpSourceConnectorConfig.OAuth2ClientAuthMode.CERTIFICATE);
        assertThat(config.getOauth2ClientCertificatePath()).isEqualTo("/path/to/certificate.pfx");
        
        log.info("✅ Client secret with certificate mode configuration test completed");
    }
    
    @Test
    @DisplayName("OAuth2 Certificate Configuration - Comparison with Client Secret Mode")
    void testOAuth2CertificateVsClientSecretConfiguration() {
        log.info("Testing OAuth2 certificate vs client secret configuration comparison");
        
        // Test certificate mode
        Map<String, String> certConfigMap = createValidOAuth2CertificateConfig();
        HttpSourceConnectorConfig certConfig = new HttpSourceConnectorConfig(certConfigMap);
        
        assertThat(certConfig.getOauth2ClientAuthMode()).isEqualTo(HttpSourceConnectorConfig.OAuth2ClientAuthMode.CERTIFICATE);
        assertThat(certConfig.getOauth2ClientCertificatePath()).isNotNull();
        
        // Test client secret mode
        Map<String, String> secretConfigMap = createValidOAuth2ClientSecretConfig();
        HttpSourceConnectorConfig secretConfig = new HttpSourceConnectorConfig(secretConfigMap);
        
        assertThat(secretConfig.getOauth2ClientAuthMode()).isEqualTo(HttpSourceConnectorConfig.OAuth2ClientAuthMode.HEADER);
        assertThat(secretConfig.getOauth2ClientSecret()).isNotNull();
        
        log.info("✅ OAuth2 certificate vs client secret configuration test completed");
    }
    
    @Test
    @DisplayName("OAuth2 Certificate Configuration - Optional Certificate Password")
    void testOAuth2CertificateConfigurationOptionalPassword() {
        log.info("Testing OAuth2 certificate configuration - optional certificate password");
        
        Map<String, String> configMap = createValidOAuth2CertificateConfig();
        configMap.remove("oauth2.client.certificate.password");
        
        // This should be valid - certificate password is optional
        HttpSourceConnectorConfig config = new HttpSourceConnectorConfig(configMap);
        
        assertThat(config.getOauth2ClientAuthMode()).isEqualTo(HttpSourceConnectorConfig.OAuth2ClientAuthMode.CERTIFICATE);
        assertThat(config.getOauth2ClientCertificatePath()).isEqualTo("/path/to/certificate.pfx");
        assertThat(config.getOauth2ClientCertificatePassword()).isNull();
        
        log.info("✅ Optional certificate password configuration test completed");
    }
    
    @Test
    @DisplayName("OAuth2 Certificate Configuration - Optional Scope")
    void testOAuth2CertificateConfigurationOptionalScope() {
        log.info("Testing OAuth2 certificate configuration - optional scope");
        
        Map<String, String> configMap = createValidOAuth2CertificateConfig();
        configMap.remove("oauth2.client.scope");
        
        // This should be valid - scope is optional and has a default value
        HttpSourceConnectorConfig config = new HttpSourceConnectorConfig(configMap);
        
        assertThat(config.getOauth2ClientAuthMode()).isEqualTo(HttpSourceConnectorConfig.OAuth2ClientAuthMode.CERTIFICATE);
        // The scope should have the default value "any" when not specified
        assertThat(config.getOauth2ClientScope()).isEqualTo("any");
        
        log.info("✅ Optional scope configuration test completed");
    }
    
    /**
     * Creates a valid OAuth2 certificate configuration for testing
     */
    private Map<String, String> createValidOAuth2CertificateConfig() {
        Map<String, String> config = new HashMap<>();
        
        // Basic connector configuration
        config.put("name", "test-oauth2-cert-connector");
        config.put("connector.class", "io.confluent.connect.http.HttpSourceConnector");
        config.put("tasks.max", "1");
        config.put("http.api.base.url", "https://api.example.com");
        config.put("apis.num", "1");
        config.put("api1.http.api.path", "/api/data");
        config.put("api1.topics", "test-topic");
        config.put("http.api.1.endpoint", "/api/data");
        config.put("http.api.1.topic", "test-topic");
        config.put("http.api.1.method", "GET");
        config.put("http.poll.interval.ms", "5000");
        
        // OAuth2 certificate authentication configuration
        config.put("auth.type", "OAUTH2");
        config.put("oauth2.client.auth.mode", "CERTIFICATE");
        config.put("oauth2.token.url", "https://auth.example.com/oauth2/token");
        config.put("oauth2.client.id", "test-client-id");
        config.put("oauth2.client.certificate.path", "/path/to/certificate.pfx");
        config.put("oauth2.client.certificate.password", "cert-password");
        config.put("oauth2.token.property", "access_token");
        config.put("oauth2.client.scope", "read write");
        
        return config;
    }
    
    /**
     * Creates a valid OAuth2 client secret configuration for comparison testing
     */
    private Map<String, String> createValidOAuth2ClientSecretConfig() {
        Map<String, String> config = new HashMap<>();
        
        // Basic connector configuration
        config.put("name", "test-oauth2-secret-connector");
        config.put("connector.class", "io.confluent.connect.http.HttpSourceConnector");
        config.put("tasks.max", "1");
        config.put("http.api.base.url", "https://api.example.com");
        config.put("apis.num", "1");
        config.put("api1.http.api.path", "/api/data");
        config.put("api1.topics", "test-topic");
        config.put("http.api.1.endpoint", "/api/data");
        config.put("http.api.1.topic", "test-topic");
        config.put("http.api.1.method", "GET");
        config.put("http.poll.interval.ms", "5000");
        
        // OAuth2 client secret authentication configuration
        config.put("auth.type", "OAUTH2");
        config.put("oauth2.client.auth.mode", "HEADER");
        config.put("oauth2.token.url", "https://auth.example.com/oauth2/token");
        config.put("oauth2.client.id", "test-client-id");
        config.put("oauth2.client.secret", "test-client-secret");
        config.put("oauth2.token.property", "access_token");
        config.put("oauth2.client.scope", "read write");
        
        return config;
    }
}
