package io.confluent.connect.http.transformation;

/**
 * Represents a filter expression for filtering records based on field values.
 */
public class FilterExpression {
    
    public enum FilterOperator {
        EQUALS,
        NOT_EQUALS,
        CONTAINS,
        NOT_CONTAINS,
        GREATER_THAN,
        LESS_THAN,
        GREATER_EQUAL,
        LESS_EQUAL,
        EXISTS,
        NOT_EXISTS,
        REGEX_MATCH,
        IN,
        NOT_IN
    }
    
    private final String fieldPath;
    private final FilterOperator operator;
    private final Object expectedValue;
    private final boolean logicalAnd;
    private final FilterExpression nextExpression;
    
    private FilterExpression(Builder builder) {
        this.fieldPath = builder.fieldPath;
        this.operator = builder.operator;
        this.expectedValue = builder.expectedValue;
        this.logicalAnd = builder.logicalAnd;
        this.nextExpression = builder.nextExpression;
    }
    
    public String getFieldPath() {
        return fieldPath;
    }
    
    public FilterOperator getOperator() {
        return operator;
    }
    
    public Object getExpectedValue() {
        return expectedValue;
    }
    
    public boolean isLogicalAnd() {
        return logicalAnd;
    }
    
    public FilterExpression getNextExpression() {
        return nextExpression;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private String fieldPath;
        private FilterOperator operator;
        private Object expectedValue;
        private boolean logicalAnd = true;
        private FilterExpression nextExpression;
        
        public Builder fieldPath(String fieldPath) {
            this.fieldPath = fieldPath;
            return this;
        }
        
        public Builder operator(FilterOperator operator) {
            this.operator = operator;
            return this;
        }
        
        public Builder expectedValue(Object expectedValue) {
            this.expectedValue = expectedValue;
            return this;
        }
        
        public Builder and(FilterExpression nextExpression) {
            this.logicalAnd = true;
            this.nextExpression = nextExpression;
            return this;
        }
        
        public Builder or(FilterExpression nextExpression) {
            this.logicalAnd = false;
            this.nextExpression = nextExpression;
            return this;
        }
        
        public FilterExpression build() {
            return new FilterExpression(this);
        }
    }
}
