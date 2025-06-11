package com.zps.zest.langchain4j.index;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Disk-based implementation of NameIndex that persists data to disk
 * and uses LRU cache for frequently accessed items.
 */
public class DiskBasedNameIndex extends NameIndex {
    private static final Logger LOG = Logger.getInstance(DiskBasedNameIndex.class);
    private static final String INDEX_DIR = ".idea/zest/name-index";
    private static final String ELEMENTS_FILE = "elements.json";
    private static final String TOKENS_FILE = "tokens.json";
    private static final int CACHE_SIZE = 10000; // Max items in memory cache
    private static final Gson GSON = new Gson();
    
    private final Path indexPath;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    
    // LRU cache for frequently accessed elements (synchronized for thread safety)
    private final Map<String, NameIndex.IndexedElement> elementCache = Collections.synchronizedMap(
        new LinkedHashMap<String, NameIndex.IndexedElement>(
        CACHE_SIZE + 1, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, NameIndex.IndexedElement> eldest) {
            if (size() > CACHE_SIZE) {
                // Mark as dirty to ensure it gets saved
                isDirty = true;
                return true;
            }
            return false;
        }
    });
    
    // In-memory token index (relatively small, can keep in memory)
    private final Map<String, Set<String>> tokenIndex = new ConcurrentHashMap<>();
    
    // Track which elements exist (for efficient disk lookups)
    private final Set<String> knownElementIds = ConcurrentHashMap.newKeySet();
    
    // Track dirty state
    private boolean isDirty = false;
    
    public DiskBasedNameIndex(Project project) throws IOException {
        // Create index directory
        String projectPath = project.getBasePath();
        if (projectPath == null) {
            throw new IOException("Project base path is null");
        }
        
        this.indexPath = Paths.get(projectPath, INDEX_DIR);
        Files.createDirectories(indexPath);
        
        // Ensure .gitignore files exist to prevent indexing files from being tracked
        Path projectRoot = Paths.get(projectPath);
        GitIgnoreManager.ensureGitIgnoreHierarchy(indexPath, projectRoot);
        
        // Create elements subdirectory
        Path elementsDir = indexPath.resolve("elements");
        Files.createDirectories(elementsDir);
        GitIgnoreManager.ensureGitIgnore(elementsDir);
        
        // Load existing index
        loadFromDisk();
    }
    
    @Override
    public void indexElement(String id, String signature, String type, String filePath, 
                           Map<String, String> additionalFields) throws IOException {
        lock.writeLock().lock();
        try {
            // Create element
            IndexedElement element = new IndexedElement(id, signature, type, filePath, additionalFields);
            
            // Add to cache
            elementCache.put(id, element);
            
            // Track as known element
            knownElementIds.add(id);
            
            // Mark as dirty
            isDirty = true;
            
            // Extract and index tokens (keep in memory for fast search)
            Set<String> tokens = extractTokens(id, signature);
            for (String token : tokens) {
                if (token.length() > 1) {
                    tokenIndex.computeIfAbsent(token, k -> ConcurrentHashMap.newKeySet()).add(id);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    @Override
    public List<SearchResult> search(String queryStr, int maxResults) throws IOException {
        if (queryStr == null || queryStr.trim().isEmpty()) {
            return new ArrayList<>();
        }
        
        lock.readLock().lock();
        try {
            Map<String, Float> scores = new HashMap<>();
            String query = queryStr.toLowerCase().trim();
            
            // Search in token index
            String[] queryTokens = query.split("\\s+");
            Set<String> candidateIds = new HashSet<>();
            
            for (String token : queryTokens) {
                Set<String> ids = tokenIndex.get(token);
                if (ids != null) {
                    candidateIds.addAll(ids);
                }
            }
            
            // Score candidates
            for (String id : candidateIds) {
                IndexedElement element = getElement(id);
                if (element == null) continue;
                
                float score = calculateScore(element, query, queryTokens);
                if (score > 0) {
                    scores.put(id, score);
                }
            }
            
            // Convert to results and sort
            List<SearchResult> results = scores.entrySet().stream()
                .map(entry -> {
                    IndexedElement element = getElement(entry.getKey());
                    return new SearchResult(
                        element.id,
                        element.signature,
                        element.type,
                        element.filePath,
                        entry.getValue()
                    );
                })
                .sorted((a, b) -> Float.compare(b.getScore(), a.getScore()))
                .limit(maxResults)
                .collect(Collectors.toList());
            
            return results;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    @Override
    public void commit() throws IOException {
        if (!isDirty) return;
        
        lock.writeLock().lock();
        try {
            saveToDisk();
            isDirty = false;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    @Override
    public void close() throws IOException {
        commit();
        elementCache.clear();
        tokenIndex.clear();
        knownElementIds.clear();
    }
    
    /**
     * Removes an element from the index.
     */
    public boolean removeElement(String id) {
        lock.writeLock().lock();
        try {
            // Remove from cache
            elementCache.remove(id);
            
            // Remove from known elements
            knownElementIds.remove(id);
            
            // Remove from token index
            for (Map.Entry<String, Set<String>> entry : tokenIndex.entrySet()) {
                entry.getValue().remove(id);
            }
            
            // Mark as dirty to save changes
            isDirty = true;
            
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Gets an element, checking cache first, then disk.
     */
    private IndexedElement getElement(String id) {
        // Check cache first
        IndexedElement cached = elementCache.get(id);
        if (cached != null) {
            return cached;
        }
        
        // Check if element exists
        if (!knownElementIds.contains(id)) {
            return null;
        }
        
        // Load from disk if not in cache
        try {
            return loadElementFromDisk(id);
        } catch (IOException e) {
            LOG.error("Failed to load element from disk: " + id, e);
            return null;
        }
    }
    
    /**
     * Loads a specific element from disk.
     */
    private IndexedElement loadElementFromDisk(String id) throws IOException {
        // Use individual file per element for better performance
        Path elementPath = indexPath.resolve("elements").resolve(getElementFileName(id));
        if (!Files.exists(elementPath)) {
            // Fallback to loading from the main elements file
            return loadElementFromMainFile(id);
        }
        
        try (Reader reader = Files.newBufferedReader(elementPath)) {
            IndexedElement element = GSON.fromJson(reader, IndexedElement.class);
            if (element != null) {
                // Add to cache
                elementCache.put(id, element);
            }
            return element;
        }
    }
    
    /**
     * Loads element from the main elements file (fallback).
     */
    private IndexedElement loadElementFromMainFile(String id) throws IOException {
        Path elementsPath = indexPath.resolve(ELEMENTS_FILE);
        if (!Files.exists(elementsPath)) {
            return null;
        }
        
        try (Reader reader = Files.newBufferedReader(elementsPath)) {
            Type mapType = new TypeToken<Map<String, IndexedElement>>(){}.getType();
            Map<String, IndexedElement> allElements = GSON.fromJson(reader, mapType);
            
            IndexedElement element = allElements.get(id);
            if (element != null) {
                // Add to cache
                elementCache.put(id, element);
            }
            return element;
        }
    }
    
    /**
     * Gets a safe file name for an element ID.
     */
    private String getElementFileName(String id) {
        // Replace problematic characters for file names
        return id.replaceAll("[^a-zA-Z0-9.-]", "_") + ".json";
    }
    
    /**
     * Loads the index from disk.
     */
    private void loadFromDisk() throws IOException {
        // Load token index
        Path tokensPath = indexPath.resolve(TOKENS_FILE);
        if (Files.exists(tokensPath)) {
            try (Reader reader = Files.newBufferedReader(tokensPath)) {
                Type mapType = new TypeToken<Map<String, Set<String>>>(){}.getType();
                Map<String, Set<String>> loaded = GSON.fromJson(reader, mapType);
                if (loaded != null) {
                    tokenIndex.clear();
                    tokenIndex.putAll(loaded);
                }
            }
        }
        
        // Load known element IDs
        Path elementsPath = indexPath.resolve(ELEMENTS_FILE);
        if (Files.exists(elementsPath)) {
            try (Reader reader = Files.newBufferedReader(elementsPath)) {
                Type mapType = new TypeToken<Map<String, IndexedElement>>(){}.getType();
                Map<String, IndexedElement> allElements = GSON.fromJson(reader, mapType);
                if (allElements != null) {
                    knownElementIds.addAll(allElements.keySet());
                }
            }
        }
        
        // Also check for individual element files
        Path elementsDir = indexPath.resolve("elements");
        if (Files.exists(elementsDir) && Files.isDirectory(elementsDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(elementsDir, "*.json")) {
                for (Path path : stream) {
                    String filename = path.getFileName().toString();
                    if (filename.endsWith(".json")) {
                        String id = filename.substring(0, filename.length() - 5)
                            .replace("_", ".");
                        knownElementIds.add(id);
                    }
                }
            }
        }
        
        LOG.info("Loaded name index from disk with " + tokenIndex.size() + " tokens and " + 
                 knownElementIds.size() + " known elements");
    }
    
    /**
     * Saves the index to disk.
     */
    private void saveToDisk() throws IOException {
        // Save elements
        Path elementsPath = indexPath.resolve(ELEMENTS_FILE);
        
        // Load existing elements and merge with cache
        Map<String, IndexedElement> allElements = new HashMap<>();
        if (Files.exists(elementsPath)) {
            try (Reader reader = Files.newBufferedReader(elementsPath)) {
                Type mapType = new TypeToken<Map<String, IndexedElement>>(){}.getType();
                Map<String, IndexedElement> existing = GSON.fromJson(reader, mapType);
                if (existing != null) {
                    allElements.putAll(existing);
                }
            }
        }
        
        // Update with cached elements
        allElements.putAll(elementCache);
        
        // Write back
        try (Writer writer = Files.newBufferedWriter(elementsPath)) {
            GSON.toJson(allElements, writer);
        }
        
        // Save token index
        Path tokensPath = indexPath.resolve(TOKENS_FILE);
        try (Writer writer = Files.newBufferedWriter(tokensPath)) {
            GSON.toJson(tokenIndex, writer);
        }
        
        LOG.info("Saved name index to disk with " + allElements.size() + " elements");
    }
    
    /**
     * Extracts searchable tokens from element.
     */
    private Set<String> extractTokens(String id, String signature) {
        Set<String> tokens = new HashSet<>();
        
        // Extract from ID
        String simpleName = extractSimpleName(id);
        tokens.add(simpleName.toLowerCase());
        tokens.addAll(tokenizeIdentifier(simpleName));
        
        // Extract from signature
        tokens.addAll(tokenizeSignature(signature));
        
        // Add prefixes for prefix search
        for (String token : new HashSet<>(tokens)) {
            if (token.length() > 3) {
                for (int i = 2; i < Math.min(token.length(), 8); i++) {
                    tokens.add(token.substring(0, i));
                }
            }
        }
        
        return tokens;
    }
    
    /**
     * Calculates search score for an element.
     */
    private float calculateScore(IndexedElement element, String query, String[] queryTokens) {
        float score = 0;
        String simpleName = extractSimpleName(element.id).toLowerCase();
        
        // Exact match
        if (simpleName.equals(query)) {
            score += 10.0f;
        } else if (simpleName.startsWith(query)) {
            score += 5.0f;
        } else if (simpleName.contains(query)) {
            score += 3.0f;
        }
        
        // Token matches
        String elementText = (element.id + " " + element.signature).toLowerCase();
        for (String token : queryTokens) {
            if (elementText.contains(token)) {
                score += 2.0f;
            }
        }
        
        return score;
    }
    
    /**
     * Gets statistics about the disk-based index.
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("cache_size", elementCache.size());
        stats.put("token_count", tokenIndex.size());
        stats.put("is_dirty", isDirty);
        stats.put("index_path", indexPath.toString());
        return stats;
    }
}
