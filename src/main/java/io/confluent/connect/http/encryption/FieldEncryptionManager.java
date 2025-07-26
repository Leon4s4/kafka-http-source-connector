package io.confluent.connect.http.encryption;

import io.confluent.connect.http.config.HttpSourceConnectorConfig;
import io.confluent.connect.http.util.JsonPointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages field-level encryption for sensitive data in HTTP responses.
 * Supports Client-Side Field Level Encryption (CSFLE) patterns.
 */
public class FieldEncryptionManager {
    
    private static final Logger log = LoggerFactory.getLogger(FieldEncryptionManager.class);
    
    private static final String ENCRYPTION_ALGORITHM = "AES";
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 16;
    
    private final boolean encryptionEnabled;
    private final SecretKey dataEncryptionKey;
    private final Map<String, String> fieldEncryptionRules;
    private final SecureRandom secureRandom;
    
    public FieldEncryptionManager(HttpSourceConnectorConfig config) {
        this.encryptionEnabled = config.isFieldEncryptionEnabled();
        this.secureRandom = new SecureRandom();
        
        if (encryptionEnabled) {
            this.dataEncryptionKey = loadOrGenerateDataKey(config);
            this.fieldEncryptionRules = parseEncryptionRules(config.getFieldEncryptionRules());
            log.info("Field-level encryption enabled with {} encryption rules", fieldEncryptionRules.size());
        } else {
            this.dataEncryptionKey = null;
            this.fieldEncryptionRules = new HashMap<>();
            log.info("Field-level encryption disabled");
        }
    }
    
    /**
     * Encrypts sensitive fields in a record based on configured rules
     */
    public Object encryptSensitiveFields(Object record, String apiId) {
        if (!encryptionEnabled || record == null) {
            return record;
        }
        
        try {
            // Clone the record to avoid modifying the original
            Object clonedRecord = cloneRecord(record);
            
            // Apply encryption rules for this API
            for (Map.Entry<String, String> rule : fieldEncryptionRules.entrySet()) {
                String fieldPath = rule.getKey();
                String encryptionType = rule.getValue();
                
                // Check if this rule applies to the current API
                if (fieldPath.startsWith(apiId + ".") || !fieldPath.contains(".")) {
                    String actualPath = fieldPath.startsWith(apiId + ".") ? 
                        fieldPath.substring(apiId.length() + 1) : fieldPath;
                    
                    encryptField(clonedRecord, actualPath, encryptionType);
                }
            }
            
            return clonedRecord;
            
        } catch (Exception e) {
            log.error("Failed to encrypt sensitive fields for API {}: {}", apiId, e.getMessage(), e);
            return record; // Return original record if encryption fails
        }
    }
    
    /**
     * Encrypts a specific field in a record
     */
    private void encryptField(Object record, String fieldPath, String encryptionType) {
        try {
            // Extract the field value using JSON pointer
            Object fieldValue = null;
            try {
                if (record == null) {
                    log.warn("Record is null, skipping encryption for field {}", fieldPath);
                    return;
                }
               fieldValue = JsonPointer.extract(record, "/" + fieldPath.replace(".", "/"));
           } catch (IllegalArgumentException e) {
               log.warn("Invalid JSON pointer for field {}: {}", fieldPath, e.getMessage());
               return;
           } catch (Exception e) {
               log.warn("Unexpected error while extracting field {}: {}", fieldPath, e.getMessage());
               return;
           }
           
           if (fieldValue == null) {
               log.debug("Field {} not found in record, skipping encryption", fieldPath);
               return;
           }
            
            // Encrypt the field value
            String encryptedValue = encryptValue(fieldValue.toString(), encryptionType);
            
            // Replace the field value with encrypted version
            replaceFieldValue(record, fieldPath, encryptedValue);
            
            log.trace("Encrypted field {} using {} encryption", fieldPath, encryptionType);
            
        } catch (Exception e) {
            log.warn("Failed to encrypt field {}: {}", fieldPath, e.getMessage());
        }
    }
    
    /**
     * Encrypts a single value
     */
    private String encryptValue(String plaintext, String encryptionType) throws Exception {
        switch (encryptionType.toUpperCase()) {
            case "AES_GCM":
                return encryptWithAESGCM(plaintext);
            case "DETERMINISTIC":
                return encryptDeterministic(plaintext);
            case "RANDOM":
                return encryptRandom(plaintext);
            default:
                throw new IllegalArgumentException("Unsupported encryption type: " + encryptionType);
        }
    }
    
    /**
     * Encrypts using AES-GCM with random IV (most secure)
     */
    private String encryptWithAESGCM(String plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        
        // Generate random IV
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);
        
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
        cipher.init(Cipher.ENCRYPT_MODE, dataEncryptionKey, gcmSpec);
        
        byte[] encryptedData = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        
        // Combine IV + encrypted data and encode as base64
        byte[] encryptedWithIv = new byte[GCM_IV_LENGTH + encryptedData.length];
        System.arraycopy(iv, 0, encryptedWithIv, 0, GCM_IV_LENGTH);
        System.arraycopy(encryptedData, 0, encryptedWithIv, GCM_IV_LENGTH, encryptedData.length);
        
        return Base64.getEncoder().encodeToString(encryptedWithIv);
    }
    
    /**
     * Deterministic encryption (same plaintext -> same ciphertext)
     */
    private String encryptDeterministic(String plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        
        // Use hash of plaintext as IV for deterministic encryption
        byte[] iv = generateDeterministicIV(plaintext);
        
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
        cipher.init(Cipher.ENCRYPT_MODE, dataEncryptionKey, gcmSpec);
        
        byte[] encryptedData = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        
        // Combine IV + encrypted data and encode as base64
        byte[] encryptedWithIv = new byte[GCM_IV_LENGTH + encryptedData.length];
        System.arraycopy(iv, 0, encryptedWithIv, 0, GCM_IV_LENGTH);
        System.arraycopy(encryptedData, 0, encryptedWithIv, GCM_IV_LENGTH, encryptedData.length);
        
        return Base64.getEncoder().encodeToString(encryptedWithIv);
    }
    
// Removed the encryptRandom method as it is redundant.
    
    /**
     * Generates a deterministic IV from plaintext for deterministic encryption
     */
    private byte[] generateDeterministicIV(String plaintext) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(plaintext.getBytes(StandardCharsets.UTF_8));
        
        // Use first 12 bytes of hash as IV
        byte[] iv = new byte[GCM_IV_LENGTH];
        System.arraycopy(hash, 0, iv, 0, GCM_IV_LENGTH);
        return iv;
    }
    
    /**
     * Replaces a field value in a record (handles nested objects)
     */
    @SuppressWarnings("unchecked")
    private void replaceFieldValue(Object record, String fieldPath, String newValue) {
        if (!(record instanceof Map)) {
            return;
        }
        
        Map<String, Object> map = (Map<String, Object>) record;
        String[] pathParts = fieldPath.split("\\.");
        
        // Navigate to the parent of the target field
        Map<String, Object> currentMap = map;
        for (int i = 0; i < pathParts.length - 1; i++) {
            Object next = currentMap.get(pathParts[i]);
            if (!(next instanceof Map)) {
                return; // Cannot navigate further
            }
            currentMap = (Map<String, Object>) next;
        }
        
        // Replace the target field
        String targetField = pathParts[pathParts.length - 1];
        currentMap.put(targetField, newValue);
    }
    
    /**
     * Creates a deep copy of a record
     */
    @SuppressWarnings("unchecked")
    private Object cloneRecord(Object record) {
        if (record instanceof Map) {
            Map<String, Object> originalMap = (Map<String, Object>) record;
            Map<String, Object> clonedMap = new HashMap<>();
            
            for (Map.Entry<String, Object> entry : originalMap.entrySet()) {
                clonedMap.put(entry.getKey(), cloneRecord(entry.getValue()));
            }
            
            return clonedMap;
        } else if (record instanceof List) {
            List<Object> originalList = (List<Object>) record;
            return originalList.stream()
                .map(this::cloneRecord)
                .collect(java.util.stream.Collectors.toList());
        } else {
            return record; // Primitive types and strings are immutable
        }
    }
    
    /**
     * Loads or generates the data encryption key
     */
    private SecretKey loadOrGenerateDataKey(HttpSourceConnectorConfig config) {
        String keyBase64 = config.getFieldEncryptionKey();
        
        if (keyBase64 != null && !keyBase64.trim().isEmpty()) {
            // Load existing key
            try {
                byte[] keyBytes = Base64.getDecoder().decode(keyBase64);
                return new SecretKeySpec(keyBytes, ENCRYPTION_ALGORITHM);
            } catch (Exception e) {
                log.warn("Failed to load encryption key from configuration, generating new key: {}", e.getMessage());
            }
        }
        
        // Generate new key
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(ENCRYPTION_ALGORITHM);
            keyGenerator.init(256); // AES-256
            SecretKey key = keyGenerator.generateKey();
            
            String generatedKeyBase64 = Base64.getEncoder().encodeToString(key.getEncoded());
            log.warn("Generated new encryption key. Save this key securely: {}", generatedKeyBase64);
            
            return key;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to generate encryption key", e);
        }
    }
    
    /**
     * Parses field encryption rules from configuration
     * Format: "field1:AES_GCM,api1.field2:DETERMINISTIC,field3:RANDOM"
     */
    private Map<String, String> parseEncryptionRules(String rulesConfig) {
        Map<String, String> rules = new HashMap<>();
        
        if (rulesConfig == null || rulesConfig.trim().isEmpty()) {
            return rules;
        }
        
        String[] rulePairs = rulesConfig.split(",");
        for (String rulePair : rulePairs) {
            rulePair = rulePair.trim();
            if (rulePair.isEmpty()) continue;
            
            String[] parts = rulePair.split(":", 2);
            if (parts.length == 2) {
                String fieldPath = parts[0].trim();
                String encryptionType = parts[1].trim();
                rules.put(fieldPath, encryptionType);
                log.debug("Configured field encryption: {} -> {}", fieldPath, encryptionType);
            } else {
                log.warn("Invalid encryption rule format: {}. Expected 'field:type'", rulePair);
            }
        }
        
        return rules;
    }
    
    /**
     * Decrypts a value (for testing purposes)
     */
    public String decryptValue(String encryptedValue) throws Exception {
        if (!encryptionEnabled || encryptedValue == null) {
            return encryptedValue;
        }
        
        byte[] encryptedWithIv = Base64.getDecoder().decode(encryptedValue);
        
        // Extract IV and encrypted data
        byte[] iv = new byte[GCM_IV_LENGTH];
        byte[] encryptedData = new byte[encryptedWithIv.length - GCM_IV_LENGTH];
        
        System.arraycopy(encryptedWithIv, 0, iv, 0, GCM_IV_LENGTH);
        System.arraycopy(encryptedWithIv, GCM_IV_LENGTH, encryptedData, 0, encryptedData.length);
        
        // Decrypt
        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
        cipher.init(Cipher.DECRYPT_MODE, dataEncryptionKey, gcmSpec);
        
        byte[] decryptedData = cipher.doFinal(encryptedData);
        return new String(decryptedData, StandardCharsets.UTF_8);
    }
    
    /**
     * Checks if encryption is enabled
     */
    public boolean isEncryptionEnabled() {
        return encryptionEnabled;
    }
}