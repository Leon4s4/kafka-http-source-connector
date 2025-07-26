package io.confluent.connect.http.converter;

import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Simple JSON record converter that creates SourceRecords with string values.
 * In a full implementation, this would integrate with Schema Registry for proper schema support.
 */
public class JsonRecordConverter implements RecordConverter {
    
    private static final Logger log = LoggerFactory.getLogger(JsonRecordConverter.class);
    
    private final HttpSourceConnectorConfig config;
    
    public JsonRecordConverter(HttpSourceConnectorConfig config) {
        this.config = config;
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
        
        log.trace("Converting record for topic: {}", topic);
        
        // For JSON format, we'll use string schema and convert the value to JSON string if needed
        Schema recordValueSchema = Schema.STRING_SCHEMA;
        String recordValue;
        
        if (value instanceof String) {
            recordValue = (String) value;
        } else if (value != null) {
            // Convert to JSON string representation
            recordValue = value.toString();
        } else {
            recordValue = null;
        }
        
        return new SourceRecord(
            sourcePartition,
            sourceOffset,
            topic,
            null, // partition - let Kafka decide
            keySchema,
            key,
            recordValueSchema,
            recordValue,
            timestamp
        );
    }
}