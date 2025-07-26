package io.confluent.connect.http.unit;

import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import io.confluent.connect.http.encryption.FieldEncryptionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Field Encryption Manager Unit Tests")
class FieldEncryptionManagerTest {
    
    @Mock
    private HttpSourceConnectorConfig config;
    
    private FieldEncryptionManager encryptionManager;
    
    @BeforeEach
    void setUp() {
        when(config.isFieldEncryptionEnabled()).thenReturn(true);
        when(config.getFieldEncryptionKey()).thenReturn("dGVzdC1lbmNyeXB0aW9uLWtleS0yNTYtYml0czEyMzQ="); // base64 encoded 32-byte test key
        when(config.getFieldEncryptionRules()).thenReturn("ssn:AES_GCM,salary:DETERMINISTIC,notes:RANDOM");
        
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
        // Given
        when(config.getFieldEncryptionRules()).thenReturn("salary:AES_GCM,api2.employee_id:DETERMINISTIC");
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
        // Given
        when(config.getFieldEncryptionRules()).thenReturn("user.ssn:AES_GCM,user.address.street:DETERMINISTIC");
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
        // Given
        when(config.isFieldEncryptionEnabled()).thenReturn(false);
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
        // Given
        when(config.getFieldEncryptionKey()).thenReturn(null);
        
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
        // Given
        when(config.getFieldEncryptionRules()).thenReturn("customer_id:DETERMINISTIC");
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
        // Given
        when(config.getFieldEncryptionRules()).thenReturn("field1:INVALID_TYPE,field2:AES_GCM");
        
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