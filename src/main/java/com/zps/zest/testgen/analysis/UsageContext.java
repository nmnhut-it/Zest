package com.zps.zest.testgen.analysis;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents how a method or class is used throughout the project.
 * This context is critical for generating realistic and comprehensive tests.
 */
public class UsageContext {
    private final String targetName; // Method or class name
    private final List<CallSite> callSites;
    private final List<EdgeCase> discoveredEdgeCases;
    private final List<TestDataExample> testDataExamples;
    private final IntegrationContext integrationContext;

    public UsageContext(@NotNull String targetName) {
        this.targetName = targetName;
        this.callSites = new ArrayList<>();
        this.discoveredEdgeCases = new ArrayList<>();
        this.testDataExamples = new ArrayList<>();
        this.integrationContext = new IntegrationContext();
    }

    public void addCallSite(@NotNull CallSite callSite) {
        this.callSites.add(callSite);
    }

    public void addEdgeCase(@NotNull EdgeCase edgeCase) {
        this.discoveredEdgeCases.add(edgeCase);
    }

    public void addTestDataExample(@NotNull TestDataExample example) {
        this.testDataExamples.add(example);
    }

    @NotNull
    public String getTargetName() {
        return targetName;
    }

    @NotNull
    public List<CallSite> getCallSites() {
        return new ArrayList<>(callSites);
    }

    @NotNull
    public List<EdgeCase> getDiscoveredEdgeCases() {
        return new ArrayList<>(discoveredEdgeCases);
    }

    @NotNull
    public List<TestDataExample> getTestDataExamples() {
        return new ArrayList<>(testDataExamples);
    }

    @NotNull
    public IntegrationContext getIntegrationContext() {
        return integrationContext;
    }

    public boolean isEmpty() {
        return callSites.isEmpty() && discoveredEdgeCases.isEmpty() && testDataExamples.isEmpty();
    }

    public int getTotalUsages() {
        return callSites.size();
    }

    /**
     * Format usage context as human-readable string for LLM consumption
     */
    @NotNull
    public String formatForLLM() {
        if (isEmpty()) {
            return "No usage information available for " + targetName;
        }

        StringBuilder formatted = new StringBuilder();
        formatted.append("**USAGE ANALYSIS FOR ").append(targetName).append("**\n");
        formatted.append("```\n");

        // Call sites
        if (!callSites.isEmpty()) {
            formatted.append("CALL SITES (").append(callSites.size()).append(" found):\n");
            int limit = Math.min(5, callSites.size()); // Limit to first 5 for brevity
            for (int i = 0; i < limit; i++) {
                CallSite site = callSites.get(i);
                formatted.append(String.format("\n%d. %s\n", i + 1, site.getCallerDescription()));

                // Show code snippet if available
                if (site.getCodeSnippet() != null && !site.getCodeSnippet().isEmpty()) {
                    formatted.append("   Code:\n");
                    String[] lines = site.getCodeSnippet().split("\n");
                    for (String line : lines) {
                        formatted.append("      ").append(line).append("\n");
                    }
                }

                if (site.hasErrorHandling()) {
                    formatted.append("   ⚠️ Error handling: ").append(site.getErrorHandlingType()).append("\n");
                }
                formatted.append("\n");
            }
            if (callSites.size() > 5) {
                formatted.append(String.format("   ... and %d more call sites\n\n", callSites.size() - 5));
            }
        }

        // Edge cases
        if (!discoveredEdgeCases.isEmpty()) {
            formatted.append("DISCOVERED EDGE CASES:\n");
            for (EdgeCase edgeCase : discoveredEdgeCases) {
                formatted.append(String.format("- %s: %s\n", edgeCase.getType(), edgeCase.getDescription()));
            }
            formatted.append("\n");
        }

        // Test data examples
        if (!testDataExamples.isEmpty()) {
            formatted.append("TEST DATA EXAMPLES (from real usage):\n");
            int limit = Math.min(3, testDataExamples.size());
            for (int i = 0; i < limit; i++) {
                TestDataExample example = testDataExamples.get(i);
                formatted.append(String.format("- %s: %s\n", example.getLabel(), example.getValue()));
            }
            formatted.append("\n");
        }

        // Integration context
        if (integrationContext.hasIntegrationPatterns()) {
            formatted.append("INTEGRATION PATTERNS:\n");
            formatted.append(integrationContext.formatForLLM()).append("\n");
        }

        formatted.append("```\n");
        return formatted.toString();
    }

    @Override
    public String toString() {
        return "UsageContext{" +
               "target='" + targetName + '\'' +
               ", callSites=" + callSites.size() +
               ", edgeCases=" + discoveredEdgeCases.size() +
               ", testExamples=" + testDataExamples.size() +
               '}';
    }
}
