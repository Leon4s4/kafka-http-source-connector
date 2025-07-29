package io.confluent.connect.http.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * JSON record converter that creates SourceRecords with properly serialized JSON string values.
 * Uses Jackson ObjectMapper to convert Java objects to valid JSON strings.
 * In a full implementation, this would integrate with Schema Registry for proper schema support.
 */
public class JsonRecordConverter implements RecordConverter {
    
    private static final Logger log = LoggerFactory.getLogger(JsonRecordConverter.class);
    
    private final HttpSourceConnectorConfig config;
    private final ObjectMapper objectMapper;
    
    public JsonRecordConverter(HttpSourceConnectorConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
        log.debug("Initialized JsonRecordConverter with output format: {}", config.getOutputDataFormat());
    }
    
    @Override
    public SourceRecord convert(Map<String, String> sourcePartition,
                               Map<String, String> sourceOffset,
                               String topic,
                               Object keySchema,
                               Object key,
                               Object valueSchema,
                               Object value,
                               Long timestamp) {
        
        log.trace("Converting record for topic: {} with output format: {}", topic, config.getOutputDataFormat());
        
        // Determine schema and value based on output format
        Schema recordValueSchema;
        Object recordValue;
        
        switch (config.getOutputDataFormat()) {
            case JSON_SR:
                // For JSON_SR, use string schema but ensure proper JSON serialization
                recordValueSchema = Schema.STRING_SCHEMA;
                if (value instanceof String) {
                    recordValue = (String) value;
                } else if (value != null) {
                    try {
                        // Convert to proper JSON string representation using Jackson
                        recordValue = objectMapper.writeValueAsString(value);
                        log.trace("Converted object to JSON: {}", ((String) recordValue).length() > 200 ? 
                                 ((String) recordValue).substring(0, 200) + "..." : recordValue);
                    } catch (Exception e) {
                        log.warn("Failed to serialize object to JSON, falling back to toString(): {}", e.getMessage());
                        recordValue = value.toString();
                    }
                } else {
                    recordValue = null;
                }
                break;
                
            case AVRO:
            case PROTOBUF:
                // For schema-based formats, we could return the structured object
                // and let Kafka Connect handle the serialization with proper schemas
                recordValueSchema = null; // Schema will be determined by the converter
                recordValue = value;
                break;
                
            default:
                // Fallback to string representation
                recordValueSchema = Schema.STRING_SCHEMA;
                if (value != null) {
                    try {
                        recordValue = objectMapper.writeValueAsString(value);
                    } catch (Exception e) {
                        log.warn("Failed to serialize object to JSON: {}", e.getMessage());
                        recordValue = value.toString();
                    }
                } else {
                    recordValue = null;
                }
        }
        
        return new SourceRecord(
            sourcePartition,
            sourceOffset,
            topic,
            null, // partition - let Kafka decide
            (Schema) keySchema,
            key,
            recordValueSchema,
            recordValue,
            timestamp
        );
    }
}