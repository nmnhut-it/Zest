package com.zps.zest.diff;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.zps.zest.browser.GitService;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * A simple git diff viewer that uses JBCefBrowser to display HTML-formatted diffs
 * in a lightweight, native IntelliJ dialog.
 */
public class SimpleGitDiffViewer extends DialogWrapper {
    private static final Logger LOG = Logger.getInstance(SimpleGitDiffViewer.class);
    
    private final Project project;
    private final JPanel mainPanel;
    private final JBList<FileStatus> fileList;
    private final DefaultListModel<FileStatus> listModel;
    private final GitService gitService;
    private JBCefBrowser browser;
    private JPanel browserPanel;
    
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
        
        public String getStatusLabel() {
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
        
        // Set up the browser panel
        browserPanel = createBrowserPanel();
        
        // Add a refresh button and actions at the top
        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(e -> refreshChangedFiles());
        actionPanel.add(refreshButton);
        
        JButton confirmButton = new JButton("Confirm Selected");
        confirmButton.addActionListener(e -> confirmSelectedFile());
        actionPanel.add(confirmButton);
        
        JButton devToolsButton = new JButton("DevTools (F12)");
        devToolsButton.addActionListener(e -> {
            if (browser != null) {
                browser.openDevtools();
            }
        });
        actionPanel.add(devToolsButton);
        
        // Layout components
        leftPanel.add(new JLabel("Changed Files:"), BorderLayout.NORTH);
        leftPanel.add(new JBScrollPane(fileList), BorderLayout.CENTER);
        
        mainPanel.add(actionPanel, BorderLayout.NORTH);
        mainPanel.add(leftPanel, BorderLayout.WEST);
        mainPanel.add(browserPanel, BorderLayout.CENTER);
        
        // Set preferred sizes
        leftPanel.setPreferredSize(JBUI.size(300, 600));
        mainPanel.setPreferredSize(JBUI.size(1000, 700));
        
        // Populate list
        populateFileList();
        
        init();
    }
    
    /**
     * Create the browser panel with JBCefBrowser
     */
    private JPanel createBrowserPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(UIUtil.getPanelBackground());
        
        try {
            // Get or create shared browser instance
            browser = DiffBrowserManager.getOrCreateSimpleDiffBrowser();
            
            // Set up the browser component
            Component browserComponent = browser.getComponent();
            browserComponent.setPreferredSize(new Dimension(700, 600));
            
            // Add keyboard shortcuts
            browserComponent.addKeyListener(new java.awt.event.KeyAdapter() {
                @Override
                public void keyPressed(java.awt.event.KeyEvent e) {
                    if (e.getKeyCode() == java.awt.event.KeyEvent.VK_F12) {
                        browser.openDevtools();
                    }
                }
            });
            
            // Add context menu
            browserComponent.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (e.isPopupTrigger() || e.getButton() == MouseEvent.BUTTON3) {
                        showContextMenu(e);
                    }
                }
                
                @Override
                public void mouseReleased(MouseEvent e) {
                    if (e.isPopupTrigger()) {
                        showContextMenu(e);
                    }
                }
            });
            
            // Add load handler to handle navigation
            browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
                @Override
                public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                    // Handle any post-load operations
                    LOG.info("Page loaded with status: " + httpStatusCode);
                }
            }, browser.getCefBrowser());
            
            panel.add(browserComponent, BorderLayout.CENTER);
            
            // Load initial content
            showWelcomeMessage();
            
        } catch (Exception e) {
            LOG.error("Error creating browser panel", e);
            JLabel errorLabel = new JLabel("Error creating browser: " + e.getMessage());
            errorLabel.setForeground(JBColor.RED);
            panel.add(errorLabel, BorderLayout.CENTER);
        }
        
        return panel;
    }
    
    /**
     * Show context menu
     */
    private void showContextMenu(MouseEvent e) {
        JPopupMenu popup = new JPopupMenu();
        
        JMenuItem devToolsItem = new JMenuItem("Open DevTools");
        devToolsItem.addActionListener(event -> browser.openDevtools());
        popup.add(devToolsItem);
        
        JMenuItem reloadItem = new JMenuItem("Reload");
        reloadItem.addActionListener(event -> browser.getCefBrowser().reload());
        popup.add(reloadItem);
        
        JMenuItem zoomInItem = new JMenuItem("Zoom In");
        zoomInItem.addActionListener(event -> browser.setZoomLevel(browser.getZoomLevel() + 0.1));
        popup.add(zoomInItem);
        
        JMenuItem zoomOutItem = new JMenuItem("Zoom Out");
        zoomOutItem.addActionListener(event -> browser.setZoomLevel(browser.getZoomLevel() - 0.1));
        popup.add(zoomOutItem);
        
        JMenuItem resetZoomItem = new JMenuItem("Reset Zoom");
        resetZoomItem.addActionListener(event -> browser.setZoomLevel(0.0));
        popup.add(resetZoomItem);
        
        popup.show(e.getComponent(), e.getX(), e.getY());
    }
    
    /**
     * Show welcome message
     */
    private void showWelcomeMessage() {
        String html = "<!DOCTYPE html><html><head>" +
                     "<style>body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; " +
                     "display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; " +
                     "background-color: " + (UIUtil.isUnderDarcula() ? "#0d1117" : "#ffffff") + "; " +
                     "color: " + (UIUtil.isUnderDarcula() ? "#c9d1d9" : "#24292e") + "; }</style>" +
                     "</head><body>" +
                     "<div style='text-align: center;'>" +
                     "<h2>Git Diff Viewer</h2>" +
                     "<p>Select a file from the list to view its diff</p>" +
                     "</div></body></html>";
        browser.loadHTML(html);
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
            showWelcomeMessage();
            return;
        }
        
        // Show loading message
        String loadingHtml = "<!DOCTYPE html><html><head>" +
                           "<style>body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; " +
                           "display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; " +
                           "background-color: " + (UIUtil.isUnderDarcula() ? "#0d1117" : "#ffffff") + "; " +
                           "color: " + (UIUtil.isUnderDarcula() ? "#c9d1d9" : "#24292e") + "; }</style>" +
                           "</head><body>" +
                           "<div style='text-align: center;'>" +
                           "<h3>Loading diff...</h3>" +
                           "<p style='color: " + (UIUtil.isUnderDarcula() ? "#8b949e" : "#6e7781") + ";'>Please wait</p>" +
                           "</div></body></html>";
        browser.loadHTML(loadingHtml);
        
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
                    
                    // Generate HTML using DiffHtmlGenerator
                    boolean isDarkTheme = UIUtil.isUnderDarcula();
                    String htmlDiff = DiffHtmlGenerator.generateDiffHtml(diffText, selected.getPath(), isDarkTheme);
                    
                    // Update UI on EDT
                    SwingUtilities.invokeLater(() -> {
                        browser.loadHTML(htmlDiff);
                    });
                } else {
                    String error = response.get("error").getAsString();
                    SwingUtilities.invokeLater(() -> {
                        String errorHtml = "<!DOCTYPE html><html><head>" +
                                         "<style>body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; " +
                                         "padding: 20px; background-color: " + (UIUtil.isUnderDarcula() ? "#0d1117" : "#ffffff") + "; " +
                                         "color: " + (UIUtil.isUnderDarcula() ? "#c9d1d9" : "#24292e") + "; }</style>" +
                                         "</head><body>" +
                                         "<h3 style='color: " + (UIUtil.isUnderDarcula() ? "#f85149" : "#d73a49") + ";'>Error loading diff</h3>" +
                                         "<p>" + error + "</p>" +
                                         "</body></html>";
                        browser.loadHTML(errorHtml);
                    });
                }
            } catch (Exception e) {
                LOG.error("Error showing diff", e);
                SwingUtilities.invokeLater(() -> {
                    String errorHtml = "<!DOCTYPE html><html><head>" +
                                     "<style>body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; " +
                                     "padding: 20px; background-color: " + (UIUtil.isUnderDarcula() ? "#0d1117" : "#ffffff") + "; " +
                                     "color: " + (UIUtil.isUnderDarcula() ? "#c9d1d9" : "#24292e") + "; }</style>" +
                                     "</head><body>" +
                                     "<h3 style='color: " + (UIUtil.isUnderDarcula() ? "#f85149" : "#d73a49") + ";'>Error loading diff</h3>" +
                                     "<p>" + e.getMessage() + "</p>" +
                                     "</body></html>";
                    browser.loadHTML(errorHtml);
                });
            }
        });
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
                            showWelcomeMessage();
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
     * Clean up resources when dialog is disposed
     */
    @Override
    protected void dispose() {
        // Don't dispose the shared browser - just clear our reference
        browser = null;
        super.dispose();
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
