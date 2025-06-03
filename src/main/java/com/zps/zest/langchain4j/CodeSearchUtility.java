package com.zps.zest.langchain4j;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.index.NameIndex;
import com.zps.zest.langchain4j.index.SemanticIndex;
import com.zps.zest.langchain4j.index.StructuralIndex;
import com.zps.zest.rag.CodeSignature;

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
    private final RelatedCodeFinder relatedCodeFinder;
    
    // Search configuration
    private SearchConfig config = new SearchConfig();
    
    public CodeSearchUtility(Project project) {
        this.project = project;
        this.indexManager = project.getService(HybridIndexManager.class);
        this.relatedCodeFinder = new RelatedCodeFinder(project);
        
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
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Phase 1: Search across all indices
                HybridSearchResults hybridResults = performHybridSearch(query, maxResults * 3);
                
                if (hybridResults.isEmpty()) {
                    LOG.info("No results found");
                    return Collections.emptyList();
                }
                
                // Phase 2: Enrich with related code
                List<EnrichedSearchResult> enrichedResults = enrichResults(hybridResults, maxResults);
                
                LOG.info("Search complete. Found " + enrichedResults.size() + " enriched results");
                return enrichedResults;
                
            } catch (Exception e) {
                LOG.error("Error during hybrid search", e);
                return Collections.emptyList();
            }
        }, EXECUTOR);
    }
    static ExecutorService EXECUTOR = Executors.newFixedThreadPool(3);
    /**
     * Performs search across all three indices and combines results.
     */
    private HybridSearchResults performHybridSearch(String query, int limit) {
        HybridSearchResults results = new HybridSearchResults();
        
        try {
            // 1. Name-based search (highest priority for exact matches)
            List<NameIndex.SearchResult> nameResults = indexManager.getNameIndex()
                .search(query, limit);
            
            for (NameIndex.SearchResult nameResult : nameResults) {
                results.addResult(new CombinedSearchResult(
                    nameResult.getId(),
                    nameResult.getSignature(),
                    nameResult.getType(),
                    nameResult.getFilePath(),
                    nameResult.getScore() * config.nameWeight,
                    SearchSource.NAME
                ));
            }
            
            // 2. Semantic search
            List<String> keywords = extractKeywords(query);
            List<SemanticIndex.SearchResult> semanticResults = indexManager.getSemanticIndex()
                .hybridSearch(query, keywords, limit, config.semanticVectorWeight);
            
            for (SemanticIndex.SearchResult semanticResult : semanticResults) {
                results.addOrMergeResult(new CombinedSearchResult(
                    semanticResult.getId(),
                    semanticResult.getContent(),
                    (String) semanticResult.getMetadata().get("type"),
                    (String) semanticResult.getMetadata().get("file_path"),
                    semanticResult.getScore() * config.semanticWeight,
                    SearchSource.SEMANTIC
                ));
            }
            
            // 3. Structural search (if query suggests relationships)
            if (shouldPerformStructuralSearch(query)) {
                addStructuralResults(query, results, limit);
            }
            
        } catch (Exception e) {
            LOG.error("Error in hybrid search", e);
        }
        
        return results;
    }
    
    /**
     * Adds structural search results based on query intent.
     */
    private void addStructuralResults(String query, HybridSearchResults results, int limit) {
        StructuralIndex structuralIndex = indexManager.getStructuralIndex();
        
        // Detect relationship keywords
        String lowerQuery = query.toLowerCase();
        
        if (lowerQuery.contains("calls") || lowerQuery.contains("uses")) {
            // Find methods that call specific patterns
            for (CombinedSearchResult existing : results.getTopResults(5)) {
                List<String> callers = structuralIndex.findCallers(existing.getId());
                for (String caller : callers) {
                    results.addOrMergeResult(new CombinedSearchResult(
                        caller,
                        "Calls " + existing.getId(),
                        "method",
                        "",
                        config.structuralWeight * 0.8,
                        SearchSource.STRUCTURAL
                    ));
                }
            }
        }
        
        if (lowerQuery.contains("extends") || lowerQuery.contains("implements")) {
            // Find inheritance relationships
            for (CombinedSearchResult existing : results.getTopResults(5)) {
                if ("class".equals(existing.getType()) || "interface".equals(existing.getType())) {
                    List<String> subclasses = structuralIndex.findSubclasses(existing.getId());
                    List<String> implementations = structuralIndex.findImplementations(existing.getId());
                    
                    for (String subclass : subclasses) {
                        results.addOrMergeResult(new CombinedSearchResult(
                            subclass,
                            "Extends " + existing.getId(),
                            "class",
                            "",
                            config.structuralWeight * 0.7,
                            SearchSource.STRUCTURAL
                        ));
                    }
                    
                    for (String impl : implementations) {
                        results.addOrMergeResult(new CombinedSearchResult(
                            impl,
                            "Implements " + existing.getId(),
                            "class",
                            "",
                            config.structuralWeight * 0.7,
                            SearchSource.STRUCTURAL
                        ));
                    }
                }
            }
        }
    }
    
    /**
     * Enriches search results with related code and context.
     */
    private List<EnrichedSearchResult> enrichResults(HybridSearchResults hybridResults, int maxResults) {
        List<EnrichedSearchResult> enrichedResults = new ArrayList<>();
        
        // Get top results
        List<CombinedSearchResult> topResults = hybridResults.getTopResults(maxResults * 2);
        
        // Group by file for context
        Map<String, List<CombinedSearchResult>> byFile = topResults.stream()
            .collect(Collectors.groupingBy(r -> r.getFilePath() != null ? r.getFilePath() : "unknown"));
        
        for (Map.Entry<String, List<CombinedSearchResult>> entry : byFile.entrySet()) {
            List<CombinedSearchResult> fileResults = entry.getValue();
            
            // Find primary result for this file
            CombinedSearchResult primary = fileResults.stream()
                .max(Comparator.comparing(CombinedSearchResult::getCombinedScore))
                .orElse(null);
                
            if (primary == null) continue;
            
            // Create enriched result
            EnrichedSearchResult enriched = new EnrichedSearchResult(
                primary.getId(),
                primary.getContent(),
                primary.getCombinedScore(),
                primary.getFilePath(),
                primary.getSources()
            );
            
            // Add structural relationships
            StructuralIndex structuralIndex = indexManager.getStructuralIndex();
            Map<StructuralIndex.RelationType, List<String>> relationships = 
                structuralIndex.findAllRelated(primary.getId());
            enriched.setStructuralRelationships(relationships);
            
            // Find similar code using semantic index
            List<SemanticIndex.SearchResult> similar = indexManager.getSemanticIndex()
                .findSimilar(primary.getId(), config.maxRelatedCodePerResult);
            
            for (SemanticIndex.SearchResult sim : similar) {
                enriched.addSimilarCode(new SimilarCode(
                    sim.getId(),
                    sim.getContent(),
                    sim.getScore(),
                    "Semantically similar"
                ));
            }
            
            // Add other results from same file
            for (CombinedSearchResult other : fileResults) {
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
     * Determines if structural search should be performed based on query.
     */
    private boolean shouldPerformStructuralSearch(String query) {
        String lower = query.toLowerCase();
        return lower.contains("call") || lower.contains("use") || 
               lower.contains("extend") || lower.contains("implement") ||
               lower.contains("override") || lower.contains("inherit") ||
               lower.contains("depend");
    }
    
    /**
     * Extracts keywords from query for hybrid search.
     */
    private List<String> extractKeywords(String query) {
        // Simple keyword extraction
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
    
    /**
     * Configures the search behavior.
     */
    public void configure(SearchConfig config) {
        this.config = config;
        LOG.info("Updated search configuration: " + config);
    }
    
    /**
     * Search configuration.
     */
    public static class SearchConfig {
        private double nameWeight = 2.0;
        private double semanticWeight = 1.5;
        private double structuralWeight = 1.0;
        private double semanticVectorWeight = 0.7;
        private int maxRelatedCodePerResult = 5;
        private double relevanceThreshold = 0.3;
        
        // Getters and setters
        public double getNameWeight() { return nameWeight; }
        public void setNameWeight(double nameWeight) { this.nameWeight = nameWeight; }
        
        public double getSemanticWeight() { return semanticWeight; }
        public void setSemanticWeight(double semanticWeight) { this.semanticWeight = semanticWeight; }
        
        public double getStructuralWeight() { return structuralWeight; }
        public void setStructuralWeight(double structuralWeight) { this.structuralWeight = structuralWeight; }
        
        public double getSemanticVectorWeight() { return semanticVectorWeight; }
        public void setSemanticVectorWeight(double semanticVectorWeight) { this.semanticVectorWeight = semanticVectorWeight; }
        
        public int getMaxRelatedCodePerResult() { return maxRelatedCodePerResult; }
        public void setMaxRelatedCodePerResult(int maxRelatedCodePerResult) { this.maxRelatedCodePerResult = maxRelatedCodePerResult; }
        
        public double getRelevanceThreshold() { return relevanceThreshold; }
        public void setRelevanceThreshold(double relevanceThreshold) { this.relevanceThreshold = relevanceThreshold; }
        
        @Override
        public String toString() {
            return "SearchConfig{" +
                   "nameWeight=" + nameWeight +
                   ", semanticWeight=" + semanticWeight +
                   ", structuralWeight=" + structuralWeight +
                   ", semanticVectorWeight=" + semanticVectorWeight +
                   ", maxRelatedCodePerResult=" + maxRelatedCodePerResult +
                   ", relevanceThreshold=" + relevanceThreshold +
                   '}';
        }
    }
    
    /**
     * Container for results from all indices.
     */
    private static class HybridSearchResults {
        private final Map<String, CombinedSearchResult> results = new HashMap<>();
        
        public void addResult(CombinedSearchResult result) {
            results.put(result.getId(), result);
        }
        
        public void addOrMergeResult(CombinedSearchResult result) {
            results.merge(result.getId(), result, (existing, newResult) -> {
                // Combine scores from different sources
                existing.addSource(newResult.getSources().iterator().next(), newResult.getCombinedScore());
                return existing;
            });
        }
        
        public boolean isEmpty() {
            return results.isEmpty();
        }
        
        public List<CombinedSearchResult> getTopResults(int limit) {
            return results.values().stream()
                .sorted((a, b) -> Double.compare(b.getCombinedScore(), a.getCombinedScore()))
                .limit(limit)
                .collect(Collectors.toList());
        }
    }
    
    /**
     * Combined search result from multiple indices.
     */
    private static class CombinedSearchResult {
        private final String id;
        private final String content;
        private final String type;
        private final String filePath;
        private final Map<SearchSource, Double> sourceScores = new EnumMap<>(SearchSource.class);
        
        public CombinedSearchResult(String id, String content, String type, String filePath, 
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
