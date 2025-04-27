package com.zps.zest.browser;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.zps.zest.InteractiveAgentService;

/**
 * Integrates the web browser with the AI assistant.
 */
public class BrowserIntegrator {
    private static final Logger LOG = Logger.getInstance(BrowserIntegrator.class);
    
    /**
     * Integrates the browser with the AI assistant.
     */
    public static void integrate(Project project) {
        try {
            LOG.info("Integrating browser with AI assistant");
            
            // Add response listener
            InteractiveAgentService agentService = InteractiveAgentService.getInstance(project);
            agentService.addListener((request, response) -> 
                BrowserIntegrationManager.processAiResponse(project, request, response)
            );
            
            LOG.info("Browser integration complete");
        } catch (Exception e) {
            LOG.error("Error integrating browser with AI assistant", e);
        }
    }
}
