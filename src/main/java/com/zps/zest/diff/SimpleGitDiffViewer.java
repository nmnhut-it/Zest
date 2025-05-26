package com.zps.zest.diff;

import com.google.gson.JsonObject;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.zps.zest.browser.GitService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A simple git diff viewer that uses JEditorPane to display HTML-formatted diffs
 * in a lightweight, native IntelliJ dialog.
 */
public class SimpleGitDiffViewer extends DialogWrapper {
    private static final Logger LOG = Logger.getInstance(SimpleGitDiffViewer.class);
    
    private final Project project;
    private final JPanel mainPanel;
    private final JBList<FileStatus> fileList;
    private final DefaultListModel<FileStatus> listModel;
    private final JEditorPane diffPane;
    private final GitService gitService;
    
    // Stores the list of changed files
    private List<FileStatus> changedFiles;
    
    /**
     * Inner class to represent a file status
     */
    public static class FileStatus {
        private final String path;
        private final String status;
        
        public FileStatus(String path, String status) {
            this.path = path;
            this.status = status;
        }
        
        public String getPath() {
            return path;
        }
        
        public String getStatus() {
            return status;
        }
        
        @Override
        public String toString() {
            return String.format("[%s] %s", getStatusLabel(), path);
        }
        
        private String getStatusLabel() {
            switch (status) {
                case "MODIFICATION": return "M";
                case "ADDITION": return "A";
                case "DELETION": return "D";
                case "MOVED": return "R";
                default: return status;
            }
        }
    }
    
    /**
     * Constructor for the dialog
     */
    public SimpleGitDiffViewer(Project project, List<FileStatus> changedFiles) {
        super(project, true);
        this.project = project;
        this.changedFiles = changedFiles;
        this.gitService = new GitService(project);
        
        setTitle("Git Diff Viewer");
        setModal(false);
        
        // Create UI components
        mainPanel = new JPanel(new BorderLayout(10, 0));
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        
        // Setup file list panel
        JPanel leftPanel = new JPanel(new BorderLayout());
        listModel = new DefaultListModel<>();
        fileList = new JBList<>(listModel);
        fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showSelectedDiff();
            }
        });
        
        // Set up the diff pane with HTML support
        diffPane = new JEditorPane();
        diffPane.setEditable(false);
        diffPane.setContentType("text/html");
        diffPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        
        // Set up HTML styling
        HTMLEditorKit kit = new HTMLEditorKit();
        diffPane.setEditorKit(kit);
        StyleSheet styleSheet = kit.getStyleSheet();
        
        // Add CSS styles
        styleSheet.addRule("body { font-family: monospace; font-size: 12px; margin: 0; padding: 0; }");
        styleSheet.addRule(".diff-header { background-color: #f0f0f0; padding: 5px; border-bottom: 1px solid #ddd; }");
        styleSheet.addRule(".diff-hunk-header { background-color: #f8f8f8; color: #999; padding: 2px 5px; border-top: 1px solid #eee; border-bottom: 1px solid #eee; }");
        styleSheet.addRule(".diff-line { font-family: monospace; white-space: pre; }");
        styleSheet.addRule(".line-number { color: #999; text-align: right; padding-right: 5px; user-select: none; }");
        styleSheet.addRule(".addition { background-color: #e6ffed; }");
        styleSheet.addRule(".addition .line-content { border-left: 1px solid #34d058; padding-left: 4px; }");
        styleSheet.addRule(".deletion { background-color: #ffeef0; }");
        styleSheet.addRule(".deletion .line-content { border-left: 1px solid #d73a49; padding-left: 4px; }");
        styleSheet.addRule(".diff-table { border-collapse: collapse; width: 100%; }");
        styleSheet.addRule(".diff-table td { padding: 0; }");
        
        // Handle hyperlinks
        diffPane.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                if ("confirm".equals(e.getDescription())) {
                    confirmSelectedFile();
                } else if ("open".equals(e.getDescription())) {
                    openSelectedFileInDiff();
                }
            }
        });
        
        // Add a refresh button and actions at the top
        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(e -> refreshChangedFiles());
        actionPanel.add(refreshButton);
        
        JButton confirmButton = new JButton("Confirm Selected");
        confirmButton.addActionListener(e -> confirmSelectedFile());
        actionPanel.add(confirmButton);
        
        // Layout components
        leftPanel.add(new JLabel("Changed Files:"), BorderLayout.NORTH);
        leftPanel.add(new JBScrollPane(fileList), BorderLayout.CENTER);
        
        mainPanel.add(actionPanel, BorderLayout.NORTH);
        mainPanel.add(leftPanel, BorderLayout.WEST);
        mainPanel.add(new JBScrollPane(diffPane), BorderLayout.CENTER);
        
        // Set preferred sizes
        leftPanel.setPreferredSize(JBUI.size(300, 600));
        mainPanel.setPreferredSize(JBUI.size(900, 600));
        
        // Populate list
        populateFileList();
        
        init();
    }
    
    /**
     * Create the centered panel with our content
     */
    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        return mainPanel;
    }
    
    /**
     * Populate the file list with changed files
     */
    private void populateFileList() {
        listModel.clear();
        
        for (FileStatus file : changedFiles) {
            listModel.addElement(file);
        }
        
        if (!changedFiles.isEmpty()) {
            fileList.setSelectedIndex(0);
        }
    }
    
    /**
     * Show the diff for the selected file
     */
    private void showSelectedDiff() {
        FileStatus selected = fileList.getSelectedValue();
        
        if (selected == null) {
            diffPane.setText("<html><body><h3>No file selected</h3></body></html>");
            return;
        }
        
        diffPane.setText("<html><body><h3>Loading diff...</h3></body></html>");
        
        // Create JsonObject for GitService
        JsonObject data = new JsonObject();
        data.addProperty("filePath", selected.getPath());
        data.addProperty("status", selected.getStatus());
        
        // Call GitService asynchronously
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                String result = gitService.getFileDiff(data);
                JsonObject response = com.google.gson.JsonParser.parseString(result).getAsJsonObject();
                
                if (response.get("success").getAsBoolean()) {
                    String diffText = response.get("diff").getAsString();
                    String htmlDiff = formatDiffAsHtml(diffText, selected);
                    
                    // Update UI on EDT
                    SwingUtilities.invokeLater(() -> {
                        diffPane.setText(htmlDiff);
                        diffPane.setCaretPosition(0);
                    });
                } else {
                    String error = response.get("error").getAsString();
                    SwingUtilities.invokeLater(() -> {
                        diffPane.setText("<html><body><h3>Error loading diff</h3><p>" + error + "</p></body></html>");
                    });
                }
            } catch (Exception e) {
                LOG.error("Error showing diff", e);
                SwingUtilities.invokeLater(() -> {
                    diffPane.setText("<html><body><h3>Error loading diff</h3><p>" + e.getMessage() + "</p></body></html>");
                });
            }
        });
    }
    
    /**
     * Format diff text as HTML for display
     */
    private String formatDiffAsHtml(String diffText, FileStatus file) {
        if (diffText == null || diffText.trim().isEmpty()) {
            return "<html><body><h3>No changes found for this file</h3></body></html>";
        }
        
        StringBuilder html = new StringBuilder();
        html.append("<html><body>");
        
        // Add a header with file info and action buttons
        html.append("<div class='diff-header'>");
        html.append("<strong>").append(escapeHtml(file.getPath())).append("</strong>");
        html.append(" <span style='color: #666;'>[").append(file.getStatusLabel()).append("]</span>");
        html.append(" <a href='open' style='margin-left: 10px;'>Open in Diff Viewer</a>");
        html.append(" <a href='confirm' style='margin-left: 10px;'>Confirm Changes</a>");
        html.append("</div>");
        
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
                // Skip header lines or add them to a header section
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
     * Confirm changes for the selected file
     */
    private void confirmSelectedFile() {
        FileStatus selected = fileList.getSelectedValue();
        
        if (selected == null) {
            return;
        }
        
        // Create parameters for openFileDiffInIDE
        JsonObject data = new JsonObject();
        data.addProperty("filePath", selected.getPath());
        data.addProperty("status", selected.getStatus());
        
        // Call GitService asynchronously
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                String result = gitService.openFileDiffInIDE(data);
                JsonObject response = com.google.gson.JsonParser.parseString(result).getAsJsonObject();
                
                if (response.get("success").getAsBoolean()) {
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(
                            mainPanel,
                            "File opened in diff viewer. Please confirm your changes there.",
                            "File Opened",
                            JOptionPane.INFORMATION_MESSAGE
                        );
                    });
                } else {
                    String error = response.get("error").getAsString();
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(
                            mainPanel,
                            "Error opening file: " + error,
                            "Error",
                            JOptionPane.ERROR_MESSAGE
                        );
                    });
                }
            } catch (Exception e) {
                LOG.error("Error opening file diff", e);
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(
                        mainPanel,
                        "Error opening file: " + e.getMessage(),
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    );
                });
            }
        });
    }
    
    /**
     * Open the selected file in the IntelliJ diff viewer
     */
    private void openSelectedFileInDiff() {
        confirmSelectedFile(); // Same implementation for now
    }
    
    /**
     * Refresh the list of changed files
     */
    private void refreshChangedFiles() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                // Get project VCS status
                String projectVcsStatus = getProjectVcsStatus();
                JsonObject response = com.google.gson.JsonParser.parseString(projectVcsStatus).getAsJsonObject();
                
                if (response.get("success").getAsBoolean()) {
                    List<FileStatus> files = new ArrayList<>();
                    
                    if (response.has("files")) {
                        com.google.gson.JsonArray filesArray = response.getAsJsonArray("files");
                        for (int i = 0; i < filesArray.size(); i++) {
                            com.google.gson.JsonObject fileObj = filesArray.get(i).getAsJsonObject();
                            String path = fileObj.get("path").getAsString();
                            String status = fileObj.get("type").getAsString();
                            files.add(new FileStatus(path, status));
                        }
                    }
                    
                    changedFiles = files;
                    
                    SwingUtilities.invokeLater(() -> {
                        populateFileList();
                        if (files.isEmpty()) {
                            diffPane.setText("<html><body><h3>No changed files found</h3></body></html>");
                        }
                    });
                } else {
                    String error = response.has("error") ? response.get("error").getAsString() : "Unknown error";
                    LOG.error("Error refreshing files: " + error);
                }
            } catch (Exception e) {
                LOG.error("Error refreshing changed files", e);
            }
        });
    }
    
    /**
     * Get project VCS status
     */
    private String getProjectVcsStatus() {
        // Create a JsonObject to represent the VCS status request
        JsonObject result = new JsonObject();
        
        try {
            // Call the command-line 'git status --porcelain' to get changed files
            Process process = Runtime.getRuntime().exec("git status --porcelain", null, new File(project.getBasePath()));
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));
            
            com.google.gson.JsonArray files = new com.google.gson.JsonArray();
            
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() < 3) continue;
                
                String status = line.substring(0, 2).trim();
                String path = line.substring(3).trim();
                
                String statusType = convertGitStatusToType(status);
                
                com.google.gson.JsonObject fileObj = new com.google.gson.JsonObject();
                fileObj.addProperty("path", path);
                fileObj.addProperty("type", statusType);
                files.add(fileObj);
            }
            
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                result.addProperty("success", true);
                result.add("files", files);
            } else {
                result.addProperty("success", false);
                result.addProperty("error", "Git status command failed with exit code " + exitCode);
            }
        } catch (Exception e) {
            LOG.error("Error getting VCS status", e);
            result.addProperty("success", false);
            result.addProperty("error", e.getMessage());
        }
        
        return result.toString();
    }
    
    /**
     * Convert git status code to a file status type
     */
    private static String convertGitStatusToType(String status) {
        if (status.contains("M")) {
            return "MODIFICATION";
        } else if (status.contains("A")) {
            return "ADDITION";
        } else if (status.contains("D")) {
            return "DELETION";
        } else if (status.contains("R")) {
            return "MOVED";
        } else if (status.contains("?")) {
            return "UNVERSIONED";
        } else {
            return "UNKNOWN";
        }
    }
    
    /**
     * Show the dialog with project changes
     */
    public static void showProjectChanges(Project project) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                // Get project VCS status
                JsonObject request = new JsonObject();
                GitService gitService = new GitService(project);
                
                // Call the command-line 'git status --porcelain' to get changed files
                Process process = Runtime.getRuntime().exec("git status --porcelain", null, new File(project.getBasePath()));
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream()));
                
                List<FileStatus> files = new ArrayList<>();
                
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.length() < 3) continue;
                    
                    String status = line.substring(0, 2).trim();
                    String path = line.substring(3).trim();
                    
                    String statusType = convertGitStatusToType(status);
                    
                    files.add(new FileStatus(path, statusType));
                }
                
                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    List<FileStatus> finalFiles = files;
                    SwingUtilities.invokeLater(() -> {
                        if (finalFiles.isEmpty()) {
                            JOptionPane.showMessageDialog(
                                null,
                                "No changed files found in the repository.",
                                "No Changes",
                                JOptionPane.INFORMATION_MESSAGE
                            );
                        } else {
                            SimpleGitDiffViewer dialog = new SimpleGitDiffViewer(project, finalFiles);
                            dialog.show();
                        }
                    });
                } else {
                    LOG.error("Git status command failed with exit code " + exitCode);
                    showError(project, "Git status command failed with exit code " + exitCode);
                }
            } catch (Exception e) {
                LOG.error("Error showing project changes", e);
                showError(project, e.getMessage());
            }
        });
    }
    

    /**
     * Show an error message
     */
    private static void showError(Project project, String message) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(
                null,
                "Error: " + message,
                "Error",
                JOptionPane.ERROR_MESSAGE
            );
        });
    }
}
