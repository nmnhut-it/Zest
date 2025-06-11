package com.zps.zest.rag;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.*;
import com.zps.zest.ConfigurationManager;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Listens for file changes and triggers index updates for the OpenWebUI knowledge base.
 */
public class FileChangeListener implements VirtualFileListener {
    private static final Logger LOG = Logger.getInstance(FileChangeListener.class);
    
    private final Project project;
    private final Set<VirtualFile> pendingUpdates = new HashSet<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private static final int UPDATE_DELAY_SECONDS = 5; // Wait 5 seconds after last change before updating
    
    private volatile boolean updateScheduled = false;
    
    public FileChangeListener(Project project) {
        this.project = project;
    }
    
    @Override
    public void contentsChanged(@NotNull VirtualFileEvent event) {
        handleFileChange(event.getFile());
    }
    
    @Override
    public void fileCreated(@NotNull VirtualFileEvent event) {
        handleFileChange(event.getFile());
    }
    
    @Override
    public void fileDeleted(@NotNull VirtualFileEvent event) {
        handleFileChange(event.getFile());
    }
    
    @Override
    public void fileMoved(@NotNull VirtualFileMoveEvent event) {
        handleFileChange(event.getFile());
    }
    
    private void handleFileChange(VirtualFile file) {
        // Check if this project has a knowledge ID (is indexed)
        ConfigurationManager config = ConfigurationManager.getInstance(project);
        if (config.getKnowledgeId() == null) {
            return; // Project not indexed yet
        }
        
        // Only process source files
        if (!isSourceFile(file)) {
            return;
        }
        
        // Add to pending updates
        synchronized (pendingUpdates) {
            pendingUpdates.add(file);
        }
        
        // Schedule an update (or reschedule if one is already pending)
        scheduleUpdate();
    }
    
    private boolean isSourceFile(VirtualFile file) {
        if (file == null || file.isDirectory()) {
            return false;
        }
        
        String extension = file.getExtension();
        return extension != null && (
            extension.equals("java") || 
            extension.equals("kt") || 
            extension.equals("scala") ||
            extension.equals("groovy") ||
            extension.equals("js") ||
            extension.equals("jsx") ||
            extension.equals("ts") ||
            extension.equals("tsx") ||
            extension.equals("mjs") ||
            extension.equals("cjs")
        );
    }
    
    private void scheduleUpdate() {
        synchronized (this) {
            if (updateScheduled) {
                return; // Update already scheduled
            }
            updateScheduled = true;
        }
        
        scheduler.schedule(() -> {
            try {
                performUpdate();
            } finally {
                synchronized (this) {
                    updateScheduled = false;
                }
            }
        }, UPDATE_DELAY_SECONDS, TimeUnit.SECONDS);
    }
    
    private void performUpdate() {
        Set<VirtualFile> filesToUpdate;
        synchronized (pendingUpdates) {
            if (pendingUpdates.isEmpty()) {
                return;
            }
            filesToUpdate = new HashSet<>(pendingUpdates);
            pendingUpdates.clear();
        }
        
        LOG.info("Updating OpenWebUI index for " + filesToUpdate.size() + " changed files");
        
        // Get the RAG agent and update the specific files
        OpenWebUIRagAgent ragAgent = OpenWebUIRagAgent.getInstance(project);
        ConfigurationManager config = ConfigurationManager.getInstance(project);
        String knowledgeId = config.getKnowledgeId();
        
        if (knowledgeId != null) {
            for (VirtualFile file : filesToUpdate) {
                try {
                    ragAgent.updateFileInIndex(knowledgeId, file);
                } catch (IOException e) {
                    // Check if this is a browser not available error
                    if (e.getMessage() != null && 
                        (e.getMessage().contains("Browser panel not initialized") || 
                         e.getMessage().contains("Browser service not available") ||
                         e.getMessage().contains("Browser manager not available"))) {
                        LOG.info("Browser not ready for file indexing, will retry later: " + file.getPath());
                        // Re-add to pending updates for next iteration
                        synchronized (pendingUpdates) {
                            pendingUpdates.add(file);
                        }
                        // Schedule another update in case browser becomes available
                        scheduleUpdate();
                    } else {
                        LOG.error("Failed to update file in index: " + file.getPath(), e);
                    }
                } catch (Exception e) {
                    LOG.error("Failed to update file in index: " + file.getPath(), e);
                }
            }
        }
    }
    
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
    }
}
