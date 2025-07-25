package com.zps.zest.langchain4j.agent.network;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

/**
 * Startup activity that automatically starts the agent proxy server for each project.
 */
public class ProxyStartupActivity implements StartupActivity {
    private static final Logger LOG = Logger.getInstance(ProxyStartupActivity.class);
    
    @Override
    public void runActivity(@NotNull Project project) {
        // Skip for default project
        if (project.isDefault()) {
            return;
        }
        
        LOG.info("Starting proxy server for project: " + project.getName());
        
        try {
            // Start proxy for this project
            ProjectProxyManager manager = ProjectProxyManager.getInstance();
            int port = manager.startProxyForProject(project);
            
            if (port > 0) {
                LOG.info("Proxy server started successfully for project " + project.getName() + 
                        " on port " + port);
                
                // Set the proxy URL for this project
                String proxyUrl = manager.getProxyUrlForProject(project);
                if (proxyUrl != null) {
                    // This will be used by WebBrowserPanel and other components
                    System.setProperty("zest.agent.proxy.url", proxyUrl);
                    LOG.info("Set proxy URL for project: " + proxyUrl);
                }
            } else {
                LOG.warn("Failed to start proxy server for project " + project.getName());
            }
        } catch (Exception e) {
            LOG.error("Error starting proxy server for project " + project.getName(), e);
        }
    }
}