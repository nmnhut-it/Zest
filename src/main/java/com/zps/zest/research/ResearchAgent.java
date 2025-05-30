package com.zps.zest.research;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.browser.KeywordGeneratorService;
import com.zps.zest.research.analyzer.LLMAnalyzer;
import com.zps.zest.research.analyzer.AnalysisResult;
import com.zps.zest.research.context.ResearchContext;
import com.zps.zest.research.context.ResearchIteration;
import com.zps.zest.research.search.SearchCoordinator;
import com.zps.zest.research.search.SearchResults;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Main orchestrator for the Research Agent system.
 * Conducts iterative searches with LLM-guided analysis to find relevant code.
 */
public class ResearchAgent {
    private static final Logger LOG = Logger.getInstance(ResearchAgent.class);
    private static final Gson GSON = new Gson();
    
    // Configuration constants
    private static final int MAX_ITERATIONS = 5;
    private static final int MIN_RESULTS_THRESHOLD = 3;
    private static final long TIMEOUT_SECONDS = 60;
    
    private final Project project;
    private final SearchCoordinator searchCoordinator;
    private final LLMAnalyzer llmAnalyzer;
    private final KeywordGeneratorService keywordGenerator;
    
    public ResearchAgent(@NotNull Project project) {
        this.project = project;
        this.searchCoordinator = new SearchCoordinator(project);
        this.llmAnalyzer = new LLMAnalyzer(project);
        this.keywordGenerator = new KeywordGeneratorService(project);
    }
    
    /**
     * Main entry point for research.
     * Conducts iterative search process to find relevant code for the user query.
     */
    public CompletableFuture<String> research(String userQuery, String currentFileContext) {
        LOG.info("=== Research Agent Started ===");
        LOG.info("User Query: " + userQuery);
        LOG.info("Current File: " + currentFileContext);
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Initialize research context
                ResearchContext context = new ResearchContext(userQuery, currentFileContext);
                
                // Get initial keywords
                List<String> keywords = keywordGenerator.generateKeywords(userQuery)
                    .get(300, TimeUnit.SECONDS);
                
                if (keywords.isEmpty()) {
                    LOG.warn("No initial keywords generated, using fallback");
                    keywords = extractBasicKeywords(userQuery);
                }
                
                LOG.info("Starting research with " + keywords.size() + " initial keywords: " + keywords);
                context.addKeywords(keywords);
                
                // Main research loop
                for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
                    LOG.info("=== Research Iteration " + (iteration + 1) + " ===");
                    
                    // Execute iteration
                    ResearchIteration iterationResult = executeIteration(keywords, context, iteration);
                    
                    // Add results to context
                    context.addIteration(iterationResult);
                    
                    // Check if we should continue
                    if (!shouldContinue(context, iteration, iterationResult)) {
                        LOG.info("Research complete after " + (iteration + 1) + " iterations");
                        break;
                    }
                    
                    // Prepare for next iteration
                    keywords = iterationResult.getAnalysisResult().getNextKeywords();
                    if (keywords.isEmpty()) {
                        LOG.info("No new keywords generated, ending research");
                        break;
                    }
                    
                    LOG.info("Continuing with " + keywords.size() + " new keywords: " + keywords);
                }
                
                // Build final enhanced context
                String finalContext = context.buildFinalContext().toString();
                LOG.info("Research completed. Final context size: " + finalContext.length() + " chars");
                
                return finalContext;
                
            } catch (Exception e) {
                LOG.error("Error during research", e);
                return buildErrorContext(userQuery, currentFileContext, e);
            }
        });
    }
    
    /**
     * Executes a single research iteration.
     */
    private ResearchIteration executeIteration(List<String> keywords, ResearchContext context, int iterationNumber) {
        LOG.info("Executing iteration " + (iterationNumber + 1) + " with keywords: " + keywords);
        
        try {
            // 1. Execute parallel searches
            long searchStart = System.currentTimeMillis();
            SearchResults searchResults = searchCoordinator.searchAll(keywords)
                .get(30, TimeUnit.SECONDS);
            long searchDuration = System.currentTimeMillis() - searchStart;
            
            LOG.info("Search completed in " + searchDuration + "ms");
            LOG.info("Found " + searchResults.getTotalResults() + " total results");
            
            // 2. Analyze results with LLM
            long analysisStart = System.currentTimeMillis();
            AnalysisResult analysisResult = llmAnalyzer.analyzeResults(
                context.getOriginalQuery(),
                iterationNumber,
                keywords,
                searchResults,
                context
            ).get(30, TimeUnit.SECONDS);
            long analysisDuration = System.currentTimeMillis() - analysisStart;
            
            LOG.info("Analysis completed in " + analysisDuration + "ms");
            LOG.info("Analysis suggests search is complete: " + analysisResult.isSearchComplete());
            LOG.info("Generated " + analysisResult.getNextKeywords().size() + " new keywords");
            
            // 3. Create iteration result
            ResearchIteration iteration = new ResearchIteration(
                iterationNumber,
                keywords,
                searchResults,
                analysisResult
            );
            
            return iteration;
            
        } catch (Exception e) {
            LOG.error("Error in iteration " + (iterationNumber + 1), e);
            
            // Return empty iteration on error
            return new ResearchIteration(
                iterationNumber,
                keywords,
                new SearchResults(),
                new AnalysisResult("", new ArrayList<>(), true, "Error: " + e.getMessage())
            );
        }
    }
    
    /**
     * Determines if research should continue.
     */
    private boolean shouldContinue(ResearchContext context, int iteration, ResearchIteration lastIteration) {
        // Check if LLM says we're done
        if (lastIteration.getAnalysisResult().isSearchComplete()) {
            LOG.info("LLM indicates search is complete");
            return false;
        }
        
        // Check if we have no new keywords
        if (lastIteration.getAnalysisResult().getNextKeywords().isEmpty()) {
            LOG.info("No new keywords to search");
            return false;
        }
        
        // Check if we've hit iteration limit
        if (iteration >= MAX_ITERATIONS - 1) {
            LOG.info("Reached maximum iteration limit");
            return false;
        }
        
        // Check if we have enough results
        int totalResults = context.getTotalResultsFound();
        if (totalResults > MIN_RESULTS_THRESHOLD * 3) {
            LOG.info("Found sufficient results (" + totalResults + "), considering stopping");
            
            // If we have many results and no significant new findings in last iteration
            if (lastIteration.getSearchResults().getTotalResults() < 2) {
                LOG.info("Last iteration yielded few new results, stopping");
                return false;
            }
        }
        
        // Check for repeated keywords (indicates we're going in circles)
        List<String> allPreviousKeywords = context.getAllDiscoveredKeywords();
        List<String> newKeywords = lastIteration.getAnalysisResult().getNextKeywords();
        long duplicates = newKeywords.stream()
            .filter(allPreviousKeywords::contains)
            .count();
        
        if (duplicates > newKeywords.size() / 2) {
            LOG.info("More than half of new keywords are duplicates, stopping");
            return false;
        }
        
        return true;
    }
    
    /**
     * Extracts basic keywords as fallback.
     */
    private List<String> extractBasicKeywords(String query) {
        List<String> keywords = new ArrayList<>();
        
        String[] words = query.toLowerCase()
            .replaceAll("[^a-zA-Z0-9\\s_]", " ")
            .split("\\s+");
        
        for (String word : words) {
            if (word.length() > 3 && !isCommonWord(word)) {
                keywords.add(word);
            }
        }
        
        return keywords.stream()
            .distinct()
            .limit(10)
            .toList();
    }
    
    /**
     * Builds error context when research fails.
     */
    private String buildErrorContext(String query, String currentFile, Exception error) {
        JsonObject errorContext = new JsonObject();
        errorContext.addProperty("userQuery", query);
        errorContext.addProperty("currentFile", currentFile);
        errorContext.addProperty("error", error.getMessage());
        errorContext.add("keywords", new com.google.gson.JsonArray());
        errorContext.add("recentChanges", new com.google.gson.JsonArray());
        errorContext.add("unstagedChanges", new com.google.gson.JsonArray());
        errorContext.add("relatedCode", new com.google.gson.JsonArray());
        
        return GSON.toJson(errorContext);
    }
    
    private boolean isCommonWord(String word) {
        return java.util.Set.of("this", "that", "with", "from", "have", "been",
            "will", "would", "could", "should", "make", "need", "want", "please",
            "help", "write", "create", "update", "modify", "change", "code").contains(word);
    }
    
    /**
     * Disposes resources.
     */
    public void dispose() {
        if (searchCoordinator != null) searchCoordinator.dispose();
    }
}
