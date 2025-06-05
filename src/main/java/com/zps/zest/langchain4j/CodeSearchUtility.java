package com.zps.zest.langchain4j;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.index.NameIndex;
import com.zps.zest.langchain4j.index.SemanticIndex;
import com.zps.zest.langchain4j.index.StructuralIndex;
import com.zps.zest.rag.CodeSignature;
import com.zps.zest.langchain4j.util.CodeSearchUtils;
import com.zps.zest.langchain4j.search.UnifiedSearchService;
import com.zps.zest.langchain4j.config.SearchConfiguration;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Main search utility that combines the three indices for comprehensive code search:
 * 1. NameIndex - For identifier matching (camelCase aware)
 * 2. SemanticIndex - For semantic similarity
 * 3. StructuralIndex - For code relationships
 */
@Service(Service.Level.PROJECT)
public final class CodeSearchUtility {
    private static final Logger LOG = Logger.getInstance(CodeSearchUtility.class);
    
    private final Project project;
    private final HybridIndexManager indexManager;
    private UnifiedSearchService searchService;
    private SearchConfiguration config;
    
    public CodeSearchUtility(Project project) {
        this.project = project;
        this.indexManager = project.getService(HybridIndexManager.class);
        this.config = new SearchConfiguration();
        
        // Create search service with appropriate embedding service
        EmbeddingService embeddingService = new LocalEmbeddingService();
        this.searchService = new UnifiedSearchService(embeddingService, config);
        
        LOG.info("Initialized CodeSearchUtility for project: " + project.getName());
    }
    
    /**
     * Performs hybrid search across all indices.
     * 
     * @param query The search query
     * @param maxResults Maximum number of results to return
     * @return Search results with enriched information
     */
    public CompletableFuture<List<EnrichedSearchResult>> searchRelatedCode(String query, int maxResults) {
        LOG.info("Starting hybrid search for query: " + query);
        
        return searchService.hybridSearch(
                query, 
                maxResults * 3,
                indexManager.getNameIndex(),
                indexManager.getSemanticIndex(),
                indexManager.getStructuralIndex()
            )
            .thenApply(hybridResults -> {
                if (hybridResults.isEmpty()) {
                    LOG.info("No results found");
                    return Collections.<EnrichedSearchResult>emptyList();
                }
                
                // Phase 2: Enrich with related code
                List<EnrichedSearchResult> enrichedResults = enrichResults(hybridResults, maxResults);
                
                LOG.info("Search complete. Found " + enrichedResults.size() + " enriched results");
                return enrichedResults;
            })
            .exceptionally(e -> {
                LOG.error("Error during hybrid search", e);
                return Collections.<EnrichedSearchResult>emptyList();
            });
    }
    
    /**
     * Enriches search results with related code and context.
     */
    private List<EnrichedSearchResult> enrichResults(UnifiedSearchService.HybridSearchResults hybridResults, int maxResults) {
        List<EnrichedSearchResult> enrichedResults = new ArrayList<>();
        
        // Get top results
        List<UnifiedSearchService.HybridSearchResult> topResults = hybridResults.getTopResults(maxResults * 2);
        
        // Group by file for context
        Map<String, List<UnifiedSearchService.HybridSearchResult>> byFile = topResults.stream()
            .collect(Collectors.groupingBy(r -> r.getFilePath() != null ? r.getFilePath() : "unknown"));
        
        for (Map.Entry<String, List<UnifiedSearchService.HybridSearchResult>> entry : byFile.entrySet()) {
            List<UnifiedSearchService.HybridSearchResult> fileResults = entry.getValue();
            
            // Find primary result for this file
            UnifiedSearchService.HybridSearchResult primary = fileResults.stream()
                .max(Comparator.comparing(UnifiedSearchService.HybridSearchResult::getCombinedScore))
                .orElse(null);
                
            if (primary == null) continue;
            
            // Create enriched result - map SearchSource types
            Set<SearchSource> mappedSources = primary.getSources().stream()
                .map(this::mapSearchSource)
                .collect(Collectors.toSet());
            
            EnrichedSearchResult enriched = new EnrichedSearchResult(
                primary.getId(),
                primary.getContent(),
                primary.getCombinedScore(),
                primary.getFilePath(),
                mappedSources
            );
            
            // Add structural relationships
            StructuralIndex structuralIndex = indexManager.getStructuralIndex();
            Map<StructuralIndex.RelationType, List<String>> relationships = 
                structuralIndex.findAllRelated(primary.getId());
            enriched.setStructuralRelationships(relationships);
            
            // Find similar code using semantic index
            List<SemanticIndex.SearchResult> similar = indexManager.getSemanticIndex()
                .findSimilar(primary.getId(), config.getMaxRelatedCodePerResult());
            
            for (SemanticIndex.SearchResult sim : similar) {
                enriched.addSimilarCode(new SimilarCode(
                    sim.getId(),
                    sim.getContent(),
                    sim.getScore(),
                    "Semantically similar"
                ));
            }
            
            // Add other results from same file
            for (UnifiedSearchService.HybridSearchResult other : fileResults) {
                if (!other.getId().equals(primary.getId())) {
                    enriched.addRelatedInFile(new RelatedInFile(
                        other.getId(),
                        other.getContent(),
                        other.getCombinedScore(),
                        other.getType()
                    ));
                }
            }
            
            enrichedResults.add(enriched);
        }
        
        // Sort by score and limit
        enrichedResults.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        
        return enrichedResults.stream()
            .limit(maxResults)
            .collect(Collectors.toList());
    }
    
    /**
     * Maps UnifiedSearchService.SearchSource to local SearchSource enum.
     */
    private SearchSource mapSearchSource(UnifiedSearchService.SearchSource source) {
        switch (source) {
            case NAME:
                return SearchSource.NAME;
            case SEMANTIC:
                return SearchSource.SEMANTIC;
            case STRUCTURAL:
                return SearchSource.STRUCTURAL;
            default:
                return SearchSource.SEMANTIC; // Default fallback
        }
    }
    
    /**
     * Configures the search behavior.
     */
    public void configure(SearchConfiguration config) {
        this.config = config;
        // Re-create search service with new configuration
        EmbeddingService embeddingService = new LocalEmbeddingService();
        this.searchService = new UnifiedSearchService(embeddingService, config);
        LOG.info("Updated search configuration: " + config);
    }
    
    /**
     * Search source indicator.
     */
    public enum SearchSource {
        NAME("Name-based"),
        SEMANTIC("Semantic"),
        STRUCTURAL("Structural");
        
        private final String displayName;
        
        SearchSource(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
    
    /**
     * Enriched search result with all related information.
     */
    public static class EnrichedSearchResult {
        private final String id;
        private final String content;
        private final double score;
        private final String filePath;
        private final Set<SearchSource> sources;
        private Map<StructuralIndex.RelationType, List<String>> structuralRelationships = new HashMap<>();
        private List<SimilarCode> similarCode = new ArrayList<>();
        private List<RelatedInFile> relatedInFile = new ArrayList<>();
        
        public EnrichedSearchResult(String id, String content, double score, 
                                   String filePath, Set<SearchSource> sources) {
            this.id = id;
            this.content = content;
            this.score = score;
            this.filePath = filePath;
            this.sources = sources;
        }
        
        public void setStructuralRelationships(Map<StructuralIndex.RelationType, List<String>> relationships) {
            this.structuralRelationships = relationships;
        }
        
        public void addSimilarCode(SimilarCode similar) {
            this.similarCode.add(similar);
        }
        
        public void addRelatedInFile(RelatedInFile related) {
            this.relatedInFile.add(related);
        }
        
        // Getters
        public String getId() { return id; }
        public String getContent() { return content; }
        public double getScore() { return score; }
        public String getFilePath() { return filePath; }
        public Set<SearchSource> getSources() { return sources; }
        public Map<StructuralIndex.RelationType, List<String>> getStructuralRelationships() { return structuralRelationships; }
        public List<SimilarCode> getSimilarCode() { return similarCode; }
        public List<RelatedInFile> getRelatedInFile() { return relatedInFile; }
    }
    
    /**
     * Similar code found through semantic search.
     */
    public static class SimilarCode {
        private final String id;
        private final String content;
        private final double similarity;
        private final String reason;
        
        public SimilarCode(String id, String content, double similarity, String reason) {
            this.id = id;
            this.content = content;
            this.similarity = similarity;
            this.reason = reason;
        }
        
        // Getters
        public String getId() { return id; }
        public String getContent() { return content; }
        public double getSimilarity() { return similarity; }
        public String getReason() { return reason; }
    }
    
    /**
     * Related code in the same file.
     */
    public static class RelatedInFile {
        private final String id;
        private final String content;
        private final double score;
        private final String type;
        
        public RelatedInFile(String id, String content, double score, String type) {
            this.id = id;
            this.content = content;
            this.score = score;
            this.type = type;
        }
        
        // Getters
        public String getId() { return id; }
        public String getContent() { return content; }
        public double getScore() { return score; }
        public String getType() { return type; }
    }
}
