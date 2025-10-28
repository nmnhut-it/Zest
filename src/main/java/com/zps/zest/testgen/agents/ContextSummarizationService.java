package com.zps.zest.testgen.agents;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.zps.zest.browser.utils.ChatboxUtilities;
import com.zps.zest.langchain4j.naive_service.NaiveLLMService;
import com.zps.zest.testgen.analysis.UsageContext;
import com.zps.zest.ClassAnalyzer;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Service for adaptively summarizing code context to reduce token usage.
 * Uses langchain4j's token counting and LLM-based summarization.
 */
public class ContextSummarizationService {
    private static final Logger LOG = Logger.getInstance(ContextSummarizationService.class);

    public enum DetailLevel {
        MINIMAL("1-2 sentences capturing only the essential purpose"),
        MODERATE("3-5 sentences including key methods and dependencies"),
        DETAILED("A paragraph preserving method signatures and important patterns"),
        FULL("Complete implementation without summarization");

        private final String instruction;

        DetailLevel(String instruction) {
            this.instruction = instruction;
        }

        public String getInstruction() {
            return instruction;
        }
    }

    private final Project project;
    private final NaiveLLMService llmService;
    private final OpenAiTokenCountEstimator tokenizer;

    // Simple in-memory cache for summaries (session-based)
    private final Map<String, String> summaryCache = new java.util.concurrent.ConcurrentHashMap<>();

    public ContextSummarizationService(@NotNull Project project, @NotNull NaiveLLMService llmService) {
        this.project = project;
        this.llmService = llmService;
        this.tokenizer = new OpenAiTokenCountEstimator("gpt-4o");
    }

    /**
     * Clear the summary cache (call when starting a new test generation session).
     */
    public void clearCache() {
        summaryCache.clear();
        LOG.info("Summary cache cleared");
    }

    /**
     * Generate a cache key for a summary request.
     */
    @NotNull
    private String generateCacheKey(@NotNull String className, @NotNull DetailLevel level, boolean isTargetClass) {
        return className + "|" + level.name() + "|" + isTargetClass;
    }

    /**
     * Estimate token count for text using OpenAI tokenizer.
     */
    public int estimateTokens(@NotNull String text) {
        return tokenizer.estimateTokenCountInText(text);
    }

    /**
     * Estimate total tokens for all analyzed classes.
     */
    public int estimateClassTokens(@NotNull Map<String, String> analyzedClasses) {
        int total = 0;
        for (String implementation : analyzedClasses.values()) {
            total += estimateTokens(implementation);
        }
        return total;
    }

    /**
     * Summarize analyzed class implementations with adaptive detail level.
     *
     * @param analyzedClasses Map of class names to implementations
     * @param targetClasses Classes that should get DETAILED treatment
     * @param maxTokens Maximum tokens to allocate for summaries
     * @return Summarized classes
     */
    @NotNull
    public CompletableFuture<Map<String, String>> summarizeClasses(
            @NotNull Map<String, String> analyzedClasses,
            @NotNull List<String> targetClasses,
            int maxTokens) {

        return CompletableFuture.supplyAsync(() -> {
            Map<String, String> summarized = new java.util.concurrent.ConcurrentHashMap<>();
            int remainingTokens = maxTokens;
            int currentTokens = estimateClassTokens(analyzedClasses);

            LOG.info("Summarizing " + analyzedClasses.size() + " classes (" + currentTokens + " tokens -> " + maxTokens + " tokens max)");

            // Process target classes first with detailed summaries
            for (String className : targetClasses) {
                if (analyzedClasses.containsKey(className)) {
                    String implementation = analyzedClasses.get(className);
                    int tokens = estimateTokens(implementation);

                    if (tokens > 2000) {
                        // Summarize even target classes if they're too large
                        String summary = summarizeSingleClass(className, implementation, DetailLevel.DETAILED);
                        summarized.put(className, summary);
                        remainingTokens -= estimateTokens(summary);
                    } else {
                        // Keep full implementation for target classes if reasonable
                        summarized.put(className, implementation);
                        remainingTokens -= tokens;
                    }
                }
            }

            // Process remaining classes with adaptive detail
            for (Map.Entry<String, String> entry : analyzedClasses.entrySet()) {
                String className = entry.getKey();
                if (targetClasses.contains(className)) {
                    continue; // Already processed
                }

                String implementation = entry.getValue();
                int tokens = estimateTokens(implementation);

                DetailLevel level;
                if (remainingTokens < maxTokens * 0.2) {
                    level = DetailLevel.MINIMAL;
                } else if (remainingTokens < maxTokens * 0.5) {
                    level = DetailLevel.MODERATE;
                } else {
                    level = DetailLevel.DETAILED;
                }

                String summary = summarizeSingleClass(className, implementation, level);
                summarized.put(className, summary);
                remainingTokens -= estimateTokens(summary);
            }

            LOG.info("Summarization complete: " + estimateClassTokens(summarized) + " tokens");
            return summarized;
        });
    }

    /**
     * Summarize a single class with usage context for better test generation.
     * This is the enhanced version that includes how the code is actually used.
     * Results are cached for the session to avoid redundant LLM calls.
     */
    @NotNull
    public String summarizeWithUsageContext(
            @NotNull String className,
            @NotNull String implementation,
            @Nullable UsageContext usageContext,
            @Nullable PsiClass psiClass,
            @NotNull DetailLevel level,
            boolean isTargetClass) {

        if (level == DetailLevel.FULL) {
            return implementation;
        }

        // Check cache first
        String cacheKey = generateCacheKey(className, level, isTargetClass);
        String cached = summaryCache.get(cacheKey);
        if (cached != null) {
            LOG.debug("Using cached summary for " + className);
            return cached;
        }

        // Build enhanced prompt with usage information
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("You are analyzing code for TEST GENERATION.\n\n");

        // Add role-specific instructions based on whether this is the target class
        if (isTargetClass) {
            promptBuilder.append("This is the TARGET CLASS being tested. Extract comprehensive information:\n\n");
            promptBuilder.append("1. **Testing Strategy Signals**:\n");
            promptBuilder.append("   - Does this class use external dependencies? (databases, HTTP, message queues)\n");
            promptBuilder.append("   - List specific dependency types (JPA, RestTemplate, Kafka, etc.)\n");
            promptBuilder.append("   - Recommendation: UNIT tests or INTEGRATION tests?\n\n");

            promptBuilder.append("2. **Method Analysis** (for each public/protected method):\n");
            promptBuilder.append("   - Signature: parameters and return type\n");
            promptBuilder.append("   - What does it do? (1 sentence)\n");
            promptBuilder.append("   - Dependencies it uses\n");
            promptBuilder.append("   - Exceptions it can throw\n");
            promptBuilder.append("   - Edge cases to test (null inputs, boundary values, etc.)\n\n");

            promptBuilder.append("3. **Test Data Hints**:\n");
            promptBuilder.append("   - What parameter types are needed?\n");
            promptBuilder.append("   - What return types to assert on?\n");
            promptBuilder.append("   - Are there domain invariants or validation rules?\n\n");

            if (level == DetailLevel.DETAILED) {
                promptBuilder.append("4. **Key Code Snippets** (include actual code):\n");
                promptBuilder.append("   - Complex conditionals\n");
                promptBuilder.append("   - Loops or recursion\n");
                promptBuilder.append("   - Important calculations or algorithms\n\n");
            }

        } else {
            // Dependency class - less detail
            promptBuilder.append("This is a DEPENDENCY class. Provide concise context:\n\n");
            promptBuilder.append("1. **Class Role**: What does this class do? (2-3 sentences)\n\n");
            promptBuilder.append("2. **Public API Summary**:\n");
            promptBuilder.append("   - Key public methods (signatures only)\n");
            promptBuilder.append("   - What behavior they provide\n\n");
            promptBuilder.append("3. **Dependency Types**:\n");
            promptBuilder.append("   - Does it use databases, HTTP, queues, etc.?\n\n");
        }

        // Add detected dependencies from static analysis
        if (psiClass != null) {
            Set<String> dependencies = ClassAnalyzer.detectExternalDependencies(psiClass);
            if (!dependencies.isEmpty()) {
                promptBuilder.append("STATIC ANALYSIS - ").append(ClassAnalyzer.formatDependenciesForTests(dependencies)).append("\n\n");
            }
        }

        // Add usage context if available
        if (usageContext != null && !usageContext.isEmpty()) {
            promptBuilder.append(usageContext.formatForLLM()).append("\n");
        }

        promptBuilder.append("CLASS IMPLEMENTATION:\n");
        promptBuilder.append("Class: ").append(className).append("\n");
        promptBuilder.append("Detail Level: ").append(level.getInstruction()).append("\n\n");
        promptBuilder.append(implementation).append("\n\n");

        promptBuilder.append("IMPORTANT: Provide ONLY the summary structured as described above, no preamble.\n");
        if (isTargetClass) {
            promptBuilder.append("Focus on information needed to write comprehensive, realistic tests.\n");
        } else {
            promptBuilder.append("Keep it concise but informative for understanding how the target class uses this.\n");
        }

        try {
            int maxTokens = isTargetClass ? 800 : 400; // More tokens for target class

            // Log the prompt for debugging
            String fullPrompt = promptBuilder.toString();
            int promptTokens = estimateTokens(fullPrompt);
            LOG.info("Summarization request for " + className + ": " + promptTokens + " tokens in prompt, requesting " + maxTokens + " tokens response");

            if (promptTokens > 15000) {
                LOG.warn("Prompt is very large (" + promptTokens + " tokens) for " + className + ", may fail");
            }

            NaiveLLMService.LLMQueryParams params = new NaiveLLMService.LLMQueryParams(fullPrompt)
                    .useLiteCodeModel()
                    .withMaxTokens(maxTokens)
                    .withTemperature(0.3);

            String summary = llmService.queryWithParams(params, ChatboxUtilities.EnumUsage.AGENT_TEST_WRITER);

            // Check if summary is null or empty
            if (summary == null || summary.trim().isEmpty() || summary.trim().equals("null")) {
                LOG.warn("LLM returned null/empty summary for " + className + ", falling back to simple summarization");
                return summarizeSingleClass(className, implementation, level);
            }

            String result = "// ENHANCED SUMMARY of " + className + ":\n" + summary;

            // Cache the result
            summaryCache.put(cacheKey, result);
            LOG.debug("Cached summary for " + className + " (" + estimateTokens(result) + " tokens)");

            return result;
        } catch (Exception e) {
            LOG.warn("Failed to summarize class " + className + " with usage context: " + e.getMessage(), e);
            // Fallback to simple summarization
            return summarizeSingleClass(className, implementation, level);
        }
    }

    /**
     * Summarize a single class with specified detail level (legacy method).
     */
    @NotNull
    private String summarizeSingleClass(@NotNull String className, @NotNull String implementation, @NotNull DetailLevel level) {
        if (level == DetailLevel.FULL) {
            return implementation;
        }

        String prompt = String.format("""
                Summarize the following class implementation.

                Class: %s

                Detail Level: %s

                IMPORTANT: Provide ONLY the summary, no preamble or explanation.
                Focus on:
                - Class purpose and responsibilities
                - Key public methods and their purposes
                - Important dependencies (external APIs, databases, etc.)
                - Notable patterns or design decisions

                Implementation:
                %s
                """, className, level.getInstruction(), implementation);

        try {
            NaiveLLMService.LLMQueryParams params = new NaiveLLMService.LLMQueryParams(prompt)
                    .useLiteCodeModel()
                    .withMaxTokens(500)
                    .withTemperature(0.3);

            String summary = llmService.queryWithParams(params, ChatboxUtilities.EnumUsage.AGENT_TEST_WRITER);
            return "// SUMMARY of " + className + ":\n" + summary;
        } catch (Exception e) {
            LOG.warn("Failed to summarize class " + className + ": " + e.getMessage());
            // Fallback: return truncated implementation
            return implementation.substring(0, Math.min(1000, implementation.length())) + "\n// ... (truncated)";
        }
    }

    /**
     * Summarize file contents with adaptive detail.
     */
    @NotNull
    public CompletableFuture<Map<String, String>> summarizeFiles(
            @NotNull Map<String, String> files,
            int maxTokens) {

        return CompletableFuture.supplyAsync(() -> {
            Map<String, String> summarized = new java.util.concurrent.ConcurrentHashMap<>();
            int remainingTokens = maxTokens;

            for (Map.Entry<String, String> entry : files.entrySet()) {
                String filePath = entry.getKey();
                String content = entry.getValue();
                int tokens = estimateTokens(content);

                if (tokens < 500) {
                    // Small file, keep as-is
                    summarized.put(filePath, content);
                    remainingTokens -= tokens;
                } else {
                    // Large file, extract relevant sections or summarize
                    DetailLevel level = remainingTokens > maxTokens * 0.5 ? DetailLevel.MODERATE : DetailLevel.MINIMAL;
                    String summary = summarizeSingleFile(filePath, content, level);
                    summarized.put(filePath, summary);
                    remainingTokens -= estimateTokens(summary);
                }
            }

            return summarized;
        });
    }

    /**
     * Summarize a single file.
     */
    @NotNull
    private String summarizeSingleFile(@NotNull String filePath, @NotNull String content, @NotNull DetailLevel level) {
        String prompt = String.format("""
                Summarize the following file content.

                File: %s

                Detail Level: %s

                IMPORTANT: Provide ONLY the summary, no preamble.
                Focus on what's relevant for understanding code dependencies and behavior.

                Content:
                %s
                """, filePath, level.getInstruction(), content);

        try {
            NaiveLLMService.LLMQueryParams params = new NaiveLLMService.LLMQueryParams(prompt)
                    .useLiteCodeModel()
                    .withMaxTokens(300)
                    .withTemperature(0.3);

            String summary = llmService.queryWithParams(params, ChatboxUtilities.EnumUsage.AGENT_TEST_WRITER);
            return "// SUMMARY of " + filePath + ":\n" + summary;
        } catch (Exception e) {
            LOG.warn("Failed to summarize file " + filePath + ": " + e.getMessage());
            return content.substring(0, Math.min(500, content.length())) + "\n// ... (truncated)";
        }
    }

    /**
     * Condense context notes by removing duplicates and combining similar notes.
     */
    @NotNull
    public List<String> condenseNotes(@NotNull List<String> contextNotes, int maxNotes) {
        if (contextNotes.size() <= maxNotes) {
            return new ArrayList<>(contextNotes);
        }

        // Simple deduplication by content similarity
        List<String> unique = new ArrayList<>();
        for (String note : contextNotes) {
            boolean isDuplicate = false;
            for (String existing : unique) {
                if (areSimilar(note, existing)) {
                    isDuplicate = true;
                    break;
                }
            }
            if (!isDuplicate) {
                unique.add(note);
            }
        }

        // If still too many, keep the first N (assumed to be most important)
        if (unique.size() > maxNotes) {
            return unique.subList(0, maxNotes);
        }

        return unique;
    }

    /**
     * Check if two notes are similar (simple keyword-based similarity).
     */
    private boolean areSimilar(@NotNull String note1, @NotNull String note2) {
        // Normalize and split into words
        String[] words1 = note1.toLowerCase().split("\\W+");
        String[] words2 = note2.toLowerCase().split("\\W+");

        // Count common words
        int common = 0;
        for (String word1 : words1) {
            if (word1.length() < 3) continue; // Skip short words
            for (String word2 : words2) {
                if (word1.equals(word2)) {
                    common++;
                    break;
                }
            }
        }

        // Consider similar if >60% words are common
        int minLength = Math.min(words1.length, words2.length);
        return minLength > 0 && (double) common / minLength > 0.6;
    }
}
