package io.confluent.connect.http.transformation;

import java.util.List;
import java.util.ArrayList;

/**
 * Represents a complete transformation rule with field mappings, filters, and enrichments.
 */
public class TransformationRule {
    
    private final List<FieldMapping> fieldMappings;
    private final FilterExpression filter;
    private final List<FieldEnrichment> enrichments;
    private final String description;
    
    private TransformationRule(Builder builder) {
        this.fieldMappings = new ArrayList<>(builder.fieldMappings);
        this.filter = builder.filter;
        this.enrichments = new ArrayList<>(builder.enrichments);
        this.description = builder.description;
    }
    
    public List<FieldMapping> getFieldMappings() {
        return new ArrayList<>(fieldMappings);
    }
    
    public FilterExpression getFilter() {
        return filter;
    }
    
    public List<FieldEnrichment> getEnrichments() {
        return new ArrayList<>(enrichments);
    }
    
    public String getDescription() {
        return description;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private final List<FieldMapping> fieldMappings = new ArrayList<>();
        private FilterExpression filter;
        private final List<FieldEnrichment> enrichments = new ArrayList<>();
        private String description;
        
        public Builder addFieldMapping(FieldMapping mapping) {
            this.fieldMappings.add(mapping);
            return this;
        }
        
        public Builder filter(FilterExpression filter) {
            this.filter = filter;
            return this;
        }
        
        public Builder addEnrichment(FieldEnrichment enrichment) {
            this.enrichments.add(enrichment);
            return this;
        }
        
        public Builder description(String description) {
            this.description = description;
            return this;
        }
        
        public TransformationRule build() {
            return new TransformationRule(this);
        }
    }
}
