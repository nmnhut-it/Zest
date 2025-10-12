package com.zps.zest.context;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.naive_service.NaiveLLMService;
import dev.langchain4j.agent.tool.Tool;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * Tool for exploring code context autonomously.
 * Wraps CodeContextAgent and exposes it as a @Tool for external AI applications.
 */
public class ExploreContextTool {
    private static final Logger LOG = Logger.getInstance(ExploreContextTool.class);
    private static final int DEFAULT_MAX_TOOL_CALLS = 20;

    private final Project project;
    private final ZestLangChain4jService langChainService;
    private final NaiveLLMService naiveLlmService;

    public ExploreContextTool(@NotNull Project project,
                             @NotNull ZestLangChain4jService langChainService,
                             @NotNull NaiveLLMService naiveLlmService) {
        this.project = project;
        this.langChainService = langChainService;
        this.naiveLlmService = naiveLlmService;
    }

    @Tool("""
        Explore code context autonomously and generate detailed report.

        The agent will autonomously search, read files, analyze classes, and gather comprehensive context
        about the target code including architecture, dependencies, usage patterns, and insights.

        Parameters:
        - target: File path or fully qualified class name to explore
        - scope: Exploration scope - one of: class, method, feature, package
        - focus: What to focus on - one of: architecture, usage, dependencies, all
        - maxToolCalls: Maximum number of tool calls for exploration (default: 20)

        Returns: Detailed markdown report with:
        - Overview and summary
        - Architecture and design patterns
        - Key code snippets (actual code)
        - Dependencies (internal and external)
        - Usage patterns and examples
        - Important insights and notes
        - Related files

        Example:
        - exploreContext("com.example.UserService", "class", "all", 25)
        - exploreContext("src/main/java/Service.java", "class", "architecture", 15)
        """)
    public String exploreContext(String target, String scope, String focus, Integer maxToolCalls) {
        LOG.info("ExploreContextTool called with target: " + target + ", scope: " + scope + ", focus: " + focus);

        try {
            if (target == null || target.trim().isEmpty()) {
                return "Error: target cannot be null or empty";
            }

            CodeContextReport.Scope reportScope = parseScope(scope);
            CodeContextReport.Focus reportFocus = parseFocus(focus);
            int toolCallLimit = (maxToolCalls != null && maxToolCalls > 0) ? maxToolCalls : DEFAULT_MAX_TOOL_CALLS;

            CodeContextAgent agent = new CodeContextAgent(
                    project,
                    langChainService,
                    naiveLlmService,
                    toolCallLimit
            );

            CompletableFuture<CodeContextReport> future = agent.exploreContext(target, reportScope, reportFocus);

            CodeContextReport report = future.join();

            String markdown = report.toMarkdown();
            LOG.info("Context exploration completed. Report length: " + markdown.length());

            return markdown;

        } catch (Exception e) {
            LOG.error("Error exploring context", e);
            return "Error exploring context: " + e.getMessage();
        }
    }

    private CodeContextReport.Scope parseScope(String scope) {
        if (scope == null || scope.trim().isEmpty()) {
            return CodeContextReport.Scope.CLASS;
        }

        try {
            return CodeContextReport.Scope.valueOf(scope.toUpperCase());
        } catch (IllegalArgumentException e) {
            LOG.warn("Invalid scope: " + scope + ", defaulting to CLASS");
            return CodeContextReport.Scope.CLASS;
        }
    }

    private CodeContextReport.Focus parseFocus(String focus) {
        if (focus == null || focus.trim().isEmpty()) {
            return CodeContextReport.Focus.ALL;
        }

        try {
            return CodeContextReport.Focus.valueOf(focus.toUpperCase());
        } catch (IllegalArgumentException e) {
            LOG.warn("Invalid focus: " + focus + ", defaulting to ALL");
            return CodeContextReport.Focus.ALL;
        }
    }
}
