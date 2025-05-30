package com.zps.zest.research.context;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zps.zest.research.search.SearchResults;
import com.zps.zest.research.analyzer.AnalysisResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Maintains the state and context throughout the research process.
 */
public class ResearchContext {
    private static final Gson GSON = new Gson();

    private final String originalQuery;
    private final String currentFileContext;
    private final Set<String> allDiscoveredKeywords;
    private final List<ResearchIteration> iterations;
    private final List<String> summaries;
    private int totalResultsFound;
    private JsonObject extractedCode;

    public ResearchContext(String originalQuery, String currentFileContext) {
        this.originalQuery = originalQuery;
        this.currentFileContext = currentFileContext;
        this.allDiscoveredKeywords = new HashSet<>();
        this.iterations = new ArrayList<>();
        this.summaries = new ArrayList<>();
        this.totalResultsFound = 0;
    }

    /**
     * Adds keywords to the discovered set.
     */
    public void addKeywords(List<String> keywords) {
        allDiscoveredKeywords.addAll(keywords);
    }

    /**
     * Adds a research iteration to the context.
     */
    public void addIteration(ResearchIteration iteration) {
        iterations.add(iteration);

        // Update keywords
        addKeywords(iteration.getKeywords());

        // Update total results
        totalResultsFound += iteration.getSearchResults().getTotalResults();

        // Add summary
        String summary = iteration.getAnalysisResult().getSummary();
        if (summary != null && !summary.isEmpty()) {
            summaries.add(summary);
        }
    }

    /**
     * Gets all discovered keywords.
     */
    public List<String> getAllDiscoveredKeywords() {
        return new ArrayList<>(allDiscoveredKeywords);
    }

    /**
     * Gets the original query.
     */
    public String getOriginalQuery() {
        return originalQuery;
    }

    /**
     * Gets the current file context.
     */
    public String getCurrentFileContext() {
        return currentFileContext;
    }

    /**
     * Gets total results found across all iterations.
     */
    public int getTotalResultsFound() {
        return totalResultsFound;
    }

    /**
     * Gets all iterations.
     */
    public List<ResearchIteration> getIterations() {
        return iterations;
    }

    /**
     * Sets the extracted code from final analysis.
     */
    public void setExtractedCode(JsonObject extractedCode) {
        this.extractedCode = extractedCode;
    }
    
    /**
     * Gets the extracted code.
     */
    public JsonObject getExtractedCode() {
        return extractedCode;
    }

    /**
     * Builds the final context JSON.
     */
    public JsonObject buildFinalContext() {
        JsonObject context = new JsonObject();

        // Basic info
        context.addProperty("userQuery", originalQuery);
        context.addProperty("currentFile", currentFileContext);

        // All keywords discovered
        JsonArray keywordsArray = new JsonArray();
        for (String keyword : allDiscoveredKeywords) {
            keywordsArray.add(keyword);
        }
        context.add("keywords", keywordsArray);

        // Aggregate results by type
        JsonArray allGitResults = new JsonArray();
        JsonArray allUnstagedResults = new JsonArray();
        JsonArray allProjectResults = new JsonArray();

        for (ResearchIteration iteration : iterations) {
            SearchResults results = iteration.getSearchResults();

            // Add git results
            for (int i = 0; i < results.getGitResults().size(); i++) {
                allGitResults.add(results.getGitResults().get(i));
            }

            // Add unstaged results
            for (int i = 0; i < results.getUnstagedResults().size(); i++) {
                allUnstagedResults.add(results.getUnstagedResults().get(i));
            }

            // Add project results
            for (int i = 0; i < results.getProjectResults().size(); i++) {
                allProjectResults.add(results.getProjectResults().get(i));
            }
        }

        context.add("recentChanges", allGitResults);
        context.add("unstagedChanges", allUnstagedResults);
        context.add("relatedCode", allProjectResults);

        // Add research metadata
        JsonObject metadata = new JsonObject();
        metadata.addProperty("totalIterations", iterations.size());
        metadata.addProperty("totalResults", totalResultsFound);
        metadata.addProperty("totalKeywords", allDiscoveredKeywords.size());

        // Add summaries
        JsonArray summariesArray = new JsonArray();
        for (String summary : summaries) {
            summariesArray.add(summary);
        }
        metadata.add("summaries", summariesArray);

        context.add("researchMetadata", metadata);
        
        // Add extracted code if available
        if (extractedCode != null) {
            context.add("extractedCode", extractedCode);
        }

        return context;
    }
}