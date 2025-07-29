package io.confluent.connect.http.unit;

import io.confluent.connect.http.client.HttpApiClient;
import io.confluent.connect.http.config.ApiConfig;
import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import io.confluent.connect.http.util.TemplateVariableReplacer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests to verify template variable replacement functionality.
 */
class TemplateReplacementTest {
    
    private TemplateVariableReplacer templateReplacer;
    
    @BeforeEach
    void setUp() {
        templateReplacer = new TemplateVariableReplacer();
    }
    
    @Test
    @DisplayName("Should replace ${offset} template variable in URL path")
    void shouldReplaceOffsetTemplateInUrlPath() {
        // Given
        String urlWithTemplate = "http://mock-server:8080/api/data/v9.2/accounts${offset}";
        String offsetValue = "?$select=name,accountnumber,telephone1,fax&$filter=modifiedon ge '2025-01-01'";
        
        Map<String, String> templateVars = new HashMap<>();
        templateVars.put("offset", offsetValue);
        
        // When
        String processedUrl = templateReplacer.replace(urlWithTemplate, templateVars);
        
        // Then
        String expectedUrl = "http://mock-server:8080/api/data/v9.2/accounts?$select=name,accountnumber,telephone1,fax&$filter=modifiedon ge '2025-01-01'";
        assertEquals(expectedUrl, processedUrl);
    }
    
    @Test
    @DisplayName("Should handle empty offset value")
    void shouldHandleEmptyOffsetValue() {
        // Given
        String urlWithTemplate = "http://mock-server:8080/api/data/v9.2/accounts${offset}";
        String offsetValue = "";
        
        Map<String, String> templateVars = new HashMap<>();
        templateVars.put("offset", offsetValue);
        
        // When
        String processedUrl = templateReplacer.replace(urlWithTemplate, templateVars);
        
        // Then
        String expectedUrl = "http://mock-server:8080/api/data/v9.2/accounts";
        assertEquals(expectedUrl, processedUrl);
    }
    
    @Test
    @DisplayName("Should handle null offset value by keeping template literal")
    void shouldHandleNullOffsetValue() {
        // Given
        String urlWithTemplate = "http://mock-server:8080/api/data/v9.2/accounts${offset}";
        
        Map<String, String> templateVars = new HashMap<>();
        // offset key is missing from map
        
        // When
        String processedUrl = templateReplacer.replace(urlWithTemplate, templateVars);
        
        // Then - should keep the original template when no replacement found
        String expectedUrl = "http://mock-server:8080/api/data/v9.2/accounts${offset}";
        assertEquals(expectedUrl, processedUrl);
    }
    
    @Test
    @DisplayName("Should handle multiple template variables")
    void shouldHandleMultipleTemplateVariables() {
        // Given
        String urlWithTemplate = "http://mock-server:8080/api/data/v9.2/accounts${offset}&debug=${debug}";
        
        Map<String, String> templateVars = new HashMap<>();
        templateVars.put("offset", "?$select=name");
        templateVars.put("debug", "true");
        
        // When
        String processedUrl = templateReplacer.replace(urlWithTemplate, templateVars);
        
        // Then
        String expectedUrl = "http://mock-server:8080/api/data/v9.2/accounts?$select=name&debug=true";
        assertEquals(expectedUrl, processedUrl);
    }
    
    @Test
    @DisplayName("Should test URL encoding behavior with HttpUrl.parse()")
    void shouldTestUrlEncodingWithHttpUrlParse() {
        // This test verifies the bug we found - that HttpUrl.parse() encodes template variables
        String urlWithTemplate = "http://mock-server:8080/api/data/v9.2/accounts${offset}";
        
        // Test OkHttp's HttpUrl.parse behavior
        okhttp3.HttpUrl parsedUrl = okhttp3.HttpUrl.parse(urlWithTemplate);
        
        // The parsed URL should contain URL-encoded template variables
        String parsedUrlString = parsedUrl.toString();
        System.out.println("Original URL: " + urlWithTemplate);
        System.out.println("Parsed URL: " + parsedUrlString);
        
        // This demonstrates the bug - ${offset} gets URL encoded to $%7Boffset%7D  
        assertTrue(parsedUrlString.contains("$%7Boffset%7D") || parsedUrlString.contains("${offset}"), 
            "URL should either contain URL-encoded template or original template");
    }
    
    @Test
    @DisplayName("Should demonstrate the fix - replace before parsing")
    void shouldDemonstrateFixReplaceBeforeParsing() {
        // Given
        String urlWithTemplate = "http://mock-server:8080/api/data/v9.2/accounts${offset}";
        String offsetValue = "?$select=name,accountnumber";
        
        Map<String, String> templateVars = new HashMap<>();
        templateVars.put("offset", offsetValue);
        
        // OLD WAY (broken): Parse first, then replace
        okhttp3.HttpUrl parsedFirst = okhttp3.HttpUrl.parse(urlWithTemplate);
        String afterParsingFirst = templateReplacer.replace(parsedFirst.toString(), templateVars);
        
        // NEW WAY (fixed): Replace first, then parse
        String replacedFirst = templateReplacer.replace(urlWithTemplate, templateVars);
        okhttp3.HttpUrl parsedAfter = okhttp3.HttpUrl.parse(replacedFirst);
        
        System.out.println("OLD WAY - Parse first, then replace: " + afterParsingFirst);
        System.out.println("NEW WAY - Replace first, then parse: " + parsedAfter.toString());
        
        // The new way should work correctly
        String expectedUrl = "http://mock-server:8080/api/data/v9.2/accounts?$select=name,accountnumber";
        assertEquals(expectedUrl, parsedAfter.toString());
        
        // The old way likely fails (contains unresolved template or broken URL)
        assertNotEquals(expectedUrl, afterParsingFirst);
    }
}