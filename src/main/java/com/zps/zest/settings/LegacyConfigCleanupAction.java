package com.zps.zest.settings;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Action to clean up legacy configuration files after migration to IDE settings.
 */
public class LegacyConfigCleanupAction extends AnAction {
    
    public LegacyConfigCleanupAction() {
        super("Clean Up Legacy Zest Config Files", 
              "Remove old zest-plugin.properties and ollama-plugin.properties files", 
              null);
    }
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        
        List<File> legacyFiles = findLegacyFiles(project);
        
        if (legacyFiles.isEmpty()) {
            Messages.showInfoMessage(project, 
                "No legacy configuration files found in the project.", 
                "No Legacy Files");
            return;
        }
        
        // Build message
        StringBuilder message = new StringBuilder("Found the following legacy configuration files:\n\n");
        for (File file : legacyFiles) {
            message.append("• ").append(file.getName()).append("\n");
        }
        message.append("\nThese files are no longer needed as settings have been migrated to IDE storage.\n");
        message.append("Would you like to delete them?");
        
        int result = Messages.showYesNoDialog(project, 
            message.toString(), 
            "Delete Legacy Configuration Files?", 
            Messages.getQuestionIcon());
        
        if (result == Messages.YES) {
            deleteLegacyFiles(project, legacyFiles);
        }
    }
    
    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null);
    }
    
    private List<File> findLegacyFiles(Project project) {
        List<File> files = new ArrayList<>();
        String basePath = project.getBasePath();
        if (basePath == null) {
            return files;
        }
        
        File legacyFile1 = new File(basePath, "ollama-plugin.properties");
        File legacyFile2 = new File(basePath, "zest-plugin.properties");
        
        if (legacyFile1.exists()) {
            files.add(legacyFile1);
        }
        if (legacyFile2.exists()) {
            files.add(legacyFile2);
        }
        
        return files;
    }
    
    private void deleteLegacyFiles(Project project, List<File> files) {
        List<String> deleted = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        
        for (File file : files) {
            try {
                // Create backup first
                Path backupPath = file.toPath().resolveSibling(file.getName() + ".backup");
                Files.copy(file.toPath(), backupPath);
                
                // Delete the file
                if (file.delete()) {
                    deleted.add(file.getName());
                    
                    // Refresh VFS
                    VirtualFile vFile = LocalFileSystem.getInstance().findFileByIoFile(file);
                    if (vFile != null) {
                        vFile.refresh(false, false);
                    }
                } else {
                    failed.add(file.getName());
                }
            } catch (IOException ex) {
                failed.add(file.getName() + " (backup failed)");
            }
        }
        
        // Show results
        StringBuilder resultMessage = new StringBuilder();
        
        if (!deleted.isEmpty()) {
            resultMessage.append("Successfully deleted:\n");
            for (String name : deleted) {
                resultMessage.append("• ").append(name).append("\n");
            }
            resultMessage.append("\nBackup files created with .backup extension.\n");
        }
        
        if (!failed.isEmpty()) {
            if (!deleted.isEmpty()) {
                resultMessage.append("\n");
            }
            resultMessage.append("Failed to delete:\n");
            for (String name : failed) {
                resultMessage.append("• ").append(name).append("\n");
            }
        }
        
        Messages.showInfoMessage(project, 
            resultMessage.toString(), 
            "Cleanup Results");
    }
}
