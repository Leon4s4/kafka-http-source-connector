package io.confluent.connect.http.unit;

import io.confluent.connect.http.auth.NoAuthenticator;
import io.confluent.connect.http.client.HttpApiClient;
import io.confluent.connect.http.config.ApiConfig;
import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import okhttp3.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify that HttpApiClient correctly builds HTTP requests with template replacement
 * by accessing the private buildRequest method through reflection.
 */
class HttpApiClientRealRequestTest {
    
    @Test
    @DisplayName("Should build HTTP request with correct URL encoding for OData template variables")
    void shouldBuildCorrectHttpRequestWithODataTemplate() throws Exception {
        // Given - OData configuration similar to the real use case
        Map<String, String> configMap = new HashMap<>();
        configMap.put("http.api.base.url", "http://example.com/api/data/v9.2");
        configMap.put("apis.num", "1");
        configMap.put("api1.http.api.path", "/accounts${offset}");
        configMap.put("api1.topics", "test-accounts");
        configMap.put("api1.http.request.method", "GET");
        configMap.put("api1.http.offset.mode", "CURSOR_PAGINATION");
        configMap.put("api1.http.initial.offset", "?$select=name,accountnumber,telephone1,fax&$filter=modifiedon ge '2025-01-01'");
        configMap.put("api1.http.response.data.json.pointer", "/value");
        configMap.put("api1.http.next.page.json.pointer", "/@odata.nextLink");
        configMap.put("connection.timeout.ms", "5000");
        configMap.put("request.timeout.ms", "5000");
        
        HttpSourceConnectorConfig globalConfig = new HttpSourceConnectorConfig(configMap);
        ApiConfig apiConfig = new ApiConfig(globalConfig, 1);
        HttpApiClient client = new HttpApiClient(apiConfig, new NoAuthenticator());
        
        // When - We build a request using the private buildRequest method
        Map<String, String> templateVars = new HashMap<>();
        templateVars.put("offset", "?$select=name,accountnumber,telephone1,fax&$filter=modifiedon ge '2025-01-01'");
        
        // Access the private buildRequest method
        Method buildRequestMethod = HttpApiClient.class.getDeclaredMethod("buildRequest", Map.class);
        buildRequestMethod.setAccessible(true);
        Request request = (Request) buildRequestMethod.invoke(client, templateVars);
        
        // Then - The request should have the correctly encoded URL
        HttpUrl requestUrl = request.url();
        
        System.out.println("Generated request URL: " + requestUrl.toString());
        System.out.println("URL scheme: " + requestUrl.scheme());
        System.out.println("URL host: " + requestUrl.host());
        System.out.println("URL path: " + requestUrl.encodedPath());
        System.out.println("URL query: " + requestUrl.encodedQuery());
        
        // Verify the URL components
        assertEquals("http", requestUrl.scheme());
        assertEquals("example.com", requestUrl.host());
        assertEquals("/api/data/v9.2/accounts", requestUrl.encodedPath());
        
        // Verify the query string is properly URL encoded
        String query = requestUrl.encodedQuery();
        assertNotNull(query, "Query string should not be null");
        
        // The $ characters in OData query parameters are allowed and don't need encoding
        assertTrue(query.contains("$select=name,accountnumber,telephone1,fax"), 
                   "Query should contain $select parameter");
        assertTrue(query.contains("$filter=modifiedon"), 
                   "Query should contain $filter parameter");
        
        // Spaces should be encoded as %20
        assertTrue(query.contains("ge%20'2025-01-01'") || query.contains("ge%20%272025-01-01%27"), 
                   "Query should contain URL-encoded spaces in date filter");
        
        // Verify the request method
        assertEquals("GET", request.method());
        
        // Verify no template variables remain unresolved
        String fullUrl = requestUrl.toString();
        assertFalse(fullUrl.contains("${"), "URL should not contain unresolved template variables");
        assertFalse(fullUrl.contains("$%7B"), "URL should not contain URL-encoded template variables");
        
        client.close();
    }
    
    @Test
    @DisplayName("Should handle empty offset template variable correctly")
    void shouldHandleEmptyOffsetCorrectly() throws Exception {
        // Given - Configuration with template variable but empty offset
        Map<String, String> configMap = new HashMap<>();
        configMap.put("http.api.base.url", "http://example.com");
        configMap.put("apis.num", "1");
        configMap.put("api1.http.api.path", "/data${offset}");
        configMap.put("api1.topics", "test");
        configMap.put("api1.http.request.method", "GET");
        configMap.put("api1.http.offset.mode", "CURSOR_PAGINATION");
        configMap.put("api1.http.initial.offset", "");
        configMap.put("api1.http.next.page.json.pointer", "/nextLink");
        
        HttpSourceConnectorConfig globalConfig = new HttpSourceConnectorConfig(configMap);
        ApiConfig apiConfig = new ApiConfig(globalConfig, 1);
        HttpApiClient client = new HttpApiClient(apiConfig, new NoAuthenticator());
        
        // When - We build a request with empty offset
        Map<String, String> templateVars = new HashMap<>();
        templateVars.put("offset", "");
        
        Method buildRequestMethod = HttpApiClient.class.getDeclaredMethod("buildRequest", Map.class);
        buildRequestMethod.setAccessible(true);
        Request request = (Request) buildRequestMethod.invoke(client, templateVars);
        
        // Then - The URL should not have query parameters
        HttpUrl requestUrl = request.url();
        
        System.out.println("Empty offset URL: " + requestUrl.toString());
        
        assertEquals("http://example.com/data", requestUrl.toString());
        assertNull(requestUrl.encodedQuery(), "Query should be null for empty offset");
        
        client.close();
    }
}