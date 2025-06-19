package com.zps.zest.vcs;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.CheckinProjectPanel;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.CommitContext;
import com.intellij.openapi.vcs.checkin.*;
import com.intellij.openapi.vcs.ui.RefreshableOnComponent;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;
import com.zps.zest.browser.utils.ChatboxUtilities;
import com.zps.zest.langchain4j.util.LLMService;
import com.zps.zest.ConfigurationManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Extension that adds AI-powered commit message generation to IntelliJ's commit dialog
 * Properly handles the gear button configuration panel lifecycle
 */
public class CommitMessageGeneratorExtension extends CheckinHandlerFactory {

    // Static cache to preserve panel instances across gear button clicks
    private static final Map<CheckinProjectPanel, CommitMessageGeneratorPanel> panelCache = new ConcurrentHashMap<>();

    @Override
    public @NotNull CheckinHandler createHandler(@NotNull CheckinProjectPanel panel, @NotNull CommitContext commitContext) {
        System.out.println("DEBUG: Creating new CheckinHandler for panel: " + panel.hashCode());
        return new CommitMessageGeneratorHandler(panel);
    }

    private static class CommitMessageGeneratorHandler extends CheckinHandler {
        private final CheckinProjectPanel panel;
        private final Project project;
        private final LLMService llmService;
        private final ConfigurationManager config;

        public CommitMessageGeneratorHandler(CheckinProjectPanel panel) {
            this.panel = panel;
            this.project = panel.getProject();
            this.llmService = project.getService(LLMService.class);
            this.config = ConfigurationManager.getInstance(project);
            System.out.println("DEBUG: Handler created for panel: " + panel.hashCode());
        }

        @Override
        public @Nullable RefreshableOnComponent getBeforeCheckinConfigurationPanel() {
            System.out.println("DEBUG: getBeforeCheckinConfigurationPanel() called for panel: " + panel.hashCode());

            // Reuse existing panel from cache or create new one
            CommitMessageGeneratorPanel generatorPanel = panelCache.computeIfAbsent(panel,
                    p -> {
                        System.out.println("DEBUG: Creating new panel for: " + p.hashCode());
                        return new CommitMessageGeneratorPanel(p, llmService, config);
                    });

            System.out.println("DEBUG: Returning panel instance: " + generatorPanel.hashCode());
            return generatorPanel;
        }
    }

    /**
     * UI Panel that integrates with the commit dialog gear button configuration
     */
    private static class CommitMessageGeneratorPanel extends JBPanel<CommitMessageGeneratorPanel>
            implements RefreshableOnComponent {

        private final CheckinProjectPanel commitPanel;
        private final LLMService llmService;
        private final ConfigurationManager config;
        private JButton generateButton;
        private JBLabel statusLabel;
        private boolean justGenerated = false;
        private boolean isInitialized = false;

        public CommitMessageGeneratorPanel(CheckinProjectPanel commitPanel, LLMService llmService, ConfigurationManager config) {
            super(new BorderLayout());
            this.commitPanel = commitPanel;
            this.llmService = llmService;
            this.config = config;
            System.out.println("DEBUG: Panel constructor called: " + this.hashCode());
            initializeUI();
        }

        private void initializeUI() {
            if (isInitialized) {
                System.out.println("DEBUG: Panel already initialized: " + this.hashCode());
                return;
            }

            System.out.println("DEBUG: Initializing UI for panel: " + this.hashCode());

            // Create generate button
            generateButton = new JButton("✨ Generate AI Message");
            generateButton.setToolTipText("Generate AI-powered commit message based on changes");
            generateButton.addActionListener(new GenerateCommitMessageAction());
            generateButton.setPreferredSize(new Dimension(180, 28));

            // Create status label
            statusLabel = new JBLabel("Ready");
            statusLabel.setForeground(JBColor.GRAY);
            statusLabel.setFont(statusLabel.getFont().deriveFont(10f));

            // Layout
            JPanel mainPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
            mainPanel.add(generateButton);
            mainPanel.add(statusLabel);

            // Clear any existing content and add new content
            this.removeAll();
            this.add(mainPanel, BorderLayout.CENTER);
            this.setBorder(JBUI.Borders.empty(5, 5));

            isInitialized = true;
            System.out.println("DEBUG: UI initialization complete for panel: " + this.hashCode());
        }

        @Override
        public JComponent getComponent() {
            System.out.println("DEBUG: getComponent() called for panel: " + this.hashCode() +
                    ", initialized: " + isInitialized);

            // Always ensure UI is initialized
            if (!isInitialized) {
                initializeUI();
            }

            // Force refresh of the component
            SwingUtilities.invokeLater(() -> {
                this.revalidate();
                this.repaint();
            });

            return this;
        }

        @Override
        public void refresh() {
            System.out.println("DEBUG: refresh() called for panel: " + this.hashCode());

            // Ensure UI is initialized
            if (!isInitialized) {
                initializeUI();
            }

            // Update button state
            if (generateButton != null && statusLabel != null) {
                boolean hasChanges = !commitPanel.getSelectedChanges().isEmpty();
                boolean isConfigured = llmService.isConfigured();

                generateButton.setEnabled(hasChanges && isConfigured);

                if (!justGenerated) {
                    if (!hasChanges) {
                        statusLabel.setText("No changes selected");
                        statusLabel.setForeground(JBColor.GRAY);
                    } else if (!isConfigured) {
                        statusLabel.setText("LLM service not configured");
                        statusLabel.setForeground(JBColor.RED);
                    } else {
                        statusLabel.setText("Ready");
                        statusLabel.setForeground(JBColor.GREEN.darker());
                    }
                }
            }
        }

        @Override
        public void saveState() {
            System.out.println("DEBUG: saveState() called for panel: " + this.hashCode());
            // No state to save, just ensure components are still there
            if (!isInitialized) {
                initializeUI();
            }
        }

        @Override
        public void restoreState() {
            System.out.println("DEBUG: restoreState() called for panel: " + this.hashCode());
            // Restore state by ensuring UI is initialized
            if (!isInitialized) {
                initializeUI();
            }
            // Force a refresh
            SwingUtilities.invokeLater(() -> {
                this.revalidate();
                this.repaint();
            });
        }

        /**
         * Action handler for generating commit messages
         */
        private class GenerateCommitMessageAction implements ActionListener {
            @Override
            public void actionPerformed(ActionEvent e) {
                System.out.println("DEBUG: Generate button clicked for panel: " + CommitMessageGeneratorPanel.this.hashCode());
                generateCommitMessage();
            }

            private void generateCommitMessage() {
                if (!llmService.isConfigured()) {
                    statusLabel.setText("LLM service not configured");
                    statusLabel.setForeground(JBColor.RED);
                    return;
                }

                // Reset flag when starting new generation
                justGenerated = false;

                // Disable button and show progress
                generateButton.setEnabled(false);
                statusLabel.setText("Generating...");
                statusLabel.setForeground(JBColor.BLUE);

                // Run in background thread
                SwingUtilities.invokeLater(() -> {
                    try {
                        String prompt = buildCommitPromptWithTemplate();

                        // Use LLMService to generate message
                        llmService.queryAsync(prompt)
                                .thenAccept(this::onCommitMessageGenerated)
                                .exceptionally(this::onGenerationFailed);

                    } catch (Exception ex) {
                        onGenerationFailed(ex);
                    }
                });
            }

            private String buildCommitPromptWithTemplate() {
                // Get the template from configuration
                String template = config.getCommitPromptTemplate();

                // Get selected changes
                Collection<Change> changes = commitPanel.getSelectedChanges();

                // Build files list grouped by status
                StringBuilder filesList = new StringBuilder();

                // Group files by status
                Map<String, List<String>> filesByStatus = new HashMap<>();
                filesByStatus.put("M", new ArrayList<>());
                filesByStatus.put("A", new ArrayList<>());
                filesByStatus.put("D", new ArrayList<>());
                filesByStatus.put("R", new ArrayList<>());
                filesByStatus.put("C", new ArrayList<>());
                filesByStatus.put("U", new ArrayList<>());

                for (com.intellij.openapi.vcs.changes.Change change : changes) {
                    String status = getChangeStatus(change);
                    String filePath = getFilePath(change);

                    if (filesByStatus.containsKey(status)) {
                        filesByStatus.get(status).add(filePath);
                    } else {
                        filesByStatus.get("U").add(filePath);
                    }
                }

                Map<String, String> statusMap = new HashMap<>();
                statusMap.put("M", "Modified");
                statusMap.put("A", "Added");
                statusMap.put("D", "Deleted");
                statusMap.put("R", "Renamed");
                statusMap.put("C", "Copied");
                statusMap.put("U", "Other");

                // Output files grouped by status
                for (Map.Entry<String, List<String>> entry : filesByStatus.entrySet()) {
                    if (!entry.getValue().isEmpty()) {
                        filesList.append("\n### ").append(statusMap.get(entry.getKey())).append(" files:\n");
                        for (String path : entry.getValue()) {
                            filesList.append("- ").append(path).append("\n");
                        }
                    }
                }

                // Build diffs section
                StringBuilder diffsSection = new StringBuilder();
                diffsSection.append("## File changes summary:\n");
                for (com.intellij.openapi.vcs.changes.Change change : changes) {
                    String changeType = getChangeType(change);
                    String filePath = getFilePath(change);
                    diffsSection.append("- ").append(changeType).append(": ").append(filePath).append("\n");
                }

                // Replace placeholders in template
                String prompt = template
                        .replace("{FILES_LIST}", filesList.toString().trim())
                        .replace("{DIFFS}", diffsSection.toString().trim());

                return prompt;
            }

            private String getChangeStatus(com.intellij.openapi.vcs.changes.Change change) {
                if (change.getType() == com.intellij.openapi.vcs.changes.Change.Type.NEW) {
                    return "A";
                } else if (change.getType() == com.intellij.openapi.vcs.changes.Change.Type.DELETED) {
                    return "D";
                } else if (change.getType() == com.intellij.openapi.vcs.changes.Change.Type.MODIFICATION) {
                    return "M";
                } else if (change.getType() == com.intellij.openapi.vcs.changes.Change.Type.MOVED) {
                    return "R";
                } else {
                    return "U";
                }
            }

            private String getChangeType(com.intellij.openapi.vcs.changes.Change change) {
                if (change.getType() == com.intellij.openapi.vcs.changes.Change.Type.NEW) {
                    return "Added";
                } else if (change.getType() == com.intellij.openapi.vcs.changes.Change.Type.DELETED) {
                    return "Deleted";
                } else if (change.getType() == com.intellij.openapi.vcs.changes.Change.Type.MODIFICATION) {
                    return "Modified";
                } else if (change.getType() == com.intellij.openapi.vcs.changes.Change.Type.MOVED) {
                    return "Moved";
                } else {
                    return "Changed";
                }
            }

            private String getFilePath(com.intellij.openapi.vcs.changes.Change change) {
                if (change.getAfterRevision() != null) {
                    return change.getAfterRevision().getFile().getPath();
                } else if (change.getBeforeRevision() != null) {
                    return change.getBeforeRevision().getFile().getPath();
                } else {
                    return "unknown";
                }
            }

            private void onCommitMessageGenerated(String message) {
                SwingUtilities.invokeLater(() -> {
                    // Clean the message
                    String cleanMessage = cleanCommitMessage(message);

                    // Set flag to prevent refresh from overriding our status
                    justGenerated = true;

                    // Set the commit message in the dialog
                    commitPanel.setCommitMessage(cleanMessage);

                    // Update UI
                    generateButton.setEnabled(true);
                    statusLabel.setText("✅ Generated");
                    statusLabel.setForeground(JBColor.GREEN.darker());

                    // Clear status and flag after 4 seconds
                    Timer timer = new Timer(4000, evt -> {
                        justGenerated = false;
                        statusLabel.setText("Ready");
                        statusLabel.setForeground(JBColor.GREEN.darker());
                    });
                    timer.setRepeats(false);
                    timer.start();
                });
            }

            private Void onGenerationFailed(Throwable throwable) {
                SwingUtilities.invokeLater(() -> {
                    // Set flag to prevent refresh from overriding our status
                    justGenerated = true;

                    generateButton.setEnabled(true);
                    statusLabel.setText("❌ Failed");
                    statusLabel.setForeground(JBColor.RED);

                    // Clear status and flag after 6 seconds
                    Timer timer = new Timer(6000, evt -> {
                        justGenerated = false;
                        statusLabel.setText("Ready");
                        statusLabel.setForeground(JBColor.GREEN.darker());
                    });
                    timer.setRepeats(false);
                    timer.start();
                });
                return null;
            }

            private String cleanCommitMessage(String message) {
                if (message == null) return "";

                // Remove markdown code blocks
                message = message.replaceAll("```[\\w-]*\\n?", "");
                message = message.replaceAll("\\n?```", "");
                message = message.replaceAll("```", "");

                // Remove leading/trailing whitespace
                message = message.trim();

                // Remove any "Commit message:" prefix that might be added
                message = message.replaceAll("^(Commit message:|Generated commit message:)\\s*", "");

                return message;
            }
        }
    }
}