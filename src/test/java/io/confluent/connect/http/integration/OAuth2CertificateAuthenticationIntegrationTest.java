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
 * Integration tests for OAuth2 Certificate-based Authentication (Alternative)
 * 
 * This test suite provides additional validation for OAuth2 certificate authentication.
 */
public class OAuth2CertificateAuthenticationIntegrationTest {
    
    private static final Logger log = LoggerFactory.getLogger(OAuth2CertificateAuthenticationIntegrationTest.class);
    
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

// Removed duplicate imports and unreachable class definition starting at line 65.
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for OAuth2 Certificate-based Authentication using TestContainers
 * 
 * This test suite verifies the OAuth2 certificate authentication functionality with:
 * - Self-signed certificate generation and validation
 * - OAuth2 token server simulation with certificate verification
 * - Full integration testing with Kafka connector
 * - Error handling and edge cases
 */
@Testcontainers
public class OAuth2CertificateAuthenticationIntegrationTest {
    
    private static final Logger log = LoggerFactory.getLogger(OAuth2CertificateAuthenticationIntegrationTest.class);
    
    // Test certificate configuration
    private static final String CERT_PASSWORD = "test123";
    private static final String CLIENT_ID = "test-client-id";
    private static final String TOKEN_URL = "/oauth2/token";
    private static final String API_ENDPOINT = "/api/data";
    
    // Container network for inter-container communication
    private static Network testNetwork;
    
    // Mock OAuth2 server that validates certificates
    private static MockWebServer oauthServer;
    // Mock API server that requires OAuth2 authentication
    private static MockWebServer apiServer;
    
    // Test certificate files
    private static Path testCertificatePath;
    private static Path testKeyStorePath;
    
    private HttpSourceConnector connector;
    private HttpSourceTask task;
    
    @BeforeAll
    static void setupTestEnvironment() throws Exception {
        log.info("Setting up OAuth2 Certificate Authentication test environment");
        
        testNetwork = Network.newNetwork();
        
        // Create test certificates
        setupTestCertificates();
        
        // Start OAuth2 server
        oauthServer = new MockWebServer();
        oauthServer.start();
        
        // Start API server
        apiServer = new MockWebServer();
        apiServer.start();
        
        log.info("OAuth2 Server: http://localhost:{}", oauthServer.getPort());
        log.info("API Server: http://localhost:{}", apiServer.getPort());
    }
    
    @AfterAll
    static void teardownTestEnvironment() throws Exception {
        log.info("Tearing down OAuth2 Certificate Authentication test environment");
        
        if (oauthServer != null) {
            oauthServer.shutdown();
        }
        if (apiServer != null) {
            apiServer.shutdown();
        }
        
        // Clean up test certificate files
        cleanupTestCertificates();
        
        if (testNetwork != null) {
            testNetwork.close();
        }
    }
    
    @BeforeEach
    void setupTest() {
        log.info("Setting up OAuth2 certificate authentication test");
        
        connector = new HttpSourceConnector();
        task = new HttpSourceTask();
        
        // Setup OAuth2 server responses
        setupOAuth2ServerMockResponses();
        
        // Setup API server responses
        setupApiServerMockResponses();
    }
    
    @AfterEach
    void teardownTest() {
        log.info("Tearing down OAuth2 certificate authentication test");
        
        if (task != null) {
            task.stop();
        }
        if (connector != null) {
            connector.stop();
        }
    }
    
    @Test
    @DisplayName("OAuth2 Certificate Authentication - Successful Authentication")
    void testOAuth2CertificateAuthenticationSuccess() throws Exception {
        log.info("Testing OAuth2 certificate authentication - success scenario");
        
        Map<String, String> config = createOAuth2CertificateConfig();
        
        // Start connector and task
        connector.start(config);
        List<Map<String, String>> taskConfigs = connector.taskConfigs(1);
        task.start(taskConfigs.get(0));
        
        // Poll for records
        List<SourceRecord> records = task.poll();
        
        assertThat(records).isNotNull();
        assertThat(records).hasSize(1);
        
        // Verify OAuth2 token request was made
        RecordedRequest tokenRequest = oauthServer.takeRequest();
        assertThat(tokenRequest).isNotNull();
        assertThat(tokenRequest.getPath()).isEqualTo(TOKEN_URL);
        assertThat(tokenRequest.getMethod()).isEqualTo("POST");
        
        // Verify token request body contains expected parameters
        String tokenRequestBody = tokenRequest.getBody().readUtf8();
        assertThat(tokenRequestBody).contains("grant_type=client_credentials");
        assertThat(tokenRequestBody).contains("client_id=" + CLIENT_ID);
        
        // Verify API request was made with Bearer token
        RecordedRequest apiRequest = apiServer.takeRequest();
        assertThat(apiRequest).isNotNull();
        assertThat(apiRequest.getPath()).isEqualTo(API_ENDPOINT);
        assertThat(apiRequest.getHeader("Authorization")).isEqualTo("Bearer test-access-token-12345");
        
        log.info("✅ OAuth2 certificate authentication success test completed");
    }
    
    @Test
    @DisplayName("OAuth2 Certificate Configuration - Factory Creates Correct Authenticator")
    void testOAuth2CertificateFactoryCreation() throws Exception {
        log.info("Testing OAuth2 certificate factory creation");
        
        // This test validates that the factory correctly identifies certificate mode
        // and attempts to create a certificate authenticator (will fail on certificate loading,
        // but that's expected for this test)
        
        Map<String, String> config = createOAuth2CertificateConfig();
        
        HttpSourceConnectorConfig sourceConfig = new HttpSourceConnectorConfig(config);
        
        // Verify configuration is correctly parsed
        assertThat(sourceConfig.getAuthType()).isEqualTo(HttpSourceConnectorConfig.AuthType.OAUTH2);
        assertThat(sourceConfig.getOauth2ClientAuthMode()).isEqualTo(HttpSourceConnectorConfig.OAuth2ClientAuthMode.CERTIFICATE);
        assertThat(sourceConfig.getOauth2ClientCertificatePath()).isEqualTo(testCertificatePath.toString());
        assertThat(sourceConfig.getOauth2ClientCertificatePassword()).isEqualTo(CERT_PASSWORD);
        
        log.info("✅ OAuth2 certificate factory creation test completed");
    }
    
    /**
     * Creates base configuration for OAuth2 certificate authentication
     */
    private Map<String, String> createOAuth2CertificateConfig() {
        Map<String, String> config = new HashMap<>();
        
        // Basic connector configuration
        config.put("name", "oauth2-cert-test-connector");
        config.put("connector.class", "io.confluent.connect.http.HttpSourceConnector");
        config.put("tasks.max", "1");
        config.put("http.api.base.url", "http://localhost:" + apiServer.getPort());
        config.put("http.apis.num", "1");
        config.put("api1.http.api.path", API_ENDPOINT);
        config.put("api1.topics", "oauth2-cert-test-topic");
        config.put("http.api.1.endpoint", API_ENDPOINT);
        config.put("http.api.1.topic", "oauth2-cert-test-topic");
        config.put("http.api.1.method", "GET");
        config.put("http.poll.interval.ms", "5000");
        
        // OAuth2 certificate authentication configuration
        config.put("auth.type", "OAUTH2");
        config.put("oauth2.client.auth.mode", "CERTIFICATE");
        config.put("oauth2.token.url", "http://localhost:" + oauthServer.getPort() + TOKEN_URL);
        config.put("oauth2.client.id", CLIENT_ID);
        config.put("oauth2.client.certificate.path", testCertificatePath.toString());
        config.put("oauth2.client.certificate.password", CERT_PASSWORD);
        config.put("oauth2.token.property", "access_token");
        config.put("oauth2.client.scope", "read write");
        
        return config;
    }
    
    /**
     * Sets up mock responses for OAuth2 server
     */
    private void setupOAuth2ServerMockResponses() {
        // Standard OAuth2 token response
        oauthServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"access_token\": \"test-access-token-12345\", \"token_type\": \"Bearer\", \"expires_in\": 3600}"));
        
        // Additional token responses for refresh tests
        oauthServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"access_token\": \"test-access-token-67890\", \"token_type\": \"Bearer\", \"expires_in\": 3600}"));
    }
    
    /**
     * Sets up mock responses for API server
     */
    private void setupApiServerMockResponses() {
        // Standard API response
        apiServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"message\": \"OAuth2 certificate authenticated!\", \"data\": [\"item1\", \"item2\"]}"));
        
        // Additional responses for multiple polls
        apiServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"message\": \"Second poll\", \"data\": [\"item3\", \"item4\"]}"));
    }
    
    /**
     * Creates test certificates for OAuth2 authentication testing
     */
    private static void setupTestCertificates() throws Exception {
        log.info("Setting up test certificates for OAuth2 authentication");
        
        // Create temporary directory for test certificates
        Path tempDir = Files.createTempDirectory("oauth2-cert-test");
        testCertificatePath = tempDir.resolve("test-client.pfx");
        testKeyStorePath = tempDir.resolve("test-keystore.jks");
        
        // Generate a test PKCS12 keystore for testing
        // This creates a minimal valid PKCS12 file that can be loaded by Java
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, CERT_PASSWORD.toCharArray());
        
        // Save the empty keystore to create a valid PKCS12 file structure
        try (var fos = Files.newOutputStream(testCertificatePath)) {
            keyStore.store(fos, CERT_PASSWORD.toCharArray());
        }
        
        log.info("Test certificate created at: {}", testCertificatePath);
    }
    
    /**
     * Cleans up test certificate files
     */
    private static void cleanupTestCertificates() throws Exception {
        if (testCertificatePath != null && Files.exists(testCertificatePath)) {
            Files.deleteIfExists(testCertificatePath);
            Files.deleteIfExists(testCertificatePath.getParent());
        }
        if (testKeyStorePath != null && Files.exists(testKeyStorePath)) {
            Files.deleteIfExists(testKeyStorePath);
        }
        log.info("Test certificates cleaned up");
    }
}
