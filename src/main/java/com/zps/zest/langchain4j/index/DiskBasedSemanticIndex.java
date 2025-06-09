package com.zps.zest.langchain4j.index;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.EmbeddingService;
import com.zps.zest.langchain4j.LocalEmbeddingService;
import com.zps.zest.langchain4j.VectorStore;
import dev.langchain4j.data.segment.TextSegment;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Disk-based implementation of SemanticIndex that persists embeddings to disk
 * using memory-mapped files for efficient access.
 */
public class DiskBasedSemanticIndex extends SemanticIndex {
    private static final Logger LOG = Logger.getInstance(DiskBasedSemanticIndex.class);
    private static final String INDEX_DIR = ".idea/zest/semantic-index";
    private static final String METADATA_FILE = "metadata.json";
    private static final String EMBEDDINGS_FILE = "embeddings.bin";
    private static final String SEGMENTS_FILE = "segments.json";
    private static final int CACHE_SIZE = 1000; // Max embeddings in cache
    private static final Gson GSON = new Gson();
    
    private final Path indexPath;
    private final EmbeddingService embeddingService;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final int embeddingDimension;
    
    // Memory-mapped file for embeddings
    private RandomAccessFile embeddingsRaf;
    private FileChannel embeddingsChannel;
    private MappedByteBuffer embeddingsBuffer;
    
    // Metadata and segments in memory (relatively small)
    private final Map<String, EmbeddingMetadata> metadataMap = new ConcurrentHashMap<>();
    private final Map<String, String> segmentsMap = new ConcurrentHashMap<>();
    
    // LRU cache for embeddings
    private final LinkedHashMap<String, float[]> embeddingCache = new LinkedHashMap<String, float[]>(
        CACHE_SIZE + 1, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    
    // Track positions in embedding file
    private final Map<String, Integer> embeddingPositions = new ConcurrentHashMap<>();
    private int nextPosition = 0;
    
    public DiskBasedSemanticIndex(Project project) throws IOException {
        this.embeddingService = new LocalEmbeddingService();
        this.embeddingDimension = embeddingService.getDimension();
        
        // Create index directory
        String projectPath = project.getBasePath();
        if (projectPath == null) {
            throw new IOException("Project base path is null");
        }
        
        this.indexPath = Paths.get(projectPath, INDEX_DIR);
        Files.createDirectories(indexPath);
        
        // Initialize memory-mapped file
        initializeEmbeddingsFile();
        
        // Load existing index
        loadFromDisk();
        
        LOG.info("Initialized DiskBasedSemanticIndex with dimension: " + embeddingDimension);
    }
    
    @Override
    public void indexElement(String id, String content, Map<String, Object> metadata) {
        lock.writeLock().lock();
        try {
            // Generate embedding
            float[] embedding = embeddingService.embed(content);
            
            // Store embedding
            int position = storeEmbedding(id, embedding);
            
            // Store metadata
            EmbeddingMetadata meta = new EmbeddingMetadata(
                id,
                position,
                System.currentTimeMillis(),
                content.length(),
                metadata
            );
            metadataMap.put(id, meta);
            
            // Store text segment
            segmentsMap.put(id, content);
            
            // Add to cache
            embeddingCache.put(id, embedding);
            
            LOG.debug("Indexed element in semantic index: " + id);
            
        } catch (Exception e) {
            LOG.error("Failed to index element: " + id, e);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    @Override
    public List<SearchResult> search(String query, int maxResults, Map<String, Object> filters) {
        lock.readLock().lock();
        try {
            // Generate query embedding
            float[] queryEmbedding = embeddingService.embed(query);
            
            // Calculate similarities
            List<ScoredResult> results = new ArrayList<>();
            
            for (Map.Entry<String, EmbeddingMetadata> entry : metadataMap.entrySet()) {
                String id = entry.getKey();
                EmbeddingMetadata meta = entry.getValue();
                
                // Apply filters
                if (filters != null && !matchesFilters(meta.additionalMetadata, filters)) {
                    continue;
                }
                
                // Get embedding
                float[] embedding = getEmbedding(id, meta.position);
                if (embedding == null) continue;
                
                // Calculate similarity
                double similarity = cosineSimilarity(queryEmbedding, embedding);
                if (similarity >= 0.5) { // Threshold
                    String content = segmentsMap.get(id);
                    results.add(new ScoredResult(id, content, meta.additionalMetadata, similarity));
                }
            }
            
            // Sort and limit
            results.sort((a, b) -> Double.compare(b.score, a.score));
            
            return results.stream()
                .limit(maxResults)
                .map(r -> new SearchResult(r.id, r.content, r.metadata, r.score, 
                    calculateSemanticFeatures(query, r.content)))
                .collect(Collectors.toList());
                
        } finally {
            lock.readLock().unlock();
        }
    }
    
    @Override
    public void clear() {
        lock.writeLock().lock();
        try {
            metadataMap.clear();
            segmentsMap.clear();
            embeddingCache.clear();
            embeddingPositions.clear();
            nextPosition = 0;
            
            // Clear the embeddings file
            embeddingsBuffer.clear();
            
            LOG.info("Cleared semantic index");
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Stores an embedding in the memory-mapped file.
     */
    private int storeEmbedding(String id, float[] embedding) throws IOException {
        int position = nextPosition;
        embeddingPositions.put(id, position);
        
        // Write to buffer
        embeddingsBuffer.position(position * embeddingDimension * Float.BYTES);
        for (float value : embedding) {
            embeddingsBuffer.putFloat(value);
        }
        
        nextPosition++;
        return position;
    }
    
    /**
     * Retrieves an embedding from cache or disk.
     */
    private float[] getEmbedding(String id, int position) {
        // Check cache first
        float[] cached = embeddingCache.get(id);
        if (cached != null) {
            return cached;
        }
        
        // Read from memory-mapped file
        try {
            float[] embedding = new float[embeddingDimension];
            embeddingsBuffer.position(position * embeddingDimension * Float.BYTES);
            
            for (int i = 0; i < embeddingDimension; i++) {
                embedding[i] = embeddingsBuffer.getFloat();
            }
            
            // Add to cache
            embeddingCache.put(id, embedding);
            
            return embedding;
        } catch (Exception e) {
            LOG.error("Failed to read embedding for: " + id, e);
            return null;
        }
    }
    
    /**
     * Initializes the memory-mapped embeddings file.
     */
    private void initializeEmbeddingsFile() throws IOException {
        Path embeddingsPath = indexPath.resolve(EMBEDDINGS_FILE);
        
        // Create or open the file
        embeddingsRaf = new RandomAccessFile(embeddingsPath.toFile(), "rw");
        embeddingsChannel = embeddingsRaf.getChannel();
        
        // Map the file to memory (start with 100MB)
        long fileSize = Math.max(embeddingsRaf.length(), 100 * 1024 * 1024);
        embeddingsRaf.setLength(fileSize);
        
        embeddingsBuffer = embeddingsChannel.map(
            FileChannel.MapMode.READ_WRITE,
            0,
            fileSize
        );
    }
    
    /**
     * Loads the index from disk.
     */
    private void loadFromDisk() throws IOException {
        // Load metadata
        Path metadataPath = indexPath.resolve(METADATA_FILE);
        if (Files.exists(metadataPath)) {
            try (Reader reader = Files.newBufferedReader(metadataPath)) {
                Type mapType = new TypeToken<Map<String, EmbeddingMetadata>>(){}.getType();
                Map<String, EmbeddingMetadata> loaded = GSON.fromJson(reader, mapType);
                if (loaded != null) {
                    metadataMap.putAll(loaded);
                    
                    // Rebuild position map
                    for (Map.Entry<String, EmbeddingMetadata> entry : loaded.entrySet()) {
                        embeddingPositions.put(entry.getKey(), entry.getValue().position);
                        nextPosition = Math.max(nextPosition, entry.getValue().position + 1);
                    }
                }
            }
        }
        
        // Load segments
        Path segmentsPath = indexPath.resolve(SEGMENTS_FILE);
        if (Files.exists(segmentsPath)) {
            try (Reader reader = Files.newBufferedReader(segmentsPath)) {
                Type mapType = new TypeToken<Map<String, String>>(){}.getType();
                Map<String, String> loaded = GSON.fromJson(reader, mapType);
                if (loaded != null) {
                    segmentsMap.putAll(loaded);
                }
            }
        }
        
        LOG.info("Loaded semantic index with " + metadataMap.size() + " embeddings");
    }
    
    /**
     * Saves metadata and segments to disk.
     */
    public void saveToDisk() throws IOException {
        lock.readLock().lock();
        try {
            // Save metadata
            Path metadataPath = indexPath.resolve(METADATA_FILE);
            try (Writer writer = Files.newBufferedWriter(metadataPath)) {
                GSON.toJson(metadataMap, writer);
            }
            
            // Save segments
            Path segmentsPath = indexPath.resolve(SEGMENTS_FILE);
            try (Writer writer = Files.newBufferedWriter(segmentsPath)) {
                GSON.toJson(segmentsMap, writer);
            }
            
            // Force buffer to disk
            embeddingsBuffer.force();
            
            LOG.info("Saved semantic index to disk");
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Closes the index and releases resources.
     */
    public void close() throws IOException {
        saveToDisk();
        
        if (embeddingsChannel != null) {
            embeddingsChannel.close();
        }
        if (embeddingsRaf != null) {
            embeddingsRaf.close();
        }
        
        clear();
    }
    
    /**
     * Calculates cosine similarity between two vectors.
     */
    private double cosineSimilarity(float[] vec1, float[] vec2) {
        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        
        for (int i = 0; i < vec1.length; i++) {
            dotProduct += vec1[i] * vec2[i];
            norm1 += vec1[i] * vec1[i];
            norm2 += vec2[i] * vec2[i];
        }
        
        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }
    
    /**
     * Checks if metadata matches filters.
     */
    private boolean matchesFilters(Map<String, Object> metadata, Map<String, Object> filters) {
        for (Map.Entry<String, Object> filter : filters.entrySet()) {
            Object value = metadata.get(filter.getKey());
            if (!Objects.equals(value, filter.getValue())) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Calculates semantic features for ranking.
     */
    private Map<String, Object> calculateSemanticFeatures(String query, String content) {
        Map<String, Object> features = new HashMap<>();
        
        String[] queryWords = query.toLowerCase().split("\\s+");
        String lowerContent = content.toLowerCase();
        
        int exactMatches = 0;
        int partialMatches = 0;
        
        for (String word : queryWords) {
            if (lowerContent.contains(" " + word + " ")) {
                exactMatches++;
            } else if (lowerContent.contains(word)) {
                partialMatches++;
            }
        }
        
        features.put("exact_matches", exactMatches);
        features.put("partial_matches", partialMatches);
        features.put("query_coverage", (double)(exactMatches + partialMatches * 0.5) / queryWords.length);
        
        return features;
    }
    
    @Override
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = super.getStatistics();
        stats.put("disk_embeddings", metadataMap.size());
        stats.put("cache_size", embeddingCache.size());
        stats.put("index_path", indexPath.toString());
        stats.put("embeddings_file_size", embeddingsBuffer.capacity());
        return stats;
    }
    
    /**
     * Metadata for stored embeddings.
     */
    private static class EmbeddingMetadata {
        String id;
        int position;
        long timestamp;
        int contentLength;
        Map<String, Object> additionalMetadata;
        
        EmbeddingMetadata(String id, int position, long timestamp, int contentLength, 
                         Map<String, Object> additionalMetadata) {
            this.id = id;
            this.position = position;
            this.timestamp = timestamp;
            this.contentLength = contentLength;
            this.additionalMetadata = additionalMetadata;
        }
    }
    
    /**
     * Intermediate result with score.
     */
    private static class ScoredResult {
        String id;
        String content;
        Map<String, Object> metadata;
        double score;
        
        ScoredResult(String id, String content, Map<String, Object> metadata, double score) {
            this.id = id;
            this.content = content;
            this.metadata = metadata;
            this.score = score;
        }
    }
}
