package com.zps.zest.diff;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A lightweight git diff viewer that shows diffs in GitHub style.
 * Uses JBCefBrowser for better HTML/CSS/JS rendering.
 */
public class GitHubStyleDiffViewer extends DialogWrapper {
    private static final Logger LOG = Logger.getInstance(GitHubStyleDiffViewer.class);
    private static final ExecutorService DIFF_EXECUTOR = Executors.newCachedThreadPool();

    private final Project project;
    private final String filePath;
    private final String fileStatus;
    private JBCefBrowser browser;
    private JPanel loadingPanel;
    private JPanel contentPanel;
    private JPanel errorPanel;
    private JPanel mainPanel;
    private boolean isLoading = false;

    /**
     * Constructor for creating a diff viewer dialog with async loading
     *
     * @param project The project context
     * @param filePath Path to the file being diffed
     * @param fileStatus The status of the file (e.g., "M", "A", "D", "R")
     */
    public GitHubStyleDiffViewer(Project project, String filePath, String fileStatus) {
        super(project, true);
        this.project = project;
        this.filePath = filePath;
        this.fileStatus = fileStatus;

        setTitle("Git Diff: " + Paths.get(filePath).getFileName().toString());
        setModal(false);
        setCancelButtonText("Close");

        init();

        // Show loading immediately
        showLoading();

        // Load diff content asynchronously
        loadDiffAsync();
    }

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        // Main container with card layout for different states
        mainPanel = new JPanel(new CardLayout());
        mainPanel.setBorder(new EmptyBorder(0, 0, 0, 0));

        // Create file info panel
        JPanel fileInfoPanel = createFileInfoPanel();

        // Create content panel (holds the diff)
        contentPanel = new JPanel(new BorderLayout());
        contentPanel.add(fileInfoPanel, BorderLayout.NORTH);

        // Set the content panel background color
        contentPanel.setBackground(DiffThemeUtil.getBackground());

        // Create browser panel
        JPanel browserPanel = createBrowserPanel();
        contentPanel.add(browserPanel, BorderLayout.CENTER);

        // Create loading panel
        loadingPanel = createLoadingPanel();

        // Create error panel
        errorPanel = createErrorPanel();

        // Add all panels to the card layout
        mainPanel.add(contentPanel, "content");
        mainPanel.add(loadingPanel, "loading");
        mainPanel.add(errorPanel, "error");

        // Set preferred size
        mainPanel.setPreferredSize(JBUI.size(1000, 700));

        return mainPanel;
    }

    /**
     * Creates the browser panel with JBCefBrowser
     */
    private JPanel createBrowserPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(UIUtil.getPanelBackground());
        
        try {
            // Get or create shared browser instance
            browser = DiffBrowserManager.getOrCreateGitHubStyleBrowser();
            
            Component browserComponent = browser.getComponent();
            browserComponent.setPreferredSize(new Dimension(1000, 600));
            
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
            
            panel.add(browserComponent, BorderLayout.CENTER);
            
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
        
        popup.addSeparator();
        
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
     * Creates a loading panel
     */
    private JPanel createLoadingPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(DiffThemeUtil.getBackground());

        JLabel loadingLabel = new JLabel("Loading diff...");
        loadingLabel.setFont(loadingLabel.getFont().deriveFont(Font.BOLD, 16f));
        loadingLabel.setForeground(DiffThemeUtil.getText());

        JProgressBar progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setPreferredSize(JBUI.size(300, 4));

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.insets = JBUI.insets(5);
        panel.add(loadingLabel, c);

        c.gridy = 1;
        panel.add(progressBar, c);

        return panel;
    }

    /**
     * Creates an error panel
     */
    private JPanel createErrorPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(DiffThemeUtil.getBackground());

        JLabel errorLabel = new JLabel("Failed to load diff");
        errorLabel.setFont(errorLabel.getFont().deriveFont(Font.BOLD, 16f));
        errorLabel.setForeground(JBColor.RED);

        JLabel errorDetailsLabel = new JLabel("An error occurred while loading the diff");
        errorDetailsLabel.setForeground(JBColor.GRAY);

        JButton retryButton = new JButton("Retry");
        retryButton.addActionListener(e -> loadDiffAsync());

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.insets = JBUI.insets(5);
        panel.add(errorLabel, c);

        c.gridy = 1;
        panel.add(errorDetailsLabel, c);

        c.gridy = 2;
        c.insets = JBUI.insets(20, 5, 5, 5);
        panel.add(retryButton, c);

        return panel;
    }

    /**
     * Load diff content asynchronously
     */
    private void loadDiffAsync() {
        showLoading();
        isLoading = true;

        CompletableFuture.supplyAsync(() -> {
            try {
                String projectPath = project.getBasePath();
                if (projectPath == null) {
                    throw new IllegalStateException("Project path not found");
                }

                // Clean file path if needed
                String cleanedPath = filePath;

                // Get the diff content for this file
                String diffContent = "";

                // Handle different file statuses
                switch (fileStatus) {
                    case "A":
                    case "ADDITION":
                        // For added files
                        if (isNewFile(projectPath, cleanedPath)) {
                            diffContent = getNewFileContent(projectPath, cleanedPath);
                        } else {
                            diffContent = executeGitCommand(projectPath, "git diff --cached -- \"" + cleanedPath + "\"");
                        }
                        break;
                        
                    case "D":
                    case "DELETION":
                        // For deleted files, we need to be extra careful with the git commands
                        try {
                            LOG.info("Processing deleted file diff: " + cleanedPath);
                            
                            // First, try to get the staged deletion diff
                            diffContent = executeGitCommand(projectPath, "git diff --cached -- \"" + cleanedPath + "\"");
                            
                            // If nothing is staged, try to get the file content from the last commit
                            if (diffContent.trim().isEmpty()) {
                                try {
                                    LOG.info("Trying to get file content from HEAD for diff");
                                    String content = executeGitCommand(projectPath, "git show HEAD:\"" + cleanedPath + "\"");
                                    if (!content.trim().isEmpty()) {
                                        LOG.info("Got content from HEAD, formatting as deletion diff");
                                        diffContent = formatDeletedFileDiff(cleanedPath, content);
                                    }
                                } catch (Exception e) {
                                    LOG.info("Failed to get content from HEAD, trying git log: " + e.getMessage());
                                    
                                    // If we can't get the content directly, try to find when it was last seen
                                    try {
                                        // List commits that affected this file
                                        String history = executeGitCommand(projectPath, "git log --pretty=format:\"%H\" -n 1 -- \"" + cleanedPath + "\"");
                                        if (!history.trim().isEmpty()) {
                                            String commitHash = history.trim();
                                            LOG.info("Found file in commit: " + commitHash);
                                            
                                            // Get the file content from that commit
                                            String content = executeGitCommand(projectPath, "git show " + commitHash + ":\"" + cleanedPath + "\"");
                                            if (!content.trim().isEmpty()) {
                                                LOG.info("Got content from commit " + commitHash);
                                                diffContent = formatDeletedFileDiff(cleanedPath, content);
                                            }
                                        }
                                    } catch (Exception ex) {
                                        LOG.info("Failed to get file history: " + ex.getMessage());
                                    }
                                }
                            }
                            
                            // If all methods failed, provide a simple message
                            if (diffContent.trim().isEmpty()) {
                                diffContent = "File was deleted: " + cleanedPath + "\n(Content not available)";
                            }
                        } catch (Exception e) {
                            LOG.warn("Error processing deleted file diff: " + e.getMessage(), e);
                            diffContent = "Deleted file: " + cleanedPath + "\n(Error retrieving content: " + e.getMessage() + ")";
                        }
                        break;
                        
                    case "M":
                    case "MODIFICATION":
                        // For modified files, try unstaged first, then staged
                        try {
                            diffContent = executeGitCommand(projectPath, "git diff -- \"" + cleanedPath + "\"");
                            if (diffContent.trim().isEmpty()) {
                                diffContent = executeGitCommand(projectPath, "git diff --cached -- \"" + cleanedPath + "\"");
                            }
                        } catch (Exception e) {
                            LOG.info("Error getting standard diff, trying with HEAD: " + e.getMessage());
                            diffContent = executeGitCommand(projectPath, "git diff HEAD -- \"" + cleanedPath + "\"");
                        }
                        break;
                        
                    case "R":
                    case "MOVED":
                        // For renamed files
                        diffContent = executeGitCommand(projectPath, "git diff --cached -- \"" + cleanedPath + "\"");
                        break;
                        
                    default:
                        // Default case, try normal diff first
                        try {
                            diffContent = executeGitCommand(projectPath, "git diff -- \"" + cleanedPath + "\"");
                            if (diffContent.trim().isEmpty()) {
                                diffContent = executeGitCommand(projectPath, "git diff --cached -- \"" + cleanedPath + "\"");
                            }
                        } catch (Exception e) {
                            LOG.info("Standard diff failed, trying HEAD diff: " + e.getMessage());
                            diffContent = executeGitCommand(projectPath, "git diff HEAD -- \"" + cleanedPath + "\"");
                        }
                        break;
                }

                return diffContent;
            } catch (Exception e) {
                LOG.error("Error loading diff content", e);
                throw new RuntimeException("Failed to load diff: " + e.getMessage(), e);
            }
        }, DIFF_EXECUTOR).thenAcceptAsync(diffContent -> {
            // Update UI on EDT
            SwingUtilities.invokeLater(() -> {
                try {
                    isLoading = false;
                    updateDiffContent(diffContent);
                    showContent();
                } catch (Exception e) {
                    LOG.error("Error updating diff content", e);
                    showError("Failed to process diff: " + e.getMessage());
                }
            });
        }).exceptionally(ex -> {
            // Handle errors on EDT
            SwingUtilities.invokeLater(() -> {
                isLoading = false;
                showError(ex.getMessage());
            });
            return null;
        });
    }

    /**
     * Update the diff content in the UI
     */
    private void updateDiffContent(String diffContent) {
        if (browser == null) {
            LOG.error("Browser is null, cannot update content");
            return;
        }
        
        boolean isDarkTheme = UIUtil.isUnderDarcula();
        // Use the new diff2html method for better performance
        String html = DiffHtmlGenerator.generateDiff2Html(diffContent, filePath, isDarkTheme);
        
        // Load the HTML content
        browser.loadHTML(html);
        
        // Add JavaScript handler for DevTools button
        browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser cefBrowser, CefFrame frame, int httpStatusCode) {
                // Inject JavaScript to handle DevTools button click
                cefBrowser.executeJavaScript(
                    "window.openDevTools = function() { console.log('DevTools requested from JS'); };",
                    frame.getURL(), 0
                );
            }
        }, browser.getCefBrowser());
    }

    /**
     * Show the content panel
     */
    private void showContent() {
        CardLayout cl = (CardLayout) mainPanel.getLayout();
        cl.show(mainPanel, "content");
    }

    /**
     * Show the loading panel
     */
    private void showLoading() {
        CardLayout cl = (CardLayout) mainPanel.getLayout();
        cl.show(mainPanel, "loading");
    }

    /**
     * Show the error panel with a message
     */
    private void showError(String message) {
        // Find the error details label (the second component) and update its text
        Component[] components = errorPanel.getComponents();
        for (Component component : components) {
            if (component instanceof JLabel && !((JLabel) component).getText().equals("Failed to load diff")) {
                ((JLabel) component).setText(message);
                break;
            }
        }

        CardLayout cl = (CardLayout) mainPanel.getLayout();
        cl.show(mainPanel, "error");
    }

    /**
     * Creates the file info panel at the top of the dialog
     */
    private JPanel createFileInfoPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new CompoundBorder(
                new MatteBorder(0, 0, 1, 0, DiffThemeUtil.getBorder()),
                new EmptyBorder(10, 12, 10, 12)
        ));

        // Use GitHub-style colors
        panel.setBackground(DiffThemeUtil.getHeaderBackground());

        // Create file info label with path and status
        JLabel fileInfoLabel = new JLabel(getFileNameWithStatus());
        fileInfoLabel.setFont(fileInfoLabel.getFont().deriveFont(Font.BOLD, 13f));
        fileInfoLabel.setForeground(DiffThemeUtil.getText());

        // Add file path
        JLabel pathLabel = new JLabel(filePath);
        pathLabel.setFont(pathLabel.getFont().deriveFont(11f));
        pathLabel.setForeground(JBColor.GRAY);

        JPanel leftPanel = new JPanel(new BorderLayout(5, 3));
        leftPanel.setOpaque(false);
        leftPanel.add(fileInfoLabel, BorderLayout.NORTH);
        leftPanel.add(pathLabel, BorderLayout.CENTER);

        panel.add(leftPanel, BorderLayout.WEST);

        // Add action buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttonPanel.setOpaque(false);
        
        JButton devToolsButton = new JButton("DevTools (F12)");
        devToolsButton.addActionListener(e -> {
            if (browser != null) {
                browser.openDevtools();
            }
        });
        buttonPanel.add(devToolsButton);

        panel.add(buttonPanel, BorderLayout.EAST);

        return panel;
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
     * Formats the content of a deleted file as a diff.
     * This is used when we can only get the file content but need to show it as a deletion.
     */
    private String formatDeletedFileDiff(String filePath, String content) {
        StringBuilder diff = new StringBuilder();
        diff.append("diff --git a/").append(filePath).append(" b/").append(filePath).append("\n");
        diff.append("deleted file mode 100644\n");
        diff.append("index 1234567..0000000\n");
        diff.append("--- a/").append(filePath).append("\n");
        diff.append("+++ /dev/null\n");
        
        String[] lines = content.split("\n");
        diff.append("@@ -1,").append(lines.length).append(" +0,0 @@\n");
        
        for (String line : lines) {
            diff.append("-").append(line).append("\n");
        }
        
        return diff.toString();
    }

    /**
     * Execute a git command
     */
    private String executeGitCommand(String workingDir, String command) throws Exception {
        LOG.info("Executing git command: " + command);
        
        ProcessBuilder processBuilder = new ProcessBuilder();
        if (System.getProperty("os.name").toLowerCase().contains("windows")) {
            processBuilder.command("cmd.exe", "/c", command);
        } else {
            processBuilder.command("bash", "-c", command);
        }

        processBuilder.directory(new File(workingDir));
        Process process = processBuilder.start();

        // Read the output
        StringBuilder output = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()))) {

            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        // Wait for the process to complete
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            // Read the error
            StringBuilder error = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getErrorStream()))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    error.append(line).append("\n");
                }
            }

            throw new Exception("Git command failed with exit code " + exitCode + ": " + error.toString());
        }

        return output.toString();
    }

    /**
     * Check if a file is truly new (untracked)
     */
    private boolean isNewFile(String projectPath, String filePath) {
        try {
            String result = executeGitCommand(projectPath, "git ls-files -- \"" + filePath + "\"");
            return result.trim().isEmpty(); // If empty, file is not tracked
        } catch (Exception e) {
            LOG.warn("Error checking if file is new: " + e.getMessage());
            return false;
        }
    }

    /**
     * Gets the content of a new file formatted as a diff
     */
    private String getNewFileContent(String projectPath, String filePath) {
        try {
            java.io.File file = new java.io.File(projectPath, filePath);
            if (!file.exists()) {
                return "File not found: " + filePath;
            }

            String content = new String(java.nio.file.Files.readAllBytes(file.toPath()));

            // Format as a diff showing all lines as added
            StringBuilder diff = new StringBuilder();
            diff.append("diff --git a/").append(filePath).append(" b/").append(filePath).append("\n");
            diff.append("new file mode 100644\n");
            diff.append("index 0000000..1234567\n");
            diff.append("--- /dev/null\n");
            diff.append("+++ b/").append(filePath).append("\n");

            String[] lines = content.split("\n");
            diff.append("@@ -0,0 +1,").append(lines.length).append(" @@\n");

            for (String line : lines) {
                diff.append("+").append(line).append("\n");
            }

            return diff.toString();

        } catch (Exception e) {
            LOG.error("Error reading new file content", e);
            return "Error reading file: " + e.getMessage();
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
     * Show diff for a specific file
     */
    public static void showDiff(Project project, String filePath, String fileStatus) {
        ApplicationManager.getApplication().invokeLater(() -> {
            GitHubStyleDiffViewer dialog = new GitHubStyleDiffViewer(project, filePath, fileStatus);
            dialog.show();
        });
    }
}
