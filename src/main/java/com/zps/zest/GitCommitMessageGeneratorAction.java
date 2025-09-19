package com.zps.zest;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerListener;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.DialogWrapper.IdeModalityType;
import com.zps.zest.browser.WebBrowserPanel;
import com.zps.zest.git.GitStatusCollector;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;

/**
 * Action that opens Git UI with auto-selected changes and auto-generated commit message.
 * Provides streamlined workflow for commit and push operations.
 */
public class GitCommitMessageGeneratorAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(GitCommitMessageGeneratorAction.class);
    
    // Singleton pattern - store dialog per project
    private static final java.util.Map<Project, GitCommitDialog> activeDialogs = new java.util.concurrent.ConcurrentHashMap<>();
    
    // Static initializer to register project close listener
    static {
        ProjectManager.getInstance().addProjectManagerListener(new ProjectManagerListener() {
            @Override
            public void projectClosed(@NotNull Project project) {
                // Clean up dialog when project is closed
                GitCommitDialog dialog = activeDialogs.remove(project);
                if (dialog != null && !dialog.isDisposed()) {
                    dialog.dispose();
                    LOG.info("Cleaned up Git Commit dialog for closed project: " + project.getName());
                }
            }
        });
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            Messages.showWarningDialog((Project)null, "No project available", "Git Commit & Push");
            return;
        }

        // Check if there are any git changes
        try {
            String projectPath = project.getBasePath();
            if (projectPath == null) {
                Messages.showErrorDialog(project, "Project path not found", "Git Commit & Push");
                return;
            }

            GitStatusCollector statusCollector = new GitStatusCollector(projectPath);
            String changes = statusCollector.collectAllChanges();
            
            if (changes == null || changes.trim().isEmpty()) {
                Messages.showInfoMessage(project, 
                    "No git changes found. Please make some changes before committing.", 
                    "Git Commit & Push");
                return;
            }

            // Launch Git UI with auto-actions
            launchGitUIWithAutoActions(project);
            
        } catch (Exception ex) {
            LOG.error("Error checking git status", ex);
            Messages.showErrorDialog(project, 
                "Failed to check git status: " + ex.getMessage(), 
                "Git Commit & Push");
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        // Enable action only when project is available
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
//        e.getPresentation().setText("Git Commit & Push");
//        e.getPresentation().setDescription("Open Git UI with auto-selected changes and AI-generated commit message");
    }

    /**
     * Launch Git UI with auto-selection and auto-generation actions
     */
    private void launchGitUIWithAutoActions(Project project) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                // Check if dialog already exists for this project
                GitCommitDialog existingDialog = activeDialogs.get(project);
                if (existingDialog != null && !existingDialog.isDisposed()) {
                    // Reuse existing dialog
                    existingDialog.toFront();
                    existingDialog.refreshAndInjectAutoActions();
                    LOG.info("Reused existing Git UI dialog for project: " + project.getName());
                    return;
                }
                
                // Create new dialog and store it
                GitCommitDialog newDialog = new GitCommitDialog(project);
                activeDialogs.put(project, newDialog);
                newDialog.show();
                
                LOG.info("Created new Git UI dialog for project: " + project.getName());
                
            } catch (Exception ex) {
                LOG.error("Failed to launch Git UI", ex);
                Messages.showErrorDialog(project, 
                    "Failed to open Git UI: " + ex.getMessage(), 
                    "Git Commit & Push");
            }
        });
    }
    
    /**
     * Inject JavaScript with retry mechanism to ensure browser is ready
     */
    private static void injectAutoActionsJavaScriptWithRetries(WebBrowserPanel gitUIPanel, int retryCount) {
        final int maxRetries = 10;
        
        // First check if browser is ready
        String readyCheckScript = "document.readyState === 'complete' && typeof intellijBridge !== 'undefined'";
        
        try {
            // Try to execute a simple check
            gitUIPanel.executeJavaScript(readyCheckScript);
            
            // If no exception, proceed with auto-actions
            injectAutoActionsJavaScript(gitUIPanel);
            LOG.info("Auto-actions JavaScript injected successfully on attempt " + (retryCount + 1));
            
        } catch (Exception e) {
            if (retryCount < maxRetries) {
                LOG.info("Browser not ready, retrying in 500ms... (attempt " + (retryCount + 1) + "/" + maxRetries + ")");
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    try {
                        Thread.sleep(500);
                        ApplicationManager.getApplication().invokeLater(() -> {
                            injectAutoActionsJavaScriptWithRetries(gitUIPanel, retryCount + 1);
                        });
                    } catch (InterruptedException ignored) {}
                });
            } else {
                LOG.warn("Failed to inject auto-actions JavaScript after " + maxRetries + " attempts", e);
            }
        }
    }
    
    /**
     * Inject JavaScript to auto-select files and trigger message generation
     */
    private static void injectAutoActionsJavaScript(WebBrowserPanel gitUIPanel) {
        String autoActionsScript = """
            // Auto-actions for Git UI with better error handling and debugging
            (function() {
                console.log('🚀 Executing auto-actions for Git Commit & Push...');
                
                let retryCount = 0;
                const maxRetries = 40; // 12 seconds max wait
                
                // Function to wait for UI elements to be available
                function waitForElements() {
                    retryCount++;
                    console.log('🔍 Waiting for elements... attempt', retryCount);
                    
                    // Check for various possible selectors
                    const fileCheckboxes = document.querySelectorAll('.file-checkbox[data-index], input[type="checkbox"][data-index], .file-row input[type="checkbox"]');
                    const generateBtn = document.querySelector('#generate-btn, button[id*="generate"], button[onclick*="generate"]');
                    
                    console.log('📁 Found', fileCheckboxes.length, 'file checkboxes');
                    console.log('🔧 Generate button:', generateBtn ? 'found' : 'not found');
                    
                    // Log all available checkboxes for debugging
                    document.querySelectorAll('input[type="checkbox"]').forEach((cb, index) => {
                        console.log('Checkbox', index + ':', cb.id || cb.className || 'no-id/class', 'checked:', cb.checked);
                    });
                    
                    if (fileCheckboxes.length > 0) {
                        console.log('✅ Auto-selecting', fileCheckboxes.length, 'files...');
                        
                        // Auto-select all file checkboxes
                        fileCheckboxes.forEach(function(checkbox, index) {
                            if (!checkbox.checked) {
                                console.log('📋 Checking checkbox', index);
                                checkbox.checked = true;
                                // Trigger multiple event types to ensure UI updates
                                ['change', 'input', 'click'].forEach(eventType => {
                                    checkbox.dispatchEvent(new Event(eventType, { bubbles: true, cancelable: true }));
                                });
                            }
                        });
                        
                        // Also check the select-all checkbox to reflect the state
                        const selectAllCheckbox = document.querySelector('#select-all, input[id*="select-all"], input[class*="select-all"]');
                        if (selectAllCheckbox && !selectAllCheckbox.checked) {
                            console.log('📋 Checking select-all checkbox');
                            selectAllCheckbox.checked = true;
                            ['change', 'input', 'click'].forEach(eventType => {
                                selectAllCheckbox.dispatchEvent(new Event(eventType, { bubbles: true, cancelable: true }));
                            });
                        }
                        
                        // Auto-trigger commit message generation after selection
                        setTimeout(function() {
                            if (generateBtn && !generateBtn.disabled) {
                                console.log('🎯 Auto-triggering commit message generation...');
                                generateBtn.click();
                            } else {
                                console.log('⚠️ Generate button not found or disabled');
                                // Try to find any button with "generate" in text
                                const altGenerateBtn = Array.from(document.querySelectorAll('button')).find(btn => 
                                    btn.textContent.toLowerCase().includes('generate')
                                );
                                if (altGenerateBtn) {
                                    console.log('🔄 Found alternative generate button, clicking...');
                                    altGenerateBtn.click();
                                }
                            }
                        }, 500); // Increased delay
                        
                    } else if (retryCount < maxRetries) {
                        // Retry if elements not ready
                        console.log('⏳ Elements not ready, retrying in 300ms...');
                        setTimeout(waitForElements, 300);
                    } else {
                        console.log('❌ Max retries reached. Available elements:');
                        console.log('All inputs:', document.querySelectorAll('input'));
                        console.log('All buttons:', document.querySelectorAll('button'));
                    }
                }
                
                // Start waiting for elements with initial delay
                setTimeout(waitForElements, 1000);
            })();
        """;
        
        try {
            gitUIPanel.executeJavaScript(autoActionsScript);
            LOG.info("Auto-actions JavaScript injected successfully");
        } catch (Exception e) {
            LOG.warn("Failed to inject auto-actions JavaScript", e);
        }
    }
    
    /**
     * Custom dialog wrapper that manages a WebBrowserPanel instance
     */
    private static class GitCommitDialog extends DialogWrapper {
        private final Project project;
        private final WebBrowserPanel gitUIPanel;
        private boolean disposed = false;
        
        public GitCommitDialog(Project project) {
            super(project, null, false, IdeModalityType.MODELESS);
            this.project = project;
            this.gitUIPanel = new WebBrowserPanel(project, false);
            
            setTitle("Git Commit & Push");
            setSize(1200, 700);
            init();
            
            // Open Git UI
            gitUIPanel.openGitUI();
            
            // Initial auto-actions injection
            refreshAndInjectAutoActions();
        }
        
        @Override
        protected JComponent createCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout());
            
            // Add context menu for debugging
            JPopupMenu contextMenu = new JPopupMenu();
            JMenuItem devToolsItem = new JMenuItem("Open Developer Tools (F12)");
            devToolsItem.addActionListener(e -> {
                try {
                    gitUIPanel.getBrowserManager().getBrowser().openDevtools();
                } catch (Exception ex) {
                    LOG.warn("Failed to open dev tools", ex);
                }
            });
            contextMenu.add(devToolsItem);
            
            // Add context menu to components
            panel.setComponentPopupMenu(contextMenu);
            JComponent browserComponent = gitUIPanel.getComponent();
            browserComponent.setComponentPopupMenu(contextMenu);
            
            // Add mouse listener for context menu
            browserComponent.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(java.awt.event.MouseEvent e) {
                    if (e.isPopupTrigger()) {
                        contextMenu.show(e.getComponent(), e.getX(), e.getY());
                    }
                }
                
                @Override
                public void mouseReleased(java.awt.event.MouseEvent e) {
                    if (e.isPopupTrigger()) {
                        contextMenu.show(e.getComponent(), e.getX(), e.getY());
                    }
                }
            });
            
            // Add F12 key binding for dev tools
            panel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_F12, 0), "openDevTools");
            panel.getActionMap().put("openDevTools", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    try {
                        gitUIPanel.getBrowserManager().getBrowser().openDevtools();
                        LOG.info("Dev tools opened via F12 key");
                    } catch (Exception ex) {
                        LOG.warn("Failed to open dev tools via F12", ex);
                    }
                }
            });
            
            panel.add(browserComponent, BorderLayout.CENTER);
            return panel;
        }
        
        @Override
        protected Action[] createActions() {
            return new Action[0]; // No default buttons
        }
        
        public void refreshAndInjectAutoActions() {
            // Refresh Git UI
            gitUIPanel.openGitUI();
            
            // Inject auto-actions with delay
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    Thread.sleep(500);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        injectAutoActionsJavaScriptWithRetries(gitUIPanel, 0);
                    });
                } catch (InterruptedException ignored) {}
            });
        }
        
        @Override
        public void dispose() {
            if (!disposed) {
                disposed = true;
                gitUIPanel.dispose();
                activeDialogs.remove(project);
                super.dispose();
            }
        }
        
        public boolean isDisposed() {
            return disposed;
        }
    }

}