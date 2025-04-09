package com.zps.zest;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Enhanced dialog for managing the knowledge base files.
 * Supports adding individual files, entire directories, and recursive scanning.
 */
public class KnowledgeBaseManagerDialog extends DialogWrapper {
    private static final Logger LOG = Logger.getInstance(KnowledgeBaseManagerDialog.class);

    private final Project project;
    private final KnowledgeBaseManager kbManager;

    private JBTable fileTable;
    private DefaultTableModel tableModel;
    private JTextField extensionFilterField;
    private JCheckBox recursiveCheckbox;
    private JLabel statusLabel;

    public KnowledgeBaseManagerDialog(Project project, KnowledgeBaseManager kbManager) {
        super(project);
        this.project = project;
        this.kbManager = kbManager;
        setTitle("Knowledge Base Manager");
        setSize(700, 500);
        init();
    }

    @Override
    @Nullable
    protected JComponent createCenterPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setPreferredSize(new Dimension(700, 500));

        // Create file table
        createFileTable();
        JBScrollPane tableScrollPane = new JBScrollPane(fileTable);
        tableScrollPane.setBorder(JBUI.Borders.empty(8));

        // Create control panel
        JPanel controlPanel = createControlPanel();

        // Add components to main panel
        mainPanel.add(tableScrollPane, BorderLayout.CENTER);
        mainPanel.add(controlPanel, BorderLayout.SOUTH);

        // Set up status label
        statusLabel = new JLabel("Ready");
        statusLabel.setBorder(JBUI.Borders.empty(5));
        mainPanel.add(statusLabel, BorderLayout.NORTH);

        return mainPanel;
    }

    private void createFileTable() {
        // Define table columns
        String[] columns = {"File Name", "Path/ID", "Status"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        fileTable = new JBTable(tableModel) {
            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
                Component comp = super.prepareRenderer(renderer, row, column);
                String status = (String) getModel().getValueAt(row, 2);
                if ("Failed".equals(status)) {
                    comp.setForeground(Color.RED);
                } else if ("Uploaded".equals(status)) {
                    comp.setForeground(new Color(0, 150, 0)); // Dark green
                } else {
                    comp.setForeground(null); // Default color
                }
                return comp;
            }
        };

        fileTable.getColumnModel().getColumn(0).setPreferredWidth(150);
        fileTable.getColumnModel().getColumn(1).setPreferredWidth(400);
        fileTable.getColumnModel().getColumn(2).setPreferredWidth(80);
        fileTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        fileTable.setRowHeight(22);
    }

    private JPanel createControlPanel() {
        JPanel controlPanel = new JPanel();
        controlPanel.setLayout(new BoxLayout(controlPanel, BoxLayout.Y_AXIS));
        controlPanel.setBorder(JBUI.Borders.empty(0, 8, 8, 8));

        // File extension filter panel
        JPanel filterPanel = new JPanel(new BorderLayout());
        filterPanel.setBorder(new TitledBorder("File Filters"));

        JPanel extensionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        extensionPanel.add(new JLabel("Extensions:"));

        extensionFilterField = new JTextField(25);
        extensionFilterField.setToolTipText("Comma-separated list of file extensions (e.g., java,xml,json)");
        extensionPanel.add(extensionFilterField);

        recursiveCheckbox = new JCheckBox("Process Subdirectories", true);
        extensionPanel.add(recursiveCheckbox);

        filterPanel.add(extensionPanel, BorderLayout.CENTER);

        // Support extensions info
        Set<String> supportedExtensions = kbManager.getSupportedExtensions();
        JLabel supportedLabel = new JLabel("Supported: " + String.join(", ", supportedExtensions));
        supportedLabel.setBorder(JBUI.Borders.empty(0, 5, 5, 5));
        filterPanel.add(supportedLabel, BorderLayout.SOUTH);

        // Action buttons panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        JButton addFileButton = new JButton("Add File");
        addFileButton.addActionListener(e -> addFile());

        JButton addDirectoryButton = new JButton("Add Directory");
        addDirectoryButton.addActionListener(e -> addDirectory());

        JButton refreshButton = new JButton("Refresh File List");
        refreshButton.addActionListener(e -> refreshFiles());

        JButton removeButton = new JButton("Remove Selected");
        removeButton.addActionListener(e -> removeSelectedFiles());

        buttonPanel.add(addFileButton);
        buttonPanel.add(addDirectoryButton);
        buttonPanel.add(refreshButton);
        buttonPanel.add(removeButton);

        // Add panels to control panel
        controlPanel.add(filterPanel);
        controlPanel.add(buttonPanel);

        return controlPanel;
    }

    private void addFile() {
        FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor();
        descriptor.setTitle("Select File to Add to Knowledge Base");

        VirtualFile file = FileChooser.chooseFile(descriptor, project, null);
        if (file != null) {
            ProgressManager.getInstance().run(new Task.Backgroundable(project, "Uploading File", false) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    try {
                        indicator.setIndeterminate(false);
                        indicator.setText("Uploading " + file.getName());
                        indicator.setFraction(0.1);

                        Path filePath = Paths.get(file.getPath());
                        String fileId = kbManager.uploadFile(filePath);

                        indicator.setFraction(1.0);

                        SwingUtilities.invokeLater(() -> {
                            if (fileId != null) {
                                addFileToTable(file.getName(), file.getPath(), fileId, "Uploaded");
                                statusLabel.setText("File uploaded successfully: " + file.getName());
                            } else {
                                addFileToTable(file.getName(), file.getPath(), "", "Failed");
                                statusLabel.setText("Failed to upload file: " + file.getName());
                            }
                        });
                    } catch (Exception e) {
                        LOG.error("Error uploading file", e);
                        SwingUtilities.invokeLater(() -> {
                            addFileToTable(file.getName(), file.getPath(), "", "Failed");
                            statusLabel.setText("Error: " + e.getMessage());
                        });
                    }
                }
            });
        }
    }

    private void addDirectory() {
        FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor();
        descriptor.setTitle("Select Directory to Add to Knowledge Base");

        VirtualFile directory = FileChooser.chooseFile(descriptor, project, null);
        if (directory != null) {
            // Get file extensions from input field
            List<String> extensions = parseExtensions(extensionFilterField.getText());
            boolean recursive = recursiveCheckbox.isSelected();

            ProgressManager.getInstance().run(new Task.Backgroundable(project, "Indexing Files", true) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    try {
                        indicator.setIndeterminate(false);
                        indicator.setText("Scanning directory: " + directory.getName());
                        indicator.setFraction(0.0);

                        Path dirPath = Paths.get(directory.getPath());
                        List<KnowledgeBaseManager.UploadResult> results =
                                kbManager.uploadDirectory(dirPath, extensions, recursive);

                        // Process results
                        AtomicInteger successCount = new AtomicInteger(0);
                        AtomicInteger failCount = new AtomicInteger(0);

                        for (int i = 0; i < results.size(); i++) {
                            KnowledgeBaseManager.UploadResult result = results.get(i);

                            // Update progress
                            final int currentIndex = i;
                            indicator.setText("Processing: " + result.getFilePath());
                            indicator.setFraction((double) currentIndex / results.size());

                            if (result.isSuccess()) {
                                successCount.incrementAndGet();
                            } else {
                                failCount.incrementAndGet();
                            }

                            // Add to table in batches to avoid UI freezing
                            if (i % 10 == 0 || i == results.size() - 1) {
                                final int startIdx = Math.max(0, i - 9);
                                final int endIdx = i;

                                SwingUtilities.invokeLater(() -> {
                                    for (int j = startIdx; j <= endIdx; j++) {
                                        if (j < results.size()) {
                                            KnowledgeBaseManager.UploadResult res = results.get(j);
                                            Path path = Paths.get(res.getFilePath());
                                            String fileName = path.getFileName().toString();
                                            String status = res.isSuccess() ? "Uploaded" : "Failed";
                                            addFileToTable(fileName, res.getFilePath(), res.getFileId(), status);
                                        }
                                    }
                                });
                            }
                        }

                        indicator.setFraction(1.0);

                        // Update status
                        SwingUtilities.invokeLater(() -> {
                            statusLabel.setText(String.format(
                                    "Processed %d files: %d uploaded, %d failed",
                                    results.size(), successCount.get(), failCount.get()));
                        });
                    } catch (Exception e) {
                        LOG.error("Error processing directory", e);
                        SwingUtilities.invokeLater(() -> {
                            statusLabel.setText("Error: " + e.getMessage());
                        });
                    }
                }
            });
        }
    }

    private void refreshFiles() {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Refreshing File List", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    indicator.setIndeterminate(true);
                    indicator.setText("Fetching file list...");

                    List<KnowledgeBaseManager.KnowledgeBaseFile> files = kbManager.listFiles();

                    SwingUtilities.invokeLater(() -> {
                        tableModel.setRowCount(0);
                        for (KnowledgeBaseManager.KnowledgeBaseFile file : files) {
                            addFileToTable(file.getName(), file.getId(), file.getId(), "Uploaded");
                        }
                        statusLabel.setText("File list refreshed: " + files.size() + " files found");
                    });
                } catch (Exception e) {
                    LOG.error("Error refreshing file list", e);
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("Error refreshing file list: " + e.getMessage());
                    });
                }
            }
        });
    }

    private void removeSelectedFiles() {
        int[] selectedRows = fileTable.getSelectedRows();
        if (selectedRows.length == 0) {
            return;
        }

        int result = Messages.showYesNoDialog(
                project,
                "Remove " + selectedRows.length + " file(s) from the knowledge base?",
                "Confirm Removal",
                Messages.getQuestionIcon()
        );

        if (result != Messages.YES) {
            return;
        }

        List<String> fileIds = new ArrayList<>();
        for (int row : selectedRows) {
            String fileId = (String) tableModel.getValueAt(row, 1);
            fileIds.add(fileId);
        }

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Removing Files", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);

                int successCount = 0;
                for (int i = 0; i < fileIds.size(); i++) {
                    String fileId = fileIds.get(i);

                    indicator.setText("Removing file ID: " + fileId);
                    indicator.setFraction((double) i / fileIds.size());

                    if (kbManager.removeFile(fileId)) {
                        successCount++;
                    }
                }

                int finalSuccessCount = successCount;
                SwingUtilities.invokeLater(() -> {
                    // Refresh the table
                    refreshFiles();
                    statusLabel.setText("Removed " + finalSuccessCount + " of " + fileIds.size() + " files");
                });
            }
        });
    }

    private void addFileToTable(String fileName, String path, String fileId, String status) {
        tableModel.addRow(new Object[]{fileName, path, status});
    }

    private List<String> parseExtensions(String extensionsText) {
        List<String> extensions = new ArrayList<>();
        if (extensionsText == null || extensionsText.trim().isEmpty()) {
            return extensions;
        }

        // Split by comma and trim each extension
        String[] parts = extensionsText.split(",");
        for (String part : parts) {
            String ext = part.trim().toLowerCase();
            if (!ext.isEmpty()) {
                // Remove leading dot if present
                if (ext.startsWith(".")) {
                    ext = ext.substring(1);
                }
                extensions.add(ext);
            }
        }

        return extensions;
    }

    @Override
    protected void doOKAction() {
        super.doOKAction();
    }

    @Override
    protected Action[] createActions() {
        return new Action[]{getOKAction(), getCancelAction()};
    }
}