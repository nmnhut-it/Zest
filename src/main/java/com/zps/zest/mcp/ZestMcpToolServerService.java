package com.zps.zest.mcp;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;

/**
 * Application-level service that manages the Zest MCP HTTP Server.
 * Runs a single HTTP server that serves all open IntelliJ projects.
 */
@Service(Service.Level.APP)
public final class ZestMcpToolServerService implements Disposable {
    private static final Logger LOG = Logger.getInstance(ZestMcpToolServerService.class);
    private static final int DEFAULT_PORT = 45450;

    private ZestMcpHttpServer httpServer;
    private boolean running = false;

    public static ZestMcpToolServerService getInstance() {
        return com.intellij.openapi.application.ApplicationManager.getApplication()
                .getService(ZestMcpToolServerService.class);
    }

    public synchronized void startServer() {
        if (running) {
            LOG.info("Zest MCP HTTP Server already running");
            return;
        }

        try {
            httpServer = new ZestMcpHttpServer(DEFAULT_PORT);
            httpServer.start();
            running = true;

            LOG.info("✅ Zest MCP HTTP Server started on port " + DEFAULT_PORT);
            LOG.info("📋 Available tools: getCurrentFile, lookupMethod, lookupClass");
            LOG.info("🌐 Server URL: http://localhost:" + DEFAULT_PORT);

        } catch (Exception e) {
            LOG.error("Failed to start Zest MCP HTTP Server", e);
            running = false;
        }
    }

    public synchronized void stopServer() {
        if (!running) {
            return;
        }

        try {
            if (httpServer != null) {
                httpServer.stop();
            }
            running = false;
            LOG.info("Zest MCP HTTP Server stopped");
        } catch (Exception e) {
            LOG.error("Error stopping Zest MCP HTTP Server", e);
        }
    }

    public boolean isRunning() {
        return running;
    }

    public String getServerUrl() {
        if (running) {
            return "http://localhost:" + DEFAULT_PORT + "/mcp";
        }
        return null;
    }

    public int getPort() {
        return DEFAULT_PORT;
    }

    @Override
    public void dispose() {
        stopServer();
    }
}
