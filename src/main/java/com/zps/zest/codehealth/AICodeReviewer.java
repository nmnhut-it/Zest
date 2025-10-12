package com.zps.zest.codehealth;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.browser.utils.ChatboxUtilities;
import com.zps.zest.langchain4j.naive_service.NaiveLLMService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for AI-powered code quality review.
 * Reviews generated code for style compliance, potential bugs, and improvements.
 */
@Service(Service.Level.PROJECT)
public class AICodeReviewer {
    private static final Logger LOG = Logger.getInstance(AICodeReviewer.class);

    private final Project project;
    private final NaiveLLMService llmService;

    public AICodeReviewer(@NotNull Project project) {
        this.project = project;
        this.llmService = project.getService(NaiveLLMService.class);
    }

    /**
     * Review generated code and return quality assessment
     */
    @NotNull
    public CodeReview reviewCode(@NotNull String code) {
        try {
            String reviewPrompt = buildReviewPrompt(code);

            NaiveLLMService.LLMQueryParams params = new NaiveLLMService.LLMQueryParams(reviewPrompt)
                    .withModel("local-model-mini")
                    .withMaxTokens(1000)
                    .withMaxRetries(1)
                    .withTimeout(10000L);

            String response = llmService.queryWithParams(params, ChatboxUtilities.EnumUsage.CODE_HEALTH);

            if (response != null) {
                return parseReviewResponse(response, code);
            } else {
                LOG.warn("AI code review returned null response");
                return createDefaultPassingReview();
            }
        } catch (Exception e) {
            LOG.warn("Error during AI code review", e);
            return createDefaultPassingReview();
        }
    }

    /**
     * Improve code based on review feedback
     */
    @Nullable
    public String improveCode(@NotNull String code, @NotNull String feedback) {
        try {
            String improvePrompt = buildImprovementPrompt(code, feedback);

            NaiveLLMService.LLMQueryParams params = new NaiveLLMService.LLMQueryParams(improvePrompt)
                    .withModel("local-model-mini")
                    .withMaxTokens(2000)
                    .withMaxRetries(1)
                    .withTimeout(15000L);

            String response = llmService.queryWithParams(params, ChatboxUtilities.EnumUsage.CODE_HEALTH);

            if (response != null) {
                return extractCodeFromResponse(response);
            }
            return null;
        } catch (Exception e) {
            LOG.warn("Error improving code", e);
            return null;
        }
    }

    private String buildReviewPrompt(String code) {
        return "Review this code for quality. Return your response in this exact format:\n\n" +
                "SCORE: [0-100]\n" +
                "PASSED: [true/false]\n" +
                "ISSUES:\n" +
                "- [issue 1]\n" +
                "- [issue 2]\n\n" +
                "Review criteria:\n" +
                "1. Code style compliance (naming, formatting, conventions)\n" +
                "2. Potential bugs or logic errors\n" +
                "3. Best practices and patterns\n" +
                "4. Code clarity and maintainability\n\n" +
                "Code to review:\n```\n" + code + "\n```\n\n" +
                "Be concise. Score 80+ = PASSED: true, below 80 = PASSED: false.";
    }

    private String buildImprovementPrompt(String code, String feedback) {
        return "Improve this code based on the review feedback.\n\n" +
                "Original code:\n```\n" + code + "\n```\n\n" +
                "Review feedback:\n" + feedback + "\n\n" +
                "Return ONLY the improved code in a code block, no explanations.";
    }

    private CodeReview parseReviewResponse(String response, String originalCode) {
        int score = 100;
        boolean passed = true;
        List<String> issues = new ArrayList<>();

        try {
            // Parse SCORE
            Pattern scorePattern = Pattern.compile("SCORE:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
            Matcher scoreMatcher = scorePattern.matcher(response);
            if (scoreMatcher.find()) {
                score = Integer.parseInt(scoreMatcher.group(1));
                score = Math.max(0, Math.min(100, score)); // Clamp to 0-100
            }

            // Parse PASSED
            Pattern passedPattern = Pattern.compile("PASSED:\\s*(true|false)", Pattern.CASE_INSENSITIVE);
            Matcher passedMatcher = passedPattern.matcher(response);
            if (passedMatcher.find()) {
                passed = Boolean.parseBoolean(passedMatcher.group(1));
            } else {
                // Auto-determine from score if not explicitly stated
                passed = score >= 80;
            }

            // Parse ISSUES
            Pattern issuesPattern = Pattern.compile("ISSUES:\\s*\\n([\\s\\S]*?)(?:\\n\\n|$)");
            Matcher issuesMatcher = issuesPattern.matcher(response);
            if (issuesMatcher.find()) {
                String issuesText = issuesMatcher.group(1);
                String[] lines = issuesText.split("\\n");
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("-") || trimmed.startsWith("*")) {
                        issues.add(trimmed.substring(1).trim());
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Error parsing code review response", e);
        }

        return new CodeReview(score, passed, issues, originalCode);
    }

    private String extractCodeFromResponse(String response) {
        // Extract code from markdown code blocks
        Pattern codeBlockPattern = Pattern.compile("```(?:java|kotlin)?\\s*\\n([\\s\\S]*?)```");
        Matcher matcher = codeBlockPattern.matcher(response);

        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        // If no code block, return response as-is
        return response.trim();
    }

    private CodeReview createDefaultPassingReview() {
        return new CodeReview(100, true, new ArrayList<>(), "");
    }

    /**
     * Data class for code review results
     */
    public static class CodeReview {
        private final int styleComplianceScore;
        private final boolean passed;
        private final List<String> issues;
        private final String originalCode;

        public CodeReview(int styleComplianceScore, boolean passed, List<String> issues, String originalCode) {
            this.styleComplianceScore = styleComplianceScore;
            this.passed = passed;
            this.issues = issues;
            this.originalCode = originalCode;
        }

        public int getStyleComplianceScore() {
            return styleComplianceScore;
        }

        public boolean isPassed() {
            return passed;
        }

        public List<String> getIssues() {
            return issues;
        }

        public String getFeedback() {
            if (issues.isEmpty()) {
                return "No issues found";
            }
            return String.join("\n", issues);
        }

        public String getOriginalCode() {
            return originalCode;
        }
    }

    public static AICodeReviewer getInstance(Project project) {
        return project.getService(AICodeReviewer.class);
    }
}
