package com.zps.zest.langchain4j;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.zps.zest.rag.CodeSignature;
import com.zps.zest.rag.RagAgent;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Hybrid RAG agent that combines LangChain4j local embeddings with OpenWebUI knowledge base.
 * Provides the best of both worlds: fast local search and cloud-based persistence.
 */
@Service(Service.Level.PROJECT)
public final class HybridRagAgent {
    private static final Logger LOG = Logger.getInstance(HybridRagAgent.class);
    
    private final Project project;
    private final RagAgent openWebUIAgent;
    private final RagService langChainService;
    
    // Configuration
    private boolean preferLocal = true;
    private double localWeight = 0.6;
    
    public HybridRagAgent(Project project) {
        this.project = project;
        this.openWebUIAgent = RagAgent.getInstance(project);
        this.langChainService = project.getService(RagService.class);
        
        LOG.info("Initialized Hybrid RAG Agent combining LangChain4j and OpenWebUI");
    }
    
    /**
     * Indexes a file in both local and cloud systems.
     * 
     * @param file The file to index
     * @param signatures Pre-extracted code signatures
     */
    public void indexFile(VirtualFile file, List<CodeSignature> signatures) {
        // Index locally for fast search
        int segmentCount = langChainService.indexFile(file, signatures);
        LOG.info("Indexed " + segmentCount + " segments locally for: " + file.getName());
        
        // Also index individual signatures for precise retrieval
        if (signatures != null) {
            for (CodeSignature signature : signatures) {
                langChainService.indexCodeSignature(signature);
            }
        }
        
        // The OpenWebUI indexing is handled by the existing RagAgent
        // It runs in the background through its own mechanism
    }
    
    /**
     * Performs hybrid search across both local and cloud indices.
     * 
     * @param query The search query
     * @param limit Maximum number of results
     * @return Combined and ranked search results
     */
    public CompletableFuture<List<HybridSearchResult>> search(String query, int limit) {
        // Start both searches in parallel
        CompletableFuture<List<RagService.SearchResult>> localSearchFuture = 
            langChainService.search(query, limit * 2);
            
        CompletableFuture<List<RagAgent.CodeMatch>> cloudSearchFuture = 
            openWebUIAgent.findRelatedCode(query);
        
        // Combine results when both complete
        return localSearchFuture.thenCombine(cloudSearchFuture, (localResults, cloudResults) -> {
            List<HybridSearchResult> hybridResults = new ArrayList<>();
            
            // Convert local results
            for (RagService.SearchResult local : localResults) {
                hybridResults.add(new HybridSearchResult(
                    local.getId(),
                    local.getContent(),
                    local.getMetadata(),
                    local.getScore() * localWeight,
                    SearchSource.LOCAL
                ));
            }
            
            // Convert cloud results
            double cloudWeight = 1.0 - localWeight;
            for (RagAgent.CodeMatch cloud : cloudResults) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("signature_id", cloud.getSignature().getId());
                metadata.put("file_path", cloud.getSignature().getFilePath());
                
                hybridResults.add(new HybridSearchResult(
                    cloud.getSignature().getId(),
                    cloud.getSignature().getSignature(),
                    metadata,
                    cloud.getRelevance() * cloudWeight,
                    SearchSource.CLOUD
                ));
            }
            
            // Merge and deduplicate results
            Map<String, HybridSearchResult> mergedResults = new HashMap<>();
            for (HybridSearchResult result : hybridResults) {
                String key = result.getId();
                if (mergedResults.containsKey(key)) {
                    // Combine scores if found in both sources
                    HybridSearchResult existing = mergedResults.get(key);
                    double combinedScore = existing.getScore() + result.getScore();
                    mergedResults.put(key, new HybridSearchResult(
                        result.getId(),
                        result.getContent(),
                        result.getMetadata(),
                        combinedScore,
                        SearchSource.BOTH
                    ));
                } else {
                    mergedResults.put(key, result);
                }
            }
            
            // Sort by combined score and limit results
            return mergedResults.values().stream()
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .limit(limit)
                .collect(Collectors.toList());
        });
    }
    
    /**
     * Performs code-specific search with enhanced accuracy.
     */
    public CompletableFuture<List<HybridSearchResult>> searchCode(String query, int limit) {
        if (preferLocal) {
            // Use local search for code as it's more accurate with semantic understanding
            return langChainService.searchCode(query, limit)
                .thenApply(results -> results.stream()
                    .map(r -> new HybridSearchResult(
                        r.getId(),
                        r.getContent(),
                        r.getMetadata(),
                        r.getScore(),
                        SearchSource.LOCAL
                    ))
                    .collect(Collectors.toList())
                );
        } else {
            return search(query, limit);
        }
    }
    
    /**
     * Gets full code for a signature ID from either source.
     */
    public String getFullCode(String signatureId) {
        // First try to get from OpenWebUI (has full code)
        String fullCode = openWebUIAgent.getFullCode(signatureId);
        
        if (fullCode == null || fullCode.isEmpty()) {
            // Fallback to local search
            VectorStore.EmbeddingEntry entry = getLocalEntry(signatureId);
            if (entry != null) {
                return entry.getTextSegment().text();
            }
        }
        
        return fullCode;
    }
    
    /**
     * Configures the hybrid search behavior.
     */
    public void configure(boolean preferLocal, double localWeight) {
        this.preferLocal = preferLocal;
        this.localWeight = Math.max(0, Math.min(1, localWeight));
        LOG.info("Configured hybrid search: preferLocal=" + preferLocal + ", localWeight=" + localWeight);
    }
    
    /**
     * Switches to a different embedding store implementation.
     * This allows switching from in-memory to persistent storage.
     * 
     * @param storeType The type of store to use
     */
    public void switchEmbeddingStore(String storeType) {
        // TODO: Implement switching between different LangChain4j stores
        // Options include:
        // - InMemoryEmbeddingStore (current)
        // - ChromaEmbeddingStore
        // - WeaviateEmbeddingStore  
        // - QdrantEmbeddingStore
        // - When LanceDB Java SDK is available, add LanceDBEmbeddingStore
        LOG.info("Store switching not yet implemented. Currently using InMemoryEmbeddingStore.");
    }
    
    /**
     * Gets statistics from both systems.
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        
        // Local statistics
        Map<String, Object> localStats = langChainService.getStatistics();
        stats.put("local", localStats);
        
        // Cloud statistics
        String knowledgeId = com.zps.zest.ConfigurationManager.getInstance(project).getKnowledgeId();
        if (knowledgeId != null) {
            com.zps.zest.rag.models.KnowledgeCollection collection = 
                openWebUIAgent.getKnowledgeCollection(knowledgeId);
            if (collection != null) {
                Map<String, Object> cloudStats = new HashMap<>();
                cloudStats.put("knowledge_id", collection.getId());
                cloudStats.put("name", collection.getName());
                cloudStats.put("file_count", collection.getFiles() != null ? collection.getFiles().size() : 0);
                stats.put("cloud", cloudStats);
            }
        }
        
        stats.put("prefer_local", preferLocal);
        stats.put("local_weight", localWeight);
        
        return stats;
    }
    
    private VectorStore.EmbeddingEntry getLocalEntry(String id) {
        // This would need to be exposed in the RagService
        // For now, return null
        return null;
    }
    
    /**
     * Represents a search result from the hybrid system.
     */
    public static class HybridSearchResult {
        private final String id;
        private final String content;
        private final Map<String, Object> metadata;
        private final double score;
        private final SearchSource source;
        
        public HybridSearchResult(String id, String content, Map<String, Object> metadata, 
                                  double score, SearchSource source) {
            this.id = id;
            this.content = content;
            this.metadata = metadata;
            this.score = score;
            this.source = source;
        }
        
        public String getId() { return id; }
        public String getContent() { return content; }
        public Map<String, Object> getMetadata() { return metadata; }
        public double getScore() { return score; }
        public SearchSource getSource() { return source; }
    }
    
    /**
     * Indicates the source of a search result.
     */
    public enum SearchSource {
        LOCAL,  // From LangChain4j local embeddings
        CLOUD,  // From OpenWebUI knowledge base
        BOTH    // Found in both sources
    }
}
