package com.zps.zest.langchain4j;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Main search utility with two-phase semantic search:
 * 1. Find files/functions semantically related to query
 * 2. Within those results, find specific code pieces and their related code
 */
@Service(Service.Level.PROJECT)
public final class CodeSearchUtility {
    private static final Logger LOG = Logger.getInstance(CodeSearchUtility.class);
    
    private final Project project;
    private final RagService ragService;
    private final RelatedCodeFinder relatedCodeFinder;
    private final FunctionLevelIndexService indexService;
    
    // Configuration
    private int maxRelatedCodePerResult = 5;
    private double relevanceThreshold = 0.3;
    
    public CodeSearchUtility(Project project) {
        this.project = project;
        this.ragService = project.getService(RagService.class);
        this.relatedCodeFinder = new RelatedCodeFinder(project);
        this.indexService = project.getService(FunctionLevelIndexService.class);
        
        LOG.info("Initialized CodeSearchUtility for project: " + project.getName());
    }
    
    /**
     * Performs two-phase semantic search for code.
     * 
     * @param query The search query
     * @param maxResults Maximum number of results to return
     * @return Search results with enriched related code information
     */
    public CompletableFuture<List<EnrichedSearchResult>> searchRelatedCode(String query, int maxResults) {
        LOG.info("Starting two-phase search for query: " + query);
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Phase 1: Broad semantic search
                List<RagService.SearchResult> phase1Results = performPhase1Search(query, maxResults * 3);
                
                if (phase1Results.isEmpty()) {
                    LOG.info("No results found in phase 1");
                    return Collections.emptyList();
                }
                
                // Phase 2: Narrow down and enrich results
                List<EnrichedSearchResult> enrichedResults = performPhase2Search(phase1Results, query, maxResults);
                
                LOG.info("Search complete. Found " + enrichedResults.size() + " enriched results");
                return enrichedResults;
                
            } catch (Exception e) {
                LOG.error("Error during two-phase search", e);
                return Collections.emptyList();
            }
        });
    }
    
    /**
     * Searches for specific code patterns with context.
     * Useful for finding implementations, usages, or specific patterns.
     */
    public CompletableFuture<List<CodePatternResult>> searchCodePatterns(String pattern, int maxResults) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Search for code matching the pattern
                List<RagService.SearchResult> results = ragService.searchCode(pattern, maxResults * 2)
                    .join();
                
                // Group by file and analyze patterns
                Map<String, List<RagService.SearchResult>> byFile = groupResultsByFile(results);
                
                List<CodePatternResult> patternResults = new ArrayList<>();
                
                for (Map.Entry<String, List<RagService.SearchResult>> entry : byFile.entrySet()) {
                    String filePath = entry.getKey();
                    List<RagService.SearchResult> fileResults = entry.getValue();
                    
                    // Create pattern result for this file
                    CodePatternResult patternResult = new CodePatternResult(
                        filePath,
                        fileResults.size(),
                        extractPatternContext(fileResults, pattern)
                    );
                    
                    // Add individual matches
                    for (RagService.SearchResult result : fileResults) {
                        patternResult.addMatch(new PatternMatch(
                            result.getId(),
                            result.getContent(),
                            result.getScore(),
                            extractSignatureType(result.getMetadata())
                        ));
                    }
                    
                    patternResults.add(patternResult);
                }
                
                // Sort by relevance (number of matches and average score)
                patternResults.sort((a, b) -> {
                    double scoreA = a.getAverageScore() * Math.log(a.getMatchCount() + 1);
                    double scoreB = b.getAverageScore() * Math.log(b.getMatchCount() + 1);
                    return Double.compare(scoreB, scoreA);
                });
                
                return patternResults.stream()
                    .limit(maxResults)
                    .collect(Collectors.toList());
                    
            } catch (Exception e) {
                LOG.error("Error searching code patterns", e);
                return Collections.emptyList();
            }
        });
    }
    
    /**
     * Finds code similar to a given signature ID.
     * Useful for finding similar implementations or patterns.
     */
    public CompletableFuture<List<SimilarCodeResult>> findSimilarCode(String signatureId, int maxResults) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // First, find the original signature's content
                String originalContent = getSignatureContent(signatureId);
                if (originalContent == null) {
                    LOG.warn("Could not find content for signature: " + signatureId);
                    return Collections.emptyList();
                }
                
                // Search for similar code
                List<RagService.SearchResult> similarResults = ragService.search(originalContent, maxResults * 2)
                    .join();
                
                // Filter out the original and convert to SimilarCodeResult
                List<SimilarCodeResult> results = new ArrayList<>();
                
                for (RagService.SearchResult result : similarResults) {
                    String resultId = extractSignatureId(result.getMetadata());
                    if (resultId != null && !resultId.equals(signatureId)) {
                        // Find what makes it similar
                        List<String> similarities = analyzeSimilarities(originalContent, result.getContent());
                        
                        results.add(new SimilarCodeResult(
                            resultId,
                            result.getContent(),
                            result.getScore(),
                            similarities,
                            result.getMetadata()
                        ));
                    }
                }
                
                return results.stream()
                    .limit(maxResults)
                    .collect(Collectors.toList());
                    
            } catch (Exception e) {
                LOG.error("Error finding similar code", e);
                return Collections.emptyList();
            }
        });
    }
    
    /**
     * Configures the search behavior.
     */
    public void configure(int maxRelatedCodePerResult, double relevanceThreshold) {
        this.maxRelatedCodePerResult = maxRelatedCodePerResult;
        this.relevanceThreshold = Math.max(0, Math.min(1, relevanceThreshold));
        LOG.info("Configured search: maxRelatedCode=" + maxRelatedCodePerResult + 
                ", relevanceThreshold=" + relevanceThreshold);
    }
    
    private List<RagService.SearchResult> performPhase1Search(String query, int limit) {
        LOG.debug("Phase 1: Broad semantic search with limit " + limit);
        
        // Perform semantic search
        List<RagService.SearchResult> results = ragService.search(query, limit).join();
        
        // Filter by relevance threshold
        results = results.stream()
            .filter(r -> r.getScore() >= relevanceThreshold)
            .collect(Collectors.toList());
        
        LOG.debug("Phase 1 found " + results.size() + " results above threshold");
        return results;
    }
    
    private List<EnrichedSearchResult> performPhase2Search(List<RagService.SearchResult> phase1Results, 
                                                           String query, int maxResults) {
        LOG.debug("Phase 2: Narrowing down and enriching results");
        
        // Group results by file
        Map<String, List<RagService.SearchResult>> byFile = groupResultsByFile(phase1Results);
        
        List<EnrichedSearchResult> enrichedResults = new ArrayList<>();
        
        for (Map.Entry<String, List<RagService.SearchResult>> entry : byFile.entrySet()) {
            String filePath = entry.getKey();
            List<RagService.SearchResult> fileResults = entry.getValue();
            
            // Find the most relevant result in this file
            RagService.SearchResult primaryResult = fileResults.stream()
                .max(Comparator.comparing(RagService.SearchResult::getScore))
                .orElse(null);
                
            if (primaryResult == null) continue;
            
            // Extract signature ID
            String signatureId = extractSignatureId(primaryResult.getMetadata());
            if (signatureId == null) continue;
            
            // Create enriched result
            EnrichedSearchResult enriched = new EnrichedSearchResult(
                signatureId,
                primaryResult.getContent(),
                primaryResult.getScore(),
                filePath,
                primaryResult.getMetadata()
            );
            
            // Find related code using IntelliJ's Find Usages
            List<RelatedCodeFinder.RelatedCodeItem> relatedCode = 
                relatedCodeFinder.findRelatedCode(signatureId, maxRelatedCodePerResult);
            enriched.setRelatedCode(relatedCode);
            
            // Add other relevant functions from the same file
            for (RagService.SearchResult otherResult : fileResults) {
                if (otherResult != primaryResult) {
                    String otherId = extractSignatureId(otherResult.getMetadata());
                    if (otherId != null) {
                        enriched.addRelatedFunction(new RelatedFunction(
                            otherId,
                            otherResult.getContent(),
                            otherResult.getScore(),
                            "Same file"
                        ));
                    }
                }
            }
            
            enrichedResults.add(enriched);
        }
        
        // Sort by relevance and limit
        enrichedResults.sort((a, b) -> Double.compare(b.getPrimaryScore(), a.getPrimaryScore()));
        
        return enrichedResults.stream()
            .limit(maxResults)
            .collect(Collectors.toList());
    }
    
    private Map<String, List<RagService.SearchResult>> groupResultsByFile(List<RagService.SearchResult> results) {
        Map<String, List<RagService.SearchResult>> byFile = new HashMap<>();
        
        for (RagService.SearchResult result : results) {
            String filePath = (String) result.getMetadata().get("file_path");
            if (filePath != null) {
                byFile.computeIfAbsent(filePath, k -> new ArrayList<>()).add(result);
            }
        }
        
        return byFile;
    }
    
    private String extractSignatureId(Map<String, Object> metadata) {
        Object signatureId = metadata.get("signature_id");
        return signatureId != null ? signatureId.toString() : null;
    }
    
    private String extractSignatureType(Map<String, Object> metadata) {
        Object type = metadata.get("type");
        return type != null ? type.toString() : "unknown";
    }
    
    private String getSignatureContent(String signatureId) {
        // Try to retrieve from vector store
        // This would need to be exposed in RagService
        // For now, return null
        return null;
    }
    
    private List<String> analyzeSimilarities(String content1, String content2) {
        List<String> similarities = new ArrayList<>();
        
        // Simple similarity analysis - can be enhanced with better NLP
        String lower1 = content1.toLowerCase();
        String lower2 = content2.toLowerCase();
        
        // Check for common patterns
        if (lower1.contains("override") && lower2.contains("override")) {
            similarities.add("Both override methods");
        }
        if (lower1.contains("static") && lower2.contains("static")) {
            similarities.add("Both are static");
        }
        if (lower1.contains("public") && lower2.contains("public")) {
            similarities.add("Same visibility");
        }
        
        // Check for similar return types
        String[] commonTypes = {"void", "string", "int", "boolean", "list", "map", "set"};
        for (String type : commonTypes) {
            if (lower1.contains(type) && lower2.contains(type)) {
                similarities.add("Similar return type: " + type);
                break;
            }
        }
        
        return similarities;
    }
    
    private String extractPatternContext(List<RagService.SearchResult> results, String pattern) {
        // Analyze the context of pattern matches
        Set<String> contexts = new HashSet<>();
        
        for (RagService.SearchResult result : results) {
            String type = extractSignatureType(result.getMetadata());
            contexts.add(type);
        }
        
        return "Found in " + String.join(", ", contexts);
    }
    
    /**
     * Enriched search result with related code information.
     */
    public static class EnrichedSearchResult {
        private final String signatureId;
        private final String content;
        private final double primaryScore;
        private final String filePath;
        private final Map<String, Object> metadata;
        private List<RelatedCodeFinder.RelatedCodeItem> relatedCode = new ArrayList<>();
        private List<RelatedFunction> relatedFunctions = new ArrayList<>();
        
        public EnrichedSearchResult(String signatureId, String content, double primaryScore,
                                   String filePath, Map<String, Object> metadata) {
            this.signatureId = signatureId;
            this.content = content;
            this.primaryScore = primaryScore;
            this.filePath = filePath;
            this.metadata = metadata;
        }
        
        public void setRelatedCode(List<RelatedCodeFinder.RelatedCodeItem> relatedCode) {
            this.relatedCode = relatedCode;
        }
        
        public void addRelatedFunction(RelatedFunction function) {
            this.relatedFunctions.add(function);
        }
        
        // Getters
        public String getSignatureId() { return signatureId; }
        public String getContent() { return content; }
        public double getPrimaryScore() { return primaryScore; }
        public String getFilePath() { return filePath; }
        public Map<String, Object> getMetadata() { return metadata; }
        public List<RelatedCodeFinder.RelatedCodeItem> getRelatedCode() { return relatedCode; }
        public List<RelatedFunction> getRelatedFunctions() { return relatedFunctions; }
    }
    
    /**
     * Represents a related function in the same file.
     */
    public static class RelatedFunction {
        private final String signatureId;
        private final String content;
        private final double relevanceScore;
        private final String relationship;
        
        public RelatedFunction(String signatureId, String content, double relevanceScore, String relationship) {
            this.signatureId = signatureId;
            this.content = content;
            this.relevanceScore = relevanceScore;
            this.relationship = relationship;
        }
        
        // Getters
        public String getSignatureId() { return signatureId; }
        public String getContent() { return content; }
        public double getRelevanceScore() { return relevanceScore; }
        public String getRelationship() { return relationship; }
    }
    
    /**
     * Result for code pattern searches.
     */
    public static class CodePatternResult {
        private final String filePath;
        private final int matchCount;
        private final String context;
        private final List<PatternMatch> matches = new ArrayList<>();
        
        public CodePatternResult(String filePath, int matchCount, String context) {
            this.filePath = filePath;
            this.matchCount = matchCount;
            this.context = context;
        }
        
        public void addMatch(PatternMatch match) {
            matches.add(match);
        }
        
        public double getAverageScore() {
            if (matches.isEmpty()) return 0;
            return matches.stream()
                .mapToDouble(PatternMatch::getScore)
                .average()
                .orElse(0);
        }
        
        // Getters
        public String getFilePath() { return filePath; }
        public int getMatchCount() { return matchCount; }
        public String getContext() { return context; }
        public List<PatternMatch> getMatches() { return matches; }
    }
    
    /**
     * Individual pattern match.
     */
    public static class PatternMatch {
        private final String signatureId;
        private final String content;
        private final double score;
        private final String type;
        
        public PatternMatch(String signatureId, String content, double score, String type) {
            this.signatureId = signatureId;
            this.content = content;
            this.score = score;
            this.type = type;
        }
        
        // Getters
        public String getSignatureId() { return signatureId; }
        public String getContent() { return content; }
        public double getScore() { return score; }
        public String getType() { return type; }
    }
    
    /**
     * Result for similar code searches.
     */
    public static class SimilarCodeResult {
        private final String signatureId;
        private final String content;
        private final double similarityScore;
        private final List<String> similarities;
        private final Map<String, Object> metadata;
        
        public SimilarCodeResult(String signatureId, String content, double similarityScore,
                                List<String> similarities, Map<String, Object> metadata) {
            this.signatureId = signatureId;
            this.content = content;
            this.similarityScore = similarityScore;
            this.similarities = similarities;
            this.metadata = metadata;
        }
        
        // Getters
        public String getSignatureId() { return signatureId; }
        public String getContent() { return content; }
        public double getSimilarityScore() { return similarityScore; }
        public List<String> getSimilarities() { return similarities; }
        public Map<String, Object> getMetadata() { return metadata; }
    }
}
