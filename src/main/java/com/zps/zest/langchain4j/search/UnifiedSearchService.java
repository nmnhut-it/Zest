package com.zps.zest.langchain4j.search;

import com.intellij.openapi.diagnostic.Logger;
import com.zps.zest.langchain4j.EmbeddingService;
import com.zps.zest.langchain4j.VectorStore;
import com.zps.zest.langchain4j.config.SearchConfiguration;
import com.zps.zest.langchain4j.index.NameIndex;
import com.zps.zest.langchain4j.index.SemanticIndex;
import com.zps.zest.langchain4j.index.StructuralIndex;
import com.zps.zest.langchain4j.util.CodeSearchUtils;
import com.zps.zest.langchain4j.util.SearchResultConverter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Unified search service that coordinates searches across different index types.
 * Eliminates duplication between search implementations.
 */
public class UnifiedSearchService {
    private static final Logger LOG = Logger.getInstance(UnifiedSearchService.class);
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3);
    
    private final EmbeddingService embeddingService;
    private final SearchConfiguration config;
    
    public UnifiedSearchService(EmbeddingService embeddingService, SearchConfiguration config) {
        this.embeddingService = embeddingService;
        this.config = config;
    }
    
    /**
     * Perform a simple vector search.
     */
    public CompletableFuture<List<SearchResultConverter.GenericSearchResult>> vectorSearch(
            String query, 
            int limit,
            VectorStore vectorStore) {
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                float[] queryEmbedding = embeddingService.embed(query);
                
                List<VectorStore.SearchResult> results;
                if (config.isUseHybridSearch()) {
                    List<String> keywords = CodeSearchUtils.extractKeywords(query);
                    results = vectorStore.hybridSearch(
                        queryEmbedding, 
                        keywords, 
                        limit, 
                        config.getHybridSearchVectorWeight()
                    );
                } else {
                    results = vectorStore.search(queryEmbedding, limit);
                }
                
                return results.stream()
                    .map(r -> SearchResultConverter.createGenericResult(
                        r.getId(),
                        r.getTextSegment().text(),
                        r.getScore(),
                        r.getMetadata()
                    ))
                    .collect(Collectors.toList());
                    
            } catch (Exception e) {
                LOG.error("Vector search failed for query: " + query, e);
                return Collections.emptyList();
            }
        }, EXECUTOR);
    }
    
    /**
     * Perform a filtered vector search.
     */
    public CompletableFuture<List<SearchResultConverter.GenericSearchResult>> filteredVectorSearch(
            String query,
            int limit,
            Map<String, Object> filter,
            VectorStore vectorStore) {
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                float[] queryEmbedding = embeddingService.embed(query);
                
                List<VectorStore.SearchResult> results = vectorStore.searchWithFilter(
                    queryEmbedding, limit, filter
                );
                
                return results.stream()
                    .map(r -> SearchResultConverter.createGenericResult(
                        r.getId(),
                        r.getTextSegment().text(),
                        r.getScore(),
                        r.getMetadata()
                    ))
                    .collect(Collectors.toList());
                    
            } catch (Exception e) {
                LOG.error("Filtered search failed for query: " + query, e);
                return Collections.emptyList();
            }
        }, EXECUTOR);
    }
    
    /**
     * Perform a multi-index hybrid search.
     */
    public CompletableFuture<HybridSearchResults> hybridSearch(
            String query,
            int limit,
            NameIndex nameIndex,
            SemanticIndex semanticIndex,
            StructuralIndex structuralIndex) {
        
        return CompletableFuture.supplyAsync(() -> {
            HybridSearchResults results = new HybridSearchResults();
            
            try {
                // 1. Name-based search (highest priority for exact matches)
                if (nameIndex != null) {
                    searchByName(query, limit, nameIndex, results);
                }
                
                // 2. Semantic search
                if (semanticIndex != null) {
                    searchBySemantic(query, limit, semanticIndex, results);
                }
                
                // 3. Structural search (if query suggests relationships)
                if (structuralIndex != null && CodeSearchUtils.suggestsStructuralSearch(query)) {
                    searchByStructure(query, limit, structuralIndex, results);
                }
                
            } catch (Exception e) {
                LOG.error("Hybrid search failed", e);
            }
            
            return results;
        }, EXECUTOR);
    }
    
    /**
     * Search by name/identifier.
     */
    private void searchByName(String query, int limit, NameIndex nameIndex, HybridSearchResults results) {
        try {
            List<NameIndex.SearchResult> nameResults = nameIndex.search(query, limit);
            
            for (NameIndex.SearchResult nameResult : nameResults) {
                results.addResult(new HybridSearchResult(
                    nameResult.getId(),
                    nameResult.getSignature(),
                    nameResult.getType(),
                    nameResult.getFilePath(),
                    nameResult.getScore() * config.getNameWeight(),
                    SearchSource.NAME
                ));
            }
        } catch (IOException e) {
            LOG.error("Error searching name index", e);
        }
    }
    
    /**
     * Search by semantic similarity.
     */
    private void searchBySemantic(String query, int limit, SemanticIndex semanticIndex, HybridSearchResults results) {
        List<String> keywords = CodeSearchUtils.extractKeywords(query);
        List<SemanticIndex.SearchResult> semanticResults = semanticIndex.hybridSearch(
            query, keywords, limit, config.getHybridSearchVectorWeight()
        );
        
        for (SemanticIndex.SearchResult semanticResult : semanticResults) {
            results.addOrMergeResult(new HybridSearchResult(
                semanticResult.getId(),
                semanticResult.getContent(),
                (String) semanticResult.getMetadata().get("type"),
                (String) semanticResult.getMetadata().get("file_path"),
                semanticResult.getScore() * config.getSemanticWeight(),
                SearchSource.SEMANTIC
            ));
        }
    }
    
    /**
     * Search by structural relationships.
     */
    private void searchByStructure(String query, int limit, StructuralIndex structuralIndex, HybridSearchResults results) {
        String lowerQuery = query.toLowerCase();
        
        // Get top results for structural analysis
        List<HybridSearchResult> topResults = results.getTopResults(5);
        
        for (HybridSearchResult existing : topResults) {
            if (lowerQuery.contains("calls") || lowerQuery.contains("uses")) {
                addCallRelationships(existing, structuralIndex, results);
            }
            
            if (lowerQuery.contains("extends") || lowerQuery.contains("implements")) {
                addInheritanceRelationships(existing, structuralIndex, results);
            }
            
            if (lowerQuery.contains("override")) {
                addOverrideRelationships(existing, structuralIndex, results);
            }
        }
    }
    
    private void addCallRelationships(HybridSearchResult existing, StructuralIndex structuralIndex, HybridSearchResults results) {
        List<String> callers = structuralIndex.findCallers(existing.getId());
        for (String caller : callers) {
            results.addOrMergeResult(new HybridSearchResult(
                caller,
                "Calls " + existing.getId(),
                "method",
                existing.getFilePath(),
                config.getStructuralWeight() * 0.8,
                SearchSource.STRUCTURAL
            ));
        }
    }
    
    private void addInheritanceRelationships(HybridSearchResult existing, StructuralIndex structuralIndex, HybridSearchResults results) {
        if ("class".equals(existing.getType()) || "interface".equals(existing.getType())) {
            List<String> subclasses = structuralIndex.findSubclasses(existing.getId());
            List<String> implementations = structuralIndex.findImplementations(existing.getId());
            
            for (String subclass : subclasses) {
                results.addOrMergeResult(new HybridSearchResult(
                    subclass,
                    "Extends " + existing.getId(),
                    "class",
                    "",
                    config.getStructuralWeight() * 0.7,
                    SearchSource.STRUCTURAL
                ));
            }
            
            for (String impl : implementations) {
                results.addOrMergeResult(new HybridSearchResult(
                    impl,
                    "Implements " + existing.getId(),
                    "class",
                    "",
                    config.getStructuralWeight() * 0.7,
                    SearchSource.STRUCTURAL
                ));
            }
        }
    }
    
    private void addOverrideRelationships(HybridSearchResult existing, StructuralIndex structuralIndex, HybridSearchResults results) {
        if ("method".equals(existing.getType())) {
            // Find methods that override this one
            List<String> overridingMethods = structuralIndex.findOverridingMethods(existing.getId());
            for (String override : overridingMethods) {
                results.addOrMergeResult(new HybridSearchResult(
                    override,
                    "Overrides " + existing.getId(),
                    "method",
                    "",
                    config.getStructuralWeight() * 0.6,
                    SearchSource.STRUCTURAL
                ));
            }
        }
    }
    
    /**
     * Container for hybrid search results.
     */
    public static class HybridSearchResults {
        private final Map<String, HybridSearchResult> results = new HashMap<>();
        
        public void addResult(HybridSearchResult result) {
            results.put(result.getId(), result);
        }
        
        public void addOrMergeResult(HybridSearchResult result) {
            results.merge(result.getId(), result, (existing, newResult) -> {
                existing.addSource(newResult.getSources().iterator().next(), newResult.getCombinedScore());
                return existing;
            });
        }
        
        public boolean isEmpty() {
            return results.isEmpty();
        }
        
        public List<HybridSearchResult> getTopResults(int limit) {
            return results.values().stream()
                .sorted((a, b) -> Double.compare(b.getCombinedScore(), a.getCombinedScore()))
                .limit(limit)
                .collect(Collectors.toList());
        }
        
        public Collection<HybridSearchResult> getAllResults() {
            return results.values();
        }
    }
    
    /**
     * Combined search result from multiple sources.
     */
    public static class HybridSearchResult {
        private final String id;
        private final String content;
        private final String type;
        private final String filePath;
        private final Map<SearchSource, Double> sourceScores = new EnumMap<>(SearchSource.class);
        
        public HybridSearchResult(String id, String content, String type, String filePath, 
                                double score, SearchSource source) {
            this.id = id;
            this.content = content;
            this.type = type;
            this.filePath = filePath;
            this.sourceScores.put(source, score);
        }
        
        public void addSource(SearchSource source, double score) {
            sourceScores.merge(source, score, Double::sum);
        }
        
        public double getCombinedScore() {
            return sourceScores.values().stream().mapToDouble(Double::doubleValue).sum();
        }
        
        public Set<SearchSource> getSources() {
            return sourceScores.keySet();
        }
        
        // Getters
        public String getId() { return id; }
        public String getContent() { return content; }
        public String getType() { return type; }
        public String getFilePath() { return filePath; }
        public Map<SearchSource, Double> getSourceScores() { return new HashMap<>(sourceScores); }
    }
    
    /**
     * Search source indicator.
     */
    public enum SearchSource {
        NAME("Name-based"),
        SEMANTIC("Semantic"),
        STRUCTURAL("Structural"),
        VECTOR("Vector");
        
        private final String displayName;
        
        SearchSource(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
}
