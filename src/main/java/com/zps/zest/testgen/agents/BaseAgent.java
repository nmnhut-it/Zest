package com.zps.zest.testgen.agents;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.browser.utils.ChatboxUtilities;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.util.LLMService;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public abstract class BaseAgent {
    protected static final Logger LOG = Logger.getInstance(BaseAgent.class);
    
    protected final Project project;
    protected final ZestLangChain4jService langChainService;
    protected final LLMService llmService;
    protected final String agentName;
    
    protected BaseAgent(@NotNull Project project,
                       @NotNull ZestLangChain4jService langChainService,
                       @NotNull LLMService llmService,
                       @NotNull String agentName) {
        this.project = project;
        this.langChainService = langChainService;
        this.llmService = llmService;
        this.agentName = agentName;
    }

    /**
     * Determine what action to take based on reasoning
     */
    @NotNull
    protected abstract AgentAction determineAction(@NotNull String reasoning, @NotNull String observation);
    
    /**
     * Execute the determined action
     */
    @NotNull
    protected abstract String executeAction(@NotNull AgentAction action);
    
    /**
     * Build the reasoning prompt for the agent
     */
    @NotNull
    protected String buildReasoningPrompt(@NotNull String task, @NotNull String observation, @NotNull List<ReActStep> previousSteps) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("You are ").append(getAgentDescription()).append(".\n\n");
        prompt.append("Task: ").append(task).append("\n\n");
        prompt.append("Current Observation:\n").append(observation).append("\n\n");
        
        if (!previousSteps.isEmpty()) {
            prompt.append("Previous Steps:\n");
            for (int i = 0; i < previousSteps.size(); i++) {
                ReActStep step = previousSteps.get(i);
                prompt.append("Step ").append(i + 1).append(":\n");
                prompt.append("  Reasoning: ").append(step.getReasoning()).append("\n");
                prompt.append("  Action: ").append(step.getAction().getDescription()).append("\n");
                prompt.append("  Observation: ").append(step.getObservation().substring(0, Math.min(200, step.getObservation().length()))).append("\n\n");
            }
        }
        
        prompt.append("Think step by step about what you should do next to complete the task. ");
        prompt.append("Consider the available actions and what information you still need.\n");
        prompt.append("Reasoning:");
        
        return prompt.toString();
    }
    
    /**
     * Get agent description for prompts
     */
    @NotNull
    protected abstract String getAgentDescription();
    
    /**
     * Get available actions for this agent
     */
    @NotNull
    protected abstract List<AgentAction.ActionType> getAvailableActions();
    
    /**
     * Execute LLM query with standard parameters
     */
    @NotNull
    protected String queryLLM(@NotNull String prompt, int maxTokens) {
        LLMService.LLMQueryParams params = new LLMService.LLMQueryParams(prompt)
            .withModel("local-model")
            .withMaxTokens(maxTokens)
            .withTimeout(45000);
        
        String response = llmService.queryWithParams(params, ChatboxUtilities.EnumUsage.AGENT_TEST_WRITING);
        return response != null ? response.trim() : "";
    }
    
    /**
     * ReAct step data structure
     */
    protected static class ReActStep {
        private final String reasoning;
        private final AgentAction action;
        private final String observation;
        
        public ReActStep(@NotNull String reasoning, @NotNull AgentAction action, @NotNull String observation) {
            this.reasoning = reasoning;
            this.action = action;
            this.observation = observation;
        }
        
        @NotNull
        public String getReasoning() {
            return reasoning;
        }
        
        @NotNull
        public AgentAction getAction() {
            return action;
        }
        
        @NotNull
        public String getObservation() {
            return observation;
        }
    }
    
    /**
     * Agent action data structure
     */
    protected static class AgentAction {
        private final ActionType type;
        private final String description;
        private final String parameters;
        
        public enum ActionType {
            ANALYZE("Analyze code structure"),
            SEARCH("Search for information"),
            GENERATE("Generate content"),
            VALIDATE("Validate results"),
            COMPLETE("Task completed"),
            ERROR("Error occurred");
            
            private final String defaultDescription;
            
            ActionType(String defaultDescription) {
                this.defaultDescription = defaultDescription;
            }
            
            public String getDefaultDescription() {
                return defaultDescription;
            }
        }
        
        public AgentAction(@NotNull ActionType type, @NotNull String description, @NotNull String parameters) {
            this.type = type;
            this.description = description;
            this.parameters = parameters;
        }
        
        public AgentAction(@NotNull ActionType type, @NotNull String parameters) {
            this(type, type.getDefaultDescription(), parameters);
        }
        
        @NotNull
        public ActionType getType() {
            return type;
        }
        
        @NotNull
        public String getDescription() {
            return description;
        }
        
        @NotNull
        public String getParameters() {
            return parameters;
        }
    }
}