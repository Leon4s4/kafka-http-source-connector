package io.confluent.connect.http.transformation;

/**
 * Represents a field enrichment operation that adds computed values to records.
 */
public class FieldEnrichment {
    
    public enum EnrichmentType {
        TIMESTAMP,
        UUID,
        CONSTANT,
        TEMPLATE,
        SEQUENCE,
        RANDOM
    }
    
    private final String targetPath;
    private final EnrichmentType type;
    private final Object value;
    private final String template;
    
    private FieldEnrichment(Builder builder) {
        this.targetPath = builder.targetPath;
        this.type = builder.type;
        this.value = builder.value;
        this.template = builder.template;
    }
    
    public String getTargetPath() {
        return targetPath;
    }
    
    public EnrichmentType getType() {
        return type;
    }
    
    public Object getValue() {
        return value;
    }
    
    public String getTemplate() {
        return template;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private String targetPath;
        private EnrichmentType type;
        private Object value;
        private String template;
        
        public Builder targetPath(String targetPath) {
            this.targetPath = targetPath;
            return this;
        }
        
        public Builder type(EnrichmentType type) {
            this.type = type;
            return this;
        }
        
        public Builder value(Object value) {
            this.value = value;
            return this;
        }
        
        public Builder template(String template) {
            this.template = template;
            return this;
        }
        
        public FieldEnrichment build() {
            return new FieldEnrichment(this);
        }
    }
}
