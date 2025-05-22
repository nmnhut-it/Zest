package com.zps.zest.autocomplete.utils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Map;
import java.util.Queue;

/**
 * Simple LRU-like cache for storing autocomplete results.
 * Helps avoid making duplicate API requests for similar context.
 */
public class CompletionCache {
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Queue<String> insertionOrder = new ConcurrentLinkedQueue<>();
    private final int maxSize;
    private final long ttlMillis;
    
    /**
     * Creates a new completion cache.
     * 
     * @param maxSize Maximum number of entries to cache
     * @param ttlMillis Time-to-live for cache entries in milliseconds
     */
    public CompletionCache(int maxSize, long ttlMillis) {
        this.maxSize = maxSize;
        this.ttlMillis = ttlMillis;
    }
    
    /**
     * Gets a cached completion if available and not expired.
     */
    public String get(String key) {
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        
        // Check if expired
        if (System.currentTimeMillis() - entry.timestamp > ttlMillis) {
            cache.remove(key);
            insertionOrder.remove(key);
            return null;
        }
        
        return entry.completion;
    }
    
    /**
     * Stores a completion in the cache.
     */
    public void put(String key, String completion) {
        // Remove oldest entries if at capacity
        while (cache.size() >= maxSize && !insertionOrder.isEmpty()) {
            String oldestKey = insertionOrder.poll();
            if (oldestKey != null) {
                cache.remove(oldestKey);
            }
        }
        
        // Add new entry
        cache.put(key, new CacheEntry(completion, System.currentTimeMillis()));
        insertionOrder.offer(key);
    }
    
    /**
     * Clears all cache entries.
     */
    public void clear() {
        cache.clear();
        insertionOrder.clear();
    }
    
    /**
     * Gets the current cache size.
     */
    public int size() {
        return cache.size();
    }
    
    /**
     * Checks if the cache is empty.
     */
    public boolean isEmpty() {
        return cache.isEmpty();
    }
    
    /**
     * Removes expired entries from the cache.
     */
    public void cleanupExpired() {
        long currentTime = System.currentTimeMillis();
        cache.entrySet().removeIf(entry -> 
            currentTime - entry.getValue().timestamp > ttlMillis);
        
        // Clean up insertion order queue
        insertionOrder.removeIf(key -> !cache.containsKey(key));
    }
    
    /**
     * Gets cache statistics as a formatted string.
     */
    public String getStats() {
        return String.format("Cache: %d/%d entries", cache.size(), maxSize);
    }
    
    /**
     * Internal cache entry class.
     */
    private static class CacheEntry {
        final String completion;
        final long timestamp;
        
        CacheEntry(String completion, long timestamp) {
            this.completion = completion;
            this.timestamp = timestamp;
        }
    }
}
