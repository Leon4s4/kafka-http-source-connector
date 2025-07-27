package io.confluent.connect.http.transformation;

/**
 * Represents a field mapping transformation from source to target path.
 */
public class FieldMapping {
    
    private final String sourcePath;
    private final String targetPath;
    private final String targetType;
    private final String template;
    private final Object defaultValue;
    private final ValueValidation validation;
    
    private FieldMapping(Builder builder) {
        this.sourcePath = builder.sourcePath;
        this.targetPath = builder.targetPath;
        this.targetType = builder.targetType;
        this.template = builder.template;
        this.defaultValue = builder.defaultValue;
        this.validation = builder.validation;
    }
    
    public String getSourcePath() {
        return sourcePath;
    }
    
    public String getTargetPath() {
        return targetPath;
    }
    
    public String getTargetType() {
        return targetType;
    }
    
    public String getTemplate() {
        return template;
    }
    
    public Object getDefaultValue() {
        return defaultValue;
    }
    
    public ValueValidation getValidation() {
        return validation;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private String sourcePath;
        private String targetPath;
        private String targetType;
        private String template;
        private Object defaultValue;
        private ValueValidation validation;
        
        public Builder sourcePath(String sourcePath) {
            this.sourcePath = sourcePath;
            return this;
        }
        
        public Builder targetPath(String targetPath) {
            this.targetPath = targetPath;
            return this;
        }
        
        public Builder targetType(String targetType) {
            this.targetType = targetType;
            return this;
        }
        
        public Builder template(String template) {
            this.template = template;
            return this;
        }
        
        public Builder defaultValue(Object defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }
        
        public Builder validation(ValueValidation validation) {
            this.validation = validation;
            return this;
        }
        
        public FieldMapping build() {
            return new FieldMapping(this);
        }
    }
}
