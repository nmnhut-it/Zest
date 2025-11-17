package com.zps.zest.mcp;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

/**
 * Starts the Zest MCP HTTP Server when the first project opens.
 * The server runs at the application level and serves all projects via HTTP.
 */
public class ZestMcpToolServerStartupActivity implements StartupActivity.Background {
    private static final Logger LOG = Logger.getInstance(ZestMcpToolServerStartupActivity.class);
    private static volatile boolean serverStarted = false;

    @Override
    public void runActivity(@NotNull Project project) {
        if (!serverStarted) {
            synchronized (ZestMcpToolServerStartupActivity.class) {
                if (!serverStarted) {
                    LOG.info("Starting Zest MCP HTTP Server for project: " + project.getName());
                    ApplicationManager.getApplication().executeOnPooledThread(() -> {
                        try {
                            ZestMcpToolServerService service = ZestMcpToolServerService.getInstance();
                            service.startServer();
                            serverStarted = true;
                            LOG.info("Zest MCP HTTP Server started successfully");
                        } catch (Exception e) {
                            LOG.error("Failed to start Zest MCP HTTP Server", e);
                        }
                    });
                }
            }
        }
    }
}
