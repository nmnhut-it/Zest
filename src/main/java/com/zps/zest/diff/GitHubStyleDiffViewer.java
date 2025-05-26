package com.zps.zest.diff;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.*;
import java.io.File;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * A lightweight git diff viewer that shows diffs in GitHub style.
 * Uses a JEditorPane with HTML rendering to display diff content.
 */
public class GitHubStyleDiffViewer extends DialogWrapper {
    private static final Logger LOG = Logger.getInstance(GitHubStyleDiffViewer.class);
    
    private final Project project;
    private final String filePath;
    private final String diffContent;
    private final String fileStatus;
    private JEditorPane diffPane;
    
    /**
     * Constructor for creating a diff viewer dialog
     * 
     * @param project The project context
     * @param filePath Path to the file being diffed
     * @param diffContent The diff content in git diff format
     * @param fileStatus The status of the file (e.g., "M", "A", "D", "R")
     */
    public GitHubStyleDiffViewer(Project project, String filePath, String diffContent, String fileStatus) {
        super(project, true);
        this.project = project;
        this.filePath = filePath;
        this.diffContent = diffContent;
        this.fileStatus = fileStatus;
        
        setTitle("Git Diff: " + filePath);
        setModal(false);
        setCancelButtonText("Close");
        
        init();
    }
    
    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBorder(new EmptyBorder(0, 0, 0, 0));
        
        // Create a top panel with file info
        JPanel fileInfoPanel = createFileInfoPanel();
        
        // Create diff content panel
        diffPane = createDiffPane();
        JScrollPane scrollPane = new JScrollPane(diffPane);
        scrollPane.setBorder(null);
        
        mainPanel.add(fileInfoPanel, BorderLayout.NORTH);
        mainPanel.add(scrollPane, BorderLayout.CENTER);
        
        // Set preferred size
        mainPanel.setPreferredSize(JBUI.size(900, 600));
        
        return mainPanel;
    }
    
    /**
     * Creates the file info panel at the top of the dialog
     */
    private JPanel createFileInfoPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new EmptyBorder(8, 12, 8, 12));
        
        // Use GitHub-style colors
        panel.setBackground(DiffThemeUtil.getHeaderBackground());
        
        // Create file info label with path and status
        JLabel fileInfoLabel = new JLabel(getFileNameWithStatus());
        fileInfoLabel.setFont(fileInfoLabel.getFont().deriveFont(Font.BOLD));
        fileInfoLabel.setForeground(DiffThemeUtil.getText());
        
        panel.add(fileInfoLabel, BorderLayout.WEST);
        
        // Add action buttons if needed (e.g., to open in standard diff)
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttonPanel.setOpaque(false);
        
        JButton openInStandardDiffButton = new JButton("Open in Standard Diff");
        openInStandardDiffButton.setFont(openInStandardDiffButton.getFont().deriveFont(11f));
        openInStandardDiffButton.addActionListener(e -> openInStandardDiff());
        
        buttonPanel.add(openInStandardDiffButton);
        panel.add(buttonPanel, BorderLayout.EAST);
        
        return panel;
    }
    
    /**
     * Creates the HTML-based diff pane
     */
    private JEditorPane createDiffPane() {
        JEditorPane pane = new JEditorPane();
        pane.setEditable(false);
        pane.setContentType("text/html");
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        pane.setBorder(null);
        
        // Setup HTML styling
        HTMLEditorKit kit = new HTMLEditorKit();
        pane.setEditorKit(kit);
        
        // Add CSS styles for GitHub-like diff
        StyleSheet styleSheet = kit.getStyleSheet();
        styleSheet.addRule(DiffThemeUtil.getGithubStyleCss());
        
        // Render the diff
        pane.setText(formatDiffAsHtml(diffContent));
        
        return pane;
    }
    
    /**
     * Format the diff text as HTML for display
     */
    private String formatDiffAsHtml(String diffText) {
        if (diffText == null || diffText.trim().isEmpty()) {
            return "<html><body style='padding: 20px;'><h3>No changes found for this file</h3></body></html>";
        }
        
        StringBuilder html = new StringBuilder();
        html.append("<html><body>");
        
        // Parse the diff
        String[] lines = diffText.split("\n");
        int oldLineNumber = 0;
        int newLineNumber = 0;
        
        html.append("<table class='diff-table'>");
        
        // Process each line
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            
            if (line.startsWith("diff --git") || line.startsWith("index ") || 
                line.startsWith("---") || line.startsWith("+++")) {
                // Skip header lines
                i++;
                continue;
            } else if (line.startsWith("@@")) {
                // Hunk header - parse the line numbers
                html.append("<tr><td colspan='3' class='diff-hunk-header'>").append(escapeHtml(line)).append("</td></tr>");
                
                // Extract line numbers from hunk header
                String[] parts = line.split(" ");
                if (parts.length >= 3) {
                    try {
                        String oldInfo = parts[1];
                        String newInfo = parts[2];
                        
                        if (oldInfo.startsWith("-")) {
                            oldInfo = oldInfo.substring(1);
                            String[] oldParts = oldInfo.split(",");
                            oldLineNumber = Integer.parseInt(oldParts[0]);
                        }
                        
                        if (newInfo.startsWith("+")) {
                            newInfo = newInfo.substring(1);
                            String[] newParts = newInfo.split(",");
                            newLineNumber = Integer.parseInt(newParts[0]);
                        }
                    } catch (NumberFormatException e) {
                        LOG.warn("Failed to parse line numbers from hunk header", e);
                    }
                }
            } else if (line.startsWith("+")) {
                // Addition line
                html.append("<tr class='diff-line addition'>");
                html.append("<td class='line-number'></td>");
                html.append("<td class='line-number'>").append(newLineNumber++).append("</td>");
                html.append("<td class='line-content'>").append(escapeHtml(line.substring(1))).append("</td>");
                html.append("</tr>");
            } else if (line.startsWith("-")) {
                // Deletion line
                html.append("<tr class='diff-line deletion'>");
                html.append("<td class='line-number'>").append(oldLineNumber++).append("</td>");
                html.append("<td class='line-number'></td>");
                html.append("<td class='line-content'>").append(escapeHtml(line.substring(1))).append("</td>");
                html.append("</tr>");
            } else {
                // Context line
                html.append("<tr class='diff-line'>");
                html.append("<td class='line-number'>").append(oldLineNumber++).append("</td>");
                html.append("<td class='line-number'>").append(newLineNumber++).append("</td>");
                html.append("<td class='line-content'>").append(escapeHtml(line.startsWith(" ") ? line.substring(1) : line)).append("</td>");
                html.append("</tr>");
            }
            
            i++;
        }
        
        html.append("</table>");
        html.append("</body></html>");
        
        return html.toString();
    }
    
    /**
     * Get file name with status indicator
     */
    private String getFileNameWithStatus() {
        String fileName = Paths.get(filePath).getFileName().toString();
        String statusLabel = getStatusLabel(fileStatus);
        return fileName + " " + statusLabel;
    }
    
    /**
     * Check if JavaScript is supported in the JEditorPane
     * Note: Standard JEditorPane has very limited JavaScript support
     */
    public static boolean isJavaScriptSupported() {
        // Standard JEditorPane doesn't support JavaScript well
        // It can execute simple scripts with a custom JavaScript engine,
        // but it's limited compared to a real browser
        return false;
    }
    
    /**
     * Get status label for display
     */
    private String getStatusLabel(String status) {
        String label;
        switch (status) {
            case "M":
            case "MODIFICATION":
                label = "Modified";
                break;
            case "A":
            case "ADDITION":
                label = "Added";
                break;
            case "D":
            case "DELETION":
                label = "Deleted";
                break;
            case "R":
            case "MOVED":
                label = "Renamed";
                break;
            default:
                label = status;
        }
        return "[" + label + "]";
    }
    
    /**
     * Open the file in IntelliJ's standard diff viewer
     */
    private void openInStandardDiff() {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                String basePath = project.getBasePath();
                if (basePath == null) {
                    return;
                }
                
                // Create an actual file path for IntelliJ
                java.io.File fileObj = new java.io.File(basePath, filePath);
                final String absolutePath = fileObj.getAbsolutePath();
                
                // Get the VirtualFile for this path
                VirtualFile virtualFile = 
                    LocalFileSystem.getInstance().refreshAndFindFileByPath(absolutePath);
                
                if (virtualFile == null) {
                    LOG.warn("Could not find virtual file for: " + absolutePath);
                    return;
                }
                
                // Use IntelliJ's standard diff action
                String actionId = "Compare.LastVersion"; // Default for modified files
                
                if ("D".equals(fileStatus) || "DELETION".equals(fileStatus)) {
                    actionId = "Compare.Selected"; // For deleted files
                }
                
                // Execute the action
                com.intellij.openapi.actionSystem.ActionManager actionManager = 
                    com.intellij.openapi.actionSystem.ActionManager.getInstance();
                com.intellij.openapi.actionSystem.AnAction action = actionManager.getAction(actionId);
                
                if (action != null) {
                    // First, open the file
                    com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(virtualFile, true);
                    
                    // Then show diff
//                    com.intellij.openapi.actionSystem.AnActionEvent event =
//                        new com.intellij.openapi.actionSystem.AnActionEvent(
//                            null,
//                            com.intellij.openapi.actionSystem.DataManager.getInstance().getDataContext(),
//                            com.intellij.openapi.actionSystem.ActionPlaces.UNKNOWN,
//                            new com.intellij.openapi.actionSystem.Presentation(),
//                            actionManager,
//                            0
//                        );
//
//                    action.actionPerformed(event);
                }
            } catch (Exception e) {
                LOG.error("Error opening in standard diff", e);
            }
        });
    }
    
    /**
     * Escape HTML special characters
     */
    private String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;")
                  .replace("'", "&#39;")
                  .replace(" ", "&nbsp;");
    }
    
    /**
     * Show diff for a specific file
     */
    public static void showDiff(Project project, String filePath, String diffContent, String fileStatus) {
        ApplicationManager.getApplication().invokeLater(() -> {
            GitHubStyleDiffViewer dialog = new GitHubStyleDiffViewer(project, filePath, diffContent, fileStatus);
            dialog.show();
        });
    }
}
