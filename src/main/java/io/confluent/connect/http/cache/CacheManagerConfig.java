package io.confluent.connect.http.cache;

import java.util.Map;

/**
 * Configuration for the Intelligent Cache Manager.
 * Provides settings for all cache types and general cache management.
 */
public class CacheManagerConfig {
    
    // General cache settings
    private final boolean enabled;
    private final long maintenanceIntervalSeconds;
    private final boolean enableStatistics;
    
    // Response cache settings
    private final int responseCacheMaxSize;
    private final long responseCacheTtlSeconds;
    
    // Schema cache settings
    private final int schemaCacheMaxSize;
    private final long schemaCacheTtlSeconds;
    
    // Transformed data cache settings
    private final int transformedCacheMaxSize;
    private final long transformedCacheTtlSeconds;
    
    // Auth cache settings
    private final int authCacheMaxSize;
    private final long authCacheTtlSeconds;
    
    // Metadata cache settings
    private final int metadataCacheMaxSize;
    private final long metadataCacheTtlSeconds;
    
    private CacheManagerConfig(Builder builder) {
        this.enabled = builder.enabled;
        this.maintenanceIntervalSeconds = builder.maintenanceIntervalSeconds;
        this.enableStatistics = builder.enableStatistics;
        
        this.responseCacheMaxSize = builder.responseCacheMaxSize;
        this.responseCacheTtlSeconds = builder.responseCacheTtlSeconds;
        
        this.schemaCacheMaxSize = builder.schemaCacheMaxSize;
        this.schemaCacheTtlSeconds = builder.schemaCacheTtlSeconds;
        
        this.transformedCacheMaxSize = builder.transformedCacheMaxSize;
        this.transformedCacheTtlSeconds = builder.transformedCacheTtlSeconds;
        
        this.authCacheMaxSize = builder.authCacheMaxSize;
        this.authCacheTtlSeconds = builder.authCacheTtlSeconds;
        
        this.metadataCacheMaxSize = builder.metadataCacheMaxSize;
        this.metadataCacheTtlSeconds = builder.metadataCacheTtlSeconds;
    }
    
    /**
     * Create a cache manager config from Kafka Connect configuration.
     */
    public static CacheManagerConfig fromConnectorConfig(Map<String, String> props) {
        Builder builder = new Builder();
        
        // General settings
        builder.enabled(Boolean.parseBoolean(props.getOrDefault("cache.enabled", "true")));
        builder.maintenanceIntervalSeconds(Long.parseLong(props.getOrDefault("cache.maintenance.interval.seconds", "300")));
        builder.enableStatistics(Boolean.parseBoolean(props.getOrDefault("cache.statistics.enabled", "true")));
        
        // Response cache
        builder.responseCacheMaxSize(Integer.parseInt(props.getOrDefault("cache.response.max.size", "1000")));
        builder.responseCacheTtlSeconds(Long.parseLong(props.getOrDefault("cache.response.ttl.seconds", "3600")));
        
        // Schema cache
        builder.schemaCacheMaxSize(Integer.parseInt(props.getOrDefault("cache.schema.max.size", "100")));
        builder.schemaCacheTtlSeconds(Long.parseLong(props.getOrDefault("cache.schema.ttl.seconds", "7200")));
        
        // Transformed data cache
        builder.transformedCacheMaxSize(Integer.parseInt(props.getOrDefault("cache.transformed.max.size", "500")));
        builder.transformedCacheTtlSeconds(Long.parseLong(props.getOrDefault("cache.transformed.ttl.seconds", "1800")));
        
        // Auth cache
        builder.authCacheMaxSize(Integer.parseInt(props.getOrDefault("cache.auth.max.size", "50")));
        builder.authCacheTtlSeconds(Long.parseLong(props.getOrDefault("cache.auth.ttl.seconds", "900")));
        
        // Metadata cache
        builder.metadataCacheMaxSize(Integer.parseInt(props.getOrDefault("cache.metadata.max.size", "200")));
        builder.metadataCacheTtlSeconds(Long.parseLong(props.getOrDefault("cache.metadata.ttl.seconds", "3600")));
        
        return builder.build();
    }
    
    /**
     * Create default configuration.
     */
    public static CacheManagerConfig createDefault() {
        return new Builder().build();
    }
    
    // Getters
    public boolean isEnabled() {
        return enabled;
    }
    
    public long getMaintenanceIntervalSeconds() {
        return maintenanceIntervalSeconds;
    }
    
    public boolean isEnableStatistics() {
        return enableStatistics;
    }
    
    public int getResponseCacheMaxSize() {
        return responseCacheMaxSize;
    }
    
    public long getResponseCacheTtlSeconds() {
        return responseCacheTtlSeconds;
    }
    
    public int getSchemaCacheMaxSize() {
        return schemaCacheMaxSize;
    }
    
    public long getSchemaCacheTtlSeconds() {
        return schemaCacheTtlSeconds;
    }
    
    public int getTransformedCacheMaxSize() {
        return transformedCacheMaxSize;
    }
    
    public long getTransformedCacheTtlSeconds() {
        return transformedCacheTtlSeconds;
    }
    
    public int getAuthCacheMaxSize() {
        return authCacheMaxSize;
    }
    
    public long getAuthCacheTtlSeconds() {
        return authCacheTtlSeconds;
    }
    
    public int getMetadataCacheMaxSize() {
        return metadataCacheMaxSize;
    }
    
    public long getMetadataCacheTtlSeconds() {
        return metadataCacheTtlSeconds;
    }
    
    @Override
    public String toString() {
        return String.format("CacheManagerConfig{enabled=%s, maintenance=%ds, response=%d/%ds, schema=%d/%ds, transformed=%d/%ds, auth=%d/%ds, metadata=%d/%ds}",
                           enabled, maintenanceIntervalSeconds,
                           responseCacheMaxSize, responseCacheTtlSeconds,
                           schemaCacheMaxSize, schemaCacheTtlSeconds,
                           transformedCacheMaxSize, transformedCacheTtlSeconds,
                           authCacheMaxSize, authCacheTtlSeconds,
                           metadataCacheMaxSize, metadataCacheTtlSeconds);
    }
    
    /**
     * Builder for CacheManagerConfig.
     */
    public static class Builder {
        private boolean enabled = true;
        private long maintenanceIntervalSeconds = 300; // 5 minutes
        private boolean enableStatistics = true;
        
        private int responseCacheMaxSize = 1000;
        private long responseCacheTtlSeconds = 3600; // 1 hour
        
        private int schemaCacheMaxSize = 100;
        private long schemaCacheTtlSeconds = 7200; // 2 hours
        
        private int transformedCacheMaxSize = 500;
        private long transformedCacheTtlSeconds = 1800; // 30 minutes
        
        private int authCacheMaxSize = 50;
        private long authCacheTtlSeconds = 900; // 15 minutes
        
        private int metadataCacheMaxSize = 200;
        private long metadataCacheTtlSeconds = 3600; // 1 hour
        
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }
        
        public Builder maintenanceIntervalSeconds(long maintenanceIntervalSeconds) {
            this.maintenanceIntervalSeconds = Math.max(60, maintenanceIntervalSeconds); // Minimum 1 minute
            return this;
        }
        
        public Builder enableStatistics(boolean enableStatistics) {
            this.enableStatistics = enableStatistics;
            return this;
        }
        
        public Builder responseCacheMaxSize(int responseCacheMaxSize) {
            this.responseCacheMaxSize = Math.max(10, responseCacheMaxSize);
            return this;
        }
        
        public Builder responseCacheTtlSeconds(long responseCacheTtlSeconds) {
            this.responseCacheTtlSeconds = Math.max(60, responseCacheTtlSeconds);
            return this;
        }
        
        public Builder schemaCacheMaxSize(int schemaCacheMaxSize) {
            this.schemaCacheMaxSize = Math.max(10, schemaCacheMaxSize);
            return this;
        }
        
        public Builder schemaCacheTtlSeconds(long schemaCacheTtlSeconds) {
            this.schemaCacheTtlSeconds = Math.max(300, schemaCacheTtlSeconds);
            return this;
        }
        
        public Builder transformedCacheMaxSize(int transformedCacheMaxSize) {
            this.transformedCacheMaxSize = Math.max(10, transformedCacheMaxSize);
            return this;
        }
        
        public Builder transformedCacheTtlSeconds(long transformedCacheTtlSeconds) {
            this.transformedCacheTtlSeconds = Math.max(60, transformedCacheTtlSeconds);
            return this;
        }
        
        public Builder authCacheMaxSize(int authCacheMaxSize) {
            this.authCacheMaxSize = Math.max(5, authCacheMaxSize);
            return this;
        }
        
        public Builder authCacheTtlSeconds(long authCacheTtlSeconds) {
            this.authCacheTtlSeconds = Math.max(60, authCacheTtlSeconds);
            return this;
        }
        
        public Builder metadataCacheMaxSize(int metadataCacheMaxSize) {
            this.metadataCacheMaxSize = Math.max(10, metadataCacheMaxSize);
            return this;
        }
        
        public Builder metadataCacheTtlSeconds(long metadataCacheTtlSeconds) {
            this.metadataCacheTtlSeconds = Math.max(300, metadataCacheTtlSeconds);
            return this;
        }
        
        public CacheManagerConfig build() {
            return new CacheManagerConfig(this);
        }
    }
}
