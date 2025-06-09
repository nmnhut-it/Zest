package com.zps.zest.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.validation.CommitTemplateValidator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Action to open the commit template editor dialog.
 */
public class EditCommitTemplateAction extends AnAction {
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        
        CommitTemplateDialog dialog = new CommitTemplateDialog(project);
        if (dialog.showAndGet()) {
            // Template was saved
            Messages.showInfoMessage(project, 
                "Commit template updated successfully!", 
                "Template Saved");
        }
    }
    
    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null);
    }
    
    /**
     * Dialog for editing the commit template.
     */
    private static class CommitTemplateDialog extends DialogWrapper {
        private final Project project;
        private final ConfigurationManager config;
        private JBTextArea templateArea;
        
        protected CommitTemplateDialog(@Nullable Project project) {
            super(project);
            this.project = project;
            this.config = ConfigurationManager.getInstance(project);
            
            setTitle("Edit Commit Message Template");
            setSize(800, 600);
            init();
        }
        
        @Nullable
        @Override
        protected JComponent createCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout(0, 10));
            
            // Instructions
            JBLabel instructions = new JBLabel(
                "<html><b>Customize your Git commit message template</b><br>" +
                "Required placeholders: {FILES_LIST}, {DIFFS}<br>" +
                "Optional: {PROJECT_NAME}, {BRANCH_NAME}, {DATE}, {USER_NAME}</html>"
            );
            panel.add(instructions, BorderLayout.NORTH);
            
            // Template editor
            templateArea = new JBTextArea(config.getCommitPromptTemplate());
            templateArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            templateArea.setLineWrap(true);
            templateArea.setWrapStyleWord(true);
            
            JBScrollPane scrollPane = new JBScrollPane(templateArea);
            scrollPane.setPreferredSize(new Dimension(750, 400));
            panel.add(scrollPane, BorderLayout.CENTER);
            
            // Buttons panel
            JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
            
            JButton validateBtn = new JButton("Validate");
            validateBtn.addActionListener(e -> validateTemplate());
            buttonsPanel.add(validateBtn);
            
            JButton resetBtn = new JButton("Reset to Default");
            resetBtn.addActionListener(e -> {
                templateArea.setText(ConfigurationManager.DEFAULT_COMMIT_PROMPT_TEMPLATE);
            });
            buttonsPanel.add(resetBtn);
            
            JButton previewBtn = new JButton("Preview with Sample Data");
            previewBtn.addActionListener(e -> showPreview());
            buttonsPanel.add(previewBtn);
            
            panel.add(buttonsPanel, BorderLayout.SOUTH);
            
            return panel;
        }
        
        private void validateTemplate() {
            String template = templateArea.getText();
            CommitTemplateValidator.ValidationResult result = 
                CommitTemplateValidator.validate(template);
            
            if (result.isValid) {
                Messages.showInfoMessage(project, 
                    "Template is valid!", 
                    "Validation Success");
            } else {
                Messages.showErrorDialog(project, 
                    result.errorMessage, 
                    "Validation Failed");
            }
        }
        
        private void showPreview() {
            String template = templateArea.getText();
            
            // Sample data for preview
            String sampleFilesList = "### Modified files:\n" +
                "- src/main/java/com/example/UserService.java\n" +
                "- src/test/java/com/example/UserServiceTest.java\n\n" +
                "### Added files:\n" +
                "- src/main/java/com/example/UserDto.java";
            
            String sampleDiffs = "### src/main/java/com/example/UserService.java\n" +
                "```diff\n" +
                "@@ -15,6 +15,10 @@\n" +
                "     public User findById(Long id) {\n" +
                "-        return userRepository.findById(id).orElse(null);\n" +
                "+        return userRepository.findById(id)\n" +
                "+            .orElseThrow(() -> new UserNotFoundException(id));\n" +
                "     }\n" +
                "```";
            
            // Replace placeholders
            String preview = template
                .replace("{FILES_LIST}", sampleFilesList)
                .replace("{DIFFS}", sampleDiffs)
                .replace("{PROJECT_NAME}", project.getName())
                .replace("{BRANCH_NAME}", "feature/user-improvements")
                .replace("{DATE}", "2024-01-15")
                .replace("{USER_NAME}", System.getProperty("user.name", "developer"));
            
            // Show preview in a dialog
            JTextArea previewArea = new JTextArea(preview);
            previewArea.setEditable(false);
            previewArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            
            JScrollPane scrollPane = new JScrollPane(previewArea);
            scrollPane.setPreferredSize(new Dimension(700, 500));
            
            Messages.showMessageDialog(project, scrollPane, 
                "Template Preview", Messages.getInformationIcon());
        }
        
        @Override
        protected void doOKAction() {
            String template = templateArea.getText();
            
            // Validate before saving
            CommitTemplateValidator.ValidationResult result = 
                CommitTemplateValidator.validate(template);
            
            if (!result.isValid) {
                Messages.showErrorDialog(project, 
                    result.errorMessage, 
                    "Invalid Template");
                return;
            }
            
            try {
                config.setCommitPromptTemplate(template);
                super.doOKAction();
            } catch (Exception e) {
                Messages.showErrorDialog(project, 
                    "Failed to save template: " + e.getMessage(), 
                    "Save Error");
            }
        }
    }
}
