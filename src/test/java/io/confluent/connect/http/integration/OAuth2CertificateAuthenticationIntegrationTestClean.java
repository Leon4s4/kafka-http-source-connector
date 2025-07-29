package io.confluent.connect.http.integration;

import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for OAuth2 Certificate-based Authentication
 * 
 * This test suite provides additional validation for OAuth2 certificate authentication.
 */
public class OAuth2CertificateAuthenticationIntegrationTestClean {
    
    private static final Logger log = LoggerFactory.getLogger(OAuth2CertificateAuthenticationIntegrationTestClean.class);
    
    @Test
    @DisplayName("OAuth2 Certificate Configuration - Validation Test")
    void testOAuth2CertificateConfiguration() {
        log.info("Testing OAuth2 certificate configuration validation");
        
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
        config.put("oauth2.client.id", "test-client-id");
        config.put("oauth2.client.certificate.path", "/path/to/certificate.pfx");
        config.put("oauth2.client.certificate.password", "cert-password");
        config.put("oauth2.token.property", "access_token");
        config.put("oauth2.client.scope", "read write");
        
        HttpSourceConnectorConfig sourceConfig = new HttpSourceConnectorConfig(config);
        
        // Verify configuration is correctly parsed
        assertThat(sourceConfig.getAuthType()).isEqualTo(HttpSourceConnectorConfig.AuthType.OAUTH2);
        assertThat(sourceConfig.getOauth2ClientAuthMode()).isEqualTo(HttpSourceConnectorConfig.OAuth2ClientAuthMode.CERTIFICATE);
        assertThat(sourceConfig.getOauth2ClientCertificatePath()).isEqualTo("/path/to/certificate.pfx");
        assertThat(sourceConfig.getOauth2ClientCertificatePassword()).isEqualTo("cert-password");
        
        log.info("✅ OAuth2 certificate configuration validation test completed");
    }
}
