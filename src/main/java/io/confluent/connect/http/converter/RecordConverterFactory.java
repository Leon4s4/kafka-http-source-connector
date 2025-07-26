package io.confluent.connect.http.converter;

import io.confluent.connect.http.config.HttpSourceConnectorConfig;

/**
 * Factory for creating record converters based on configuration.
 */
public class RecordConverterFactory {
    
    /**
     * Creates a record converter based on the output data format configuration
     * 
     * @param config The connector configuration
     * @return A RecordConverter instance
     */
    public static RecordConverter create(HttpSourceConnectorConfig config) {
        // For now, return a simple JSON converter
        // In a full implementation, this would support Avro, Protobuf, etc.
        return new JsonRecordConverter(config);
    }
}