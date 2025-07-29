package io.confluent.connect.http.unit;

import io.confluent.connect.http.config.ApiConfig;
import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import io.confluent.connect.http.offset.ODataOffsetManager;
import org.apache.kafka.connect.source.SourceTaskContext;
import org.apache.kafka.connect.storage.OffsetStorageReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import org.mockito.quality.Strictness;

/**
 * Unit tests for ODataOffsetManager
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ODataOffsetManagerTest {

    @Mock
    private SourceTaskContext mockContext;
    
    @Mock
    private OffsetStorageReader mockOffsetStorageReader;
    
    private HttpSourceConnectorConfig globalConfig;
    private ApiConfig apiConfig;
    private ODataOffsetManager offsetManager;
    
    @BeforeEach
    void setUp() {
        // Setup basic configuration
        Map<String, String> configProps = new HashMap<>();
        configProps.put("http.api.base.url", "https://api.example.com");
        configProps.put("apis.num", "1");
        configProps.put("api1.http.api.path", "/api/data/v9.0/accounts");
        configProps.put("api1.topics", "accounts-topic");
        configProps.put("api1.http.offset.mode", "ODATA_PAGINATION");
        configProps.put("api1.http.initial.offset", "?$select=name,accountnumber&$filter=modifiedon ge '2025-01-01'");
        configProps.put("api1.odata.nextlink.field", "@odata.nextLink");
        configProps.put("api1.odata.deltalink.field", "@odata.deltaLink");
        configProps.put("api1.odata.token.mode", "FULL_URL");
        
        globalConfig = new HttpSourceConnectorConfig(configProps);
        apiConfig = new ApiConfig(globalConfig, 1);
        
        // Setup mock context
        when(mockContext.offsetStorageReader()).thenReturn(mockOffsetStorageReader);
    }
    
    @Test
    void testInitializationWithFullUrlMode() {
        offsetManager = new ODataOffsetManager(apiConfig, mockContext);
        
        assertThat(offsetManager.getOffsetMode()).isEqualTo(ApiConfig.HttpOffsetMode.ODATA_PAGINATION);
        assertThat(offsetManager.getNextLinkField()).isEqualTo("@odata.nextLink");
        assertThat(offsetManager.getDeltaLinkField()).isEqualTo("@odata.deltaLink");
        assertThat(offsetManager.getTokenMode()).isEqualTo(ODataOffsetManager.ODataTokenMode.FULL_URL);
        assertThat(offsetManager.getCurrentOffset()).isEqualTo("?$select=name,accountnumber&$filter=modifiedon ge '2025-01-01'");
    }
    
    @Test
    void testInitializationWithTokenOnlyMode() {
        Map<String, String> configProps = new HashMap<>();
        configProps.put("http.api.base.url", "https://api.example.com");
        configProps.put("apis.num", "1");
        configProps.put("api1.http.api.path", "/api/data/v9.0/accounts");
        configProps.put("api1.topics", "accounts-topic");
        configProps.put("api1.http.offset.mode", "ODATA_PAGINATION");
        configProps.put("api1.http.initial.offset", "initial-token");
        configProps.put("api1.odata.nextlink.field", "@odata.nextLink");
        configProps.put("api1.odata.token.mode", "TOKEN_ONLY");
        
        HttpSourceConnectorConfig config = new HttpSourceConnectorConfig(configProps);
        ApiConfig apiConfig = new ApiConfig(config, 1);
        
        offsetManager = new ODataOffsetManager(apiConfig, mockContext);
        
        assertThat(offsetManager.getTokenMode()).isEqualTo(ODataOffsetManager.ODataTokenMode.TOKEN_ONLY);
        assertThat(offsetManager.getCurrentOffset()).isEqualTo("initial-token");
    }
    
    @Test
    void testUpdateOffsetWithFullUrlNextLink() {
        offsetManager = new ODataOffsetManager(apiConfig, mockContext);
        
        String nextLinkUrl = "https://org.api.crm.dynamics.com/api/data/v9.0/accounts?$select=name&$skiptoken=%3Ccookie%20pagenumber=%222%22%20pagingcookie=%22%253ccookie%2520page%253d%25221%2522%253e%253caccountid%2520last%253d%2522%257bAAA19CDD-88DF-E311-B8E5-6C3BE5A8B200%257d%2522%2520first%253d%2522%257b475B158C-541C-E511-80D3-3863BB347BA8%257d%2522%2520%252f%253e%253c%252fcookie%253e%22%20istracking=%22False%22%20/%3E";
        
        offsetManager.updateOffset(nextLinkUrl);
        
        // Should extract path and query from full URL
        assertThat(offsetManager.getCurrentOffset()).isEqualTo("/api/data/v9.0/accounts?$select=name&$skiptoken=%3Ccookie%20pagenumber=%222%22%20pagingcookie=%22%253ccookie%2520page%253d%25221%2522%253e%253caccountid%2520last%253d%2522%257bAAA19CDD-88DF-E311-B8E5-6C3BE5A8B200%257d%2522%2520first%253d%2522%257b475B158C-541C-E511-80D3-3863BB347BA8%257d%2522%2520%252f%253e%253c%252fcookie%253e%22%20istracking=%22False%22%20/%3E");
        assertThat(offsetManager.hasMorePages()).isTrue();
    }
    
    @Test
    void testUpdateOffsetWithFullUrlDeltaLink() {
        offsetManager = new ODataOffsetManager(apiConfig, mockContext);
        
        String deltaLinkUrl = "https://org.api.crm.dynamics.com/api/data/v9.0/accounts?$select=name,accountnumber,telephone1,fax&$deltatoken=919042%2108%2f22%2f2017%2008%3a10%3a44";
        
        offsetManager.updateOffset(deltaLinkUrl);
        
        // Should extract path and query from full URL
        assertThat(offsetManager.getCurrentOffset()).isEqualTo("/api/data/v9.0/accounts?$select=name,accountnumber,telephone1,fax&$deltatoken=919042%2108%2f22%2f2017%2008%3a10%3a44");
        assertThat(offsetManager.hasMorePages()).isTrue();
    }
    
    @Test
    void testUpdateOffsetWithTokenOnlyMode() {
        Map<String, String> configProps = new HashMap<>();
        configProps.put("http.api.base.url", "https://api.example.com");
        configProps.put("apis.num", "1");
        configProps.put("api1.http.api.path", "/api/data/v9.0/accounts");
        configProps.put("api1.topics", "accounts-topic");
        configProps.put("api1.http.offset.mode", "ODATA_PAGINATION");
        configProps.put("api1.http.initial.offset", "");
        configProps.put("api1.odata.nextlink.field", "@odata.nextLink");
        configProps.put("api1.odata.token.mode", "TOKEN_ONLY");
        
        HttpSourceConnectorConfig config = new HttpSourceConnectorConfig(configProps);
        ApiConfig apiConfig = new ApiConfig(config, 1);
        offsetManager = new ODataOffsetManager(apiConfig, mockContext);
        
        String nextLinkUrl = "https://org.api.crm.dynamics.com/api/data/v9.0/accounts?$select=name&$skiptoken=some-encoded-token";
        
        offsetManager.updateOffset(nextLinkUrl);
        
        // Should extract only the token value
        assertThat(offsetManager.getCurrentOffset()).isEqualTo("some-encoded-token");
        assertThat(offsetManager.hasMorePages()).isTrue();
    }
    
    @Test
    void testUpdateOffsetWithDeltaTokenInTokenOnlyMode() {
        Map<String, String> configProps = new HashMap<>();
        configProps.put("http.api.base.url", "https://api.example.com");
        configProps.put("apis.num", "1");
        configProps.put("api1.http.api.path", "/api/data/v9.0/accounts");
        configProps.put("api1.topics", "accounts-topic");
        configProps.put("api1.http.offset.mode", "ODATA_PAGINATION");
        configProps.put("api1.http.initial.offset", "");
        configProps.put("api1.odata.nextlink.field", "@odata.nextLink");
        configProps.put("api1.odata.token.mode", "TOKEN_ONLY");
        
        HttpSourceConnectorConfig config = new HttpSourceConnectorConfig(configProps);
        ApiConfig apiConfig = new ApiConfig(config, 1);
        offsetManager = new ODataOffsetManager(apiConfig, mockContext);
        
        String deltaLinkUrl = "https://org.api.crm.dynamics.com/api/data/v9.0/accounts?$select=name&$deltatoken=919042%2108%2f22%2f2017%2008%3a10%3a44";
        
        offsetManager.updateOffset(deltaLinkUrl);
        
        // Should extract only the delta token value (URL decoded)
        assertThat(offsetManager.getCurrentOffset()).isEqualTo("919042!08/22/2017 08:10:44");
        assertThat(offsetManager.hasMorePages()).isTrue();
    }
    
    @Test
    void testUpdateOffsetWithNullValue() {
        offsetManager = new ODataOffsetManager(apiConfig, mockContext);
        
        offsetManager.updateOffset(null);
        
        assertThat(offsetManager.getCurrentOffset()).isNull();
        assertThat(offsetManager.hasMorePages()).isFalse();
    }
    
    @Test
    void testUpdateOffsetWithEmptyValue() {
        offsetManager = new ODataOffsetManager(apiConfig, mockContext);
        
        offsetManager.updateOffset("");
        
        assertThat(offsetManager.getCurrentOffset()).isNull();
        assertThat(offsetManager.hasMorePages()).isFalse();
    }
    
    @Test
    void testResetOffset() {
        offsetManager = new ODataOffsetManager(apiConfig, mockContext);
        
        // Update to some value
        offsetManager.updateOffset("https://api.example.com/accounts?$skiptoken=abc");
        assertThat(offsetManager.getCurrentOffset()).isNotEqualTo("?$select=name,accountnumber&$filter=modifiedon ge '2025-01-01'");
        
        // Reset should restore initial value
        offsetManager.resetOffset();
        assertThat(offsetManager.getCurrentOffset()).isEqualTo("?$select=name,accountnumber&$filter=modifiedon ge '2025-01-01'");
    }
    
    @Test
    void testBuildNextRequestUrlWithFullUrlMode() {
        offsetManager = new ODataOffsetManager(apiConfig, mockContext);
        
        // Initially it should return the current offset (initial offset)
        assertThat(offsetManager.buildNextRequestUrl()).isEqualTo("?$select=name,accountnumber&$filter=modifiedon ge '2025-01-01'");
        
        // After getting a next link, should use the path from offset
        offsetManager.updateOffset("https://api.example.com/api/data/v9.0/accounts?$skiptoken=abc");
        assertThat(offsetManager.buildNextRequestUrl()).isEqualTo("/api/data/v9.0/accounts?$skiptoken=abc");
    }
    
    @Test
    void testBuildNextRequestUrlWithTokenOnlyMode() {
        Map<String, String> configProps = new HashMap<>();
        configProps.put("http.api.base.url", "https://api.example.com");
        configProps.put("apis.num", "1");
        configProps.put("api1.http.api.path", "/api/data/v9.0/accounts?$select=name");
        configProps.put("api1.topics", "accounts-topic");
        configProps.put("api1.http.offset.mode", "ODATA_PAGINATION");
        configProps.put("api1.http.initial.offset", "");
        configProps.put("api1.odata.nextlink.field", "@odata.nextLink");
        configProps.put("api1.odata.token.mode", "TOKEN_ONLY");
        
        HttpSourceConnectorConfig config = new HttpSourceConnectorConfig(configProps);
        ApiConfig apiConfig = new ApiConfig(config, 1);
        offsetManager = new ODataOffsetManager(apiConfig, mockContext);
        
        // Initial request should use the API path
        assertThat(offsetManager.buildNextRequestUrl()).isEqualTo("/api/data/v9.0/accounts?$select=name");
        
        // After getting a skiptoken, should append it to base path
        offsetManager.updateOffset("https://api.example.com/accounts?$skiptoken=abc123");
        assertThat(offsetManager.buildNextRequestUrl()).isEqualTo("/api/data/v9.0/accounts?$select=name&$skiptoken=abc123");
        
        // Test with deltatoken
        offsetManager.updateOffset("https://api.example.com/accounts?$deltatoken=def456");
        assertThat(offsetManager.buildNextRequestUrl()).isEqualTo("/api/data/v9.0/accounts?$select=name&$deltatoken=def456");
    }
    
    @Test
    void testLoadExistingOffsetFromStorage() {
        Map<String, Object> storedOffset = new HashMap<>();
        storedOffset.put("offset", "/api/data/v9.0/accounts?$skiptoken=stored-token");
        
        when(mockOffsetStorageReader.offset(any())).thenReturn(storedOffset);
        
        offsetManager = new ODataOffsetManager(apiConfig, mockContext);
        
        // Should load the stored offset instead of initial offset
        assertThat(offsetManager.getCurrentOffset()).isEqualTo("/api/data/v9.0/accounts?$skiptoken=stored-token");
    }
    
    @Test
    void testHandleMalformedUrl() {
        offsetManager = new ODataOffsetManager(apiConfig, mockContext);
        
        String malformedUrl = "not-a-valid-url";
        
        // Should handle gracefully and use the input as-is
        offsetManager.updateOffset(malformedUrl);
        assertThat(offsetManager.getCurrentOffset()).isEqualTo(malformedUrl);
    }
    
    @Test
    void testValidationFailsWithoutNextLinkField() {
        // This test doesn't use mocks, so create separate config
        Map<String, String> configProps = new HashMap<>();
        configProps.put("http.api.base.url", "https://api.example.com");
        configProps.put("apis.num", "1");
        configProps.put("api1.http.api.path", "/api/data/v9.0/accounts");
        configProps.put("api1.topics", "accounts-topic");
        configProps.put("api1.http.offset.mode", "ODATA_PAGINATION");
        configProps.put("api1.http.initial.offset", "");
        // Missing odata.nextlink.field
        
        HttpSourceConnectorConfig config = new HttpSourceConnectorConfig(configProps);
        
        assertThatThrownBy(() -> new ApiConfig(config, 1))
            .isInstanceOf(org.apache.kafka.common.config.ConfigException.class)
            .hasMessageContaining("odata.nextlink.field must be set for ODATA_PAGINATION mode");
    }
    
    @Test
    void testCustomFieldNames() {
        Map<String, String> configProps = new HashMap<>();
        configProps.put("http.api.base.url", "https://api.example.com");
        configProps.put("apis.num", "1");
        configProps.put("api1.http.api.path", "/api/data/v9.0/accounts");
        configProps.put("api1.topics", "accounts-topic");
        configProps.put("api1.http.offset.mode", "ODATA_PAGINATION");
        configProps.put("api1.http.initial.offset", "");
        configProps.put("api1.odata.nextlink.field", "custom.nextLink");
        configProps.put("api1.odata.deltalink.field", "custom.deltaLink");
        configProps.put("api1.odata.token.mode", "FULL_URL");
        
        HttpSourceConnectorConfig config = new HttpSourceConnectorConfig(configProps);
        ApiConfig apiConfig = new ApiConfig(config, 1);
        offsetManager = new ODataOffsetManager(apiConfig, mockContext);
        
        assertThat(offsetManager.getNextLinkField()).isEqualTo("custom.nextLink");
        assertThat(offsetManager.getDeltaLinkField()).isEqualTo("custom.deltaLink");
    }
    
    @Test
    void testSourcePartitionCreation() {
        offsetManager = new ODataOffsetManager(apiConfig, mockContext);
        
        Map<String, String> sourcePartition = offsetManager.getSourcePartition();
        assertThat(sourcePartition).containsEntry("url", "https://api.example.com/api/data/v9.0/accounts");
    }
    
    @Test
    void testConfigurableSkipTokenParameter() {
        Map<String, String> configProps = new HashMap<>();
        configProps.put("http.api.base.url", "https://api.example.com");
        configProps.put("apis.num", "1");
        configProps.put("api1.http.api.path", "/api/data/v9.0/accounts");
        configProps.put("api1.topics", "accounts-topic");
        configProps.put("api1.http.offset.mode", "ODATA_PAGINATION");
        configProps.put("api1.http.initial.offset", "");
        configProps.put("api1.odata.nextlink.field", "@odata.nextLink");
        configProps.put("api1.odata.token.mode", "TOKEN_ONLY");
        configProps.put("api1.odata.skiptoken.param", "customSkipToken");
        
        HttpSourceConnectorConfig config = new HttpSourceConnectorConfig(configProps);
        ApiConfig apiConfig = new ApiConfig(config, 1);
        offsetManager = new ODataOffsetManager(apiConfig, mockContext);
        
        // Test that custom skiptoken parameter is used
        assertThat(offsetManager.getSkipTokenParam()).isEqualTo("customSkipToken");
        
        // Test URL with custom skiptoken parameter
        String urlWithCustomToken = "https://api.example.com/accounts?customSkipToken=abc123";
        offsetManager.updateOffset(urlWithCustomToken);
        
        assertThat(offsetManager.getCurrentOffset()).isEqualTo("abc123");
        
        // Test buildNextRequestUrl with custom parameter
        String nextUrl = offsetManager.buildNextRequestUrl();
        assertThat(nextUrl).contains("customSkipToken=abc123");
    }
    
    @Test
    void testConfigurableDeltaTokenParameter() {
        Map<String, String> configProps = new HashMap<>();
        configProps.put("http.api.base.url", "https://api.example.com");
        configProps.put("apis.num", "1");
        configProps.put("api1.http.api.path", "/api/data/v9.0/accounts");
        configProps.put("api1.topics", "accounts-topic");
        configProps.put("api1.http.offset.mode", "ODATA_PAGINATION");
        configProps.put("api1.http.initial.offset", "");
        configProps.put("api1.odata.nextlink.field", "@odata.nextLink");
        configProps.put("api1.odata.token.mode", "TOKEN_ONLY");
        configProps.put("api1.odata.deltatoken.param", "customDeltaToken");
        
        HttpSourceConnectorConfig config = new HttpSourceConnectorConfig(configProps);
        ApiConfig apiConfig = new ApiConfig(config, 1);
        offsetManager = new ODataOffsetManager(apiConfig, mockContext);
        
        // Test that custom deltatoken parameter is used
        assertThat(offsetManager.getDeltaTokenParam()).isEqualTo("customDeltaToken");
        
        // Test URL with custom deltatoken parameter
        String urlWithCustomToken = "https://api.example.com/accounts?customDeltaToken=delta123";
        offsetManager.updateOffset(urlWithCustomToken);
        
        assertThat(offsetManager.getCurrentOffset()).isEqualTo("delta123");
        
        // Test buildNextRequestUrl with custom parameter
        String nextUrl = offsetManager.buildNextRequestUrl();
        assertThat(nextUrl).contains("customDeltaToken=delta123");
    }
    
    @Test
    void testBothCustomTokenParameters() {
        Map<String, String> configProps = new HashMap<>();
        configProps.put("http.api.base.url", "https://api.example.com");
        configProps.put("apis.num", "1");
        configProps.put("api1.http.api.path", "/api/data/v9.0/accounts?$select=name");
        configProps.put("api1.topics", "accounts-topic");
        configProps.put("api1.http.offset.mode", "ODATA_PAGINATION");
        configProps.put("api1.http.initial.offset", "");
        configProps.put("api1.odata.nextlink.field", "@odata.nextLink");
        configProps.put("api1.odata.token.mode", "TOKEN_ONLY");
        configProps.put("api1.odata.skiptoken.param", "mySkipToken");
        configProps.put("api1.odata.deltatoken.param", "myDeltaToken");
        
        HttpSourceConnectorConfig config = new HttpSourceConnectorConfig(configProps);
        ApiConfig apiConfig = new ApiConfig(config, 1);
        offsetManager = new ODataOffsetManager(apiConfig, mockContext);
        
        // Test skiptoken extraction and URL building
        offsetManager.updateOffset("https://api.example.com/accounts?mySkipToken=skip123");
        assertThat(offsetManager.getCurrentOffset()).isEqualTo("skip123");
        assertThat(offsetManager.buildNextRequestUrl()).isEqualTo("/api/data/v9.0/accounts?$select=name&mySkipToken=skip123");
        
        // Test deltatoken extraction and URL building
        offsetManager.updateOffset("https://api.example.com/accounts?myDeltaToken=delta456");
        assertThat(offsetManager.getCurrentOffset()).isEqualTo("delta456");
        assertThat(offsetManager.buildNextRequestUrl()).isEqualTo("/api/data/v9.0/accounts?$select=name&myDeltaToken=delta456");
    }
    
    @Test
    void testSpecialCharactersInTokenParameters() {
        Map<String, String> configProps = new HashMap<>();
        configProps.put("http.api.base.url", "https://api.example.com");
        configProps.put("apis.num", "1");
        configProps.put("api1.http.api.path", "/api/data/v9.0/accounts");
        configProps.put("api1.topics", "accounts-topic");
        configProps.put("api1.http.offset.mode", "ODATA_PAGINATION");
        configProps.put("api1.http.initial.offset", "");
        configProps.put("api1.odata.nextlink.field", "@odata.nextLink");
        configProps.put("api1.odata.token.mode", "TOKEN_ONLY");
        configProps.put("api1.odata.skiptoken.param", "skip-token");
        configProps.put("api1.odata.deltatoken.param", "delta_token");
        
        HttpSourceConnectorConfig config = new HttpSourceConnectorConfig(configProps);
        ApiConfig apiConfig = new ApiConfig(config, 1);
        offsetManager = new ODataOffsetManager(apiConfig, mockContext);
        
        // Test that special characters are properly escaped in patterns
        offsetManager.updateOffset("https://api.example.com/accounts?skip-token=abc123&other=value");
        assertThat(offsetManager.getCurrentOffset()).isEqualTo("abc123");
        
        offsetManager.updateOffset("https://api.example.com/accounts?delta_token=def456&other=value");
        assertThat(offsetManager.getCurrentOffset()).isEqualTo("def456");
    }
    
    @Test
    void testDefaultTokenParametersWhenNotConfigured() {
        offsetManager = new ODataOffsetManager(apiConfig, mockContext);
        
        // Should use default parameter names
        assertThat(offsetManager.getSkipTokenParam()).isEqualTo("$skiptoken");
        assertThat(offsetManager.getDeltaTokenParam()).isEqualTo("$deltatoken");
        
        // Should work with default parameters
        offsetManager.updateOffset("https://api.example.com/accounts?$skiptoken=default123");
        // This test uses FULL_URL mode, so it should extract path and query
        assertThat(offsetManager.getCurrentOffset()).isEqualTo("/accounts?$skiptoken=default123");
    }
    
    @Test
    void testCloseMethod() {
        offsetManager = new ODataOffsetManager(apiConfig, mockContext);
        
        // Should not throw any exception
        assertThatCode(() -> offsetManager.close()).doesNotThrowAnyException();
    }
}