package io.confluent.connect.http.cache;

import org.apache.kafka.connect.data.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance schema cache optimized for Kafka Connect schemas.
 * Provides fast schema lookup and caching with performance metrics.
 */
public class SchemaCache {
    
    private static final Logger log = LoggerFactory.getLogger(SchemaCache.class);
    
    private final IntelligentCacheManager cacheManager;
    private final ConcurrentHashMap<String, String> schemaHashMap; // Hash -> Schema Name mapping
    
    // Schema-specific metrics
    private final AtomicLong schemaHits = new AtomicLong(0);
    private final AtomicLong schemaMisses = new AtomicLong(0);
    private final AtomicLong schemaComputations = new AtomicLong(0);
    
    public SchemaCache(IntelligentCacheManager cacheManager) {
        this.cacheManager = cacheManager;
        this.schemaHashMap = new ConcurrentHashMap<>();
        
        log.info("Schema cache initialized with intelligent cache manager");
    }
    
    /**
     * Get a cached schema by name.
     */
    public Schema getSchema(String schemaName) {
        if (schemaName == null || schemaName.trim().isEmpty()) {
            return null;
        }
        
        Schema schema = cacheManager.get(IntelligentCacheManager.CacheType.SCHEMA, schemaName, Schema.class);
        
        if (schema != null) {
            schemaHits.incrementAndGet();
            log.trace("Schema cache hit for: {}", schemaName);
        } else {
            schemaMisses.incrementAndGet();
            log.trace("Schema cache miss for: {}", schemaName);
        }
        
        return schema;
    }
    
    /**
     * Cache a schema with its name.
     */
    public void putSchema(String schemaName, Schema schema) {
        if (schemaName == null || schema == null) {
            return;
        }
        
        // Calculate schema hash for deduplication
        String schemaHash = calculateSchemaHash(schema);
        
        // Check if we already have this schema cached under a different name
        String existingSchemaName = schemaHashMap.get(schemaHash);
        if (existingSchemaName != null && !existingSchemaName.equals(schemaName)) {
            // Reuse existing schema object
            Schema existingSchema = cacheManager.get(IntelligentCacheManager.CacheType.SCHEMA, existingSchemaName, Schema.class);
            if (existingSchema != null) {
                cacheManager.put(IntelligentCacheManager.CacheType.SCHEMA, schemaName, existingSchema);
                log.trace("Reused existing schema for '{}' from '{}'", schemaName, existingSchemaName);
                return;
            }
        }
        
        // Cache new schema
        cacheManager.put(IntelligentCacheManager.CacheType.SCHEMA, schemaName, schema);
        schemaHashMap.put(schemaHash, schemaName);
        
        log.trace("Cached new schema: {} (hash: {})", schemaName, schemaHash.substring(0, 8));
    }
    
    /**
     * Get or compute a schema using a schema provider function.
     */
    public Schema getOrComputeSchema(String schemaName, SchemaProvider provider) {
        Schema schema = getSchema(schemaName);
        
        if (schema == null) {
            try {
                schema = provider.computeSchema(schemaName);
                if (schema != null) {
                    putSchema(schemaName, schema);
                    schemaComputations.incrementAndGet();
                    log.debug("Computed and cached schema: {}", schemaName);
                }
            } catch (Exception e) {
                log.error("Failed to compute schema '{}': {}", schemaName, e.getMessage(), e);
            }
        }
        
        return schema;
    }
    
    /**
     * Check if a schema is cached.
     */
    public boolean containsSchema(String schemaName) {
        if (schemaName == null) {
            return false;
        }
        
        return cacheManager.get(IntelligentCacheManager.CacheType.SCHEMA, schemaName, Schema.class) != null;
    }
    
    /**
     * Remove a schema from cache.
     */
    public void removeSchema(String schemaName) {
        if (schemaName == null) {
            return;
        }
        
        Schema removedSchema = cacheManager.get(IntelligentCacheManager.CacheType.SCHEMA, schemaName, Schema.class);
        if (removedSchema != null) {
            cacheManager.remove(IntelligentCacheManager.CacheType.SCHEMA, schemaName);
            
            // Remove from hash map
            String schemaHash = calculateSchemaHash(removedSchema);
            schemaHashMap.remove(schemaHash, schemaName);
            
            log.debug("Removed schema from cache: {}", schemaName);
        }
    }
    
    /**
     * Clear all cached schemas.
     */
    public void clearAll() {
        cacheManager.clear(IntelligentCacheManager.CacheType.SCHEMA);
        schemaHashMap.clear();
        
        log.info("All schemas cleared from cache");
    }
    
    /**
     * Get schema cache statistics.
     */
    public SchemaCacheStatistics getStatistics() {
        SchemaCacheStatistics stats = new SchemaCacheStatistics();
        
        stats.hits = schemaHits.get();
        stats.misses = schemaMisses.get();
        stats.computations = schemaComputations.get();
        stats.totalRequests = stats.hits + stats.misses;
        stats.hitRatio = stats.totalRequests > 0 ? (double) stats.hits / stats.totalRequests : 0.0;
        stats.uniqueSchemas = schemaHashMap.size();
        
        // Get cache size from cache manager
        IntelligentCacheManager.CacheStatistics globalStats = cacheManager.getStatistics();
        IntelligentCacheManager.CacheTypeStatistics typeStats = globalStats.typeStatistics.get(IntelligentCacheManager.CacheType.SCHEMA);
        if (typeStats != null) {
            stats.cacheSize = typeStats.size;
            stats.maxCacheSize = typeStats.maxSize;
            stats.utilization = typeStats.getUtilization();
        }
        
        return stats;
    }
    
    /**
     * Calculate a hash for schema deduplication.
     */
    private String calculateSchemaHash(Schema schema) {
        if (schema == null) {
            return "";
        }
        
        // Use schema string representation for hash calculation
        String schemaStr = schema.toString();
        return Integer.toHexString(schemaStr.hashCode());
    }
    
    /**
     * Interface for schema computation.
     */
    @FunctionalInterface
    public interface SchemaProvider {
        Schema computeSchema(String schemaName) throws Exception;
    }
    
    /**
     * Schema cache statistics.
     */
    public static class SchemaCacheStatistics {
        public long hits;
        public long misses;
        public long computations;
        public long totalRequests;
        public double hitRatio;
        public int uniqueSchemas;
        public int cacheSize;
        public int maxCacheSize;
        public double utilization;
        
        @Override
        public String toString() {
            return String.format("SchemaCacheStats{hits=%d, misses=%d, hitRatio=%.2f%%, computations=%d, unique=%d, size=%d/%d (%.1f%%)}",
                               hits, misses, hitRatio * 100, computations, uniqueSchemas, cacheSize, maxCacheSize, utilization * 100);
        }
    }
}
