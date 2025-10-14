package com.zps.zest.git;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.zps.zest.browser.BrowserPurpose;
import com.zps.zest.browser.WebBrowserPanel;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;

/**
 * Dialog for Git UI operations with optional auto-actions.
 * Wraps a WebBrowserPanel configured for Git operations.
 */
public class GitUIDialog extends DialogWrapper {
    private static final Logger LOG = Logger.getInstance(GitUIDialog.class);

    private final Project project;
    private final WebBrowserPanel gitUIPanel;
    private final boolean withAutoActions;
    private boolean disposed = false;

    public GitUIDialog(Project project, boolean withAutoActions) {
        super(project, null, false, IdeModalityType.MODELESS);
        this.project = project;
        this.withAutoActions = withAutoActions;
        this.gitUIPanel = new WebBrowserPanel(project, false, BrowserPurpose.GIT);

        setTitle(withAutoActions ? "Git Commit & Push" : "Git UI");
        setSize(1200, 700);
        init();

        gitUIPanel.openGitUI();

        if (withAutoActions) {
            refreshAndInjectAutoActions();
        }
    }

    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout());

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

        panel.setComponentPopupMenu(contextMenu);
        JComponent browserComponent = gitUIPanel.getComponent();
        browserComponent.setComponentPopupMenu(contextMenu);

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
        return new Action[0];
    }

    public void refreshAndInjectAutoActions() {
        gitUIPanel.openGitUI();

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                Thread.sleep(500);
                ApplicationManager.getApplication().invokeLater(() -> {
                    injectAutoActionsJavaScriptWithRetries(gitUIPanel, 0);
                });
            } catch (InterruptedException ignored) {}
        });
    }

    private static void injectAutoActionsJavaScriptWithRetries(WebBrowserPanel gitUIPanel, int retryCount) {
        final int maxRetries = 10;

        String readyCheckScript = "document.readyState === 'complete' && typeof intellijBridge !== 'undefined'";

        try {
            gitUIPanel.executeJavaScript(readyCheckScript);
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

    @Override
    public void dispose() {
        if (!disposed) {
            disposed = true;
            gitUIPanel.dispose();
            super.dispose();
        }
    }

    public boolean isDisposed() {
        return disposed;
    }
}
