package com.zps.zest.browser;

import com.intellij.openapi.project.Project;
import com.zps.zest.CodeContext;

import java.util.List; /**
 * Context class for git commit message generation.
 * Moved here to be accessible by GitService.
 */
public class GitCommitContext extends CodeContext {
    private String gitDiff;
    private String changedFiles;
    private String branchName;
    private String prompt;
    private List<SelectedFile> selectedFiles;
    private Project project;
    private com.intellij.openapi.actionSystem.AnActionEvent event;

    // Getters and setters
    public String getGitDiff() { return gitDiff; }
    public void setGitDiff(String gitDiff) { this.gitDiff = gitDiff; }

    public String getChangedFiles() { return changedFiles; }
    public void setChangedFiles(String changedFiles) { this.changedFiles = changedFiles; }

    public String getBranchName() { return branchName; }
    public void setBranchName(String branchName) { this.branchName = branchName; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public List<SelectedFile> getSelectedFiles() { return selectedFiles; }
    public void setSelectedFiles(List<SelectedFile> selectedFiles) { this.selectedFiles = selectedFiles; }

    public Project getProject() { return project; }
    public void setProject(Project project) { this.project = project; }

    public com.intellij.openapi.actionSystem.AnActionEvent getEvent() { return event; }
    public void setEvent(com.intellij.openapi.actionSystem.AnActionEvent event) { this.event = event; }

    /**
     * Inner class for selected files with status
     */
    public static class SelectedFile {
        private String path;
        private String status;

        public SelectedFile(String path, String status) {
            this.path = path;
            this.status = status;
        }

        public String getPath() { return path; }
        public String getStatus() { return status; }
        
        @Override
        public String toString() {
            return status + "\t" + path;
        }
    }
}
