package io.confluent.connect.http.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Intelligent caching system for HTTP responses, schemas, and computed data.
 * Provides multiple cache levels with TTL, LRU eviction, and performance metrics.
 */
public class IntelligentCacheManager {
    
    private static final Logger log = LoggerFactory.getLogger(IntelligentCacheManager.class);
    
    public enum CacheType {
        RESPONSE,      // HTTP response caching
        SCHEMA,        // Schema caching for performance
        TRANSFORMED,   // Cached transformed data
        AUTH,          // Authentication token caching
        METADATA       // API metadata caching
    }
    
    private final ConcurrentHashMap<CacheType, Cache<String, CacheEntry>> caches;
    private final ConcurrentHashMap<CacheType, CacheConfig> cacheConfigs;
    private final ScheduledExecutorService maintenanceScheduler;
    
    // Global cache statistics
    private final AtomicLong totalHits = new AtomicLong(0);
    private final AtomicLong totalMisses = new AtomicLong(0);
    private final AtomicLong totalEvictions = new AtomicLong(0);
    private final AtomicLong totalExpired = new AtomicLong(0);
    
    // Cache management settings
    private final boolean enabled;
    private final long maintenanceIntervalSeconds;
    private final boolean enableStatistics;
    
    public IntelligentCacheManager(CacheManagerConfig config) {
        this.caches = new ConcurrentHashMap<>();
        this.cacheConfigs = new ConcurrentHashMap<>();
        this.maintenanceScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cache-maintenance");
            t.setDaemon(true);
            return t;
        });
        
        this.enabled = config.isEnabled();
        this.maintenanceIntervalSeconds = config.getMaintenanceIntervalSeconds();
        this.enableStatistics = config.isEnableStatistics();
        
        // Initialize cache configurations
        initializeCacheConfigs(config);
        
        // Initialize caches
        initializeCaches();
        
        // Start maintenance scheduler
        if (enabled) {
            startMaintenanceScheduler();
        }
        
        log.info("Intelligent cache manager initialized: enabled={}, caches={}, maintenance={}s",
                enabled, caches.size(), maintenanceIntervalSeconds);
    }
    
    /**
     * Get a cached value.
     */
    public <T> T get(CacheType type, String key, Class<T> valueType) {
        if (!enabled) {
            return null;
        }
        
        Cache<String, CacheEntry> cache = caches.get(type);
        if (cache == null) {
            return null;
        }
        
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            totalMisses.incrementAndGet();
            return null;
        }
        
        // Check expiration
        if (entry.isExpired()) {
            cache.remove(key);
            totalExpired.incrementAndGet();
            totalMisses.incrementAndGet();
            return null;
        }
        
        // Update access time for LRU
        entry.updateAccessTime();
        totalHits.incrementAndGet();
        
        try {
            return valueType.cast(entry.getValue());
        } catch (ClassCastException e) {
            log.warn("Cache value type mismatch for key '{}': expected {}, got {}", 
                    key, valueType.getSimpleName(), entry.getValue().getClass().getSimpleName());
            return null;
        }
    }
    
    /**
     * Put a value in the cache.
     */
    public <T> void put(CacheType type, String key, T value, long ttlSeconds) {
        if (!enabled || value == null) {
            return;
        }
        
        Cache<String, CacheEntry> cache = caches.get(type);
        if (cache == null) {
            return;
        }
        
        CacheConfig config = cacheConfigs.get(type);
        if (config == null) {
            return;
        }
        
        // Check cache size limit
        if (cache.size() >= config.getMaxSize()) {
            evictLRU(cache, config.getMaxSize() / 4); // Evict 25% when full
        }
        
        // Calculate expiry time
        Instant expiryTime = ttlSeconds > 0 ? 
            Instant.now().plusSeconds(ttlSeconds) : 
            Instant.now().plusSeconds(config.getDefaultTtlSeconds());
        
        CacheEntry entry = new CacheEntry(value, expiryTime);
        cache.put(key, entry);
        
        log.trace("Cached value for type {} with key '{}', expires at {}", type, key, expiryTime);
    }
    
    /**
     * Put a value with default TTL.
     */
    public <T> void put(CacheType type, String key, T value) {
        CacheConfig config = cacheConfigs.get(type);
        long ttl = config != null ? config.getDefaultTtlSeconds() : 3600; // 1 hour default
        put(type, key, value, ttl);
    }
    
    /**
     * Remove a cached value.
     */
    public void remove(CacheType type, String key) {
        if (!enabled) {
            return;
        }
        
        Cache<String, CacheEntry> cache = caches.get(type);
        if (cache != null) {
            cache.remove(key);
        }
    }
    
    /**
     * Clear all caches.
     */
    public void clearAll() {
        if (!enabled) {
            return;
        }
        
        for (Cache<String, CacheEntry> cache : caches.values()) {
            cache.clear();
        }
        
        log.info("All caches cleared");
    }
    
    /**
     * Clear a specific cache type.
     */
    public void clear(CacheType type) {
        if (!enabled) {
            return;
        }
        
        Cache<String, CacheEntry> cache = caches.get(type);
        if (cache != null) {
            cache.clear();
            log.info("Cache cleared for type: {}", type);
        }
    }
    
    /**
     * Get cache statistics for all cache types.
     */
    public CacheStatistics getStatistics() {
        if (!enableStatistics) {
            return new CacheStatistics();
        }
        
        CacheStatistics stats = new CacheStatistics();
        stats.totalHits = totalHits.get();
        stats.totalMisses = totalMisses.get();
        stats.totalEvictions = totalEvictions.get();
        stats.totalExpired = totalExpired.get();
        
        // Calculate per-cache statistics
        for (Map.Entry<CacheType, Cache<String, CacheEntry>> entry : caches.entrySet()) {
            CacheType type = entry.getKey();
            Cache<String, CacheEntry> cache = entry.getValue();
            
            CacheTypeStatistics typeStats = new CacheTypeStatistics();
            typeStats.size = cache.size();
            typeStats.maxSize = cacheConfigs.get(type).getMaxSize();
            
            // Count expired entries
            long expiredCount = 0;
            for (CacheEntry cacheEntry : cache.values()) {
                if (cacheEntry.isExpired()) {
                    expiredCount++;
                }
            }
            typeStats.expiredEntries = expiredCount;
            
            stats.typeStatistics.put(type, typeStats);
        }
        
        return stats;
    }
    
    /**
     * Get cache hit ratio (0.0 to 1.0).
     */
    public double getHitRatio() {
        long hits = totalHits.get();
        long misses = totalMisses.get();
        long total = hits + misses;
        
        return total > 0 ? (double) hits / total : 0.0;
    }
    
    /**
     * Check if caching is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * Shutdown the cache manager.
     */
    public void shutdown() {
        if (maintenanceScheduler != null && !maintenanceScheduler.isShutdown()) {
            maintenanceScheduler.shutdown();
            try {
                if (!maintenanceScheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    maintenanceScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                maintenanceScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        clearAll();
        log.info("Cache manager shutdown completed");
    }
    
    /**
     * Initialize cache configurations.
     */
    private void initializeCacheConfigs(CacheManagerConfig config) {
        // Response cache config
        cacheConfigs.put(CacheType.RESPONSE, new CacheConfig(
            config.getResponseCacheMaxSize(),
            config.getResponseCacheTtlSeconds(),
            true // Enable LRU eviction
        ));
        
        // Schema cache config
        cacheConfigs.put(CacheType.SCHEMA, new CacheConfig(
            config.getSchemaCacheMaxSize(),
            config.getSchemaCacheTtlSeconds(),
            true
        ));
        
        // Transformed data cache config
        cacheConfigs.put(CacheType.TRANSFORMED, new CacheConfig(
            config.getTransformedCacheMaxSize(),
            config.getTransformedCacheTtlSeconds(),
            true
        ));
        
        // Auth cache config
        cacheConfigs.put(CacheType.AUTH, new CacheConfig(
            config.getAuthCacheMaxSize(),
            config.getAuthCacheTtlSeconds(),
            true
        ));
        
        // Metadata cache config
        cacheConfigs.put(CacheType.METADATA, new CacheConfig(
            config.getMetadataCacheMaxSize(),
            config.getMetadataCacheTtlSeconds(),
            true
        ));
    }
    
    /**
     * Initialize all caches.
     */
    private void initializeCaches() {
        for (CacheType type : CacheType.values()) {
            caches.put(type, new Cache<>());
        }
    }
    
    /**
     * Start the maintenance scheduler.
     */
    private void startMaintenanceScheduler() {
        maintenanceScheduler.scheduleWithFixedDelay(() -> {
            try {
                performMaintenance();
            } catch (Exception e) {
                log.error("Cache maintenance failed: {}", e.getMessage(), e);
            }
        }, maintenanceIntervalSeconds, maintenanceIntervalSeconds, TimeUnit.SECONDS);
        
        log.debug("Cache maintenance scheduler started with interval: {}s", maintenanceIntervalSeconds);
    }
    
    /**
     * Perform cache maintenance.
     */
    private void performMaintenance() {
        long startTime = System.currentTimeMillis();
        int expiredRemoved = 0;
        
        for (Map.Entry<CacheType, Cache<String, CacheEntry>> entry : caches.entrySet()) {
            CacheType type = entry.getKey();
            Cache<String, CacheEntry> cache = entry.getValue();
            
            // Remove expired entries
            expiredRemoved += removeExpiredEntries(cache);
            
            // Perform LRU eviction if needed
            CacheConfig config = cacheConfigs.get(type);
            if (cache.size() > config.getMaxSize() * 0.9) { // Evict when 90% full
                evictLRU(cache, (int) (config.getMaxSize() * 0.1)); // Evict 10%
            }
        }
        
        if (expiredRemoved > 0) {
            totalExpired.addAndGet(expiredRemoved);
            log.debug("Cache maintenance completed: removed {} expired entries in {}ms", 
                     expiredRemoved, System.currentTimeMillis() - startTime);
        }
    }
    
    /**
     * Remove expired entries from a cache.
     */
    private int removeExpiredEntries(Cache<String, CacheEntry> cache) {
        int removed = 0;
        
        for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
            if (entry.getValue().isExpired()) {
                cache.remove(entry.getKey());
                removed++;
            }
        }
        
        return removed;
    }
    
    /**
     * Evict least recently used entries.
     */
    private void evictLRU(Cache<String, CacheEntry> cache, int evictCount) {
        if (evictCount <= 0) {
            return;
        }
        
        // Find least recently used entries
        cache.entrySet().stream()
            .sorted((e1, e2) -> e1.getValue().getLastAccessTime().compareTo(e2.getValue().getLastAccessTime()))
            .limit(evictCount)
            .forEach(entry -> {
                cache.remove(entry.getKey());
                totalEvictions.incrementAndGet();
            });
        
        log.trace("Evicted {} LRU entries from cache", evictCount);
    }
    
    /**
     * Thread-safe cache implementation.
     */
    private static class Cache<K, V> extends ConcurrentHashMap<K, V> {
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        
        @Override
        public V get(Object key) {
            lock.readLock().lock();
            try {
                return super.get(key);
            } finally {
                lock.readLock().unlock();
            }
        }
        
        @Override
        public V put(K key, V value) {
            lock.writeLock().lock();
            try {
                return super.put(key, value);
            } finally {
                lock.writeLock().unlock();
            }
        }
        
        @Override
        public V remove(Object key) {
            lock.writeLock().lock();
            try {
                return super.remove(key);
            } finally {
                lock.writeLock().unlock();
            }
        }
    }
    
    /**
     * Cache entry with expiration and access tracking.
     */
    private static class CacheEntry {
        private final Object value;
        private final Instant expiryTime;
        private volatile Instant lastAccessTime;
        
        public CacheEntry(Object value, Instant expiryTime) {
            this.value = value;
            this.expiryTime = expiryTime;
            this.lastAccessTime = Instant.now();
        }
        
        public Object getValue() {
            return value;
        }
        
        public Instant getExpiryTime() {
            return expiryTime;
        }
        
        public Instant getLastAccessTime() {
            return lastAccessTime;
        }
        
        public void updateAccessTime() {
            this.lastAccessTime = Instant.now();
        }
        
        public boolean isExpired() {
            return Instant.now().isAfter(expiryTime);
        }
    }
    
    /**
     * Cache configuration.
     */
    private static class CacheConfig {
        private final int maxSize;
        private final long defaultTtlSeconds;
        private final boolean enableLru;
        
        public CacheConfig(int maxSize, long defaultTtlSeconds, boolean enableLru) {
            this.maxSize = maxSize;
            this.defaultTtlSeconds = defaultTtlSeconds;
            this.enableLru = enableLru;
        }
        
        public int getMaxSize() {
            return maxSize;
        }
        
        public long getDefaultTtlSeconds() {
            return defaultTtlSeconds;
        }
        
        public boolean isEnableLru() {
            return enableLru;
        }
    }
    
    /**
     * Cache statistics.
     */
    public static class CacheStatistics {
        public long totalHits;
        public long totalMisses;
        public long totalEvictions;
        public long totalExpired;
        public final Map<CacheType, CacheTypeStatistics> typeStatistics = new ConcurrentHashMap<>();
        
        public double getHitRatio() {
            long total = totalHits + totalMisses;
            return total > 0 ? (double) totalHits / total : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("CacheStats{hits=%d, misses=%d, hitRatio=%.2f%%, evictions=%d, expired=%d, types=%d}",
                               totalHits, totalMisses, getHitRatio() * 100, totalEvictions, totalExpired, typeStatistics.size());
        }
    }
    
    /**
     * Per-cache-type statistics.
     */
    public static class CacheTypeStatistics {
        public int size;
        public int maxSize;
        public long expiredEntries;
        
        public double getUtilization() {
            return maxSize > 0 ? (double) size / maxSize : 0.0;
        }
        
        @Override
        public String toString() {
            return String.format("TypeStats{size=%d/%d (%.1f%%), expired=%d}",
                               size, maxSize, getUtilization() * 100, expiredEntries);
        }
    }
}
