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
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.*;
import java.io.File;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A lightweight git diff viewer that shows diffs in GitHub style.
 * Uses a JEditorPane with HTML rendering to display diff content.
 */
public class GitHubStyleDiffViewer extends DialogWrapper {
    private static final Logger LOG = Logger.getInstance(GitHubStyleDiffViewer.class);
    private static final ExecutorService DIFF_EXECUTOR = Executors.newCachedThreadPool();

    private final Project project;
    private final String filePath;
    private final String fileStatus;
    private JEditorPane diffPane;
    private JPanel loadingPanel;
    private JPanel contentPanel;
    private JPanel errorPanel;
    private JPanel mainPanel;

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

        // Create diff pane
        diffPane = createDiffPane();
        JBScrollPane scrollPane = new JBScrollPane(diffPane);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        contentPanel.add(scrollPane, BorderLayout.CENTER);

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
                    updateDiffContent(diffContent);
                    showContent();
                } catch (Exception e) {
                    LOG.error("Error updating diff content", e);
                    showError("Failed to process diff: " + e.getMessage());
                }
            });
        }).exceptionally(ex -> {
            // Handle errors on EDT
            SwingUtilities.invokeLater(() -> showError(ex.getMessage()));
            return null;
        });
    }

    /**
     * Update the diff content in the UI
     */
    private void updateDiffContent(String diffContent) {
        if (diffContent == null || diffContent.trim().isEmpty()) {
            diffPane.setText("<html><body style='padding: 20px;'><h3>No changes found for this file</h3></body></html>");
        } else {
            // Create a minimal HTML document with style scoped to this document only
            String html = formatDiffAsHtml(diffContent);
            diffPane.setText(html);

            // Force a repaint to ensure proper rendering
            diffPane.invalidate();
            diffPane.repaint();
        }
        diffPane.setCaretPosition(0);
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
        pane.setBackground(DiffThemeUtil.getBackground());

        // Get editor font and size from current IDE settings
        EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
        EditorFontType plain = EditorFontType.PLAIN;
        Font font = scheme.getFont(plain);
        Font editorFont = font;

        // Setup HTML styling
        HTMLEditorKit kit = new HTMLEditorKit();
        pane.setEditorKit(kit);

        // Add CSS styles for GitHub-like diff
        StyleSheet styleSheet = kit.getStyleSheet();

        // Add custom CSS
        styleSheet.addRule(DiffThemeUtil.getGithubStyleCss());

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
        html.append("<html><head><style type='text/css'>");
        html.append(DiffThemeUtil.getGithubStyleCss());
        html.append("</style></head><body>");

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
                // Addition line - simplify and use class only
                html.append("<tr class='addition'>");
                html.append("<td class='line-number'></td>");
                html.append("<td class='line-number'>").append(newLineNumber++).append("</td>");
                html.append("<td class='line-content'><strong style='color:" +
                        colorToHex(JBColor.isBright() ? new Color(0x22863a) : new Color(0x85e89d)) +
                        ";'>+</strong>").append(syntaxHighlight(line.substring(1))).append("</td>");
                html.append("</tr>");
            } else if (line.startsWith("-")) {
                // Deletion line - simplify and use class only
                html.append("<tr class='deletion'>");
                html.append("<td class='line-number'>").append(oldLineNumber++).append("</td>");
                html.append("<td class='line-number'></td>");
                html.append("<td class='line-content'><strong style='color:" +
                        colorToHex(JBColor.isBright() ? new Color(0xd73a49) : new Color(0xff7b72)) +
                        ";'>-</strong>").append(syntaxHighlight(line.substring(1))).append("</td>");
                html.append("</tr>");
            } else {
                // Context line
                html.append("<tr class='diff-line'>");
                html.append("<td class='line-number'>").append(oldLineNumber++).append("</td>");
                html.append("<td class='line-number'>").append(newLineNumber++).append("</td>");
                html.append("<td class='line-content'>").append(syntaxHighlight(line.startsWith(" ") ? line.substring(1) : line)).append("</td>");
                html.append("</tr>");
            }

            i++;
        }

        html.append("</table>");
        html.append("</body></html>");

        return html.toString();
    }

    /**
     * Apply basic syntax highlighting to code
     */
    private String syntaxHighlight(String code) {
        String escaped = escapeHtml(code);

        // Very basic syntax highlighting for Java
        // Keywords
        escaped = escaped.replaceAll("\\b(abstract|assert|boolean|break|byte|case|catch|char|class|const|continue|default|do|double|else|enum|extends|final|finally|float|for|goto|if|implements|import|instanceof|int|interface|long|native|new|package|private|protected|public|return|short|static|strictfp|super|switch|synchronized|this|throw|throws|transient|try|void|volatile|while|true|false|null)\\b", "<span style='color:" + (JBColor.isBright() ? "#0033b3" : "#cc7832") + ";'>$1</span>");

        // Strings
        escaped = escaped.replaceAll("(&quot;.*?&quot;)", "<span style='color:" + (JBColor.isBright() ? "#008000" : "#6a8759") + ";'>$1</span>");

        // Comments
        escaped = escaped.replaceAll("(//.*)$", "<span style='color:" + (JBColor.isBright() ? "#808080" : "#808080") + ";'>$1</span>");

        return escaped;
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
     * Convert a Color to hex string
     */
    private String colorToHex(Color color) {
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
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