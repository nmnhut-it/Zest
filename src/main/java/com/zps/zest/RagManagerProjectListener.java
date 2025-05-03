package com.zps.zest;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManagerListener;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Project listener that initializes and shuts down RAG managers for projects.
 * Ensures RAG files persist across project sessions.
 */
public class RagManagerProjectListener implements ProjectManagerListener {
    private static final Logger LOG = Logger.getInstance(RagManagerProjectListener.class);

    // Store one manager per project
    private static final Map<String, PersistentRagManager> managers = new ConcurrentHashMap<>();

    @SuppressWarnings("removal")
    @Override
    public void projectOpened(@NotNull Project project) {
        LOG.info("Project opened: " + project.getName());

        try {
            // Get configuration
            ConfigurationManager config = ConfigurationManager.getInstance(project);

            // Only initialize if RAG is enabled
            if (config.isRagEnabled()) {
                String apiUrl = config.getOpenWebUIRagEndpoint();
                String authToken = config.getAuthToken();

                if (apiUrl != null && !apiUrl.isEmpty() && authToken != null && !authToken.isEmpty()) {
                    // Create and initialize manager
                    PersistentRagManager manager = new PersistentRagManager(project, apiUrl, authToken);
                    manager.initialize();

                    // Store in map
                    managers.put(project.getLocationHash(), manager);
                    LOG.info("RAG manager initialized for project: " + project.getName());
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to initialize RAG manager for project: " + project.getName(), e);
        }
    }

    @Override
    public void projectClosing(@NotNull Project project) {
        LOG.info("Project closing: " + project.getName());

        try {
            // Get manager for this project
            PersistentRagManager manager = managers.get(project.getLocationHash());

            if (manager != null) {
                // Shut down manager
                manager.shutdown();

                // Remove from map
                managers.remove(project.getLocationHash());
                LOG.info("RAG manager shut down for project: " + project.getName());
            }
        } catch (Exception e) {
            LOG.error("Failed to shut down RAG manager for project: " + project.getName(), e);
        }
    }

    /**
     * Gets the RAG manager for a project.
     */
    public static PersistentRagManager getManager(Project project) {
        if (project == null) return null;
        return managers.get(project.getLocationHash());
    }
}