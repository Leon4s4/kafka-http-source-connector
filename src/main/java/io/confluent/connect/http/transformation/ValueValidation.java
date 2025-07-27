package io.confluent.connect.http.transformation;

/**
 * Represents validation rules for field values.
 */
public class ValueValidation {
    
    private final boolean required;
    private final String pattern;
    private final int minLength;
    private final int maxLength;
    private final Object minValue;
    private final Object maxValue;
    
    private ValueValidation(Builder builder) {
        this.required = builder.required;
        this.pattern = builder.pattern;
        this.minLength = builder.minLength;
        this.maxLength = builder.maxLength;
        this.minValue = builder.minValue;
        this.maxValue = builder.maxValue;
    }
    
    public boolean isRequired() {
        return required;
    }
    
    public String getPattern() {
        return pattern;
    }
    
    public int getMinLength() {
        return minLength;
    }
    
    public int getMaxLength() {
        return maxLength;
    }
    
    public Object getMinValue() {
        return minValue;
    }
    
    public Object getMaxValue() {
        return maxValue;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private boolean required = false;
        private String pattern;
        private int minLength = 0;
        private int maxLength = 0;
        private Object minValue;
        private Object maxValue;
        
        public Builder required(boolean required) {
            this.required = required;
            return this;
        }
        
        public Builder pattern(String pattern) {
            this.pattern = pattern;
            return this;
        }
        
        public Builder minLength(int minLength) {
            this.minLength = minLength;
            return this;
        }
        
        public Builder maxLength(int maxLength) {
            this.maxLength = maxLength;
            return this;
        }
        
        public Builder minValue(Object minValue) {
            this.minValue = minValue;
            return this;
        }
        
        public Builder maxValue(Object maxValue) {
            this.maxValue = maxValue;
            return this;
        }
        
        public ValueValidation build() {
            return new ValueValidation(this);
        }
    }
}
