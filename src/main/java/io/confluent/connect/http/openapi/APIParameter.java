package io.confluent.connect.http.openapi;

/**
 * Represents a parameter in an API endpoint for OpenAPI documentation.
 * Can be a query parameter, path parameter, header, etc.
 */
public class APIParameter {
    
    public enum Location {
        QUERY("query"),
        PATH("path"),
        HEADER("header"),
        COOKIE("cookie");
        
        private final String openApiValue;
        
        Location(String openApiValue) {
            this.openApiValue = openApiValue;
        }
        
        public String getOpenApiValue() {
            return openApiValue;
        }
    }
    
    private final String name;
    private final Location location;
    private final String type;
    private final boolean required;
    private final String description;
    private final String example;
    private final String defaultValue;
    
    private APIParameter(Builder builder) {
        this.name = builder.name;
        this.location = builder.location;
        this.type = builder.type;
        this.required = builder.required;
        this.description = builder.description;
        this.example = builder.example;
        this.defaultValue = builder.defaultValue;
    }
    
    public String getName() {
        return name;
    }
    
    public String getLocation() {
        return location.getOpenApiValue();
    }
    
    public Location getLocationEnum() {
        return location;
    }
    
    public String getType() {
        return type;
    }
    
    public boolean isRequired() {
        return required;
    }
    
    public String getDescription() {
        return description;
    }
    
    public String getExample() {
        return example;
    }
    
    public String getDefaultValue() {
        return defaultValue;
    }
    
    public static Builder builder(String name, Location location) {
        return new Builder(name, location);
    }
    
    public static class Builder {
        private final String name;
        private final Location location;
        private String type = "string";
        private boolean required = false;
        private String description;
        private String example;
        private String defaultValue;
        
        public Builder(String name, Location location) {
            this.name = name;
            this.location = location;
        }
        
        public Builder type(String type) {
            this.type = type;
            return this;
        }
        
        public Builder required(boolean required) {
            this.required = required;
            return this;
        }
        
        public Builder description(String description) {
            this.description = description;
            return this;
        }
        
        public Builder example(String example) {
            this.example = example;
            return this;
        }
        
        public Builder defaultValue(String defaultValue) {
            this.defaultValue = defaultValue;
            return this;
        }
        
        public APIParameter build() {
            return new APIParameter(this);
        }
    }
    
    /**
     * Create a query parameter.
     */
    public static APIParameter queryParam(String name) {
        return builder(name, Location.QUERY).build();
    }
    
    /**
     * Create a required query parameter.
     */
    public static APIParameter requiredQueryParam(String name) {
        return builder(name, Location.QUERY).required(true).build();
    }
    
    /**
     * Create a path parameter (always required).
     */
    public static APIParameter pathParam(String name) {
        return builder(name, Location.PATH).required(true).build();
    }
    
    /**
     * Create a header parameter.
     */
    public static APIParameter headerParam(String name) {
        return builder(name, Location.HEADER).build();
    }
    
    /**
     * Create a required header parameter.
     */
    public static APIParameter requiredHeaderParam(String name) {
        return builder(name, Location.HEADER).required(true).build();
    }
    
    @Override
    public String toString() {
        return String.format("APIParameter{name='%s', location=%s, type='%s', required=%s}",
                           name, location, type, required);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        
        APIParameter that = (APIParameter) o;
        
        if (!name.equals(that.name)) return false;
        return location == that.location;
    }
    
    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + location.hashCode();
        return result;
    }
}
