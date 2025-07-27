package io.confluent.connect.http.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validation result container for configuration validation.
 */
public class ValidationResult {
    
    private final List<ValidationError> errors;
    private final List<ValidationWarning> warnings;
    private final Map<String, Object> metadata;
    
    public ValidationResult() {
        this.errors = new ArrayList<>();
        this.warnings = new ArrayList<>();
        this.metadata = new ConcurrentHashMap<>();
    }
    
    /**
     * Add validation error.
     */
    public void addError(String code, String message) {
        errors.add(new ValidationError(code, message));
    }
    
    /**
     * Add validation warning.
     */
    public void addWarning(String code, String message) {
        warnings.add(new ValidationWarning(code, message));
    }
    
    /**
     * Add metadata about validation.
     */
    public void addMetadata(String key, Object value) {
        metadata.put(key, value);
    }
    
    /**
     * Check if validation passed (no errors).
     */
    public boolean isValid() {
        return errors.isEmpty();
    }
    
    /**
     * Check if there are validation errors.
     */
    public boolean hasErrors() {
        return !errors.isEmpty();
    }
    
    /**
     * Check if there are validation warnings.
     */
    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
    
    /**
     * Get validation errors.
     */
    public List<ValidationError> getErrors() {
        return new ArrayList<>(errors);
    }
    
    /**
     * Get validation warnings.
     */
    public List<ValidationWarning> getWarnings() {
        return new ArrayList<>(warnings);
    }
    
    /**
     * Get validation metadata.
     */
    public Map<String, Object> getMetadata() {
        return new ConcurrentHashMap<>(metadata);
    }
    
    /**
     * Get total issue count.
     */
    public int getTotalIssues() {
        return errors.size() + warnings.size();
    }
    
    /**
     * Create successful validation result.
     */
    public static ValidationResult success() {
        return new ValidationResult();
    }
    
    /**
     * Create validation result with error.
     */
    public static ValidationResult error(String code, String message) {
        ValidationResult result = new ValidationResult();
        result.addError(code, message);
        return result;
    }
    
    /**
     * Create validation result with warning.
     */
    public static ValidationResult warning(String code, String message) {
        ValidationResult result = new ValidationResult();
        result.addWarning(code, message);
        return result;
    }
    
    /**
     * Merge another validation result into this one.
     */
    public void merge(ValidationResult other) {
        if (other != null) {
            this.errors.addAll(other.errors);
            this.warnings.addAll(other.warnings);
            this.metadata.putAll(other.metadata);
        }
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ValidationResult{");
        sb.append("valid=").append(isValid());
        sb.append(", errors=").append(errors.size());
        sb.append(", warnings=").append(warnings.size());
        
        if (!errors.isEmpty()) {
            sb.append(", errorDetails=[");
            for (int i = 0; i < errors.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(errors.get(i));
            }
            sb.append("]");
        }
        
        if (!warnings.isEmpty()) {
            sb.append(", warningDetails=[");
            for (int i = 0; i < warnings.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(warnings.get(i));
            }
            sb.append("]");
        }
        
        sb.append("}");
        return sb.toString();
    }
    
    /**
     * Validation error class.
     */
    public static class ValidationError {
        private final String code;
        private final String message;
        
        public ValidationError(String code, String message) {
            this.code = code;
            this.message = message;
        }
        
        public String getCode() {
            return code;
        }
        
        public String getMessage() {
            return message;
        }
        
        @Override
        public String toString() {
            return String.format("Error[%s]: %s", code, message);
        }
    }
    
    /**
     * Validation warning class.
     */
    public static class ValidationWarning {
        private final String code;
        private final String message;
        
        public ValidationWarning(String code, String message) {
            this.code = code;
            this.message = message;
        }
        
        public String getCode() {
            return code;
        }
        
        public String getMessage() {
            return message;
        }
        
        @Override
        public String toString() {
            return String.format("Warning[%s]: %s", code, message);
        }
    }
}
