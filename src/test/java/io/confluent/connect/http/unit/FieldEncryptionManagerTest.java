package io.confluent.connect.http.unit;

import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import io.confluent.connect.http.encryption.FieldEncryptionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Field Encryption Manager Unit Tests")
class FieldEncryptionManagerTest {
    
    private HttpSourceConnectorConfig config;
    private FieldEncryptionManager encryptionManager;
    
    @BeforeEach
    void setUp() {
        // Create real config instead of mocking
        Map<String, String> configMap = new HashMap<>();
        configMap.put("http.api.base.url", "http://localhost:8080");
        configMap.put("apis.num", "1");
        configMap.put("api1.http.api.path", "/test");
        configMap.put("api1.topics", "test-topic");
        configMap.put("field.encryption.enabled", "true");
        configMap.put("field.encryption.key", "dGVzdC1lbmNyeXB0aW9uLWtleS0yNTYtYml0czEyMzQ="); // base64 encoded 32-byte test key
        configMap.put("field.encryption.rules", "ssn:AES_GCM,salary:DETERMINISTIC,notes:RANDOM");
        
        config = new HttpSourceConnectorConfig(configMap);
        encryptionManager = new FieldEncryptionManager(config);
    }
    
    @Test
    @DisplayName("Should encrypt sensitive fields according to rules")
    void shouldEncryptSensitiveFields() {
        // Given
        Map<String, Object> record = new HashMap<>();
        record.put("id", 1);
        record.put("name", "John Doe");
        record.put("ssn", "123-45-6789");
        record.put("salary", 75000);
        record.put("notes", "Confidential information");
        record.put("email", "john@example.com");
        
        // When
        Object encryptedRecord = encryptionManager.encryptSensitiveFields(record, "api1");
        
        // Then
        assertThat(encryptedRecord).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> encryptedMap = (Map<String, Object>) encryptedRecord;
        
        // Non-sensitive fields should remain unchanged
        assertThat(encryptedMap.get("id")).isEqualTo(1);
        assertThat(encryptedMap.get("name")).isEqualTo("John Doe");
        assertThat(encryptedMap.get("email")).isEqualTo("john@example.com");
        
        // Sensitive fields should be encrypted (different from original)
        assertThat(encryptedMap.get("ssn")).isNotEqualTo("123-45-6789");
        assertThat(encryptedMap.get("salary")).isNotEqualTo(75000);
        assertThat(encryptedMap.get("notes")).isNotEqualTo("Confidential information");
        
        // Encrypted values should be strings
        assertThat(encryptedMap.get("ssn")).isInstanceOf(String.class);
        assertThat(encryptedMap.get("salary")).isInstanceOf(String.class);
        assertThat(encryptedMap.get("notes")).isInstanceOf(String.class);
    }
    
    @Test
    @DisplayName("Should handle API-specific field rules")
    void shouldHandleApiSpecificFieldRules() {
        // Given - create new config with API-specific rules
        Map<String, String> configMap = new HashMap<>();
        configMap.put("http.api.base.url", "http://localhost:8080");
        configMap.put("apis.num", "1");
        configMap.put("api1.http.api.path", "/test");
        configMap.put("api1.topics", "test-topic");
        configMap.put("field.encryption.enabled", "true");
        configMap.put("field.encryption.key", "dGVzdC1lbmNyeXB0aW9uLWtleS0yNTYtYml0czEyMzQ=");
        configMap.put("field.encryption.rules", "salary:AES_GCM,api2.employee_id:DETERMINISTIC");
        
        config = new HttpSourceConnectorConfig(configMap);
        encryptionManager = new FieldEncryptionManager(config);
        
        Map<String, Object> record = new HashMap<>();
        record.put("salary", 75000);
        record.put("employee_id", "EMP001");
        
        // When - encrypt for api1 (should only encrypt salary)
        Object encryptedRecord1 = encryptionManager.encryptSensitiveFields(record, "api1");
        
        // When - encrypt for api2 (should encrypt both fields)
        Object encryptedRecord2 = encryptionManager.encryptSensitiveFields(record, "api2");
        
        // Then
        @SuppressWarnings("unchecked")
        Map<String, Object> encryptedMap1 = (Map<String, Object>) encryptedRecord1;
        @SuppressWarnings("unchecked")
        Map<String, Object> encryptedMap2 = (Map<String, Object>) encryptedRecord2;
        
        // For api1: salary encrypted, employee_id not encrypted
        assertThat(encryptedMap1.get("salary")).isNotEqualTo(75000);
        assertThat(encryptedMap1.get("employee_id")).isEqualTo("EMP001");
        
        // For api2: both fields encrypted
        assertThat(encryptedMap2.get("salary")).isNotEqualTo(75000);
        assertThat(encryptedMap2.get("employee_id")).isNotEqualTo("EMP001");
    }
    
    @Test
    @DisplayName("Should handle nested field encryption")
    void shouldHandleNestedFieldEncryption() {
        // Given - create new config with nested field rules
        Map<String, String> configMap = new HashMap<>();
        configMap.put("http.api.base.url", "http://localhost:8080");
        configMap.put("apis.num", "1");
        configMap.put("api1.http.api.path", "/test");
        configMap.put("api1.topics", "test-topic");
        configMap.put("field.encryption.enabled", "true");
        configMap.put("field.encryption.key", "dGVzdC1lbmNyeXB0aW9uLWtleS0yNTYtYml0czEyMzQ=");
        configMap.put("field.encryption.rules", "user.ssn:AES_GCM,user.address.street:DETERMINISTIC");
        
        config = new HttpSourceConnectorConfig(configMap);
        encryptionManager = new FieldEncryptionManager(config);
        
        Map<String, Object> address = new HashMap<>();
        address.put("street", "123 Main St");
        address.put("city", "Anytown");
        
        Map<String, Object> user = new HashMap<>();
        user.put("ssn", "123-45-6789");
        user.put("name", "John Doe");
        user.put("address", address);
        
        Map<String, Object> record = new HashMap<>();
        record.put("id", 1);
        record.put("user", user);
        
        // When
        Object encryptedRecord = encryptionManager.encryptSensitiveFields(record, "api1");
        
        // Then
        @SuppressWarnings("unchecked")
        Map<String, Object> encryptedMap = (Map<String, Object>) encryptedRecord;
        @SuppressWarnings("unchecked")
        Map<String, Object> encryptedUser = (Map<String, Object>) encryptedMap.get("user");
        @SuppressWarnings("unchecked")
        Map<String, Object> encryptedAddress = (Map<String, Object>) encryptedUser.get("address");
        
        // Nested sensitive fields should be encrypted
        assertThat(encryptedUser.get("ssn")).isNotEqualTo("123-45-6789");
        assertThat(encryptedAddress.get("street")).isNotEqualTo("123 Main St");
        
        // Non-sensitive nested fields should remain unchanged
        assertThat(encryptedUser.get("name")).isEqualTo("John Doe");
        assertThat(encryptedAddress.get("city")).isEqualTo("Anytown");
    }
    
    @Test
    @DisplayName("Should handle disabled encryption")
    void shouldHandleDisabledEncryption() {
        // Given - create config with encryption disabled
        Map<String, String> configMap = new HashMap<>();
        configMap.put("http.api.base.url", "http://localhost:8080");
        configMap.put("apis.num", "1");
        configMap.put("api1.http.api.path", "/test");
        configMap.put("api1.topics", "test-topic");
        configMap.put("field.encryption.enabled", "false");
        
        config = new HttpSourceConnectorConfig(configMap);
        encryptionManager = new FieldEncryptionManager(config);
        
        Map<String, Object> record = new HashMap<>();
        record.put("ssn", "123-45-6789");
        record.put("salary", 75000);
        
        // When
        Object result = encryptionManager.encryptSensitiveFields(record, "api1");
        
        // Then
        assertThat(result).isSameAs(record); // Should return same object unchanged
    }
    
    @Test
    @DisplayName("Should handle null and empty records")
    void shouldHandleNullAndEmptyRecords() {
        // When/Then - null record
        Object result1 = encryptionManager.encryptSensitiveFields(null, "api1");
        assertThat(result1).isNull();
        
        // When/Then - empty record
        Map<String, Object> emptyRecord = new HashMap<>();
        Object result2 = encryptionManager.encryptSensitiveFields(emptyRecord, "api1");
        assertThat(result2).isSameAs(emptyRecord);
    }
    
    @Test
    @DisplayName("Should generate encryption key when not provided")
    void shouldGenerateEncryptionKeyWhenNotProvided() {
        // Given - create config without encryption key
        Map<String, String> configMap = new HashMap<>();
        configMap.put("http.api.base.url", "http://localhost:8080");
        configMap.put("apis.num", "1");
        configMap.put("api1.http.api.path", "/test");
        configMap.put("api1.topics", "test-topic");
        configMap.put("field.encryption.enabled", "true");
        configMap.put("field.encryption.rules", "ssn:AES_GCM");
        // No encryption key provided
        
        config = new HttpSourceConnectorConfig(configMap);
        
        // When/Then - should not throw exception
        FieldEncryptionManager manager = new FieldEncryptionManager(config);
        
        Map<String, Object> record = new HashMap<>();
        record.put("ssn", "123-45-6789");
        
        Object result = manager.encryptSensitiveFields(record, "api1");
        assertThat(result).isNotNull();
    }
    
    @Test
    @DisplayName("Should handle deterministic encryption consistency")
    void shouldHandleDeterministicEncryptionConsistency() {
        // Given - create config with deterministic encryption
        Map<String, String> configMap = new HashMap<>();
        configMap.put("http.api.base.url", "http://localhost:8080");
        configMap.put("apis.num", "1");
        configMap.put("api1.http.api.path", "/test");
        configMap.put("api1.topics", "test-topic");
        configMap.put("field.encryption.enabled", "true");
        configMap.put("field.encryption.key", "dGVzdC1lbmNyeXB0aW9uLWtleS0yNTYtYml0czEyMzQ=");
        configMap.put("field.encryption.rules", "customer_id:DETERMINISTIC");
        
        config = new HttpSourceConnectorConfig(configMap);
        encryptionManager = new FieldEncryptionManager(config);
        
        Map<String, Object> record1 = new HashMap<>();
        record1.put("customer_id", "CUST123");
        
        Map<String, Object> record2 = new HashMap<>();
        record2.put("customer_id", "CUST123");
        
        // When
        Object encrypted1 = encryptionManager.encryptSensitiveFields(record1, "api1");
        Object encrypted2 = encryptionManager.encryptSensitiveFields(record2, "api1");
        
        // Then - same input should produce same encrypted output for deterministic encryption
        @SuppressWarnings("unchecked")
        Map<String, Object> encryptedMap1 = (Map<String, Object>) encrypted1;
        @SuppressWarnings("unchecked")
        Map<String, Object> encryptedMap2 = (Map<String, Object>) encrypted2;
        
        assertThat(encryptedMap1.get("customer_id")).isEqualTo(encryptedMap2.get("customer_id"));
    }
    
    @Test
    @DisplayName("Should handle invalid encryption rules gracefully")
    void shouldHandleInvalidEncryptionRulesGracefully() {
        // Given - create config with invalid encryption rules
        Map<String, String> configMap = new HashMap<>();
        configMap.put("http.api.base.url", "http://localhost:8080");
        configMap.put("apis.num", "1");
        configMap.put("api1.http.api.path", "/test");
        configMap.put("api1.topics", "test-topic");
        configMap.put("field.encryption.enabled", "true");
        configMap.put("field.encryption.key", "dGVzdC1lbmNyeXB0aW9uLWtleS0yNTYtYml0czEyMzQ=");
        configMap.put("field.encryption.rules", "field1:INVALID_TYPE,field2:AES_GCM");
        
        config = new HttpSourceConnectorConfig(configMap);
        
        // When/Then - should not throw exception during initialization
        FieldEncryptionManager manager = new FieldEncryptionManager(config);
        
        Map<String, Object> record = new HashMap<>();
        record.put("field1", "value1");
        record.put("field2", "value2");
        
        // Should handle invalid rule gracefully
        Object result = manager.encryptSensitiveFields(record, "api1");
        assertThat(result).isNotNull();
    }
}