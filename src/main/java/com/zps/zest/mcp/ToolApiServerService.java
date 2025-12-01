package com.zps.zest.mcp;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * Service that manages the simple Tool API Server lifecycle for each project.
 * Automatically starts the server when the project opens and stops it when the project closes.
 */
@Service(Service.Level.PROJECT)
public final class ToolApiServerService implements Disposable {
    private static final Logger LOG = Logger.getInstance(ToolApiServerService.class);
    private static final int BASE_PORT = 8765;
    private static final int MAX_PORT = 8865;

    private final Project project;
    private ToolApiServer server;
    private int assignedPort = -1;

    public ToolApiServerService(@NotNull Project project) {
        this.project = project;
    }

    /**
     * Start the Tool API Server on an available port.
     * @return the port number or -1 if failed
     */
    public synchronized int startServer() {
        if (server != null) {
            LOG.info("Tool API Server already running for project " + project.getName() + " on port " + assignedPort);
            return assignedPort;
        }

        // Find available port
        int port = findAvailablePort();
        if (port == -1) {
            LOG.error("Failed to find available port for Tool API Server");
            return -1;
        }

        try {
            server = new ToolApiServer(project, port);
            server.start();
            assignedPort = port;

            LOG.info("Tool API Server started for project " + project.getName() + " on port " + port);
            LOG.info("OpenAPI schema available at: http://localhost:" + port + "/openapi.json");

            return port;
        } catch (Exception e) {
            LOG.error("Failed to start Tool API Server", e);
            return -1;
        }
    }

    /**
     * Stop the Tool API Server.
     */
    public synchronized void stopServer() {
        if (server != null) {
            try {
                server.stop();
                LOG.info("Tool API Server stopped for project " + project.getName());
            } catch (Exception e) {
                LOG.error("Error stopping Tool API Server", e);
            } finally {
                server = null;
                assignedPort = -1;
            }
        }
    }

    /**
     * Check if the server is running.
     */
    public boolean isRunning() {
        return server != null;
    }

    /**
     * Get the port the server is running on.
     */
    public int getPort() {
        return assignedPort;
    }

    /**
     * Get the base URL of the server.
     */
    public String getBaseUrl() {
        return isRunning() ? "http://localhost:" + assignedPort : null;
    }

    /**
     * Find an available port in the range.
     */
    private int findAvailablePort() {
        for (int port = BASE_PORT; port <= MAX_PORT; port++) {
            if (isPortAvailable(port)) {
                return port;
            }
        }
        return -1;
    }

    /**
     * Check if a port is available.
     */
    private boolean isPortAvailable(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void dispose() {
        stopServer();
    }
}
