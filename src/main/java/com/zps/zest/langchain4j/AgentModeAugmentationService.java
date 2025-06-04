package com.zps.zest.langchain4j;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.agent.CodeExplorationReport;
import com.zps.zest.langchain4j.agent.ImprovedToolCallingAutonomousAgent;
import com.zps.zest.langchain4j.agent.network.AgentProxyConfiguration;
import com.zps.zest.langchain4j.util.LLMService;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Service that uses ImprovedToolCallingAutonomousAgent for advanced query augmentation
 * in Agent Mode and Project Mode.
 */
@Service(Service.Level.PROJECT)
public final class AgentModeAugmentationService {
    
    private static final Logger LOG = Logger.getInstance(AgentModeAugmentationService.class);
    
    private final Project project;
    private final ImprovedToolCallingAutonomousAgent explorationAgent;
    private final LLMService llmService;
    
    public AgentModeAugmentationService(@NotNull Project project) {
        this.project = project;
        this.explorationAgent = project.getService(ImprovedToolCallingAutonomousAgent.class);
        this.llmService = project.getService(LLMService.class);
        LOG.info("Initialized AgentModeAugmentationService");
    }
    
    /**
     * Augments a query using comprehensive code exploration and LLM rewriting.
     * This is designed for Agent Mode and Project Mode where we want rich context.
     * 
     * @param query The user's original query
     * @param mode The current mode (Agent Mode or Project Mode)
     * @return Augmented query with code context
     */
    public CompletableFuture<String> augmentQueryWithExploration(String query, String mode) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOG.info("Starting exploration-based augmentation for mode: " + mode);
                
                // Step 1: Perform comprehensive code exploration
                CodeExplorationReport report = explorationAgent.exploreAndGenerateReport(query);
                
                // Step 2: Generate augmented prompt based on mode
                String augmentedPrompt = generateAugmentedPrompt(query, report, mode);
                
                LOG.info("Augmentation completed successfully");
                return augmentedPrompt;
                
            } catch (Exception e) {
                LOG.error("Error during exploration-based augmentation", e);
                // Return original query on error
                return query;
            }
        });
    }
    
    /**
     * Generates an augmented prompt based on the exploration report and mode.
     */
    private String generateAugmentedPrompt(String originalQuery, CodeExplorationReport report, String mode) {
        if (!llmService.isConfigured()) {
            // Fallback to rule-based augmentation if no LLM
            return generateRuleBasedAugmentation(originalQuery, report, mode);
        }
        
        String llmPrompt = buildLLMPromptForAugmentation(originalQuery, report, mode);
        String augmented = llmService.query(llmPrompt);
        
        if (augmented == null || augmented.isEmpty()) {
            // Fallback to rule-based if LLM fails
            return generateRuleBasedAugmentation(originalQuery, report, mode);
        }
        
        return augmented;
    }
    
    /**
     * Builds the LLM prompt for query augmentation.
     */
    private String buildLLMPromptForAugmentation(String originalQuery, CodeExplorationReport report, String mode) {
        StringBuilder prompt = new StringBuilder();
        
        if ("Agent Mode".equals(mode)) {
            prompt.append("""
                You are augmenting a user's query with comprehensive code context for Agent Mode.
                Agent Mode allows direct code manipulation, so include specific implementation details.
                
                """);
        } else { // Project Mode
            prompt.append("""
                You are augmenting a user's query with code context for Project Mode.
                Project Mode focuses on understanding and exploration without direct manipulation.
                
                """);
        }
        
        prompt.append("Original Query: ").append(originalQuery).append("\n\n");
        
        // Add exploration summary
        prompt.append("Code Exploration Summary:\n");
        prompt.append(report.getSummary()).append("\n\n");
        
        // Add discovered elements (limit to most relevant)
        prompt.append("Key Code Elements Discovered:\n");
        report.getDiscoveredElements().stream()
            .limit(10)
            .forEach(element -> prompt.append("- ").append(element).append("\n"));
        prompt.append("\n");
        
        // Add code snippets if available
        if (!report.getCodePieces().isEmpty()) {
            prompt.append("Relevant Code Snippets:\n");
            report.getCodePieces().stream()
                .limit(3)
                .forEach(piece -> {
                    prompt.append("```").append(piece.getLanguage()).append("\n");
                    prompt.append("// From: ").append(piece.getId()).append("\n");
                    prompt.append(piece.getContent()).append("\n");
                    prompt.append("```\n\n");
                });
        }
        
        // Add relationships if available
        if (report.getRelationships() != null && !report.getRelationships().isEmpty()) {
            prompt.append("Key Relationships:\n");
            report.getRelationships().entrySet().stream()
                .limit(5)
                .forEach(entry -> {
                    prompt.append("- ").append(entry.getKey()).append(": ");
                    prompt.append(String.join(", ", entry.getValue())).append("\n");
                });
            prompt.append("\n");
        }
        
        // Instructions for rewriting
        prompt.append("""
            
            Rewrite the user's query to include ALL relevant code context discovered during exploration.
            The rewritten query should:
            1. Start with the original intent
            2. Reference specific classes, methods, and files by their exact names
            3. Include relevant code patterns and structures found
            4. Mention important relationships and dependencies
            5. Add any necessary implementation details or constraints
            6. Be self-contained so an LLM has all context needed
            
            """);
        
        if ("Agent Mode".equals(mode)) {
            prompt.append("""
                For Agent Mode, also include:
                - Specific file paths where changes might be needed
                - Method signatures and parameter types
                - Any design patterns or conventions to follow
                - Test files that might need updates
                
                """);
        }
        
        prompt.append("Rewritten Query with Full Context:\n");
        
        return prompt.toString();
    }
    
    /**
     * Generates a rule-based augmentation when LLM is not available.
     */
    private String generateRuleBasedAugmentation(String originalQuery, CodeExplorationReport report, String mode) {
        StringBuilder augmented = new StringBuilder();
        
        // Start with context header
        augmented.append("<context>\n");
        augmented.append("Project: ").append(project.getName()).append("\n");
        augmented.append("Mode: ").append(mode).append("\n\n");
        
        // Add discovered elements
        if (!report.getDiscoveredElements().isEmpty()) {
            augmented.append("Relevant Code Elements:\n");
            report.getDiscoveredElements().stream()
                .limit(15)
                .forEach(element -> augmented.append("- ").append(element).append("\n"));
            augmented.append("\n");
        }
        
        // Add summary
        if (report.getSummary() != null) {
            augmented.append("Code Analysis:\n");
            augmented.append(report.getSummary()).append("\n\n");
        }
        
        // Add code snippets
        if (!report.getCodePieces().isEmpty()) {
            augmented.append("Key Code Sections:\n");
            report.getCodePieces().stream()
                .limit(3)
                .forEach(piece -> {
                    augmented.append("\n[").append(piece.getId()).append("]\n");
                    augmented.append("```").append(piece.getLanguage()).append("\n");
                    augmented.append(piece.getContent()).append("\n");
                    augmented.append("```\n");
                });
        }
        
        augmented.append("</context>\n\n");
        
        // Add the original query
        augmented.append("Query: ").append(originalQuery);
        
        return augmented.toString();
    }
    
    /**
     * Quick exploration for real-time augmentation with limited depth.
     */
    public CompletableFuture<String> quickAugmentation(String query) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Use quick configuration for faster results
                AgentProxyConfiguration quickConfig = AgentProxyConfiguration.getQuickAugmentation();
                
                // This would require modifying ImprovedToolCallingAutonomousAgent to accept config
                // For now, use the standard exploration with timeout
                CompletableFuture<CodeExplorationReport> future = CompletableFuture.supplyAsync(() ->
                    explorationAgent.exploreAndGenerateReport(query)
                );
                
                CodeExplorationReport report = future.get(15, TimeUnit.SECONDS);
                
                // Generate a concise augmentation
                return generateConciseAugmentation(query, report);
                
            } catch (Exception e) {
                LOG.warn("Quick augmentation failed, returning original query", e);
                return query;
            }
        });
    }
    
    /**
     * Generates a concise augmentation for quick mode.
     */
    private String generateConciseAugmentation(String query, CodeExplorationReport report) {
        StringBuilder augmented = new StringBuilder(query);
        
        if (!report.getDiscoveredElements().isEmpty()) {
            augmented.append("\n\n[Context: ");
            augmented.append(report.getDiscoveredElements().stream()
                .limit(5)
                .collect(Collectors.joining(", ")));
            augmented.append("]");
        }
        
        return augmented.toString();
    }
    
    /**
     * Gets exploration statistics for monitoring.
     */
    public ExplorationStats getStats() {
        // This would track exploration metrics
        return new ExplorationStats();
    }
    
    /**
     * Simple stats class for tracking exploration metrics.
     */
    public static class ExplorationStats {
        private int totalExplorations = 0;
        private int successfulExplorations = 0;
        private long totalTimeMs = 0;
        
        public int getTotalExplorations() { return totalExplorations; }
        public int getSuccessfulExplorations() { return successfulExplorations; }
        public long getTotalTimeMs() { return totalTimeMs; }
        public double getAverageTimeMs() {
            return totalExplorations > 0 ? (double) totalTimeMs / totalExplorations : 0;
        }
    }
}