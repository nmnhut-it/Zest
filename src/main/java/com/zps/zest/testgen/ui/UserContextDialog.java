package com.zps.zest.testgen.ui;

import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Dialog for users to provide additional context for test generation.
 * Allows selection of related files and pasting of code snippets.
 * @deprecated Use UnifiedTestGenerationDialog instead which combines method selection, context, and configuration
 */
@Deprecated
public class UserContextDialog extends DialogWrapper {
    private static final int DIALOG_WIDTH = 800;
    private static final int DIALOG_HEIGHT = 600;

    private final Project project;
    private final DefaultListModel<String> fileListModel;
    private final JBList<String> fileList;
    private final JBTextArea codeTextArea;
    private final List<String> selectedFilePaths;

    public UserContextDialog(@NotNull Project project) {
        super(project, true);
        this.project = project;
        this.fileListModel = new DefaultListModel<>();
        this.fileList = new JBList<>(fileListModel);
        this.codeTextArea = new JBTextArea();
        this.selectedFilePaths = new ArrayList<>();

        setTitle("Add Context for Test Generation");
        setOKButtonText("Add Context");
        setCancelButtonText("Skip");

        init();
    }

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setPreferredSize(new Dimension(DIALOG_WIDTH, DIALOG_HEIGHT));

        // Create tabbed pane
        JTabbedPane tabbedPane = new JTabbedPane();

        // File selection tab
        JPanel filePanel = createFileSelectionPanel();
        tabbedPane.addTab("Related Files", filePanel);

        // Code snippet tab
        JPanel codePanel = createCodeSnippetPanel();
        tabbedPane.addTab("Code Snippets", codePanel);

        mainPanel.add(tabbedPane, BorderLayout.CENTER);

        // Add instruction label at top
        JLabel instructionLabel = new JLabel(
            "<html><b>Optional:</b> Add files or code snippets that will help the AI understand your code better.<br>" +
            "This context will be analyzed first before generating tests.</html>"
        );
        instructionLabel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        mainPanel.add(instructionLabel, BorderLayout.NORTH);

        return mainPanel;
    }

    private JPanel createFileSelectionPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Instructions
        JLabel label = new JLabel("Select files that are related to the code you want to test:");
        panel.add(label, BorderLayout.NORTH);

        // File list
        fileList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        JBScrollPane scrollPane = new JBScrollPane(fileList);
        panel.add(scrollPane, BorderLayout.CENTER);

        // Button panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        // Add files button
        JButton addFilesButton = new JButton("Add Files...");
        addFilesButton.addActionListener(e -> addFiles());
        buttonPanel.add(addFilesButton);

        // Add recent files button
        JButton recentFilesButton = new JButton("Add Recent Files");
        recentFilesButton.addActionListener(e -> addRecentFiles());
        buttonPanel.add(recentFilesButton);

        // Remove selected button
        JButton removeButton = new JButton("Remove Selected");
        removeButton.addActionListener(e -> removeSelectedFiles());
        buttonPanel.add(removeButton);

        // Clear all button
        JButton clearButton = new JButton("Clear All");
        clearButton.addActionListener(e -> fileListModel.clear());
        buttonPanel.add(clearButton);

        panel.add(buttonPanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createCodeSnippetPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Instructions
        JLabel label = new JLabel(
            "<html>Paste code snippets, configurations, or examples that might help understand the code:<br>" +
            "(e.g., API responses, database schemas, configuration files)</html>"
        );
        panel.add(label, BorderLayout.NORTH);

        // Text area
        codeTextArea.setRows(20);
        codeTextArea.setColumns(60);
        codeTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JBScrollPane scrollPane = new JBScrollPane(codeTextArea);
        panel.add(scrollPane, BorderLayout.CENTER);

        // Button panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        // Clear button
        JButton clearButton = new JButton("Clear");
        clearButton.addActionListener(e -> codeTextArea.setText(""));
        buttonPanel.add(clearButton);

        // Add placeholder examples
        JButton exampleButton = new JButton("Show Example");
        exampleButton.addActionListener(e -> showCodeExample());
        buttonPanel.add(exampleButton);

        panel.add(buttonPanel, BorderLayout.SOUTH);

        return panel;
    }

    private void addFiles() {
        FileChooserDescriptor descriptor = new FileChooserDescriptor(
            true,  // files
            false, // directories
            false, // jars
            false, // archives
            false, // jars as contents
            true   // multiple selection
        );
        descriptor.setTitle("Select Related Files");
        descriptor.setDescription("Choose files that are related to the code you want to test");

        VirtualFile[] files = FileChooser.chooseFiles(descriptor, project, null);
        for (VirtualFile file : files) {
            String path = file.getPath();
            if (!containsPath(path)) {
                fileListModel.addElement(path);
            }
        }
    }

    private void addRecentFiles() {
        FileEditorManager editorManager = FileEditorManager.getInstance(project);
        VirtualFile[] openFiles = editorManager.getOpenFiles();

        if (openFiles.length == 0) {
            Messages.showInfoMessage(project, "No recently opened files found", "No Recent Files");
            return;
        }

        for (VirtualFile file : openFiles) {
            String path = file.getPath();
            if (!containsPath(path) && isRelevantFile(file)) {
                fileListModel.addElement(path);
            }
        }
    }

    private boolean isRelevantFile(VirtualFile file) {
        String name = file.getName();
        // Filter for relevant file types
        return name.endsWith(".java") || name.endsWith(".kt") ||
               name.endsWith(".xml") || name.endsWith(".properties") ||
               name.endsWith(".json") || name.endsWith(".yaml") ||
               name.endsWith(".yml") || name.endsWith(".sql");
    }

    private boolean containsPath(String path) {
        for (int i = 0; i < fileListModel.size(); i++) {
            if (fileListModel.get(i).equals(path)) {
                return true;
            }
        }
        return false;
    }

    private void removeSelectedFiles() {
        int[] selectedIndices = fileList.getSelectedIndices();
        // Remove in reverse order to maintain indices
        for (int i = selectedIndices.length - 1; i >= 0; i--) {
            fileListModel.remove(selectedIndices[i]);
        }
    }

    private void showCodeExample() {
        String example = """
            // Example: Database schema
            CREATE TABLE users (
                id BIGINT PRIMARY KEY,
                username VARCHAR(50) NOT NULL,
                email VARCHAR(100) NOT NULL
            );

            // Example: API response format
            {
                "status": "success",
                "data": {
                    "userId": 123,
                    "roles": ["admin", "user"]
                }
            }

            // Example: Configuration
            spring.datasource.url=jdbc:postgresql://localhost:5432/testdb
            spring.jpa.hibernate.ddl-auto=update
            """;

        codeTextArea.setText(example);
    }

    /**
     * Get the list of selected file paths.
     */
    @NotNull
    public List<String> getSelectedFiles() {
        List<String> files = new ArrayList<>();
        for (int i = 0; i < fileListModel.size(); i++) {
            files.add(fileListModel.get(i));
        }
        return files;
    }

    /**
     * Get the user-provided code snippets.
     */
    @Nullable
    public String getProvidedCode() {
        String code = codeTextArea.getText();
        return code != null && !code.trim().isEmpty() ? code : null;
    }

    /**
     * Check if user provided any context.
     */
    public boolean hasContext() {
        return !getSelectedFiles().isEmpty() || getProvidedCode() != null;
    }
}