package io.confluent.connect.http.error;

import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import io.confluent.connect.http.error.AdvancedErrorHandler.ErrorCategory;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Future;

/**
 * Enhanced Dead Letter Queue (DLQ) integration for handling failed records.
 * Provides comprehensive error record handling with metadata, categorization,
 * and configurable DLQ topic management.
 */
public class EnhancedDLQHandler {
    
    private static final Logger log = LoggerFactory.getLogger(EnhancedDLQHandler.class);
    
    // DLQ Schema for error records
    private static final Schema DLQ_ERROR_SCHEMA = SchemaBuilder.struct()
        .name("io.confluent.connect.http.DLQError")
        .version(1)
        .field("originalTopic", Schema.OPTIONAL_STRING_SCHEMA)
        .field("originalPartition", Schema.OPTIONAL_INT32_SCHEMA)
        .field("originalOffset", Schema.OPTIONAL_INT64_SCHEMA)
        .field("errorCategory", Schema.STRING_SCHEMA)
        .field("errorCode", Schema.STRING_SCHEMA)
        .field("errorMessage", Schema.STRING_SCHEMA)
        .field("httpStatusCode", Schema.OPTIONAL_INT32_SCHEMA)
        .field("apiEndpoint", Schema.OPTIONAL_STRING_SCHEMA)
        .field("requestHeaders", Schema.OPTIONAL_STRING_SCHEMA)
        .field("responseHeaders", Schema.OPTIONAL_STRING_SCHEMA)
        .field("requestPayload", Schema.OPTIONAL_STRING_SCHEMA)
        .field("responsePayload", Schema.OPTIONAL_STRING_SCHEMA)
        .field("timestamp", Schema.INT64_SCHEMA)
        .field("retryCount", Schema.INT32_SCHEMA)
        .field("connectorName", Schema.STRING_SCHEMA)
        .field("taskId", Schema.INT32_SCHEMA)
        .build();
    
    private final HttpSourceConnectorConfig config;
    private final KafkaProducer<String, Object> dlqProducer;
    private final String dlqTopicName;
    private final boolean dlqEnabled;
    private final int maxRetries;
    private final String connectorName;
    
    // DLQ configuration properties
    private final boolean includeHeaders;
    private final boolean includePayload;
    private final int maxPayloadSize;
    private final String keyStrategy;
    
    public EnhancedDLQHandler(HttpSourceConnectorConfig config, String connectorName) {
        this.config = config;
        this.connectorName = connectorName;
        
        // Extract DLQ configuration
        this.dlqEnabled = isDLQEnabled(config);
        this.dlqTopicName = getDLQTopicName(config);
        this.maxRetries = getMaxRetries(config);
        this.includeHeaders = shouldIncludeHeaders(config);
        this.includePayload = shouldIncludePayload(config);
        this.maxPayloadSize = getMaxPayloadSize(config);
        this.keyStrategy = getKeyStrategy(config);
        
        // Initialize DLQ producer if enabled
        if (dlqEnabled) {
            Properties producerProps = createDLQProducerProperties(config);
            this.dlqProducer = new KafkaProducer<>(producerProps);
            log.info("DLQ handler initialized with topic: {}", dlqTopicName);
        } else {
            this.dlqProducer = null;
            log.info("DLQ handler disabled");
        }
    }
    
    /**
     * Handle a failed record by sending it to the DLQ.
     */
    public void handleFailedRecord(SourceRecord originalRecord, ErrorCategory errorCategory, 
                                 String errorCode, String errorMessage, 
                                 DLQContext context) {
        if (!dlqEnabled) {
            log.debug("DLQ disabled, skipping failed record");
            return;
        }
        
        try {
            // Create DLQ record
            Struct dlqValue = createDLQRecord(originalRecord, errorCategory, errorCode, 
                                            errorMessage, context);
            
            // Generate DLQ key
            String dlqKey = generateDLQKey(originalRecord, context);
            
            // Create headers
            Headers dlqHeaders = createDLQHeaders(originalRecord, context);
            
            // Create producer record
            ProducerRecord<String, Object> dlqRecord = new ProducerRecord<>(
                dlqTopicName,
                null, // partition - let Kafka decide
                dlqKey,
                dlqValue,
                dlqHeaders
            );
            
            // Send to DLQ
            Future<org.apache.kafka.clients.producer.RecordMetadata> future = 
                dlqProducer.send(dlqRecord);
            
            log.debug("Sent failed record to DLQ: topic={}, key={}, error={}", 
                     dlqTopicName, dlqKey, errorMessage);
            
        } catch (Exception e) {
            log.error("Failed to send record to DLQ topic: " + dlqTopicName, e);
            // Don't rethrow - we don't want DLQ failures to break the connector
        }
    }
    
    /**
     * Handle a failed HTTP request that didn't produce a SourceRecord.
     */
    public void handleFailedRequest(String apiEndpoint, ErrorCategory errorCategory,
                                  String errorCode, String errorMessage,
                                  DLQContext context) {
        if (!dlqEnabled) {
            return;
        }
        
        try {
            // Create DLQ record for failed request
            Struct dlqValue = createRequestDLQRecord(apiEndpoint, errorCategory, 
                                                   errorCode, errorMessage, context);
            
            // Generate key for request failure
            String dlqKey = generateRequestDLQKey(apiEndpoint, context);
            
            // Create headers
            Headers dlqHeaders = createRequestDLQHeaders(context);
            
            // Create producer record
            ProducerRecord<String, Object> dlqRecord = new ProducerRecord<>(
                dlqTopicName,
                null,
                dlqKey,
                dlqValue,
                dlqHeaders
            );
            
            // Send to DLQ
            dlqProducer.send(dlqRecord);
            
            log.debug("Sent failed request to DLQ: endpoint={}, error={}", 
                     apiEndpoint, errorMessage);
            
        } catch (Exception e) {
            log.error("Failed to send request failure to DLQ topic: " + dlqTopicName, e);
        }
    }
    
    private Struct createDLQRecord(SourceRecord originalRecord, ErrorCategory errorCategory,
                                  String errorCode, String errorMessage,
                                  DLQContext context) {
        Struct dlqRecord = new Struct(DLQ_ERROR_SCHEMA);
        
        // Original record information
        if (originalRecord.topic() != null) {
            dlqRecord.put("originalTopic", originalRecord.topic());
        }
        if (originalRecord.kafkaPartition() != null) {
            dlqRecord.put("originalPartition", originalRecord.kafkaPartition());
        }
        
        // Extract offset if available
        Map<String, ?> sourceOffset = originalRecord.sourceOffset();
        if (sourceOffset != null && sourceOffset.containsKey("offset")) {
            Object offset = sourceOffset.get("offset");
            if (offset instanceof Long) {
                dlqRecord.put("originalOffset", (Long) offset);
            } else if (offset instanceof Integer) {
                dlqRecord.put("originalOffset", ((Integer) offset).longValue());
            }
        }
        
        // Error information
        dlqRecord.put("errorCategory", errorCategory.name());
        dlqRecord.put("errorCode", errorCode);
        dlqRecord.put("errorMessage", errorMessage);
        
        // HTTP context
        if (context.getHttpStatusCode() != null) {
            dlqRecord.put("httpStatusCode", context.getHttpStatusCode());
        }
        if (context.getApiEndpoint() != null) {
            dlqRecord.put("apiEndpoint", context.getApiEndpoint());
        }
        
        // Headers and payload (if enabled)
        if (includeHeaders) {
            if (context.getRequestHeaders() != null) {
                dlqRecord.put("requestHeaders", context.getRequestHeaders());
            }
            if (context.getResponseHeaders() != null) {
                dlqRecord.put("responseHeaders", context.getResponseHeaders());
            }
        }
        
        if (includePayload) {
            if (context.getRequestPayload() != null) {
                String payload = truncatePayload(context.getRequestPayload());
                dlqRecord.put("requestPayload", payload);
            }
            if (context.getResponsePayload() != null) {
                String payload = truncatePayload(context.getResponsePayload());
                dlqRecord.put("responsePayload", payload);
            }
        }
        
        // Metadata
        dlqRecord.put("timestamp", System.currentTimeMillis());
        dlqRecord.put("retryCount", context.getRetryCount());
        dlqRecord.put("connectorName", connectorName);
        dlqRecord.put("taskId", context.getTaskId());
        
        return dlqRecord;
    }
    
    private Struct createRequestDLQRecord(String apiEndpoint, ErrorCategory errorCategory,
                                        String errorCode, String errorMessage,
                                        DLQContext context) {
        Struct dlqRecord = new Struct(DLQ_ERROR_SCHEMA);
        
        // No original record information for request failures
        dlqRecord.put("errorCategory", errorCategory.name());
        dlqRecord.put("errorCode", errorCode);
        dlqRecord.put("errorMessage", errorMessage);
        dlqRecord.put("apiEndpoint", apiEndpoint);
        
        // HTTP context
        if (context.getHttpStatusCode() != null) {
            dlqRecord.put("httpStatusCode", context.getHttpStatusCode());
        }
        
        // Headers and payload (if enabled)
        if (includeHeaders) {
            if (context.getRequestHeaders() != null) {
                dlqRecord.put("requestHeaders", context.getRequestHeaders());
            }
            if (context.getResponseHeaders() != null) {
                dlqRecord.put("responseHeaders", context.getResponseHeaders());
            }
        }
        
        if (includePayload) {
            if (context.getRequestPayload() != null) {
                String payload = truncatePayload(context.getRequestPayload());
                dlqRecord.put("requestPayload", payload);
            }
            if (context.getResponsePayload() != null) {
                String payload = truncatePayload(context.getResponsePayload());
                dlqRecord.put("responsePayload", payload);
            }
        }
        
        // Metadata
        dlqRecord.put("timestamp", System.currentTimeMillis());
        dlqRecord.put("retryCount", context.getRetryCount());
        dlqRecord.put("connectorName", connectorName);
        dlqRecord.put("taskId", context.getTaskId());
        
        return dlqRecord;
    }
    
    private String generateDLQKey(SourceRecord originalRecord, DLQContext context) {
        switch (keyStrategy.toLowerCase()) {
            case "connector_task":
                return String.format("%s_%d", connectorName, context.getTaskId());
            case "endpoint":
                return context.getApiEndpoint() != null ? context.getApiEndpoint() : "unknown";
            case "error_category":
                return context.getErrorCategory() != null ? context.getErrorCategory() : "unknown";
            case "original_key":
                return originalRecord.key() != null ? originalRecord.key().toString() : "null";
            case "timestamp":
                return String.valueOf(System.currentTimeMillis());
            default:
                // Default: combination of connector, task, and timestamp
                return String.format("%s_%d_%d", connectorName, context.getTaskId(), 
                                   System.currentTimeMillis());
        }
    }
    
    private String generateRequestDLQKey(String apiEndpoint, DLQContext context) {
        switch (keyStrategy.toLowerCase()) {
            case "connector_task":
                return String.format("%s_%d", connectorName, context.getTaskId());
            case "endpoint":
                return apiEndpoint != null ? apiEndpoint : "unknown";
            case "error_category":
                return context.getErrorCategory() != null ? context.getErrorCategory() : "unknown";
            case "timestamp":
                return String.valueOf(System.currentTimeMillis());
            default:
                return String.format("%s_%d_%d", connectorName, context.getTaskId(), 
                                   System.currentTimeMillis());
        }
    }
    
    private Headers createDLQHeaders(SourceRecord originalRecord, DLQContext context) {
        Headers headers = new RecordHeaders();
        
        // Standard DLQ headers
        headers.add("dlq.connector.name", connectorName.getBytes());
        headers.add("dlq.task.id", String.valueOf(context.getTaskId()).getBytes());
        headers.add("dlq.error.category", context.getErrorCategory().getBytes());
        headers.add("dlq.timestamp", String.valueOf(System.currentTimeMillis()).getBytes());
        
        // Original record headers if available
        if (originalRecord.headers() != null) {
            originalRecord.headers().forEach(header -> {
                byte[] headerValue;
                if (header.value() instanceof byte[]) {
                    headerValue = (byte[]) header.value();
                } else if (header.value() != null) {
                    headerValue = header.value().toString().getBytes();
                } else {
                    headerValue = new byte[0];
                }
                headers.add("original." + header.key(), headerValue);
            });
        }
        
        // HTTP context headers
        if (context.getApiEndpoint() != null) {
            headers.add("http.endpoint", context.getApiEndpoint().getBytes());
        }
        if (context.getHttpStatusCode() != null) {
            headers.add("http.status", context.getHttpStatusCode().toString().getBytes());
        }
        
        return headers;
    }
    
    private Headers createRequestDLQHeaders(DLQContext context) {
        Headers headers = new RecordHeaders();
        
        headers.add("dlq.connector.name", connectorName.getBytes());
        headers.add("dlq.task.id", String.valueOf(context.getTaskId()).getBytes());
        headers.add("dlq.error.category", context.getErrorCategory().getBytes());
        headers.add("dlq.timestamp", String.valueOf(System.currentTimeMillis()).getBytes());
        headers.add("dlq.failure.type", "request".getBytes());
        
        if (context.getApiEndpoint() != null) {
            headers.add("http.endpoint", context.getApiEndpoint().getBytes());
        }
        if (context.getHttpStatusCode() != null) {
            headers.add("http.status", context.getHttpStatusCode().toString().getBytes());
        }
        
        return headers;
    }
    
    private String truncatePayload(String payload) {
        if (payload == null || payload.length() <= maxPayloadSize) {
            return payload;
        }
        return payload.substring(0, maxPayloadSize) + "...[truncated]";
    }
    
    private Properties createDLQProducerProperties(HttpSourceConnectorConfig config) {
        Properties props = new Properties();
        
        // Bootstrap servers from connector config
        String bootstrapServers = getBootstrapServers(config);
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        
        // Serializers
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, 
                 "org.apache.kafka.common.serialization.StringSerializer");
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, 
                 "io.confluent.connect.avro.AvroConverter");
        
        // Producer optimizations for DLQ
        props.put(ProducerConfig.ACKS_CONFIG, "1"); // Wait for leader acknowledgment
        props.put(ProducerConfig.RETRIES_CONFIG, "3");
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, "16384");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "10");
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, "33554432");
        
        // Error handling
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "1");
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "30000");
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "40000");
        
        // Add any custom DLQ producer properties from config
        Map<String, Object> dlqProducerProps = getDLQProducerProperties(config);
        if (dlqProducerProps != null) {
            props.putAll(dlqProducerProps);
        }
        
        return props;
    }
    
    public void close() {
        if (dlqProducer != null) {
            try {
                dlqProducer.flush();
                dlqProducer.close();
                log.info("DLQ producer closed");
            } catch (Exception e) {
                log.warn("Error closing DLQ producer", e);
            }
        }
    }
    
    // Configuration extraction methods
    
    private boolean isDLQEnabled(HttpSourceConnectorConfig config) {
        return Boolean.parseBoolean(System.getProperty("dlq.enabled", "true"));
    }
    
    private String getDLQTopicName(HttpSourceConnectorConfig config) {
        return System.getProperty("dlq.topic.name", connectorName + "-dlq");
    }
    
    private int getMaxRetries(HttpSourceConnectorConfig config) {
        try {
            return Integer.parseInt(System.getProperty("dlq.max.retries", "3"));
        } catch (NumberFormatException e) {
            return 3;
        }
    }
    
    private boolean shouldIncludeHeaders(HttpSourceConnectorConfig config) {
        return Boolean.parseBoolean(System.getProperty("dlq.include.headers", "true"));
    }
    
    private boolean shouldIncludePayload(HttpSourceConnectorConfig config) {
        return Boolean.parseBoolean(System.getProperty("dlq.include.payload", "true"));
    }
    
    private int getMaxPayloadSize(HttpSourceConnectorConfig config) {
        try {
            return Integer.parseInt(System.getProperty("dlq.max.payload.size", "10240"));
        } catch (NumberFormatException e) {
            return 10240; // 10KB default
        }
    }
    
    private String getKeyStrategy(HttpSourceConnectorConfig config) {
        return System.getProperty("dlq.key.strategy", "connector_task_timestamp");
    }
    
    private String getBootstrapServers(HttpSourceConnectorConfig config) {
        // Try to get from system property, fallback to config, then default
        String servers = System.getProperty("dlq.bootstrap.servers");
        if (servers != null) {
            return servers;
        }
        
        // Try to extract from connector config if available
        // This would need to be implemented based on the actual config structure
        return "localhost:9092"; // Default fallback
    }
    
    private Map<String, Object> getDLQProducerProperties(HttpSourceConnectorConfig config) {
        Map<String, Object> props = new HashMap<>();
        
        // Look for DLQ producer properties in system properties
        System.getProperties().forEach((key, value) -> {
            String keyStr = key.toString();
            if (keyStr.startsWith("dlq.producer.")) {
                String kafkaKey = keyStr.substring("dlq.producer.".length());
                props.put(kafkaKey, value);
            }
        });
        
        return props.isEmpty() ? null : props;
    }
    
    public boolean isDLQEnabled() {
        return dlqEnabled;
    }
    
    public String getDLQTopicName() {
        return dlqTopicName;
    }
}
