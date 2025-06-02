package com.zps.zest.langchain4j;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.zps.zest.rag.CodeSignature;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * RAG service using LangChain4j for local embedding generation and vector search.
 * Can be used alongside or as a replacement for OpenWebUI-based RAG.
 */
@Service(Service.Level.PROJECT)
public final class RagService {
    private static final Logger LOG = Logger.getInstance(RagService.class);
    
    private final Project project;
    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;
    private final DocumentProcessor documentProcessor;
    
    // Configuration
    private boolean useHybridSearch = true;
    private double hybridSearchVectorWeight = 0.7;
    
    public RagService(Project project) {
        this.project = project;
        this.embeddingService = new LocalEmbeddingService();
        this.vectorStore = new LangChain4jVectorStore(embeddingService);
        this.documentProcessor = new DocumentProcessor();
        
        LOG.info("Initialized LangChain4j RAG service for project: " + project.getName());
    }
    
    /**
     * Indexes a file for RAG retrieval.
     * 
     * @param file The file to index
     * @param codeSignatures Optional pre-extracted code signatures for enrichment
     * @return Number of segments indexed
     */
    public int indexFile(VirtualFile file, List<CodeSignature> codeSignatures) {
        try {
            List<TextSegment> segments;
            
            // Use PSI-aware processing if we have code signatures
            if (codeSignatures != null && !codeSignatures.isEmpty()) {
                PsiFile psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(file);
                if (psiFile != null) {
                    segments = documentProcessor.processPsiFile(psiFile, codeSignatures);
                } else {
                    segments = documentProcessor.processFile(file);
                }
            } else {
                segments = documentProcessor.processFile(file);
            }
            
            // Generate embeddings and store
            List<VectorStore.EmbeddingEntry> entries = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                TextSegment segment = segments.get(i);
                String id = file.getPath() + "#segment-" + i;
                
                float[] embedding = embeddingService.embed(segment.text());
                
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("file_path", file.getPath());
                metadata.put("file_name", file.getName());
                metadata.put("segment_index", i);
                metadata.put("type", "code");
                
                // Add segment metadata
                if (segment.metadata() != null) {
                    segment.metadata().toMap().forEach((key, value) -> 
                        metadata.put(key, value)
                    );
                }
                
                entries.add(new VectorStore.EmbeddingEntry(id, embedding, segment, metadata));
            }
            
            vectorStore.storeBatch(entries);
            LOG.info("Indexed " + segments.size() + " segments from: " + file.getName());
            
            return segments.size();
        } catch (Exception e) {
            LOG.error("Failed to index file: " + file.getPath(), e);
            return 0;
        }
    }
    
    /**
     * Indexes a code signature directly.
     * 
     * @param signature The code signature to index
     */
    public void indexCodeSignature(CodeSignature signature) {
        String content = buildSignatureContent(signature);
        TextSegment segment = documentProcessor.createSegment(content, Map.of(
            "type", "signature",
            "signature_id", signature.getId(),
            "file_path", signature.getFilePath()
        ));
        
        float[] embedding = embeddingService.embed(content);
        
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", "signature");
        metadata.put("signature_id", signature.getId());
        metadata.put("signature_type", extractSignatureType(signature));
        metadata.put("file_path", signature.getFilePath());
        
        vectorStore.store(signature.getId(), embedding, segment, metadata);
    }
    
    /**
     * Searches for relevant content using natural language query.
     * 
     * @param query The search query
     * @param limit Maximum number of results
     * @return List of search results
     */
    public CompletableFuture<List<SearchResult>> search(String query, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Generate query embedding
                float[] queryEmbedding = embeddingService.embed(query);
                
                List<VectorStore.SearchResult> results;
                
                if (useHybridSearch) {
                    // Extract keywords from query
                    List<String> keywords = extractKeywords(query);
                    results = vectorStore.hybridSearch(queryEmbedding, keywords, limit, hybridSearchVectorWeight);
                } else {
                    results = vectorStore.search(queryEmbedding, limit);
                }
                
                // Convert to our SearchResult format
                return results.stream()
                    .map(this::convertSearchResult)
                    .collect(Collectors.toList());
                    
            } catch (Exception e) {
                LOG.error("Search failed for query: " + query, e);
                return Collections.emptyList();
            }
        });
    }
    
    /**
     * Searches specifically for code elements.
     * 
     * @param query The search query
     * @param limit Maximum number of results
     * @return List of code-specific search results
     */
    public CompletableFuture<List<SearchResult>> searchCode(String query, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                float[] queryEmbedding = embeddingService.embed(query);
                
                // Filter for code-related content
                Map<String, Object> filter = Map.of("type", "code");
                
                List<VectorStore.SearchResult> results = vectorStore.searchWithFilter(
                    queryEmbedding, limit, filter
                );
                
                return results.stream()
                    .map(this::convertSearchResult)
                    .collect(Collectors.toList());
                    
            } catch (Exception e) {
                LOG.error("Code search failed for query: " + query, e);
                return Collections.emptyList();
            }
        });
    }
    
    /**
     * Gets statistics about the indexed content.
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total_embeddings", vectorStore.size());
        stats.put("embedding_model", embeddingService.getModelName());
        stats.put("embedding_dimension", embeddingService.getDimension());
        stats.put("use_hybrid_search", useHybridSearch);
        return stats;
    }
    
    private void convertMetadata(Metadata from, Map<String, Object> to) {
        // Convert LangChain4j Metadata to our Map format
        for (String key : from.toMap().keySet()) {
            to.put(key, from.get(key));
        }
    }
    
    /**
     * Clears all indexed content.
     */
    public void clearIndex() {
        vectorStore.clear();
        LOG.info("Cleared RAG index");
    }
    
    /**
     * Configures the search behavior.
     */
    public void configureSearch(boolean useHybridSearch, double hybridSearchVectorWeight) {
        this.useHybridSearch = useHybridSearch;
        this.hybridSearchVectorWeight = Math.max(0, Math.min(1, hybridSearchVectorWeight));
    }
    
    private String buildSignatureContent(CodeSignature signature) {
        StringBuilder sb = new StringBuilder();
        sb.append(signature.getSignature()).append("\n");
        sb.append("ID: ").append(signature.getId()).append("\n");
        
        // Parse metadata to add more context
        try {
            com.google.gson.JsonObject metadata = com.google.gson.JsonParser
                .parseString(signature.getMetadata())
                .getAsJsonObject();
                
            if (metadata.has("javadoc")) {
                String javadoc = metadata.get("javadoc").getAsString();
                if (javadoc != null && !javadoc.isEmpty()) {
                    sb.append("\n").append(javadoc);
                }
            }
        } catch (Exception e) {
            // Ignore metadata parsing errors
        }
        
        return sb.toString();
    }
    
    private String extractSignatureType(CodeSignature signature) {
        try {
            com.google.gson.JsonObject metadata = com.google.gson.JsonParser
                .parseString(signature.getMetadata())
                .getAsJsonObject();
            return metadata.has("type") ? metadata.get("type").getAsString() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
    
    private List<String> extractKeywords(String query) {
        // Simple keyword extraction - can be improved with NLP
        return Arrays.stream(query.toLowerCase().split("\\s+"))
            .filter(word -> word.length() > 2)
            .filter(word -> !isStopWord(word))
            .distinct()
            .collect(Collectors.toList());
    }
    
    private boolean isStopWord(String word) {
        Set<String> stopWords = Set.of(
            "the", "and", "for", "with", "from", "that", "this", "what", "where",
            "how", "when", "which", "who", "why", "are", "was", "were", "been"
        );
        return stopWords.contains(word);
    }
    
    private SearchResult convertSearchResult(VectorStore.SearchResult result) {
        return new SearchResult(
            result.getId(),
            result.getTextSegment().text(),
            result.getMetadata(),
            result.getScore()
        );
    }
    
    /**
     * Represents a search result.
     */
    public static class SearchResult {
        private final String id;
        private final String content;
        private final Map<String, Object> metadata;
        private final double score;
        
        public SearchResult(String id, String content, Map<String, Object> metadata, double score) {
            this.id = id;
            this.content = content;
            this.metadata = metadata;
            this.score = score;
        }
        
        public String getId() { return id; }
        public String getContent() { return content; }
        public Map<String, Object> getMetadata() { return metadata; }
        public double getScore() { return score; }
    }
}
