package com.zps.zest.vcs;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.LocalChangeList;
import com.zps.zest.browser.utils.ChatboxUtilities;
import com.zps.zest.langchain4j.util.LLMService;
import com.zps.zest.ConfigurationManager;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.*;
import java.util.List;

/**
 * Standalone action to generate commit messages using the same prompt template system as JS implementation
 * Can be triggered from VCS menu or via Ctrl+Shift+G
 */
public class GenerateCommitMessageAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        LLMService llmService = project.getService(LLMService.class);
        if (!llmService.isConfigured()) {
            Messages.showErrorDialog(project, 
                "LLM service is not configured. Please check your Zest settings.", 
                "Generate Commit Message");
            return;
        }

        // Get changes
        ChangeListManager changeListManager = ChangeListManager.getInstance(project);
        LocalChangeList defaultChangeList = changeListManager.getDefaultChangeList();
        Collection<Change> changes = defaultChangeList.getChanges();

        if (changes.isEmpty()) {
            Messages.showInfoMessage(project, 
                "No changes found to generate commit message for.", 
                "Generate Commit Message");
            return;
        }

        // Generate message in background
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Generating Commit Message", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                indicator.setText("Analyzing changes and generating commit message...");

                try {
                    String prompt = buildCommitPromptWithTemplate(project, changes);
                    String message = llmService.query(prompt, ChatboxUtilities.EnumUsage.VCS_COMMIT_MESSAGE);

                    if (message != null) {
                        String cleanMessage = cleanCommitMessage(message);
                        
                        ApplicationManager.getApplication().invokeLater(() -> {
                            // Show generated message and copy to clipboard
                            setCommitMessage(project, cleanMessage);
                        });
                    } else {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            Messages.showErrorDialog(project, 
                                "Failed to generate commit message. Please try again.", 
                                "Generate Commit Message");
                        });
                    }
                } catch (Exception ex) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        Messages.showErrorDialog(project, 
                            "Error generating commit message: " + ex.getMessage(), 
                            "Generate Commit Message");
                    });
                }
            }
        });
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        boolean enabled = false;

        if (project != null) {
            LLMService llmService = project.getService(LLMService.class);
            ChangeListManager changeListManager = ChangeListManager.getInstance(project);
            
            enabled = llmService.isConfigured() && 
                     !changeListManager.getDefaultChangeList().getChanges().isEmpty();
        }

        e.getPresentation().setEnabled(enabled);
    }

    /**
     * Builds commit prompt using the same configurable template system as JS implementation
     */
    private String buildCommitPromptWithTemplate(Project project, Collection<Change> changes) {
        ConfigurationManager config = ConfigurationManager.getInstance(project);
        
        // Get the template from configuration (same as JS implementation)
        String template = config.getCommitPromptTemplate();
        
        // Build files list grouped by status (same format as JS)
        StringBuilder filesList = new StringBuilder();
        
        // Group files by status
        Map<String, List<String>> filesByStatus = new HashMap<>();
        filesByStatus.put("M", new ArrayList<>());
        filesByStatus.put("A", new ArrayList<>());
        filesByStatus.put("D", new ArrayList<>());
        filesByStatus.put("R", new ArrayList<>());
        filesByStatus.put("C", new ArrayList<>());
        filesByStatus.put("U", new ArrayList<>());
        
        for (Change change : changes) {
            String status = getChangeStatus(change);
            String filePath = getFilePath(change);
            
            if (filesByStatus.containsKey(status)) {
                filesByStatus.get(status).add(filePath);
            } else {
                filesByStatus.get("U").add(filePath);
            }
        }
        
        Map<String, String> statusMap = new HashMap<>();
        statusMap.put("M", "Modified");
        statusMap.put("A", "Added");
        statusMap.put("D", "Deleted");
        statusMap.put("R", "Renamed");
        statusMap.put("C", "Copied");
        statusMap.put("U", "Other");
        
        // Output files grouped by status (same format as JS)
        for (Map.Entry<String, List<String>> entry : filesByStatus.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                filesList.append("\n### ").append(statusMap.get(entry.getKey())).append(" files:\n");
                for (String path : entry.getValue()) {
                    filesList.append("- ").append(path).append("\n");
                }
            }
        }
        
        // Build diffs section (simplified for now, can be enhanced later)
        StringBuilder diffsSection = new StringBuilder();
        diffsSection.append("## File changes summary:\n");
        for (Change change : changes) {
            String changeType = getChangeType(change);
            String filePath = getFilePath(change);
            diffsSection.append("- ").append(changeType).append(": ").append(filePath).append("\n");
        }
        
        // Replace placeholders in template (same as JS implementation)
        String prompt = template
            .replace("{FILES_LIST}", filesList.toString().trim())
            .replace("{DIFFS}", diffsSection.toString().trim());

        return prompt;
    }

    private String getChangeStatus(Change change) {
        if (change.getType() == Change.Type.NEW) {
            return "A";
        } else if (change.getType() == Change.Type.DELETED) {
            return "D";
        } else if (change.getType() == Change.Type.MODIFICATION) {
            return "M";
        } else if (change.getType() == Change.Type.MOVED) {
            return "R";
        } else {
            return "U";
        }
    }

    private String getChangeType(Change change) {
        if (change.getType() == Change.Type.NEW) {
            return "Added";
        } else if (change.getType() == Change.Type.DELETED) {
            return "Deleted";
        } else if (change.getType() == Change.Type.MODIFICATION) {
            return "Modified";
        } else if (change.getType() == Change.Type.MOVED) {
            return "Moved";
        } else {
            return "Changed";
        }
    }

    private String getFilePath(Change change) {
        if (change.getAfterRevision() != null) {
            return change.getAfterRevision().getFile().getPath();
        } else if (change.getBeforeRevision() != null) {
            return change.getBeforeRevision().getFile().getPath();
        } else {
            return "unknown";
        }
    }

    private void setCommitMessage(Project project, String message) {
        // Show the generated message to user with better formatting
        String displayMessage = "Generated commit message:\n\n" + message + 
                               "\n\n✅ Message has been copied to clipboard.\n" +
                               "You can now paste it into any commit dialog.";
        
        Messages.showInfoMessage(project, displayMessage, "AI Commit Message Generated");
        
        // Copy to clipboard
        StringSelection stringSelection = new StringSelection(message);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(stringSelection, null);
    }

    /**
     * Cleans commit message from markdown formatting (same as JS implementation)
     */
    private String cleanCommitMessage(String message) {
        if (message == null) return "";
        
        // Remove markdown code blocks
        message = message.replaceAll("```[\\w-]*\\n?", "");
        message = message.replaceAll("\\n?```", "");
        message = message.replaceAll("```", "");
        
        // Remove leading/trailing whitespace
        message = message.trim();
        
        // Remove any "Commit message:" prefix that might be added
        message = message.replaceAll("^(Commit message:|Generated commit message:)\\s*", "");
        
        return message;
    }
}
