package com.zps.zest.langchain4j;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.zps.zest.rag.CodeSignature;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import com.zps.zest.langchain4j.util.CodeSearchUtils;
import com.zps.zest.langchain4j.util.SearchResultConverter;

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
        if (!CodeSearchUtils.isValidForIndexing(signature)) {
            LOG.warn("Invalid signature for indexing: " + signature);
            return;
        }
        
        String content = CodeSearchUtils.buildSignatureContent(signature);
        
        // Create metadata map for the segment
        Map<String, String> segmentMetadata = new HashMap<>();
        segmentMetadata.put("type", "signature");
        segmentMetadata.put("signature_id", signature.getId());
        segmentMetadata.put("file_path", signature.getFilePath());
        
        TextSegment segment = documentProcessor.createSegment(content, segmentMetadata);
        
        float[] embedding = embeddingService.embed(content);
        
        Map<String, Object> metadata = CodeSearchUtils.createVectorMetadata(signature, Map.of(
            "type", "signature",
            "signature_type", CodeSearchUtils.extractSignatureType(signature)
        ));
        
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
                    List<String> keywords = CodeSearchUtils.extractKeywords(query);
                    results = vectorStore.hybridSearch(queryEmbedding, keywords, limit, hybridSearchVectorWeight);
                } else {
                    results = vectorStore.search(queryEmbedding, limit);
                }
                
                // Convert to our SearchResult format
                return results.stream()
                    .map(SearchResultConverter::fromVectorStoreResult)
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
                    .map(SearchResultConverter::fromVectorStoreResult)
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
