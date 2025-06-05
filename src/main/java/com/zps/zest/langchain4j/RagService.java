package com.zps.zest.langchain4j;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.openapi.application.ReadAction;
import com.zps.zest.rag.CodeSignature;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import com.zps.zest.langchain4j.util.CodeSearchUtils;
import com.zps.zest.langchain4j.util.SearchResultConverter;
import com.zps.zest.langchain4j.index.IndexingService;
import com.zps.zest.langchain4j.search.UnifiedSearchService;
import com.zps.zest.langchain4j.config.SearchConfiguration;

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
    private final IndexingService indexingService;
    private final UnifiedSearchService searchService;
    private final SearchConfiguration searchConfig;
    
    public RagService(Project project) {
        this.project = project;
        this.embeddingService = new LocalEmbeddingService();
        this.vectorStore = new LangChain4jVectorStore(embeddingService);
        this.documentProcessor = new DocumentProcessor();
        this.searchConfig = new SearchConfiguration();
        this.indexingService = new IndexingService(embeddingService, documentProcessor);
        this.searchService = new UnifiedSearchService(embeddingService, searchConfig);
        
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
        PsiFile psiFile = codeSignatures != null && !codeSignatures.isEmpty() 
            ? ReadAction.compute(() -> com.intellij.psi.PsiManager.getInstance(project).findFile(file))
            : null;
            
        CompletableFuture<IndexingService.IndexResult> future = 
            indexingService.indexFileInVectorStore(file, codeSignatures, vectorStore, psiFile);
        
        try {
            IndexingService.IndexResult result = future.get();
            return result.isSuccess() ? result.getItemsIndexed() : 0;
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
        CompletableFuture<IndexingService.IndexResult> future = 
            indexingService.indexSignature(signature, vectorStore, null, null, null);
        
        try {
            IndexingService.IndexResult result = future.get();
            if (!result.isSuccess()) {
                LOG.warn("Failed to index signature: " + signature.getId() + " - " + result.getMessage());
            }
        } catch (Exception e) {
            LOG.error("Failed to index signature: " + signature.getId(), e);
        }
    }
    
    /**
     * Searches for relevant content using natural language query.
     * 
     * @param query The search query
     * @param limit Maximum number of results
     * @return List of search results
     */
    public CompletableFuture<List<SearchResult>> search(String query, int limit) {
        return searchService.vectorSearch(query, limit, vectorStore)
            .thenApply(results -> results.stream()
                .map(r -> new SearchResult(r.getId(), r.getContent(), r.getMetadata(), r.getScore()))
                .collect(Collectors.toList()));
    }
    
    /**
     * Searches specifically for code elements.
     * 
     * @param query The search query
     * @param limit Maximum number of results
     * @return List of code-specific search results
     */
    public CompletableFuture<List<SearchResult>> searchCode(String query, int limit) {
        Map<String, Object> filter = Map.of("type", "code");
        return searchService.filteredVectorSearch(query, limit, filter, vectorStore)
            .thenApply(results -> results.stream()
                .map(r -> new SearchResult(r.getId(), r.getContent(), r.getMetadata(), r.getScore()))
                .collect(Collectors.toList()));
    }
    
    /**
     * Gets statistics about the indexed content.
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total_embeddings", vectorStore.size());
        stats.put("embedding_model", embeddingService.getModelName());
        stats.put("embedding_dimension", embeddingService.getDimension());
        stats.put("search_config", searchConfig.toString());
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
        searchConfig.setUseHybridSearch(useHybridSearch);
        searchConfig.setHybridSearchVectorWeight(hybridSearchVectorWeight);
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
