package com.zps.zest.context;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.naive_service.NaiveLLMService;
import com.zps.zest.langchain4j.tools.CodeExplorationToolRegistry;
import com.zps.zest.testgen.agents.ContextAgent;
import com.zps.zest.testgen.agents.StreamingBaseAgent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.SystemMessage;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * AI Agent for exploring code context autonomously.
 * General-purpose agent that understands code architecture, usage patterns, and dependencies.
 */
public class CodeContextAgent extends StreamingBaseAgent {
    private static final Logger LOG = Logger.getInstance(CodeContextAgent.class);
    private static final int DEFAULT_MAX_TOOLS = 20;

    private final CodeExplorationToolRegistry toolRegistry;
    private final ContextAgent.ContextGatheringTools contextTools;
    private final CodeContextReportGenerator reportGenerator;
    private final CodeExplorationAssistant assistant;
    private final MessageWindowChatMemory chatMemory;
    private final int maxToolCalls;

    public CodeContextAgent(@NotNull Project project,
                           @NotNull ZestLangChain4jService langChainService,
                           @NotNull NaiveLLMService naiveLlmService,
                           int maxToolCalls) {
        super(project, langChainService, naiveLlmService, "CodeContextAgent");
        this.maxToolCalls = maxToolCalls > 0 ? maxToolCalls : DEFAULT_MAX_TOOLS;
        this.toolRegistry = project.getService(CodeExplorationToolRegistry.class);
        this.contextTools = new ContextAgent.ContextGatheringTools(project, toolRegistry, this::sendToUI, null);
        this.reportGenerator = new CodeContextReportGenerator(contextTools, naiveLlmService);

        this.chatMemory = MessageWindowChatMemory.withMaxMessages(100);
        this.assistant = AgenticServices
                .agentBuilder(CodeExplorationAssistant.class)
                .chatModel(getChatModelWithStreaming())
                .maxSequentialToolsInvocations(maxToolCalls * 2)
                .chatMemory(chatMemory)
                .tools(contextTools, reportGenerator)
                .build();
    }

    public interface CodeExplorationAssistant {
        @SystemMessage("""
You are a code exploration expert. Your goal is to understand code and generate a CONCISE report.

IMPORTANT: You MUST call generateReport() when you have gathered sufficient context to write the report.

EXPLORATION WORKFLOW:
1. Start with findProjectDependencies() to understand frameworks/libraries
2. Analyze target code structure
3. Explore related files and dependencies (use searchCode with context lines, NOT readFile for full files)
4. Take notes on key findingsf
5. Call generateReport() to create the final concise report

RESPONSE TEMPLATE:
📍 Phase: [Discovery|Analysis|Complete]
🔍 Exploring: [Current focus]
📊 Found: [Key findings - brief bullet points]
🎯 Next: [Specific next action]
💰 Budget: [X/N tool calls used]

EXPLORATION STRATEGY:
- Use searchCode() with beforeLines/afterLines to get targeted snippets (NOT full files)
- Use takeNote() to record patterns, insights, and important findings
- Focus on understanding, not collecting everything
- When you have enough understanding, call generateReport()

TOOLS AVAILABLE:
- findProjectDependencies(): Analyze build files
- analyzeClass(className): Deep class analysis
- searchCode(query, filePattern, excludePattern, beforeLines, afterLines): Get code snippets with context
- findFiles(pattern): Find files by pattern
- listFiles(directory, recursiveLevel): List directory
- readFile(filePath): Read files (use sparingly, prefer searchCode)
- lookupMethod(className, methodName): Find method signatures
- lookupClass(className): Find class structure
- takeNote(note): Record findings (use this frequently!)
- generateReport(): Generate final concise report from your notes and findings

WHEN TO CALL generateReport():
- After you've explored the target and related code
- After you've taken notes on key patterns and insights
- When you have enough understanding to write a useful report
- Tool budget is running low

The generateReport() tool will use your notes and findings to create a concise markdown report.
""")
        @dev.langchain4j.agentic.Agent
        String exploreCode(String request);
    }

    public CompletableFuture<CodeContextReport> exploreContext(
            String target,
            CodeContextReport.Scope scope,
            CodeContextReport.Focus focus) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                LOG.debug("Starting code context exploration for: " + target);

                contextTools.reset();
                CodeContextReport report = new CodeContextReport(target, scope, focus);

                String request = buildExplorationRequest(target, scope, focus);

                sendToUI("🔍 Exploring code context...\n\n");
                sendToUI("📋 Target: " + target + "\n");
                sendToUI("📋 Scope: " + scope + ", Focus: " + focus + "\n\n");
                sendToUI("🤖 Starting exploration:\n");
                sendToUI("-".repeat(60) + "\n");

                int maxIterations = 3;
                int iteration = 0;

                while (!contextTools.isContextCollectionDone() && !reportGenerator.isReportGenerated() && iteration < maxIterations) {
                    iteration++;

                    try {
                        String promptToUse = (iteration == 1) ? request :
                                "Continue exploration. Call generateReport() when you have sufficient context.";

                        String response = assistant.exploreCode(promptToUse);
                        sendToUI(response);
                        sendToUI("\n" + "-".repeat(60) + "\n");

                        if (reportGenerator.isReportGenerated()) {
                            sendToUI("✅ Report generated successfully.\n");
                            break;
                        }

                        if (contextTools.isContextCollectionDone()) {
                            sendToUI("✅ Context exploration complete.\n");
                            break;
                        }

                        if (iteration < maxIterations) {
                            sendToUI("\n🔄 Continuing exploration (iteration " + (iteration + 1) + ")...\n");
                        }

                    } catch (Exception e) {
                        LOG.warn("Context agent encountered an error", e);
                        sendToUI("\n⚠️ Error: " + e.getMessage() + "\n");
                        break;
                    }
                }

                if (iteration >= maxIterations && !reportGenerator.isReportGenerated()) {
                    LOG.warn("Exploration reached max iterations without report");
                    sendToUI("\n⚠️ Reached max iterations. Generating report from current findings...\n");
                    String finalReport = reportGenerator.generateReport();
                    sendToUI(finalReport + "\n");
                }

                populateReport(report);
                return report;

            } catch (Exception e) {
                LOG.error("Failed to explore context", e);
                sendToUI("\n❌ Exploration failed: " + e.getMessage() + "\n");
                throw new RuntimeException("Context exploration failed: " + e.getMessage(), e);
            }
        });
    }

    private String  buildExplorationRequest(String target, CodeContextReport.Scope scope, CodeContextReport.Focus focus) {
        StringBuilder request = new StringBuilder();
        request.append("Explore code context with detailed analysis.\n");
        request.append("Tool budget: ").append(maxToolCalls).append(" calls per response.\n\n");

        request.append("Target: ").append(target).append("\n");
        request.append("Scope: ").append(scope).append("\n");
        request.append("Focus: ").append(focus).append("\n\n");

        request.append("Provide detailed context including:\n");
        request.append("- Architecture and design patterns\n");
        request.append("- Dependencies (internal and external)\n");
        request.append("- Usage patterns and examples\n");
        request.append("- Code snippets (actual code, not summaries)\n");
        request.append("- Integration points\n");
        request.append("- Important insights and notes\n\n");

        request.append("Start by calling findProjectDependencies(), then explore the target.\n");

        return request.toString();
    }

    private void populateReport(CodeContextReport report) {
        // If AI generated a report, use that directly
        if (reportGenerator.isReportGenerated()) {
            String generatedReport = reportGenerator.getGeneratedReport();
            report.setSummary(generatedReport);
            LOG.info("Using LLM-generated report");
            return;
        }

        // Fallback: populate from raw data if no report was generated
        LOG.info("No LLM report generated, using raw data");
        Map<String, String> analyzedClasses = contextTools.getAnalyzedClasses();
        List<String> contextNotes = contextTools.getContextNotes();

        StringBuilder summaryBuilder = new StringBuilder();

        for (String note : contextNotes) {
            if (note.contains("[DEPENDENCY]")) {
                report.addDependency(note.replaceFirst("\\[DEPENDENCY\\]", "").trim());
            } else {
                report.addInsight(note);
            }

            if (summaryBuilder.length() == 0 && note.length() > 50) {
                summaryBuilder.append(note.substring(0, Math.min(200, note.length())));
            }
        }

        report.setSummary(summaryBuilder.toString().trim());

        for (String filePath : analyzedClasses.keySet()) {
            report.addRelatedFile(filePath);
        }
    }

    public MessageWindowChatMemory getChatMemory() {
        return chatMemory;
    }
}
