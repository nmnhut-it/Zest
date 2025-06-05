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
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.zps.zest.rag.OpenWebUIRagAgent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Enhanced dialog for managing the knowledge base with persistent file tracking.
 */
public class KnowledgeBaseManagerDialog extends DialogWrapper {
    private static final Logger LOG = Logger.getInstance(KnowledgeBaseManagerDialog.class);

    private final Project project;
    private final PersistentRagManager ragManager;

    private JBTable fileTable;
    private DefaultTableModel tableModel;
    private JTextField extensionFilterField;
    private JCheckBox recursiveCheckbox;
    private JLabel statusLabel;
    private JProgressBar progressBar;

    /**
     * Creates a new enhanced knowledge base manager dialog.
     */
    public KnowledgeBaseManagerDialog(Project project, PersistentRagManager ragManager) {
        super(project);
        this.project = project;
        this.ragManager = ragManager;
        setTitle("Enhanced Knowledge Base Manager");
        setSize(800, 600);
        init();
    }

    @Override
    @Nullable
    protected JComponent createCenterPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setPreferredSize(new Dimension(800, 600));

        // Create file table
        createFileTable();
        JBScrollPane tableScrollPane = new JBScrollPane(fileTable);
        tableScrollPane.setBorder(JBUI.Borders.empty(8));

        // Create control panel
        JPanel controlPanel = createControlPanel();

        // Add components to main panel
        mainPanel.add(tableScrollPane, BorderLayout.CENTER);
        mainPanel.add(controlPanel, BorderLayout.SOUTH);

        // Set up status panel
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusLabel = new JLabel("Ready");
        statusLabel.setBorder(JBUI.Borders.empty(5));
        progressBar = new JProgressBar();
        progressBar.setVisible(false);

        statusPanel.add(statusLabel, BorderLayout.CENTER);
        statusPanel.add(progressBar, BorderLayout.EAST);

        mainPanel.add(statusPanel, BorderLayout.NORTH);

        // Load tracked files
        loadTrackedFiles();

        return mainPanel;
    }

    /**
     * Creates the file table.
     */
    private void createFileTable() {
        // Define table columns
        String[] columns = {"File Name", "Path", "File ID", "Last Modified", "Status"};
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
                String status = (String) getModel().getValueAt(row, 4);
                if ("Failed".equals(status)) {
                    comp.setForeground(Color.RED);
                } else if ("Synced".equals(status)) {
                    comp.setForeground(new Color(0, 150, 0)); // Dark green
                } else if ("Modified".equals(status)) {
                    comp.setForeground(new Color(200, 120, 0)); // Orange
                } else {
                    comp.setForeground(null); // Default color
                }
                return comp;
            }
        };

        fileTable.getColumnModel().getColumn(0).setPreferredWidth(150); // File Name
        fileTable.getColumnModel().getColumn(1).setPreferredWidth(300); // Path
        fileTable.getColumnModel().getColumn(2).setPreferredWidth(200); // File ID
        fileTable.getColumnModel().getColumn(3).setPreferredWidth(150); // Last Modified
        fileTable.getColumnModel().getColumn(4).setPreferredWidth(80);  // Status

        fileTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        fileTable.setRowHeight(22);
    }

    /**
     * Creates the control panel.
     */
    private JPanel createControlPanel() {
        JPanel controlPanel = new JPanel();
        controlPanel.setLayout(new BoxLayout(controlPanel, BoxLayout.Y_AXIS));
        controlPanel.setBorder(JBUI.Borders.empty(0, 8, 8, 8));

        // RAG Indexing Panel
        JPanel ragPanel = new JPanel(new BorderLayout());
        ragPanel.setBorder(new TitledBorder("Code Indexing (RAG)"));
        
        JPanel ragControlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        
        JButton indexProjectButton = new JButton("Index Project");
        indexProjectButton.setToolTipText("Index all project code for intelligent search");
        indexProjectButton.addActionListener(e -> indexProject());
        
        JButton refreshIndexButton = new JButton("Refresh Index");
        refreshIndexButton.setToolTipText("Re-index the entire project");
        refreshIndexButton.addActionListener(e -> refreshIndex());
        
        ConfigurationManager config = ConfigurationManager.getInstance(project);
        String knowledgeId = config.getKnowledgeId();
        JLabel indexStatusLabel = new JLabel(knowledgeId != null ? 
            "Status: Indexed (ID: " + knowledgeId.substring(0, Math.min(8, knowledgeId.length())) + "...)" : 
            "Status: Not indexed");
        
        ragControlPanel.add(indexProjectButton);
        ragControlPanel.add(refreshIndexButton);
        ragControlPanel.add(indexStatusLabel);
        
        ragPanel.add(ragControlPanel, BorderLayout.CENTER);

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
        Set<String> supportedExtensions = ragManager.getKnowledgeBaseManager().getSupportedExtensions();
        JLabel supportedLabel = new JLabel("Supported: " + String.join(", ", supportedExtensions));
        supportedLabel.setBorder(JBUI.Borders.empty(0, 5, 5, 5));
        filterPanel.add(supportedLabel, BorderLayout.SOUTH);

        // Action buttons panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));

        JButton addFileButton = new JButton("Add File");
        addFileButton.addActionListener(e -> addFile());

        JButton addDirectoryButton = new JButton("Add Directory");
        addDirectoryButton.addActionListener(e -> addDirectory());

        JButton refreshButton = new JButton("Refresh & Validate");
        refreshButton.addActionListener(e -> refreshAndValidate());

        JButton removeButton = new JButton("Remove Selected");
        removeButton.addActionListener(e -> removeSelectedFiles());

        buttonPanel.add(addFileButton);
        buttonPanel.add(addDirectoryButton);
        buttonPanel.add(refreshButton);
        buttonPanel.add(removeButton);

        // Add panels to control panel
        controlPanel.add(ragPanel);
        controlPanel.add(filterPanel);
        controlPanel.add(buttonPanel);

        return controlPanel;
    }

    /**
     * Indexes the project for RAG.
     */
    private void indexProject() {
        int result = Messages.showYesNoDialog(
            project,
            "This will index all Java and Kotlin files in your project.\n" +
            "The process may take a few minutes for large projects.\n\n" +
            "Do you want to proceed?",
            "Index Project",
            Messages.getQuestionIcon()
        );
        
        if (result == Messages.YES) {
            OpenWebUIRagAgent openWebUIRagAgent = OpenWebUIRagAgent.getInstance(project);
            openWebUIRagAgent.indexProject(false);
            
            Messages.showInfoMessage(
                "Project indexing started in background.\n" +
                "You can continue working while indexing proceeds.",
                "Indexing Started"
            );
        }
    }
    
    /**
     * Refreshes the project index.
     */
    private void refreshIndex() {
        int result = Messages.showYesNoDialog(
            project,
            "This will remove the existing index and re-index all files.\n" +
            "Do you want to proceed?",
            "Refresh Index",
            Messages.getQuestionIcon()
        );
        
        if (result == Messages.YES) {
            OpenWebUIRagAgent openWebUIRagAgent = OpenWebUIRagAgent.getInstance(project);
            openWebUIRagAgent.indexProject(true);
            
            Messages.showInfoMessage(
                "Project re-indexing started in background.",
                "Re-indexing Started"
            );
        }
    }

    /**
     * Adds a single file to the knowledge base.
     */
    private void addFile() {
        FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor();
        descriptor.setTitle("Select File to Add to Knowledge Base");

        VirtualFile file = FileChooser.chooseFile(descriptor, project, null);
        if (file != null) {
            setProcessingState(true, "Uploading file...");

            ProgressManager.getInstance().run(new Task.Backgroundable(project, "Uploading File", false) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    indicator.setIndeterminate(false);
                    indicator.setText("Uploading " + file.getName());
                    indicator.setFraction(0.5);

                    boolean success = ragManager.uploadFile(file);

                    indicator.setFraction(1.0);

                    SwingUtilities.invokeLater(() -> {
                        if (success) {
                            setStatusText("File uploaded successfully: " + file.getName());
                            loadTrackedFiles();
                        } else {
                            setStatusText("Failed to upload file: " + file.getName());
                        }
                        setProcessingState(false, null);
                    });
                }
            });
        }
    }

    /**
     * Adds a directory to the knowledge base.
     */
    private void addDirectory() {
        FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor();
        descriptor.setTitle("Select Directory to Add to Knowledge Base");

        VirtualFile directory = FileChooser.chooseFile(descriptor, project, null);
        if (directory != null) {
            boolean recursive = recursiveCheckbox.isSelected();
            setProcessingState(true, "Processing directory...");

            ProgressManager.getInstance().run(new Task.Backgroundable(project, "Indexing Files", true) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    indicator.setIndeterminate(false);
                    indicator.setText("Scanning directory: " + directory.getName());
                    indicator.setFraction(0.1);

                    List<KnowledgeBaseManager.UploadResult> results =
                            ragManager.uploadDirectory(directory.getPath(), recursive);

                    // Process results
                    AtomicInteger successCount = new AtomicInteger(0);
                    AtomicInteger failCount = new AtomicInteger(0);

                    for (KnowledgeBaseManager.UploadResult result : results) {
                        if (result.isSuccess()) {
                            successCount.incrementAndGet();
                        } else {
                            failCount.incrementAndGet();
                        }
                    }

                    indicator.setFraction(1.0);

                    SwingUtilities.invokeLater(() -> {
                        setStatusText(String.format(
                                "Processed %d files: %d uploaded, %d failed",
                                results.size(), successCount.get(), failCount.get()));
                        loadTrackedFiles();
                        setProcessingState(false, null);
                    });
                }
            });
        }
    }

    /**
     * Refreshes and validates the file list.
     */
    private void refreshAndValidate() {
        setProcessingState(true, "Validating files...");

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Validating Files", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                indicator.setText("Validating tracked files...");

                ragManager.validateTrackedFiles();

                SwingUtilities.invokeLater(() -> {
                    loadTrackedFiles();
                    setStatusText("File validation complete");
                    setProcessingState(false, null);
                });
            }
        });
    }

    /**
     * Removes selected files from the knowledge base.
     */
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

        setProcessingState(true, "Removing files...");

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Removing Files", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);

                int successCount = 0;
                for (int i = 0; i < selectedRows.length; i++) {
                    int row = selectedRows[i];
                    String filePath = (String) tableModel.getValueAt(row, 1);

                    indicator.setText("Removing file: " + filePath);
                    indicator.setFraction((double) i / selectedRows.length);

                    if (ragManager.removeFile(filePath)) {
                        successCount++;
                    }
                }

                int finalSuccessCount = successCount;
                SwingUtilities.invokeLater(() -> {
                    loadTrackedFiles();
                    setStatusText("Removed " + finalSuccessCount + " of " + selectedRows.length + " files");
                    setProcessingState(false, null);
                });
            }
        });
    }

    /**
     * Loads tracked files into the table.
     */
    private void loadTrackedFiles() {
        // Clear the table
        tableModel.setRowCount(0);

        // Add all tracked files
        List<PersistentRagManager.TrackedFileInfo> files = ragManager.getTrackedFiles();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        for (PersistentRagManager.TrackedFileInfo file : files) {
            tableModel.addRow(new Object[]{
                    file.getName(),
                    file.getPath(),
                    file.getFileId(),
                    dateFormat.format(file.getLastModified()),
                    "Synced"
            });
        }

        setStatusText("Loaded " + files.size() + " tracked files");
    }

    /**
     * Sets the processing state of the UI.
     */
    private void setProcessingState(boolean processing, String statusMessage) {
        progressBar.setVisible(processing);
        progressBar.setIndeterminate(processing);

        if (statusMessage != null) {
            setStatusText(statusMessage);
        }
    }

    /**
     * Sets the status text.
     */
    private void setStatusText(String text) {
        statusLabel.setText(text);
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