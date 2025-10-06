package com.zps.zest.mcp;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.zps.zest.ConfigurationManager;
import org.jetbrains.annotations.NotNull;

/**
 * Startup activity that automatically starts the Tool API Server.
 * Respects the toolServerEnabled setting in project configuration.
 */
public class ToolApiServerStartupActivity implements StartupActivity {
    private static final Logger LOG = Logger.getInstance(ToolApiServerStartupActivity.class);

    @Override
    public void runActivity(@NotNull Project project) {
        // Skip for default project
        if (project.isDefault()) {
            return;
        }

        // Check if tool server is enabled in settings
        ConfigurationManager config = ConfigurationManager.getInstance(project);
        if (!config.isToolServerEnabled()) {
            LOG.info("Tool API Server disabled for project: " + project.getName());
            return;
        }

        LOG.info("Starting Tool API Server for project: " + project.getName());

        try {
            ToolApiServerService service = project.getService(ToolApiServerService.class);
            int port = service.startServer();

            if (port > 0) {
                LOG.info("Tool API Server started successfully on port " + port);
                LOG.info("OpenAPI schema: http://localhost:" + port + "/openapi.json");

                // Store tool server URL in system property for this project
                String toolServerUrl = "http://localhost:" + port;
                System.setProperty("zest.tool.server.url", toolServerUrl);
                System.setProperty("zest.tool.server.url." + sanitizeProjectName(project.getName()), toolServerUrl);
            } else {
                LOG.warn("Failed to start Tool API Server for project " + project.getName());
            }
        } catch (Exception e) {
            LOG.error("Error starting Tool API Server", e);
        }
    }

    /**
     * Sanitize project name for use in system property key.
     */
    private String sanitizeProjectName(String name) {
        return name.replaceAll("[^a-zA-Z0-9]", "_");
    }
}
