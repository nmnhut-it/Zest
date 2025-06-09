package com.zps.zest.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.validation.CommitTemplateValidator;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public class ZestSettingsConfigurable implements Configurable {
    private final Project project;
    private JBTextArea templateTextArea;
    private ConfigurationManager config;
    
    public ZestSettingsConfigurable(Project project) {
        this.project = project;
        this.config = ConfigurationManager.getInstance(project);
    }
    
    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "Zest Plugin Settings";
    }
    
    @Nullable
    @Override
    public JComponent createComponent() {
        JPanel panel = new JPanel(new BorderLayout());
        
        // Commit Template Section
        JPanel templateSection = new JPanel(new BorderLayout(0, 10));
        templateSection.setBorder(BorderFactory.createTitledBorder("Git Commit Message Template"));
        
        // Description
        JBLabel description = new JBLabel(
            "<html>Configure the template for generating commit messages.<br>" +
            "Available placeholders: {FILES_LIST}, {DIFFS}</html>"
        );
        templateSection.add(description, BorderLayout.NORTH);
        
        // Template editor
        templateTextArea = new JBTextArea(15, 60);
        templateTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        templateTextArea.setText(config.getCommitPromptTemplate());
        
        JBScrollPane scrollPane = new JBScrollPane(templateTextArea);
        templateSection.add(scrollPane, BorderLayout.CENTER);
        
        // Buttons panel
        JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        
        JButton resetButton = new JButton("Reset to Default");
        resetButton.addActionListener(e -> {
            templateTextArea.setText(ConfigurationManager.DEFAULT_COMMIT_PROMPT_TEMPLATE);
        });
        buttonsPanel.add(resetButton);
        
        JButton validateButton = new JButton("Validate Template");
        validateButton.addActionListener(e -> {
            CommitTemplateValidator.ValidationResult result = 
                CommitTemplateValidator.validate(templateTextArea.getText());
            if (result.isValid) {
                Messages.showInfoMessage(project, "Template is valid!", "Validation Success");
            } else {
                Messages.showErrorDialog(project, result.errorMessage, "Validation Failed");
            }
        });
        buttonsPanel.add(validateButton);
        
        templateSection.add(buttonsPanel, BorderLayout.SOUTH);
        
        panel.add(templateSection, BorderLayout.NORTH);
        
        return panel;
    }
    
    @Override
    public boolean isModified() {
        return !templateTextArea.getText().equals(config.getCommitPromptTemplate());
    }
    
    @Override
    public void apply() {
        String newTemplate = templateTextArea.getText();
        
        // Validate before saving
        CommitTemplateValidator.ValidationResult result = 
            CommitTemplateValidator.validate(newTemplate);
        
        if (!result.isValid) {
            Messages.showErrorDialog(project, result.errorMessage, "Invalid Template");
            return;
        }
        
        config.setCommitPromptTemplate(newTemplate);
        config.saveConfig();
    }
    
    @Override
    public void reset() {
        templateTextArea.setText(config.getCommitPromptTemplate());
    }
}
