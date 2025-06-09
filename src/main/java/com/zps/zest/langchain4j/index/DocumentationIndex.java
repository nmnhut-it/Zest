package com.zps.zest.langchain4j.index;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.zps.zest.langchain4j.VectorStore;
import com.zps.zest.langchain4j.EmbeddingService;
import com.zps.zest.langchain4j.InMemoryVectorStore;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Specialized index for documentation files using LangChain4j.
 * Optimized for natural language queries and documentation structure.
 */
public class DocumentationIndex {
    private static final Logger LOG = Logger.getInstance(DocumentationIndex.class);
    
    private final VectorStore vectorStore;
    private final EmbeddingService embeddingService;
    private final MarkdownDocumentSplitter splitter;
    
    // Documentation-specific configuration
    private static final double DOC_SIMILARITY_THRESHOLD = 0.3; // Lower threshold for prose
    private static final int DOC_CHUNK_SIZE = 500;
    private static final int DOC_CHUNK_OVERLAP = 100;
    
    // Cache for file modification times to avoid re-indexing
    private final Map<String, Long> fileModificationTimes = new ConcurrentHashMap<>();
    
    // Cache for section headers for better navigation
    private final Map<String, List<String>> fileHeaders = new ConcurrentHashMap<>();
    
    public DocumentationIndex(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
        this.vectorStore = new InMemoryVectorStore(embeddingService);
        this.splitter = new MarkdownDocumentSplitter(DOC_CHUNK_SIZE, DOC_CHUNK_OVERLAP);
        LOG.info("Initialized DocumentationIndex with embedding dimension: " + embeddingService.getDimension());
    }
    
    /**
     * Indexes a documentation file.
     * 
     * @param file The markdown file to index
     * @return true if file was indexed, false if skipped (unchanged)
     */
    public boolean indexDocFile(VirtualFile file) {
        try {
            // Check if file needs re-indexing
            Long lastModified = fileModificationTimes.get(file.getPath());
            if (lastModified != null && lastModified >= file.getModificationStamp()) {
                LOG.debug("Skipping unchanged file: " + file.getPath());
                return false;
            }
            
            // Remove old entries for this file
            removeFileFromIndex(file.getPath());
            
            // Read file content
            String content = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
            
            // Create LangChain4j Document with metadata
            Metadata metadata = createMetadata(file);
            Document document = Document.from(content, metadata);
            
            // Split into segments using our custom splitter
            List<TextSegment> segments = splitter.split(document);
            
            // Track headers for this file
            List<String> headers = new ArrayList<>();
            
            // Generate embeddings and store
            for (int i = 0; i < segments.size(); i++) {
                TextSegment segment = segments.get(i);
                String segmentId = file.getPath() + "#segment-" + i;
                
                // Extract header info
                String header = segment.metadata().get("section_header");
                if (header != null && !headers.contains(header)) {
                    headers.add(header);
                }
                
                // Generate embedding
                float[] embedding = embeddingService.embed(segment.text());
                
                // Enrich metadata
                Map<String, Object> enrichedMetadata = new HashMap<>(segment.metadata().asMap());
                enrichedMetadata.put("segment_index", i);
                enrichedMetadata.put("total_segments", segments.size());
                enrichedMetadata.put("file_path", file.getPath());
                
                // Store in vector store
                vectorStore.store(segmentId, embedding, segment, enrichedMetadata);
            }
            
            // Update caches
            fileModificationTimes.put(file.getPath(), file.getModificationStamp());
            fileHeaders.put(file.getPath(), headers);
            
            LOG.info("Indexed documentation file: " + file.getName() + " (" + segments.size() + " segments, " + headers.size() + " sections)");
            return true;
            
        } catch (IOException e) {
            LOG.error("Failed to index documentation file: " + file.getPath(), e);
            return false;
        }
    }
    
    /**
     * Searches documentation with natural language query.
     * 
     * @param query The search query
     * @param maxResults Maximum number of results
     * @return List of search results with highlights and context
     */
    public List<DocumentSearchResult> search(String query, int maxResults) {
        try {
            // Generate query embedding
            float[] queryEmbedding = embeddingService.embed(query);
            
            // Extract keywords for hybrid search
            List<String> keywords = extractKeywords(query);
            
            // Perform hybrid search
            List<VectorStore.SearchResult> results = vectorStore.hybridSearch(
                queryEmbedding, 
                keywords, 
                maxResults * 3, // Get more results for filtering
                0.7 // Weight towards semantic search
            );
            
            // Convert and enrich results
            List<DocumentSearchResult> docResults = new ArrayList<>();
            Set<String> seenSections = new HashSet<>(); // Avoid duplicate sections
            
            for (VectorStore.SearchResult result : results) {
                if (result.getScore() < DOC_SIMILARITY_THRESHOLD) {
                    continue; // Skip low-relevance results
                }
                
                // Create section identifier to avoid duplicates
                String sectionId = result.getMetadata().get("file_path") + ":" + 
                                  result.getMetadata().get("section_header");
                
                if (seenSections.contains(sectionId)) {
                    continue; // Skip duplicate sections
                }
                seenSections.add(sectionId);
                
                // Extract highlight
                String highlight = extractHighlight(query, result.getTextSegment().text(), keywords);
                
                // Create result
                docResults.add(new DocumentSearchResult(
                    result.getId(),
                    result.getTextSegment().text(),
                    result.getMetadata(),
                    result.getScore(),
                    highlight,
                    buildBreadcrumb(result.getMetadata())
                ));
                
                if (docResults.size() >= maxResults) {
                    break;
                }
            }
            
            return docResults;
            
        } catch (Exception e) {
            LOG.error("Documentation search failed for query: " + query, e);
            return Collections.emptyList();
        }
    }
    
    /**
     * Finds documentation sections similar to a given section.
     * 
     * @param sectionId The section ID to find similar sections for
     * @param maxResults Maximum number of results
     * @return List of similar sections
     */
    public List<DocumentSearchResult> findSimilar(String sectionId, int maxResults) {
        try {
            VectorStore.EmbeddingEntry entry = vectorStore.get(sectionId);
            if (entry == null) {
                return Collections.emptyList();
            }
            
            // Search for similar sections
            List<VectorStore.SearchResult> results = vectorStore.search(
                entry.getEmbedding(), 
                maxResults + 1 // +1 to exclude self
            );
            
            return results.stream()
                .filter(r -> !r.getId().equals(sectionId)) // Exclude self
                .map(r -> new DocumentSearchResult(
                    r.getId(),
                    r.getTextSegment().text(),
                    r.getMetadata(),
                    r.getScore(),
                    r.getTextSegment().text().substring(0, Math.min(200, r.getTextSegment().text().length())),
                    buildBreadcrumb(r.getMetadata())
                ))
                .limit(maxResults)
                .collect(Collectors.toList());
                
        } catch (Exception e) {
            LOG.error("Find similar failed for: " + sectionId, e);
            return Collections.emptyList();
        }
    }
    
    /**
     * Gets all indexed files and their section headers.
     * 
     * @return Map of file paths to section headers
     */
    public Map<String, List<String>> getIndexedFiles() {
        return new HashMap<>(fileHeaders);
    }
    
    /**
     * Removes a file from the index.
     */
    private void removeFileFromIndex(String filePath) {
        Map<String, Object> filter = new HashMap<>();
        filter.put("file_path", filePath);
        int removed = vectorStore.deleteByMetadata(filter);
        if (removed > 0) {
            LOG.info("Removed " + removed + " segments for file: " + filePath);
        }
        fileHeaders.remove(filePath);
        fileModificationTimes.remove(filePath);
    }
    
    /**
     * Creates metadata for a documentation file.
     */
    private Metadata createMetadata(VirtualFile file) {
        Metadata metadata = new Metadata();
        metadata.add("filename", file.getName());
        metadata.add("path", file.getPath());
        metadata.add("type", "documentation");
        metadata.add("extension", file.getExtension() != null ? file.getExtension() : "");
        return metadata;
    }
    
    /**
     * Extracts keywords from a query for hybrid search.
     */
    private List<String> extractKeywords(String query) {
        // Simple keyword extraction - can be enhanced with NLP
        String[] words = query.toLowerCase()
            .replaceAll("[^a-zA-Z0-9\\s]", " ")
            .split("\\s+");
        
        // Filter out common stop words
        Set<String> stopWords = Set.of("the", "a", "an", "and", "or", "but", "in", 
                                       "on", "at", "to", "for", "of", "with", "by",
                                       "from", "as", "is", "was", "are", "were",
                                       "how", "what", "where", "when", "why");
        
        return Arrays.stream(words)
            .filter(word -> word.length() > 2 && !stopWords.contains(word))
            .distinct()
            .collect(Collectors.toList());
    }
    
    /**
     * Extracts a highlight snippet from content.
     */
    private String extractHighlight(String query, String content, List<String> keywords) {
        // Find the best matching sentence or paragraph
        String[] sentences = content.split("(?<=[.!?])\\s+");
        
        int bestScore = 0;
        int bestIndex = 0;
        
        // Score each sentence
        for (int i = 0; i < sentences.length; i++) {
            String sentence = sentences[i].toLowerCase();
            int score = 0;
            
            // Check for keyword matches
            for (String keyword : keywords) {
                if (sentence.contains(keyword)) {
                    score += 2;
                }
            }
            
            // Check for query phrase matches
            if (sentence.contains(query.toLowerCase())) {
                score += 5;
            }
            
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }
        
        // Build highlight with context
        StringBuilder highlight = new StringBuilder();
        int start = Math.max(0, bestIndex - 1);
        int end = Math.min(sentences.length, bestIndex + 2);
        
        if (start > 0) {
            highlight.append("...");
        }
        
        for (int i = start; i < end; i++) {
            highlight.append(sentences[i]);
            if (i < end - 1) {
                highlight.append(" ");
            }
        }
        
        if (end < sentences.length) {
            highlight.append("...");
        }
        
        // Limit length
        String result = highlight.toString();
        if (result.length() > 300) {
            result = result.substring(0, 297) + "...";
        }
        
        return result;
    }
    
    /**
     * Builds a breadcrumb navigation string from metadata.
     */
    private String buildBreadcrumb(Map<String, Object> metadata) {
        String filename = (String) metadata.get("filename");
        String parentHeaders = (String) metadata.get("parent_headers");
        String sectionHeader = (String) metadata.get("section_header");
        
        StringBuilder breadcrumb = new StringBuilder(filename);
        
        if (parentHeaders != null && !parentHeaders.isEmpty()) {
            breadcrumb.append(" > ").append(parentHeaders);
        }
        
        if (sectionHeader != null && !sectionHeader.isEmpty()) {
            if (parentHeaders == null || parentHeaders.isEmpty()) {
                breadcrumb.append(" > ");
            } else {
                breadcrumb.append(" > ");
            }
            breadcrumb.append(sectionHeader);
        }
        
        return breadcrumb.toString();
    }
    
    /**
     * Gets statistics about the documentation index.
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total_segments", vectorStore.size());
        stats.put("indexed_files", fileHeaders.size());
        stats.put("total_sections", fileHeaders.values().stream()
            .mapToInt(List::size)
            .sum());
        stats.put("similarity_threshold", DOC_SIMILARITY_THRESHOLD);
        return stats;
    }
    
    /**
     * Clears the entire documentation index.
     */
    public void clear() {
        vectorStore.clear();
        fileModificationTimes.clear();
        fileHeaders.clear();
        LOG.info("Cleared documentation index");
    }
    
    /**
     * Documentation search result with enriched information.
     */
    public static class DocumentSearchResult {
        private final String id;
        private final String content;
        private final Map<String, Object> metadata;
        private final double score;
        private final String highlight;
        private final String breadcrumb;
        
        public DocumentSearchResult(String id, String content, Map<String, Object> metadata, 
                                   double score, String highlight, String breadcrumb) {
            this.id = id;
            this.content = content;
            this.metadata = metadata;
            this.score = score;
            this.highlight = highlight;
            this.breadcrumb = breadcrumb;
        }
        
        // Getters
        public String getId() { return id; }
        public String getContent() { return content; }
        public Map<String, Object> getMetadata() { return metadata; }
        public double getScore() { return score; }
        public String getHighlight() { return highlight; }
        public String getBreadcrumb() { return breadcrumb; }
    }
}
