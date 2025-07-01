package com.zps.zest.browser.jcef;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerListener;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import org.jetbrains.annotations.NotNull;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * IntelliJ-native JCEF resource manager that properly tracks and disposes JCEF resources
 * without using dangerous process killing.
 */
@Service
public final class JCEFResourceManager implements Disposable {
    private static final Logger LOG = Logger.getInstance(JCEFResourceManager.class);
    
    // Track all active JCEF browsers
    private final Set<WeakReference<JBCefBrowser>> activeBrowsers = ConcurrentHashMap.newKeySet();
    
    // Track browsers by project
    private final Map<Project, Set<WeakReference<JBCefBrowser>>> projectBrowsers = new ConcurrentHashMap<>();
    
    // Scheduled executor for periodic cleanup
    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "JCEF-Resource-Cleanup");
        thread.setDaemon(true);
        return thread;
    });
    
    private JCEFResourceManager() {
        // Register project close listener
        ApplicationManager.getApplication().getMessageBus().connect(this)
            .subscribe(ProjectManager.TOPIC, new ProjectManagerListener() {
                @Override
                public void projectClosed(@NotNull Project project) {
                    cleanupProjectResources(project);
                }
            });
        
        // Schedule periodic cleanup of weak references
        cleanupExecutor.scheduleWithFixedDelay(this::performPeriodicCleanup, 30, 30, TimeUnit.SECONDS);
        
        // Register shutdown hook for application exit
        Disposer.register(ApplicationManager.getApplication(), this);
        
        LOG.info("JCEFResourceManager initialized");
    }
    
    public static JCEFResourceManager getInstance() {
        return ApplicationManager.getApplication().getService(JCEFResourceManager.class);
    }
    
    /**
     * Register a browser for tracking
     */
    public void registerBrowser(@NotNull JBCefBrowser browser, @NotNull Project project) {
        WeakReference<JBCefBrowser> ref = new WeakReference<>(browser);
        activeBrowsers.add(ref);
        
        projectBrowsers.computeIfAbsent(project, k -> ConcurrentHashMap.newKeySet()).add(ref);
        
        // Register disposal listener
        Disposer.register(browser, () -> {
            LOG.info("Browser disposed through Disposer framework");
            activeBrowsers.remove(ref);
            Set<WeakReference<JBCefBrowser>> projectSet = projectBrowsers.get(project);
            if (projectSet != null) {
                projectSet.remove(ref);
            }
        });
        
        LOG.info("Registered JCEF browser for project: " + project.getName());
    }
    
    /**
     * Clean up resources for a specific project
     */
    private void cleanupProjectResources(@NotNull Project project) {
        LOG.info("Cleaning up JCEF resources for project: " + project.getName());
        
        Set<WeakReference<JBCefBrowser>> browsers = projectBrowsers.remove(project);
        if (browsers != null) {
            for (WeakReference<JBCefBrowser> ref : browsers) {
                JBCefBrowser browser = ref.get();
                if (browser != null && !Disposer.isDisposed(browser)) {
                    try {
                        // Navigate to blank page first
                        browser.getCefBrowser().loadURL("about:blank");
                        
                        // Close the browser properly
                        browser.getCefBrowser().close(true);
                        
                        // Dispose through IntelliJ's framework
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (!Disposer.isDisposed(browser)) {
                                Disposer.dispose(browser);
                            }
                        });
                    } catch (Exception e) {
                        LOG.warn("Error disposing browser for project " + project.getName(), e);
                    }
                }
                activeBrowsers.remove(ref);
            }
        }
    }
    
    /**
     * Perform periodic cleanup of weak references and check for leaked resources
     */
    private void performPeriodicCleanup() {
        try {
            // Clean up cleared weak references
            activeBrowsers.removeIf(ref -> ref.get() == null);
            
            // Clean up project maps
            for (Map.Entry<Project, Set<WeakReference<JBCefBrowser>>> entry : projectBrowsers.entrySet()) {
                entry.getValue().removeIf(ref -> ref.get() == null);
                
                // Remove empty sets
                if (entry.getValue().isEmpty()) {
                    projectBrowsers.remove(entry.getKey());
                }
            }
            
            // Log current resource usage
            int activeBrowserCount = (int) activeBrowsers.stream()
                .filter(ref -> ref.get() != null)
                .count();
            
            if (activeBrowserCount > 0) {
                LOG.debug("Active JCEF browsers: " + activeBrowserCount);
            }
            
            // Check for potential leaks (browsers in disposed projects)
            for (Map.Entry<Project, Set<WeakReference<JBCefBrowser>>> entry : projectBrowsers.entrySet()) {
                if (entry.getKey().isDisposed()) {
                    LOG.warn("Found browsers in disposed project, cleaning up: " + entry.getKey().getName());
                    cleanupProjectResources(entry.getKey());
                }
            }
            
        } catch (Exception e) {
            LOG.error("Error during periodic JCEF cleanup", e);
        }
    }
    
    /**
     * Force cleanup of all JCEF resources (use with caution)
     */
    public void forceCleanupAll() {
        LOG.info("Force cleanup of all JCEF resources requested");
        
        for (WeakReference<JBCefBrowser> ref : new ArrayList<>(activeBrowsers)) {
            JBCefBrowser browser = ref.get();
            if (browser != null && !Disposer.isDisposed(browser)) {
                try {
                    browser.getCefBrowser().loadURL("about:blank");
                    browser.getCefBrowser().close(true);
                    
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (!Disposer.isDisposed(browser)) {
                            Disposer.dispose(browser);
                        }
                    });
                } catch (Exception e) {
                    LOG.warn("Error during force cleanup", e);
                }
            }
        }
        
        activeBrowsers.clear();
        projectBrowsers.clear();
        
        // Request garbage collection
        System.gc();
    }
    
    /**
     * Get statistics about current JCEF resource usage
     */
    public Map<String, Object> getResourceStats() {
        Map<String, Object> stats = new HashMap<>();
        
        int totalBrowsers = (int) activeBrowsers.stream()
            .filter(ref -> ref.get() != null)
            .count();
        
        Map<String, Integer> browsersByProject = new HashMap<>();
        for (Map.Entry<Project, Set<WeakReference<JBCefBrowser>>> entry : projectBrowsers.entrySet()) {
            int count = (int) entry.getValue().stream()
                .filter(ref -> ref.get() != null)
                .count();
            if (count > 0) {
                browsersByProject.put(entry.getKey().getName(), count);
            }
        }
        
        stats.put("totalActiveBrowsers", totalBrowsers);
        stats.put("browsersByProject", browsersByProject);
        stats.put("jcefInitialized", JBCefApp.isSupported() && JBCefApp.isStarted());
        
        return stats;
    }
    
    @Override
    public void dispose() {
        LOG.info("Disposing JCEFResourceManager");
        
        // Stop the cleanup executor
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Clean up all resources
        forceCleanupAll();
        
        LOG.info("JCEFResourceManager disposed");
    }
}
