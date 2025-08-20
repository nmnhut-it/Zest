package com.zps.zest.langchain4j;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.zps.zest.langchain4j.util.LLMService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.CompressingQueryTransformer;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Advanced RAG service implementing cutting-edge retrieval techniques from 2024:
 * - Contextual Retrieval (Anthropic)
 * - Sentence Window Retrieval  
 * - Parent Document Retrieval
 * - Self-Query with metadata filtering
 * - Enhanced Hybrid Search (BM25 + Semantic)
 */
public class AdvancedRAGService {
    private static final Logger LOG = Logger.getInstance(AdvancedRAGService.class);
    
    private final Project project;
    private final LLMService llmService;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final CompressingQueryTransformer compressingTransformer;
    
    // Advanced RAG configuration
    private static final int SENTENCE_WINDOW_SIZE = 3; // Sentences before/after
    private static final int MAX_PARENT_DOCUMENT_SIZE = 5000; // characters
    private static final double BM25_WEIGHT = 0.3;
    private static final double SEMANTIC_WEIGHT = 0.7;
    
    // Contextual information storage
    private final Map<String, String> contextualChunks = new HashMap<>(); // chunk_id -> contextualized_content
    private final Map<String, String> parentDocuments = new HashMap<>(); // chunk_id -> parent_document
    private final Map<String, List<String>> chunkHierarchy = new HashMap<>(); // parent_id -> child_chunk_ids
    
    // BM25 components
    private final Map<String, Map<String, Integer>> termFrequencies = new HashMap<>(); // doc_id -> term -> frequency
    private final Map<String, Integer> documentFrequencies = new HashMap<>(); // term -> number of docs containing it
    private final Map<String, Integer> documentLengths = new HashMap<>(); // doc_id -> document length
    private int totalDocuments = 0;
    private double avgDocLength = 0.0;
    
    // File content cache to avoid repeated I/O
    private final Map<String, String> fileContentCache = new HashMap<>();
    private static final int MAX_CACHE_SIZE = 50; // Limit cache size
    private static final long CACHE_TTL_MS = 300000; // 5 minutes TTL
    private final Map<String, Long> cacheTimestamps = new HashMap<>();
    
    public AdvancedRAGService(Project project, LLMService llmService, EmbeddingModel embeddingModel, 
                             EmbeddingStore<TextSegment> embeddingStore, 
                             CompressingQueryTransformer compressingTransformer) {
        this.project = project;
        this.llmService = llmService;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.compressingTransformer = compressingTransformer;
        
        LOG.info("Advanced RAG Service initialized with cutting-edge techniques");
    }
    
    /**
     * Ultra-fast retrieval using direct semantic search with keyword filtering
     */
    public CompletableFuture<List<ContextualResult>> retrieveFast(String query, int maxResults) {
        return retrieveFast(query, maxResults, null);
    }
    
    /**
     * Ultra-fast retrieval with file exclusion
     */
    public CompletableFuture<List<ContextualResult>> retrieveFast(String query, int maxResults, String excludeFileName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOG.info("Starting ultra-fast semantic search for query length: " + query.length());
                
                // Direct semantic search - no analysis overhead
                Embedding queryEmbedding = embeddingModel.embed(query).content();
                var request = dev.langchain4j.store.embedding.EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(maxResults * 3) // Get more candidates
                    .minScore(0.3) // Much lower threshold to find more results
                    .build();
                List<EmbeddingMatch<TextSegment>> semanticMatches = embeddingStore.search(request).matches();
                LOG.info("Found " + semanticMatches.size() + " semantic matches");
                
                // Filter out current file if specified
                if (excludeFileName != null && !excludeFileName.isEmpty()) {
                    semanticMatches = semanticMatches.stream()
                        .filter(match -> {
                            String filePath = match.embedded().metadata().getString("file");
                            return filePath == null || !filePath.endsWith(excludeFileName);
                        })
                        .collect(Collectors.toList());
                    LOG.info("After excluding " + excludeFileName + ": " + semanticMatches.size() + " matches");
                }
                
                // Simple keyword boost (no complex BM25)
                List<EmbeddingMatch<TextSegment>> filteredMatches = applySimpleKeywordBoost(semanticMatches, query, maxResults);
                LOG.info("After keyword boost: " + filteredMatches.size() + " matches");
                
                // Create minimal results
                List<ContextualResult> results = new ArrayList<>();
                for (EmbeddingMatch<TextSegment> match : filteredMatches) {
                    results.add(new ContextualResult(
                        match.embedded(),
                        match.score(),
                        match.embedded().text(), // Use original content everywhere
                        match.embedded().text(), 
                        match.embedded().text(), 
                        new QueryAnalysis(query) // Minimal analysis
                    ));
                }
                
                LOG.info("Ultra-fast search completed: " + results.size() + " results");
                return results;
                
            } catch (Exception e) {
                LOG.error("Ultra-fast search failed", e);
                return Collections.emptyList();
            }
        });
    }
    
    /**
     * Ultra-fast retrieval with progress reporting and LLM-based keyword extraction
     */
    public CompletableFuture<List<ContextualResult>> retrieveFastWithProgress(
            String query, int maxResults, String excludeFileName, RetrievalProgressListener progressListener) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            
            try {
                if (progressListener != null) {
                    progressListener.onStageUpdate("COMPRESS", "Extracting keywords from query...");
                }
                
                // Stage 1: Compress query to extract keywords with timeout
                List<String> extractedKeywords = compressQueryWithTimeout(query, 3000);
                if (progressListener != null) {
                    progressListener.onKeywordsExtracted(extractedKeywords);
                }
                
                if (progressListener != null) {
                    progressListener.onStageUpdate("SEARCH", "Searching codebase...");
                }
                
                // Stage 2: Direct semantic search with original query
                Embedding queryEmbedding = embeddingModel.embed(query).content();
                var request = dev.langchain4j.store.embedding.EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(maxResults * 3) // Get more candidates
                    .minScore(0.3) // Lower threshold to find more results
                    .build();
                List<EmbeddingMatch<TextSegment>> semanticMatches = embeddingStore.search(request).matches();
                
                // Stage 3: Filter out excluded file
                int candidatesFound = semanticMatches.size();
                if (excludeFileName != null && !excludeFileName.isEmpty()) {
                    semanticMatches = semanticMatches.stream()
                        .filter(match -> {
                            String filePath = match.embedded().metadata().getString("file");
                            return filePath == null || !filePath.endsWith(excludeFileName);
                        })
                        .collect(Collectors.toList());
                }
                
                if (progressListener != null) {
                    progressListener.onSearchComplete(candidatesFound, semanticMatches.size());
                    progressListener.onStageUpdate("BOOST", "Ranking results...");
                }
                
                // Stage 4: Apply intelligent keyword boosting
                List<EmbeddingMatch<TextSegment>> filteredMatches = 
                    applyIntelligentKeywordBoost(semanticMatches, extractedKeywords, maxResults);
                
                // Stage 5: Create final results
                List<ContextualResult> results = new ArrayList<>();
                for (EmbeddingMatch<TextSegment> match : filteredMatches) {
                    results.add(new ContextualResult(
                        match.embedded(),
                        match.score(),
                        match.embedded().text(),
                        match.embedded().text(), 
                        match.embedded().text(), 
                        new QueryAnalysis(query)
                    ));
                }
                
                long totalTime = System.currentTimeMillis() - startTime;
                if (progressListener != null) {
                    progressListener.onComplete(results.size(), totalTime);
                }
                
                LOG.info("Fast retrieval with progress completed: " + results.size() + " results in " + totalTime + "ms");
                return results;
                
            } catch (Exception e) {
                LOG.error("Fast retrieval with progress failed", e);
                if (progressListener != null) {
                    progressListener.onStageUpdate("ERROR", "Retrieval failed: " + e.getMessage());
                }
                return Collections.emptyList();
            }
        });
    }
    
    /**
     * Compress query using LLM with timeout to extract keywords
     */
    private List<String> compressQueryWithTimeout(String query, int timeoutMs) {
        try {
            // Limit query length for performance
            String limitedQuery = query.length() > 2000 
                ? query.substring(0, 2000) + "..." 
                : query;
            
            // Use CompressingQueryTransformer with custom timeout
            CompletableFuture<Collection<Query>> compressionFuture = CompletableFuture.supplyAsync(() -> {
                return compressingTransformer.transform(Query.from(limitedQuery));
            });
            
            Collection<Query> compressedQueries = compressionFuture
                .get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            
            // Extract keywords from compressed query
            List<String> keywords = new ArrayList<>();
            for (Query compressedQuery : compressedQueries) {
                String compressed = compressedQuery.text();
                if (compressed != null && !compressed.trim().isEmpty()) {
                    // Extract keywords from compressed text
                    for (String word : compressed.split("\\s+")) {
                        if (word.length() > 2 && !keywords.contains(word)) {
                            keywords.add(word);
                        }
                    }
                }
                if (keywords.size() >= 10) break; // Limit keywords
            }
            
            LOG.debug("LLM extracted keywords: " + keywords);
            return keywords.isEmpty() ? extractKeywordsFallback(query) : keywords;
            
        } catch (Exception e) {
            LOG.warn("LLM keyword extraction failed, using fallback: " + e.getMessage());
            return extractKeywordsFallback(query);
        }
    }
    
    /**
     * Fallback keyword extraction using simple rules
     */
    private List<String> extractKeywordsFallback(String query) {
        List<String> keywords = new ArrayList<>();
        
        // Extract method names, class names, and important terms
        Pattern methodPattern = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(");
        Matcher methodMatcher = methodPattern.matcher(query);
        while (methodMatcher.find() && keywords.size() < 10) {
            String method = methodMatcher.group(1);
            if (method.length() > 2) {
                keywords.add(method);
            }
        }
        
        // Extract camelCase identifiers
        Pattern camelCasePattern = Pattern.compile("\\b([A-Z][a-zA-Z0-9_]*|[a-z][a-zA-Z0-9_]*[A-Z][a-zA-Z0-9_]*)\\b");
        Matcher camelMatcher = camelCasePattern.matcher(query);
        while (camelMatcher.find() && keywords.size() < 15) {
            String identifier = camelMatcher.group(1);
            if (identifier.length() > 3 && !keywords.contains(identifier)) {
                keywords.add(identifier);
            }
        }
        
        return keywords;
    }
    
    /**
     * Apply intelligent keyword boosting using LLM-extracted keywords
     */
    private List<EmbeddingMatch<TextSegment>> applyIntelligentKeywordBoost(
            List<EmbeddingMatch<TextSegment>> matches, List<String> keywords, int maxResults) {
        
        if (keywords.isEmpty()) {
            return matches.stream().limit(maxResults).collect(Collectors.toList());
        }
        
        // Boost matches that contain LLM-extracted keywords
        List<ScoredMatch> scoredMatches = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : matches) {
            double semanticScore = match.score();
            double keywordBoost = calculateIntelligentKeywordBoost(match.embedded().text(), keywords);
            double finalScore = semanticScore + keywordBoost;
            
            scoredMatches.add(new ScoredMatch(match.embedded(), finalScore, semanticScore, keywordBoost));
        }
        
        // Sort and return top results
        return scoredMatches.stream()
            .sorted((a, b) -> Double.compare(b.finalScore, a.finalScore))
            .limit(maxResults)
            .map(sm -> new EmbeddingMatch<>(sm.finalScore, "boost_" + System.nanoTime(), null, sm.textSegment))
            .collect(Collectors.toList());
    }
    
    /**
     * Calculate intelligent keyword boost based on LLM-extracted keywords
     */
    private double calculateIntelligentKeywordBoost(String text, List<String> keywords) {
        if (keywords.isEmpty()) return 0.0;
        
        String lowerText = text.toLowerCase();
        int matches = 0;
        double boost = 0.0;
        
        for (String keyword : keywords) {
            String lowerKeyword = keyword.toLowerCase();
            if (lowerText.contains(lowerKeyword)) {
                matches++;
                // Higher boost for longer, more specific keywords
                boost += 0.05 + (keyword.length() * 0.01);
            }
        }
        
        // Cap the boost at 0.4 to prevent overwhelming semantic score
        return Math.min(boost, 0.4);
    }
    
    /**
     * Simple keyword boosting without complex BM25
     */
    private List<EmbeddingMatch<TextSegment>> applySimpleKeywordBoost(
            List<EmbeddingMatch<TextSegment>> matches, String query, int maxResults) {
        
        // Extract simple keywords from query (just split and lowercase)
        Set<String> keywords = new HashSet<>();
        for (String word : query.toLowerCase().split("\\W+")) {
            if (word.length() > 2) { // Skip very short words
                keywords.add(word);
            }
        }
        
        // Boost matches that contain query keywords
        List<ScoredMatch> scoredMatches = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : matches) {
            double semanticScore = match.score();
            double keywordBoost = calculateSimpleKeywordBoost(match.embedded().text(), keywords);
            double finalScore = semanticScore + keywordBoost;
            
            scoredMatches.add(new ScoredMatch(match.embedded(), finalScore, semanticScore, keywordBoost));
        }
        
        // Sort and return top results
        return scoredMatches.stream()
            .sorted((a, b) -> Double.compare(b.finalScore, a.finalScore))
            .limit(maxResults)
            .map(sm -> new EmbeddingMatch<>(sm.finalScore, "boost_" + System.nanoTime(), null, sm.textSegment))
            .collect(Collectors.toList());
    }
    
    /**
     * Simple keyword boost calculation
     */
    private double calculateSimpleKeywordBoost(String text, Set<String> keywords) {
        if (keywords.isEmpty()) return 0.0;
        
        String lowerText = text.toLowerCase();
        int matches = 0;
        for (String keyword : keywords) {
            if (lowerText.contains(keyword)) {
                matches++;
            }
        }
        
        // Simple boost: 0.1 for each keyword match, max 0.3
        return Math.min(matches * 0.1, 0.3);
    }
    
    /**
     * Advanced retrieval using multiple techniques (slower but more comprehensive)
     */
    public CompletableFuture<List<ContextualResult>> retrieveAdvanced(String query, int maxResults) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOG.info("Starting advanced RAG retrieval for query: " + query);
                
                // Stage 1: Self-Query Analysis and Metadata Extraction
                QueryAnalysis queryAnalysis = performSelfQuery(query);
                LOG.debug("Query analysis: " + queryAnalysis);
                
                // Stage 2: Enhanced Hybrid Search (BM25 + Semantic)
                List<ScoredMatch> hybridMatches = performEnhancedHybridSearch(queryAnalysis, maxResults * 3);
                
                // Stage 3: Apply advanced retrieval techniques
                List<ContextualResult> results = new ArrayList<>();
                
                for (ScoredMatch match : hybridMatches.stream().limit(maxResults).collect(Collectors.toList())) {
                    // Apply Sentence Window Retrieval
                    String windowContext = applySentenceWindowRetrieval(match.textSegment, match.textSegment.text());
                    
                    // Apply Parent Document Retrieval
                    String parentContext = applyParentDocumentRetrieval(match.textSegment);
                    
                    // Apply Contextual Retrieval (Anthropic's approach)
                    String contextualContent = applyContextualRetrieval(match.textSegment);
                    
                    results.add(new ContextualResult(
                        match.textSegment,
                        match.finalScore,
                        windowContext,
                        parentContext,
                        contextualContent,
                        queryAnalysis
                    ));
                }
                
                LOG.info("Advanced RAG completed: " + results.size() + " contextual results");
                return results;
                
            } catch (Exception e) {
                LOG.error("Advanced RAG retrieval failed", e);
                return Collections.emptyList();
            }
        });
    }
    
    /**
     * Stage 1: Self-Query Analysis - Parse query intent and extract metadata filters
     */
    private QueryAnalysis performSelfQuery(String query) {
        QueryAnalysis analysis = new QueryAnalysis(query);
        
        // Extract file type intentions
        if (query.toLowerCase().matches(".*\\b(java|kotlin|javascript|typescript|python)\\b.*")) {
            analysis.preferredFileTypes = extractFileTypes(query);
        }
        
        // Extract entity intentions (classes, methods, functions)
        if (query.toLowerCase().matches(".*\\b(class|method|function|interface)\\b.*")) {
            analysis.entityTypes = extractEntityTypes(query);
        }
        
        // Extract semantic intent
        analysis.intent = classifyIntent(query);
        
        // Extract key terms for BM25
        analysis.keyTerms = extractKeyTerms(query);
        
        LOG.debug("Self-query analysis completed: " + analysis.keyTerms.size() + " key terms, intent: " + analysis.intent);
        return analysis;
    }
    
    /**
     * Stage 2: Enhanced Hybrid Search with proper BM25 + Semantic combination
     */
    private List<ScoredMatch> performEnhancedHybridSearch(QueryAnalysis queryAnalysis, int maxCandidates) {
        // Get semantic matches
        Embedding queryEmbedding = embeddingModel.embed(queryAnalysis.originalQuery).content();
        var request = dev.langchain4j.store.embedding.EmbeddingSearchRequest.builder()
            .queryEmbedding(queryEmbedding)
            .maxResults(maxCandidates)
            .minScore(0.1) // Low threshold to get many candidates
            .build();
        List<EmbeddingMatch<TextSegment>> semanticMatches = embeddingStore.search(request).matches();
        
        // Score with BM25 + Semantic hybrid approach
        List<ScoredMatch> hybridScored = new ArrayList<>();
        
        for (EmbeddingMatch<TextSegment> match : semanticMatches) {
            double semanticScore = match.score();
            double bm25Score = calculateBM25Score(queryAnalysis.keyTerms, match.embedded());
            
            // Enhanced hybrid scoring with query-specific weights
            double finalScore = calculateHybridScore(semanticScore, bm25Score, queryAnalysis.intent);
            
            // Apply metadata filtering boost
            finalScore = applyMetadataBoost(finalScore, match.embedded(), queryAnalysis);
            
            hybridScored.add(new ScoredMatch(match.embedded(), finalScore, semanticScore, bm25Score));
        }
        
        // Sort by final score
        hybridScored.sort((a, b) -> Double.compare(b.finalScore, a.finalScore));
        
        LOG.debug("Enhanced hybrid search: " + hybridScored.size() + " results scored");
        return hybridScored;
    }
    
    /**
     * Stage 3a: Sentence Window Retrieval - Expand small chunks with surrounding context
     */
    private String applySentenceWindowRetrieval(TextSegment segment, String content) {
        try {
            String filePath = segment.metadata().getString("file");
            if (filePath == null) return content;
            
            // Find the segment in its original file context
            String fullFileContent = getFullFileContent(filePath);
            if (fullFileContent == null) return content;
            
            // Find the position of this segment in the full file
            int segmentStart = fullFileContent.indexOf(content.trim());
            if (segmentStart == -1) return content;
            
            // Extract sentences around the segment
            String[] sentences = fullFileContent.split("\\. |\\n");
            int segmentSentenceIndex = findSentenceIndex(sentences, segmentStart);
            
            if (segmentSentenceIndex >= 0) {
                int windowStart = Math.max(0, segmentSentenceIndex - SENTENCE_WINDOW_SIZE);
                int windowEnd = Math.min(sentences.length, segmentSentenceIndex + SENTENCE_WINDOW_SIZE + 1);
                
                StringBuilder windowContent = new StringBuilder();
                for (int i = windowStart; i < windowEnd; i++) {
                    windowContent.append(sentences[i]);
                    if (i < windowEnd - 1) windowContent.append(". ");
                }
                
                return windowContent.toString();
            }
            
        } catch (Exception e) {
            LOG.debug("Sentence window retrieval failed, using original content: " + e.getMessage());
        }
        
        return content;
    }
    
    /**
     * Stage 3b: Parent Document Retrieval - Get complete parent document context  
     */
    private String applyParentDocumentRetrieval(TextSegment segment) {
        String chunkId = getChunkId(segment);
        String parentDoc = parentDocuments.get(chunkId);
        
        if (parentDoc != null && parentDoc.length() <= MAX_PARENT_DOCUMENT_SIZE) {
            return parentDoc;
        }
        
        // Try to reconstruct parent from file metadata
        try {
            String filePath = segment.metadata().getString("file");
            String nodeType = segment.metadata().getString("nodeType");
            
            if ("class_declaration".equals(nodeType) || "interface_declaration".equals(nodeType)) {
                // This chunk is likely already a complete class
                return segment.text();
            }
            
            if ("method_declaration".equals(nodeType) || "function_declaration".equals(nodeType)) {
                // Try to get the containing class
                return findParentClass(segment, filePath);
            }
            
        } catch (Exception e) {
            LOG.debug("Parent document retrieval failed: " + e.getMessage());
        }
        
        return segment.text();
    }
    
    /**
     * Stage 3c: Contextual Retrieval (Anthropic's approach) - Add contextual information
     */
    private String applyContextualRetrieval(TextSegment segment) {
        String chunkId = getChunkId(segment);
        String contextualContent = contextualChunks.get(chunkId);
        
        if (contextualContent != null) {
            return contextualContent;
        }
        
        // Generate contextual information on-the-fly if not pre-computed
        return generateContextualInformation(segment);
    }
    
    /**
     * BM25 scoring implementation
     */
    private double calculateBM25Score(List<String> queryTerms, TextSegment document) {
        String docId = getChunkId(document);
        Map<String, Integer> docTermFreqs = termFrequencies.get(docId);
        
        if (docTermFreqs == null) {
            // Build term frequencies on-the-fly if not pre-computed
            docTermFreqs = buildTermFrequencies(document.text());
        }
        
        double score = 0.0;
        double k1 = 1.5; // BM25 parameter
        double b = 0.75;  // BM25 parameter
        
        int docLength = documentLengths.getOrDefault(docId, document.text().split("\\s+").length);
        
        for (String term : queryTerms) {
            term = term.toLowerCase();
            
            int termFreq = docTermFreqs.getOrDefault(term, 0);
            if (termFreq == 0) continue;
            
            int docFreq = documentFrequencies.getOrDefault(term, 1);
            
            // IDF component
            double idf = Math.log((double) totalDocuments / docFreq);
            
            // TF component with BM25 normalization
            double tf = (termFreq * (k1 + 1)) / 
                       (termFreq + k1 * (1 - b + b * (docLength / avgDocLength)));
            
            score += idf * tf;
        }
        
        return score;
    }
    
    /**
     * Enhanced hybrid scoring based on query intent
     */
    private double calculateHybridScore(double semanticScore, double bm25Score, QueryIntent intent) {
        // Adjust weights based on query intent
        double semWeight = SEMANTIC_WEIGHT;
        double bm25Weight = BM25_WEIGHT;
        
        switch (intent) {
            case EXACT_MATCH -> {
                // Favor BM25 for exact matches
                semWeight = 0.4;
                bm25Weight = 0.6;
            }
            case CONCEPTUAL -> {
                // Favor semantic for conceptual queries  
                semWeight = 0.8;
                bm25Weight = 0.2;
            }
            case MIXED -> {
                // Default weights
            }
        }
        
        // Normalize BM25 score to 0-1 range
        double normalizedBM25 = Math.tanh(bm25Score / 10.0);
        
        return (semanticScore * semWeight) + (normalizedBM25 * bm25Weight);
    }
    
    // Helper methods and classes...
    
    private List<String> extractFileTypes(String query) {
        List<String> types = new ArrayList<>();
        String[] fileTypeKeywords = {"java", "kotlin", "javascript", "typescript", "python", "js", "ts", "kt", "py"};
        
        for (String type : fileTypeKeywords) {
            if (query.toLowerCase().contains(type)) {
                types.add(type);
            }
        }
        return types;
    }
    
    private List<String> extractEntityTypes(String query) {
        List<String> entities = new ArrayList<>();
        String[] entityKeywords = {"class", "method", "function", "interface", "enum", "constructor"};
        
        for (String entity : entityKeywords) {
            if (query.toLowerCase().contains(entity)) {
                entities.add(entity);
            }
        }
        return entities;
    }
    
    private QueryIntent classifyIntent(String query) {
        // Simple intent classification
        if (query.contains("\"") || query.toLowerCase().matches(".*\\bexact\\b.*")) {
            return QueryIntent.EXACT_MATCH;
        }
        if (query.toLowerCase().matches(".*\\b(similar|like|related|concept)\\b.*")) {
            return QueryIntent.CONCEPTUAL;
        }
        return QueryIntent.MIXED;
    }
    
    private List<String> extractKeyTerms(String query) {
        // Extract meaningful terms for BM25
        return Arrays.stream(query.toLowerCase().split("\\W+"))
            .filter(term -> term.length() > 2)
            .filter(term -> !isStopWord(term))
            .distinct()
            .collect(Collectors.toList());
    }
    
    private boolean isStopWord(String word) {
        Set<String> stopWords = Set.of("the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with", "by");
        return stopWords.contains(word.toLowerCase());
    }
    
    // Additional helper methods would go here...
    
    // Data classes
    public enum QueryIntent { EXACT_MATCH, CONCEPTUAL, MIXED }
    
    public static class QueryAnalysis {
        public final String originalQuery;
        public List<String> preferredFileTypes = new ArrayList<>();
        public List<String> entityTypes = new ArrayList<>();
        public QueryIntent intent = QueryIntent.MIXED;
        public List<String> keyTerms = new ArrayList<>();
        
        public QueryAnalysis(String query) {
            this.originalQuery = query;
        }
        
        @Override
        public String toString() {
            return String.format("QueryAnalysis[intent=%s, fileTypes=%s, entities=%s, terms=%d]", 
                intent, preferredFileTypes, entityTypes, keyTerms.size());
        }
    }
    
    public static class ScoredMatch {
        public final TextSegment textSegment;
        public final double finalScore;
        public final double semanticScore;
        public final double bm25Score;
        
        public ScoredMatch(TextSegment textSegment, double finalScore, double semanticScore, double bm25Score) {
            this.textSegment = textSegment;
            this.finalScore = finalScore;
            this.semanticScore = semanticScore;
            this.bm25Score = bm25Score;
        }
    }
    
    public static class ContextualResult {
        public final TextSegment originalSegment;
        public final double score;
        public final String windowContext;
        public final String parentContext;
        public final String contextualContent;
        public final QueryAnalysis queryAnalysis;
        
        public ContextualResult(TextSegment originalSegment, double score, String windowContext, 
                               String parentContext, String contextualContent, QueryAnalysis queryAnalysis) {
            this.originalSegment = originalSegment;
            this.score = score;
            this.windowContext = windowContext;
            this.parentContext = parentContext;
            this.contextualContent = contextualContent;
            this.queryAnalysis = queryAnalysis;
        }
        
        // Getter methods for compatibility with ContextItem
        public String getId() {
            return originalSegment.metadata().getString("id");
        }
        
        public String getTitle() {
            String fileName = getFilePath();
            if (fileName != null) {
                // Extract just the filename from the path
                int lastSlash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
                return lastSlash >= 0 ? fileName.substring(lastSlash + 1) : fileName;
            }
            return "Code Segment";
        }
        
        public String getContent() {
            return contextualContent != null ? contextualContent : originalSegment.text();
        }
        
        public double getScore() {
            return score;
        }
        
        public String getFilePath() {
            return originalSegment.metadata().getString("file");
        }
        
        public Integer getLineNumber() {
            String lineStr = originalSegment.metadata().getString("startLine");
            if (lineStr != null) {
                try {
                    return Integer.parseInt(lineStr);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return null;
        }
    }
    
    // Implementation of helper methods
    
    private double applyMetadataBoost(double score, TextSegment segment, QueryAnalysis analysis) {
        double boost = 0.0;
        Metadata metadata = segment.metadata();
        
        // File type preference boost
        String fileType = metadata.getString("fileType");
        if (fileType != null && analysis.preferredFileTypes.contains(fileType)) {
            boost += 5.0;
        }
        
        // Node type preference boost
        String nodeType = metadata.getString("nodeType");
        if (nodeType != null) {
            for (String entityType : analysis.entityTypes) {
                if (nodeType.toLowerCase().contains(entityType)) {
                    boost += 3.0;
                }
            }
        }
        
        // File path keyword boost
        String filePath = metadata.getString("file");
        if (filePath != null) {
            String filePathLower = filePath.toLowerCase();
            for (String term : analysis.keyTerms) {
                if (filePathLower.contains(term)) {
                    boost += 2.0;
                }
            }
        }
        
        return Math.min(score + boost, 100.0);
    }
    
    private String getFullFileContent(String filePath) {
        // Check cache first
        String cachedContent = fileContentCache.get(filePath);
        Long timestamp = cacheTimestamps.get(filePath);
        
        if (cachedContent != null && timestamp != null && 
            (System.currentTimeMillis() - timestamp) < CACHE_TTL_MS) {
            return cachedContent;
        }
        
        try {
            // Convert relative path to virtual file
            VirtualFile virtualFile = VirtualFileManager.getInstance().findFileByUrl(
                "file://" + project.getBasePath() + "/" + filePath
            );
            
            if (virtualFile != null && virtualFile.exists()) {
                String content = new String(virtualFile.contentsToByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                
                // Cache the content
                cacheFileContent(filePath, content);
                
                return content;
            }
        } catch (Exception e) {
            LOG.debug("Failed to read full file content for: " + filePath + ": " + e.getMessage());
        }
        return null;
    }
    
    private void cacheFileContent(String filePath, String content) {
        // Clean cache if too large
        if (fileContentCache.size() >= MAX_CACHE_SIZE) {
            cleanCache();
        }
        
        fileContentCache.put(filePath, content);
        cacheTimestamps.put(filePath, System.currentTimeMillis());
    }
    
    private void cleanCache() {
        long now = System.currentTimeMillis();
        
        // Remove expired entries
        cacheTimestamps.entrySet().removeIf(entry -> {
            boolean expired = (now - entry.getValue()) > CACHE_TTL_MS;
            if (expired) {
                fileContentCache.remove(entry.getKey());
            }
            return expired;
        });
        
        // If still too large, remove oldest entries
        if (fileContentCache.size() >= MAX_CACHE_SIZE) {
            String oldestKey = cacheTimestamps.entrySet().stream()
                .min(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
                
            if (oldestKey != null) {
                fileContentCache.remove(oldestKey);
                cacheTimestamps.remove(oldestKey);
            }
        }
    }
    
    private int findSentenceIndex(String[] sentences, int charPosition) {
        int currentPos = 0;
        
        for (int i = 0; i < sentences.length; i++) {
            int sentenceEnd = currentPos + sentences[i].length();
            if (charPosition >= currentPos && charPosition <= sentenceEnd) {
                return i;
            }
            currentPos = sentenceEnd + 2; // Account for ". " separator
        }
        
        return sentences.length > 0 ? sentences.length - 1 : 0;
    }
    
    private String getChunkId(TextSegment segment) {
        Metadata metadata = segment.metadata();
        String file = metadata.getString("file");
        String startLine = metadata.getString("startLine");
        
        if (file != null && startLine != null) {
            return file + ":" + startLine;
        } else {
            // Fallback to content hash
            return "chunk_" + Math.abs(segment.text().hashCode());
        }
    }
    
    private String findParentClass(TextSegment segment, String filePath) {
        try {
            String fullContent = getFullFileContent(filePath);
            if (fullContent == null) return segment.text();
            
            String segmentText = segment.text().trim();
            int segmentStart = fullContent.indexOf(segmentText);
            
            if (segmentStart == -1) return segment.text();
            
            // Look backwards for class declaration
            String beforeSegment = fullContent.substring(0, segmentStart);
            String[] lines = beforeSegment.split("\n");
            
            // Find the most recent class declaration
            for (int i = lines.length - 1; i >= 0; i--) {
                String line = lines[i].trim();
                if (line.matches(".*\\b(class|interface)\\s+\\w+.*\\{")) {
                    // Found class, extract from here to end of segment
                    int classStart = beforeSegment.lastIndexOf(lines[i]);
                    int segmentEnd = segmentStart + segmentText.length();
                    
                    if (segmentEnd - classStart <= MAX_PARENT_DOCUMENT_SIZE) {
                        return fullContent.substring(classStart, segmentEnd);
                    }
                    break;
                }
            }
            
        } catch (Exception e) {
            LOG.debug("Failed to find parent class for segment: " + e.getMessage());
        }
        
        return segment.text();
    }
    
    private String generateContextualInformation(TextSegment segment) {
        try {
            StringBuilder contextual = new StringBuilder();
            Metadata metadata = segment.metadata();
            
            String filePath = metadata.getString("file");
            String nodeType = metadata.getString("nodeType");
            String startLine = metadata.getString("startLine");
            
            // Add file context
            if (filePath != null) {
                contextual.append("// File: ").append(filePath).append("\n");
                
                // Add surrounding context hint
                if (nodeType != null) {
                    contextual.append("// Code element: ").append(nodeType);
                    if (startLine != null) {
                        contextual.append(" (line ").append(startLine).append(")");
                    }
                    contextual.append("\n");
                }
                
                // Try to get some surrounding context
                String fullContent = getFullFileContent(filePath);
                if (fullContent != null) {
                    String segmentText = segment.text().trim();
                    int segmentStart = fullContent.indexOf(segmentText);
                    
                    if (segmentStart > 0) {
                        // Add some context before
                        int contextStart = Math.max(0, segmentStart - 200);
                        String beforeContext = fullContent.substring(contextStart, segmentStart).trim();
                        
                        if (!beforeContext.isEmpty()) {
                            contextual.append("// Preceding context:\n// ").append(
                                beforeContext.replace("\n", "\n// ")
                            ).append("\n\n");
                        }
                    }
                }
            }
            
            // Add the actual content
            contextual.append(segment.text());
            
            return contextual.toString();
            
        } catch (Exception e) {
            LOG.debug("Failed to generate contextual information: " + e.getMessage());
            return segment.text();
        }
    }
    
    private Map<String, Integer> buildTermFrequencies(String text) {
        Map<String, Integer> termFreqs = new HashMap<>();
        
        // Tokenize and count terms
        String[] tokens = text.toLowerCase()
            .replaceAll("[^a-zA-Z0-9\\s]", " ")
            .split("\\s+");
            
        for (String token : tokens) {
            if (token.length() > 2 && !isStopWord(token)) {
                termFreqs.merge(token, 1, Integer::sum);
            }
        }
        
        return termFreqs;
    }
}