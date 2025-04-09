package com.zps.zest;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.util.List;

public class KnowledgeBaseManagerDialog extends DialogWrapper {
    private final Project project;
    private final KnowledgeBaseManager kbManager;
    private JList<String> fileList;
    private DefaultListModel<String> fileListModel;
    
    public KnowledgeBaseManagerDialog(Project project, KnowledgeBaseManager kbManager) {
        super(project);
        this.project = project;
        this.kbManager = kbManager;
        setTitle("Knowledge Base Manager");
        init();
    }
    
    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(500, 400));
        
        // Create file list
        fileListModel = new DefaultListModel<>();
        fileList = new JBList<>(fileListModel);
        JBScrollPane scrollPane = new JBScrollPane(fileList);
        panel.add(scrollPane, BorderLayout.CENTER);
        
        // Create button panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        
        JButton addFileButton = new JButton("Add File");
        addFileButton.addActionListener(e -> addFile());
        
        JButton addDirectoryButton = new JButton("Add Directory");
        addDirectoryButton.addActionListener(e -> addDirectory());
        
        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(e -> refreshFiles());
        
        buttonPanel.add(addFileButton);
        buttonPanel.add(addDirectoryButton);
        buttonPanel.add(refreshButton);
        
        panel.add(buttonPanel, BorderLayout.SOUTH);
        
        return panel;
    }
    
    private void addFile() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setMultiSelectionEnabled(false);
        if (fileChooser.showOpenDialog(getContentPane()) == JFileChooser.APPROVE_OPTION) {
            try {
                Path filePath = fileChooser.getSelectedFile().toPath();
                String fileId = kbManager.uploadFile(filePath);
                if (fileId != null) {
                    fileListModel.addElement(filePath.getFileName().toString() + " (ID: " + fileId + ")");
                }
            } catch (Exception e) {
                e.printStackTrace();
                Messages.showErrorDialog(project, "Failed to add file: " + e.getMessage(), "Error");
            }
        }
    }
    
    private void addDirectory() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fileChooser.showOpenDialog(getContentPane()) == JFileChooser.APPROVE_OPTION) {
            // Show file type selection dialog
            String fileType = Messages.showInputDialog(
                project,
                "Enter file extension to index (e.g., java, xml, etc.)",
                "Select File Type",
                null
            );
            
            if (fileType != null && !fileType.isEmpty()) {
                // Start background task to index files
                ProgressManager.getInstance().run(new Task.Backgroundable(project, "Indexing Files", true) {
                    @Override
                    public void run(@NotNull ProgressIndicator indicator) {
                        File dir = fileChooser.getSelectedFile();
                        File[] files = dir.listFiles((d, name) -> name.endsWith("." + fileType));
                        
                        if (files != null) {
                            indicator.setIndeterminate(false);
                            for (int i = 0; i < files.length; i++) {
                                indicator.setFraction((double) i / files.length);
                                indicator.setText("Indexing: " + files[i].getName());
                                
                                try {
                                    String fileId = kbManager.uploadFile(files[i].toPath());
                                    if (fileId != null) {
                                        int finalI = i;
                                        SwingUtilities.invokeLater(() -> {
                                            fileListModel.addElement(files[finalI].getName() + " (ID: " + fileId + ")");
                                        });
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        }
                    }
                });
            }
        }
    }
    
    private void refreshFiles() {
        // Implementation depends on the ability to list files in OpenWebUI
        // This would typically call an API endpoint to get the list of files
        fileListModel.clear();
        // Example placeholder implementation:
        fileListModel.addElement("File listing not implemented yet");
    }
}