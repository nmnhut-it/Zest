package com.zps.zest.context;

import com.intellij.openapi.diagnostic.Logger;
import com.zps.zest.browser.utils.ChatboxUtilities;
import com.zps.zest.langchain4j.naive_service.NaiveLLMService;
import com.zps.zest.testgen.agents.ContextAgent;
import dev.langchain4j.agent.tool.Tool;

import java.util.List;
import java.util.Map;

/**
 * Generates concise context reports using LLM to distill exploration findings.
 */
public class CodeContextReportGenerator {
    private static final Logger LOG = Logger.getInstance(CodeContextReportGenerator.class);
    private static final int MAX_REPORT_TOKENS = 3000;

    private final ContextAgent.ContextGatheringTools contextTools;
    private final NaiveLLMService naiveLlmService;
    private volatile boolean reportGenerated = false;
    private String generatedReport = "";

    public CodeContextReportGenerator(ContextAgent.ContextGatheringTools contextTools,
                                     NaiveLLMService naiveLlmService) {
        this.contextTools = contextTools;
        this.naiveLlmService = naiveLlmService;
    }

    @Tool("""
        Generate a concise context report from your exploration findings.

        This tool analyzes all your notes and gathered context to create a focused,
        readable markdown report with:
        - High-level overview (what this code does)
        - Architecture and design patterns found
        - Key code snippets (selective - only the most important ones)
        - Dependencies and integration points
        - Important insights and notes

        Call this tool when you have:
        - Explored the target code
        - Taken notes on key findings
        - Gathered enough understanding

        The report will be concise and actionable, not a data dump.
        """)
    public String generateReport() {
        LOG.info("Generating context report from exploration findings");

        try {
            List<String> contextNotes = contextTools.getContextNotes();
            Map<String, String> analyzedClasses = contextTools.getAnalyzedClasses();
            Map<String, String> readFiles = contextTools.getReadFiles();

            if (contextNotes.isEmpty() && analyzedClasses.isEmpty()) {
                return "⚠️ No context gathered yet. Please explore the code first using searchCode, analyzeClass, and takeNote tools.";
            }

            String rawContext = buildRawContext(contextNotes, analyzedClasses, readFiles);

            String reportPrompt = buildReportPrompt(rawContext);

            NaiveLLMService.LLMQueryParams params = new NaiveLLMService.LLMQueryParams(reportPrompt)
                    .withModel("local-model")
                    .withMaxTokens(MAX_REPORT_TOKENS)
                    .withTimeout(60000);

            String report = naiveLlmService.queryWithParams(params, ChatboxUtilities.EnumUsage.CHAT_CODE_REVIEW);

            if (report == null || report.trim().isEmpty()) {
                report = "Error: Failed to generate report";
            }

            generatedReport = report;
            reportGenerated = true;
            contextTools.markContextCollectionDone();

            return "✅ Report generated successfully!\n\n" + report;

        } catch (Exception e) {
            LOG.error("Error generating context report", e);
            return "❌ Error generating report: " + e.getMessage();
        }
    }

    private String buildRawContext(List<String> notes,
                                  Map<String, String> classes,
                                  Map<String, String> files) {
        StringBuilder context = new StringBuilder();

        // Add context notes (most important)
        if (!notes.isEmpty()) {
            context.append("=== EXPLORATION NOTES ===\n");
            for (String note : notes) {
                context.append("- ").append(note).append("\n");
            }
            context.append("\n");
        }

        // Add analyzed classes summary (not full source)
        if (!classes.isEmpty()) {
            context.append("=== ANALYZED CLASSES ===\n");
            for (Map.Entry<String, String> entry : classes.entrySet()) {
                String filePath = entry.getKey();
                String analysis = entry.getValue();

                // Include first 500 chars of analysis as summary
                String summary = analysis.length() > 500
                    ? analysis.substring(0, 500) + "..."
                    : analysis;

                context.append("File: ").append(filePath).append("\n");
                context.append(summary).append("\n\n");
            }
        }

        // Add important files (config, build files)
        if (!files.isEmpty()) {
            context.append("=== KEY FILES EXAMINED ===\n");
            for (String filePath : files.keySet()) {
                if (isImportantFile(filePath)) {
                    context.append("- ").append(filePath).append("\n");
                }
            }
            context.append("\n");
        }

        return context.toString();
    }

    private boolean isImportantFile(String filePath) {
        String lower = filePath.toLowerCase();
        return lower.contains("pom.xml") ||
               lower.contains("build.gradle") ||
               lower.contains("application.") ||
               lower.contains("config") ||
               lower.contains(".properties") ||
               lower.contains(".yml") ||
               lower.contains(".yaml");
    }

    private String buildReportPrompt(String rawContext) {
        return """
            You are a technical writer creating a concise code context report.

            Based on the exploration findings below, write a focused markdown report that:

            1. **Overview** (2-3 sentences): What this code does and its purpose

            2. **Architecture & Design** (150 words max):
               - Key design patterns used
               - Code organization and structure
               - Important architectural decisions

            3. **Key Code Snippets** (3-5 snippets max):
               - Include ONLY the most critical code snippets
               - Each snippet: 5-15 lines, well-formatted
               - Add brief description for each

            4. **Dependencies** (bullet list):
               - Key external libraries/frameworks
               - Important internal dependencies
               - Integration points

            5. **Important Insights** (bullet list):
               - Critical behaviors or patterns
               - Potential issues or improvements
               - Things developers should know

            Keep it CONCISE and ACTIONABLE. Focus on what matters.
            Use markdown formatting: ##, ###, -, ``` for code blocks.

            === EXPLORATION FINDINGS ===

            """ + rawContext;
    }

    public boolean isReportGenerated() {
        return reportGenerated;
    }

    public String getGeneratedReport() {
        return generatedReport;
    }
}
