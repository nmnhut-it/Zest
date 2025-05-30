package com.zps.zest.research;

import com.intellij.openapi.project.Project;
import com.zps.zest.browser.AgentModeContextEnhancer;
import com.zps.zest.research.ResearchAgent;
import java.util.concurrent.CompletableFuture;

/**
 * Factory to create context enhancers based on configuration.
 */
public class ContextEnhancerFactory {
    
    // System property or configuration to control which implementation to use
    private static final String USE_RESEARCH_AGENT_PROPERTY = "zest.use.research.agent";
    
    public static ContextEnhancer createEnhancer(Project project) {
        boolean useResearchAgent = true; // Boolean.getBoolean(USE_RESEARCH_AGENT_PROPERTY);
        
        if (useResearchAgent) {
            return new ResearchAgentAdapter(project);
        } else {
            return new AgentModeContextEnhancerAdapter(project);
        }
    }
    
    /**
     * Common interface for context enhancers.
     */
    public interface ContextEnhancer {
        CompletableFuture<String> enhancePromptWithContext(String userQuery, String currentFileContext);
        void dispose();
    }
    
    /**
     * Adapter for ResearchAgent.
     */
    private static class ResearchAgentAdapter implements ContextEnhancer {
        private final ResearchAgent agent;
        
        public ResearchAgentAdapter(Project project) {
            this.agent = new ResearchAgent(project);
        }
        
        @Override
        public CompletableFuture<String> enhancePromptWithContext(String userQuery, String currentFileContext) {
            return agent.research(userQuery, currentFileContext);
        }
        
        @Override
        public void dispose() {
            agent.dispose();
        }
    }
    
    /**
     * Adapter for AgentModeContextEnhancer.
     */
    private static class AgentModeContextEnhancerAdapter implements ContextEnhancer {
        private final AgentModeContextEnhancer enhancer;
        
        public AgentModeContextEnhancerAdapter(Project project) {
            this.enhancer = new AgentModeContextEnhancer(project);
        }
        
        @Override
        public CompletableFuture<String> enhancePromptWithContext(String userQuery, String currentFileContext) {
            return enhancer.enhancePromptWithContext(userQuery, currentFileContext);
        }
        
        @Override
        public void dispose() {
            enhancer.dispose();
        }
    }
}