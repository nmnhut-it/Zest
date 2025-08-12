package com.zps.zest.langchain4j.agent.network;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Stub implementation of AgentProxyServer since agent functionality has been removed.
 * This class exists to prevent compilation errors but provides no functionality.
 */
public class AgentProxyServerStub {
    private static final Logger LOG = Logger.getInstance(AgentProxyServerStub.class);
    
    public AgentProxyServerStub(Project project, int port) {
        LOG.info("AgentProxyServer functionality has been removed - stub implementation");
    }
    
    public void start() {
        LOG.info("AgentProxyServer start() - no-op (functionality removed)");
    }
    
    public void stop() {
        LOG.info("AgentProxyServer stop() - no-op (functionality removed)");
    }
    
    public boolean isRunning() {
        return false;
    }
}