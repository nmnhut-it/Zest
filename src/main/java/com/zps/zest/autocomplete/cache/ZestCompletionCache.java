package com.zps.zest.autocomplete.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.text.StringUtil;
import com.zps.zest.autocomplete.ZestCompletionData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Caffeine-based cache for completion items.
 * Provides efficient storage and retrieval of completions with automatic expiration.
 */
public class ZestCompletionCache {
    private static final Logger LOG = Logger.getInstance(ZestCompletionCache.class);
    
    // Default cache settings
    private static final int MAX_CACHE_SIZE = 500;
    private static final int EXPIRATION_MINUTES = 30;
    private static final int CONTEXT_WINDOW_SIZE = 100; // Number of characters to use for context

    // The cache instance
    private final Cache<String, ZestCompletionData.CompletionItem> cache;
    
    public ZestCompletionCache() {
        this(MAX_CACHE_SIZE, EXPIRATION_MINUTES);
    }
    
    public ZestCompletionCache(int maxSize, int expirationMinutes) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(expirationMinutes, TimeUnit.MINUTES)
                .recordStats()
                .build();
        
        LOG.info("ZestCompletionCache initialized with size=" + maxSize + 
                ", expiration=" + expirationMinutes + " minutes");
    }
    
    /**
     * Creates a cache key based on the editor context and position.
     * The key includes file type, surrounding text, and position information.
     * 
     * @param editor The editor instance
     * @param offset Current cursor offset
     * @return A string key that represents the context
     */
    @NotNull
    public String createCacheKey(@NotNull Editor editor, int offset) {
        StringBuilder keyBuilder = new StringBuilder();
        
        try {
            // Add file type information
            String fileType = "unknown";
            if (editor.getVirtualFile() != null) {
                fileType = editor.getVirtualFile().getFileType().getName();
            }
            keyBuilder.append("type=").append(fileType).append("|");
            
            // Add surrounding text context
            String documentText = editor.getDocument().getText();
            int textLength = documentText.length();
            
            // Get text before cursor (limited window size)
            int beforeStart = Math.max(0, offset - CONTEXT_WINDOW_SIZE);
            String beforeText = documentText.substring(beforeStart, offset);
            
            // Get text after cursor (limited window size)
            int afterEnd = Math.min(textLength, offset + CONTEXT_WINDOW_SIZE);
            String afterText = documentText.substring(offset, afterEnd);
            
            // Add to key, with normalization to reduce noise
            keyBuilder.append("ctx=").append(normalizeContext(beforeText))
                      .append("||").append(normalizeContext(afterText));
            
            // Add line and column information for precise positioning
            int line = editor.getDocument().getLineNumber(offset);
            int column = offset - editor.getDocument().getLineStartOffset(line);
            keyBuilder.append("|pos=").append(line).append(":").append(column);
            
            return keyBuilder.toString();
            
        } catch (Exception e) {
            LOG.warn("Error creating cache key", e);
            // Fallback to a simpler key
            return "fallback:" + offset + ":" + Objects.hash(
                    editor.getDocument().getText().substring(
                            Math.max(0, offset - 50),
                            Math.min(editor.getDocument().getTextLength(), offset + 50)));
        }
    }
    
    /**
     * Normalizes context for more consistent cache keys.
     * Trims excessive whitespace and removes highly variable elements.
     */
    private String normalizeContext(String context) {
        if (context == null || context.isEmpty()) {
            return "";
        }
        
        // Replace multiple whitespaces with a single space
        String normalized = context.replaceAll("\\s+", " ");
        
        // Trim to reduce noise from trailing whitespace
        normalized = normalized.trim();
        
        return normalized;
    }
    
    /**
     * Stores a completion item in the cache.
     * 
     * @param key The cache key
     * @param item The completion item to store
     */
    public void put(@NotNull String key, @NotNull ZestCompletionData.CompletionItem item) {
        cache.put(key, item);
        LOG.debug("Cached completion item with key: " + key);
    }
    
    /**
     * Convenience method to store using editor context.
     * 
     * @param editor The editor instance
     * @param offset The cursor offset
     * @param item The completion item to store
     * @return The generated cache key
     */
    public String put(@NotNull Editor editor, int offset, @NotNull ZestCompletionData.CompletionItem item) {
        String key = createCacheKey(editor, offset);
        put(key, item);
        return key;
    }
    
    /**
     * Retrieves a completion item from the cache.
     * 
     * @param key The cache key
     * @return The completion item, or null if not found
     */
    @Nullable
    public ZestCompletionData.CompletionItem get(@NotNull String key) {
        return cache.getIfPresent(key);
    }
    
    /**
     * Convenience method to retrieve using editor context.
     * 
     * @param editor The editor instance
     * @param offset The cursor offset
     * @return The completion item, or null if not found
     */
    @Nullable
    public ZestCompletionData.CompletionItem get(@NotNull Editor editor, int offset) {
        String key = createCacheKey(editor, offset);
        ZestCompletionData.CompletionItem item = get(key);
        
        if (item != null) {
            LOG.debug("Cache hit for key: " + key);
        } else {
            LOG.debug("Cache miss for key: " + key);
        }
        
        return item;
    }
    
    /**
     * Invalidates a specific cache entry.
     * 
     * @param key The cache key to invalidate
     */
    public void invalidate(@NotNull String key) {
        cache.invalidate(key);
        LOG.debug("Invalidated cache entry with key: " + key);
    }
    
    /**
     * Invalidates cache entries related to a specific editor.
     * Since exact matching is difficult, this is a best-effort invalidation.
     * 
     * @param editor The editor instance
     */
    public void invalidateEditor(@NotNull Editor editor) {
        // Since we don't have direct access to keys by editor,
        // we simply clear the entire cache if it's getting too large
        if (size() > MAX_CACHE_SIZE / 2) {
            invalidateAll();
        }
    }
    
    /**
     * Clears the entire cache.
     */
    public void invalidateAll() {
        cache.invalidateAll();
        LOG.debug("Invalidated all cache entries");
    }
    
    /**
     * Returns the number of entries in the cache.
     * 
     * @return The cache size
     */
    public long size() {
        return cache.estimatedSize();
    }
    
    /**
     * Returns a string representation of cache statistics.
     * 
     * @return Cache statistics string
     */
    @NotNull
    public String getStats() {
        return String.format("Size: %d, Hits: %d, Misses: %d, Hit Rate: %.2f%%",
                cache.estimatedSize(),
                cache.stats().hitCount(),
                cache.stats().missCount(),
                cache.stats().hitRate() * 100);
    }
}
