package io.confluent.connect.http.converter;

import org.apache.kafka.connect.source.SourceRecord;

import java.util.Map;

/**
 * Interface for converting HTTP API response data to Kafka Connect SourceRecords.
 */
public interface RecordConverter {
    
    /**
     * Converts data from HTTP API response to a Kafka Connect SourceRecord
     * 
     * @param sourcePartition The source partition for Kafka Connect
     * @param sourceOffset The source offset for Kafka Connect
     * @param topic The target Kafka topic
     * @param keySchema The key schema (can be null)
     * @param key The record key (can be null)
     * @param valueSchema The value schema (can be null)
     * @param value The record value
     * @param timestamp The record timestamp
     * @return A SourceRecord ready to be sent to Kafka
     */
    SourceRecord convert(Map<String, String> sourcePartition,
                        Map<String, String> sourceOffset,
                        String topic,
                        Object keySchema,
                        Object key,
                        Object valueSchema,
                        Object value,
                        Long timestamp);
}