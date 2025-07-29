package io.confluent.connect.http.unit;

import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import io.confluent.connect.http.converter.JsonRecordConverter;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JsonRecordConverter to ensure proper JSON serialization.
 */
class JsonRecordConverterTest {
    
    private JsonRecordConverter converter;
    private Map<String, String> config;
    
    @BeforeEach
    void setUp() {
        config = new HashMap<>();
        config.put("http.api.base.url", "http://test.com");
        config.put("output.data.format", "JSON_SR");
        
        HttpSourceConnectorConfig httpConfig = new HttpSourceConnectorConfig(config);
        converter = new JsonRecordConverter(httpConfig);
    }
    
    @Test
    @DisplayName("Should serialize Map objects to proper JSON strings")
    void shouldSerializeMapToJsonString() {
        // Given
        Map<String, Object> testData = new HashMap<>();
        testData.put("accountid", "a7e7aa9f-a3bc-43b7-867d-0008e2595f8a");
        testData.put("name", "Impact Aquariums");
        testData.put("telephone1", "(250) 899-4775");
        testData.put("fax", null);
        testData.put("accountnumber", null);
        testData.put("@odata.etag", "W/\"217466890\"");
        
        Map<String, String> sourcePartition = Map.of("api", "test");
        Map<String, String> sourceOffset = Map.of("offset", "0");
        
        // When
        SourceRecord record = converter.convert(
            sourcePartition,
            sourceOffset,
            "test-topic",
            null,
            null,
            null,
            testData,
            System.currentTimeMillis()
        );
        
        // Then
        assertNotNull(record);
        assertNotNull(record.value());
        assertTrue(record.value() instanceof String);
        
        String jsonValue = (String) record.value();
        
        // Should be valid JSON, not toString() output
        assertTrue(jsonValue.startsWith("{"));
        assertTrue(jsonValue.endsWith("}"));
        assertFalse(jsonValue.contains("accountid=a7e7aa9f")); // Should not contain toString() format
        assertTrue(jsonValue.contains("\"accountid\":\"a7e7aa9f-a3bc-43b7-867d-0008e2595f8a\"")); // Should contain JSON format
        assertTrue(jsonValue.contains("\"name\":\"Impact Aquariums\""));
        assertTrue(jsonValue.contains("\"fax\":null"));
    }
    
    @Test
    @DisplayName("Should handle string values without additional serialization")
    void shouldHandleStringValuesDirectly() {
        // Given
        String testValue = "{\"already\":\"json\"}";
        Map<String, String> sourcePartition = Map.of("api", "test");
        Map<String, String> sourceOffset = Map.of("offset", "0");
        
        // When
        SourceRecord record = converter.convert(
            sourcePartition,
            sourceOffset,
            "test-topic",
            null,
            null,
            null,
            testValue,
            System.currentTimeMillis()
        );
        
        // Then
        assertNotNull(record);
        assertEquals(testValue, record.value());
    }
    
    @Test
    @DisplayName("Should handle null values gracefully")
    void shouldHandleNullValues() {
        // Given
        Map<String, String> sourcePartition = Map.of("api", "test");
        Map<String, String> sourceOffset = Map.of("offset", "0");
        
        // When
        SourceRecord record = converter.convert(
            sourcePartition,
            sourceOffset,
            "test-topic",
            null,
            null,
            null,
            null,
            System.currentTimeMillis()
        );
        
        // Then
        assertNotNull(record);
        assertNull(record.value());
    }
}