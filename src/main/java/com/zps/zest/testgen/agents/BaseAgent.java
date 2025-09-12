package com.zps.zest.testgen.agents;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.browser.utils.ChatboxUtilities;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.util.NaiveLLMService;
import org.jetbrains.annotations.NotNull;

public abstract class BaseAgent {
    protected static final Logger LOG = Logger.getInstance(BaseAgent.class);
    
    protected final Project project;
    protected final ZestLangChain4jService langChainService;
    protected final NaiveLLMService naiveLlmService;
    protected final String agentName;
    
    protected BaseAgent(@NotNull Project project,
                       @NotNull ZestLangChain4jService langChainService,
                       @NotNull NaiveLLMService naiveLlmService,
                       @NotNull String agentName) {
        this.project = project;
        this.langChainService = langChainService;
        this.naiveLlmService = naiveLlmService;
        this.agentName = agentName;
    }

    /**
     * Execute LLM query with standard parameters
     */
    @NotNull
    protected String queryLLM(@NotNull String prompt, int maxTokens) {
        NaiveLLMService.LLMQueryParams params = new NaiveLLMService.LLMQueryParams(prompt)
            .withModel("local-model")
            .withMaxTokens(maxTokens)
            .withTimeout(45000);
        
        String response = naiveLlmService.queryWithParams(params, ChatboxUtilities.EnumUsage.AGENT_TEST_WRITING);
        return response != null ? response.trim() : "";
    }

}