package com.zps.zest;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManagerListener;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.zps.zest.rag.FileChangeListener;
import com.zps.zest.rag.OpenWebUIRagAgent;
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
    // Store file change listeners per project
    private static final Map<String, FileChangeListener> fileListeners = new ConcurrentHashMap<>();

    @SuppressWarnings("removal")
    @Override
    public void projectOpened(@NotNull Project project) {
        LOG.info("Project opened: " + project.getName());

        try {
            // Get configuration
            ConfigurationManager config = ConfigurationManager.getInstance(project);

            // Check if project needs RAG indexing
            if (config.getKnowledgeId() == null) {
                // Schedule indexing after project is fully loaded
                ApplicationManager.getApplication().invokeLater(() -> {
                    // Don't auto-prompt anymore, let user use the button in browser
                    LOG.info("Project not indexed. User can index via browser button.");
                });
            } else {
                // Project is indexed, set up file change listener
                setupFileChangeListener(project);
            }

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
            // Stop file change listener
            FileChangeListener fileListener = fileListeners.get(project.getLocationHash());
            if (fileListener != null) {
                VirtualFileManager.getInstance().removeVirtualFileListener(fileListener);
                fileListener.shutdown();
                fileListeners.remove(project.getLocationHash());
                LOG.info("File change listener shut down for project: " + project.getName());
            }
            
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
    
    /**
     * Sets up the file change listener for a project.
     */
    private static void setupFileChangeListener(Project project) {
        String projectKey = project.getLocationHash();
        
        // Check if listener already exists
        if (fileListeners.containsKey(projectKey)) {
            return;
        }
        
        // Create and register file change listener
        FileChangeListener listener = new FileChangeListener(project);
        VirtualFileManager.getInstance().addVirtualFileListener(listener);
        fileListeners.put(projectKey, listener);
        
        LOG.info("File change listener set up for project: " + project.getName());
    }
    
    /**
     * Sets up file change listener after indexing completes.
     * This is called by the indexing process.
     */
    public static void onIndexingComplete(Project project) {
        setupFileChangeListener(project);
    }
}