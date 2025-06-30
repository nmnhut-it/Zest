package com.zps.zest.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.*;
import com.intellij.util.ui.JBUI;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.validation.CommitTemplateValidator;
import com.zps.zest.git.CommitTemplateExamples;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ItemEvent;

/**
 * This is a backup of the original settings file - to be deleted
 */
public class ZestSettingsConfigurableBackup implements Configurable {
    private final Project project;
    private ConfigurationManager config;
    
    // API Settings
    private JBTextField apiUrlField;
    private JBTextField authTokenField;
    
    // Model Settings
    private JBTextField testModelField;
    private JBTextField codeModelField;
    private JSpinner maxIterationsSpinner;
    
    // Feature Toggles
    private JBCheckBox ragEnabledCheckbox;
    private JBCheckBox mcpEnabledCheckbox;
    private JBTextField mcpServerUriField;
    
    // Inline Completion Settings
    private JBCheckBox inlineCompletionCheckbox;
    private JBCheckBox autoTriggerCheckbox;
    private JBCheckBox backgroundContextCheckbox;
    
    // Context Settings
    private JBRadioButton contextInjectionRadio;
    private JBRadioButton projectIndexRadio;
    private JBTextField knowledgeIdField;
    
    // Documentation Search Settings
    private JBTextField docsPathField;
    private JBCheckBox docsSearchEnabledCheckbox;
    
    // System Prompts
    private JBTextArea systemPromptArea;
    private JBTextArea codeSystemPromptArea;
    private JBTextArea commitPromptTemplateArea;
    
    // UI State
    private boolean isModified = false;
    
    public ZestSettingsConfigurableBackup(Project project) {
        this.project = project;
        this.config = ConfigurationManager.getInstance(project);
    }
    
    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "Zest Plugin";
    }
    
    @Nullable
    @Override
    public JComponent createComponent() {
        JPanel mainPanel = new JPanel(new BorderLayout());
        
        // Create tabbed pane for different setting categories
        JBTabbedPane tabbedPane = new JBTabbedPane();
        
        // Add tabs
        tabbedPane.addTab("API & Models", createApiSettingsPanel());
        tabbedPane.addTab("Features", createFeaturesPanel());
        tabbedPane.addTab("System Prompts", createPromptsPanel());
        tabbedPane.addTab("Commit Template", createCommitTemplatePanel());
        
        mainPanel.add(tabbedPane, BorderLayout.CENTER);
        
        // Add save hint at bottom
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bottomPanel.add(new JBLabel("Changes will be saved to project's zest-plugin.properties file"));
        mainPanel.add(bottomPanel, BorderLayout.SOUTH);
        
        return mainPanel;
    }
    
    private JPanel createApiSettingsPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(5);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        
        int row = 0;
        
        // API URL Section
        gbc.gridx = 0; gbc.gridy = row++; gbc.gridwidth = 2;
        panel.add(new TitledSeparator("API Configuration"), gbc);
        
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1;
        panel.add(new JBLabel("API URL:"), gbc);
        
        gbc.gridx = 1; gbc.gridy = row++; gbc.weightx = 1.0;
        apiUrlField = new JBTextField(config.getApiUrl());
        panel.add(apiUrlField, gbc);
        
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        panel.add(new JBLabel("Auth Token:"), gbc);
        
        gbc.gridx = 1; gbc.gridy = row++; gbc.weightx = 1.0;
        authTokenField = new JBTextField(config.getAuthTokenNoPrompt());
        panel.add(authTokenField, gbc);
        
        // Model Configuration Section
        gbc.gridx = 0; gbc.gridy = row++; gbc.gridwidth = 2;
        panel.add(new TitledSeparator("Model Configuration"), gbc);
        
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1;
        panel.add(new JBLabel("Test Model:"), gbc);
        
        gbc.gridx = 1; gbc.gridy = row++; gbc.weightx = 1.0;
        testModelField = new JBTextField(config.getTestModel());
        panel.add(testModelField, gbc);
        
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        panel.add(new JBLabel("Code Model:"), gbc);
        
        gbc.gridx = 1; gbc.gridy = row++; gbc.weightx = 1.0;
        codeModelField = new JBTextField(config.getCodeModel());
        panel.add(codeModelField, gbc);
        
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        panel.add(new JBLabel("Max Iterations:"), gbc);
        
        gbc.gridx = 1; gbc.gridy = row++; gbc.weightx = 1.0;
        maxIterationsSpinner = new JSpinner(new SpinnerNumberModel(config.getMaxIterations(), 1, 10, 1));
        panel.add(maxIterationsSpinner, gbc);
        
        // Add vertical glue to push content to top
        gbc.gridx = 0; gbc.gridy = row; gbc.weighty = 1.0;
        panel.add(Box.createVerticalGlue(), gbc);
        
        return wrapInScrollPane(panel);
    }
    
    private JPanel createFeaturesPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(5);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        
        int row = 0;
        
        // Inline Completion Section
        gbc.gridx = 0; gbc.gridy = row++; gbc.gridwidth = 2;
        panel.add(new TitledSeparator("Inline Completion"), gbc);
        
        gbc.gridx = 0; gbc.gridy = row++; gbc.gridwidth = 2;
        inlineCompletionCheckbox = new JBCheckBox("Enable Inline Completion", config.isInlineCompletionEnabled());
        panel.add(inlineCompletionCheckbox, gbc);
        
        gbc.gridx = 0; gbc.gridy = row++; gbc.gridwidth = 2;
        autoTriggerCheckbox = new JBCheckBox("Enable Auto-trigger for Inline Completion", config.isAutoTriggerEnabled());
        autoTriggerCheckbox.setEnabled(config.isInlineCompletionEnabled());
        panel.add(autoTriggerCheckbox, gbc);
        
        gbc.gridx = 0; gbc.gridy = row++; gbc.gridwidth = 2;
        backgroundContextCheckbox = new JBCheckBox("Enable Background Context Collection", config.isBackgroundContextEnabled());
        panel.add(backgroundContextCheckbox, gbc);
        
        // Enable/disable dependent checkboxes
        inlineCompletionCheckbox.addItemListener(e -> {
            boolean enabled = e.getStateChange() == ItemEvent.SELECTED;
            autoTriggerCheckbox.setEnabled(enabled);
        });
        
        // RAG Section
        gbc.gridx = 0; gbc.gridy = row++; gbc.gridwidth = 2;
        panel.add(new TitledSeparator("RAG (Retrieval-Augmented Generation)"), gbc);
        
        gbc.gridx = 0; gbc.gridy = row++; gbc.gridwidth = 2;
        ragEnabledCheckbox = new JBCheckBox("Enable RAG", config.isRagEnabled());
        panel.add(ragEnabledCheckbox, gbc);
        
        // MCP Section
        gbc.gridx = 0; gbc.gridy = row++; gbc.gridwidth = 2;
        panel.add(new TitledSeparator("MCP (Model Context Protocol)"), gbc);
        
        gbc.gridx = 0; gbc.gridy = row++; gbc.gridwidth = 2;
        mcpEnabledCheckbox = new JBCheckBox("Enable MCP", config.isMcpEnabled());
        panel.add(mcpEnabledCheckbox, gbc);
        
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1;
        panel.add(new JBLabel("MCP Server URI:"), gbc);
        
        gbc.gridx = 1; gbc.gridy = row++; gbc.weightx = 1.0;
        mcpServerUriField = new JBTextField(config.getMcpServerUri());
        mcpServerUriField.setEnabled(config.isMcpEnabled());
        panel.add(mcpServerUriField, gbc);
        
        // Enable/disable MCP URI field based on checkbox
        mcpEnabledCheckbox.addItemListener(e -> {
            mcpServerUriField.setEnabled(e.getStateChange() == ItemEvent.SELECTED);
        });
        
        // Context Settings Section
        gbc.gridx = 0; gbc.gridy = row++; gbc.gridwidth = 2; gbc.weightx = 0;
        panel.add(new TitledSeparator("Context Settings"), gbc);
        
        // Radio buttons for mutual exclusion
        ButtonGroup contextGroup = new ButtonGroup();
        
        gbc.gridx = 0; gbc.gridy = row++; gbc.gridwidth = 2;
        contextInjectionRadio = new JBRadioButton("Context Injection (includes file contents in prompts)", 
                                                  config.isContextInjectionEnabled());
        contextGroup.add(contextInjectionRadio);
        panel.add(contextInjectionRadio, gbc);
        
        gbc.gridx = 0; gbc.gridy = row++; gbc.gridwidth = 2;
        projectIndexRadio = new JBRadioButton("Project Index (uses knowledge base)", 
                                              config.isProjectIndexEnabled());
        contextGroup.add(projectIndexRadio);
        panel.add(projectIndexRadio, gbc);
        
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1;
        panel.add(new JBLabel("Knowledge ID:"), gbc);
        
        gbc.gridx = 1; gbc.gridy = row++; gbc.weightx = 1.0;
        knowledgeIdField = new JBTextField(config.getKnowledgeId() != null ? config.getKnowledgeId() : "");
        knowledgeIdField.setEnabled(config.isProjectIndexEnabled());
        panel.add(knowledgeIdField, gbc);
        
        // Enable/disable knowledge ID field based on radio selection
        projectIndexRadio.addItemListener(e -> {
            knowledgeIdField.setEnabled(e.getStateChange() == ItemEvent.SELECTED);
        });
        
        // Add index project button
        gbc.gridx = 1; gbc.gridy = row++; gbc.weightx = 1.0;
        JButton indexButton = new JButton("Index Project Now");
        indexButton.setEnabled(config.isProjectIndexEnabled());
        indexButton.addActionListener(e -> indexProject());
        panel.add(indexButton, gbc);
        
        projectIndexRadio.addItemListener(e -> {
            indexButton.setEnabled(e.getStateChange() == ItemEvent.SELECTED);
        });
        
        // Documentation Search Section
        gbc.gridx = 0; gbc.gridy = row++; gbc.gridwidth = 2; gbc.weightx = 0;
        panel.add(new TitledSeparator("Documentation Search"), gbc);
        
        gbc.gridx = 0; gbc.gridy = row++; gbc.gridwidth = 2;
        docsSearchEnabledCheckbox = new JBCheckBox("Enable documentation search", config.isDocsSearchEnabled());
        panel.add(docsSearchEnabledCheckbox, gbc);
        
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 1;
        panel.add(new JBLabel("Docs Path:"), gbc);
        
        gbc.gridx = 1; gbc.gridy = row++; gbc.weightx = 1.0;
        docsPathField = new JBTextField(config.getDocsPath());
        docsPathField.setEnabled(config.isDocsSearchEnabled());
        panel.add(docsPathField, gbc);
        
        // Enable/disable docs path field based on checkbox
        docsSearchEnabledCheckbox.addItemListener(e -> {
            docsPathField.setEnabled(e.getStateChange() == ItemEvent.SELECTED);
        });
        
        // Add description
        gbc.gridx = 0; gbc.gridy = row++; gbc.gridwidth = 2;
        JBLabel docsDescription = new JBLabel("<html><i>Search markdown documentation files using natural language queries</i></html>");
        docsDescription.setFont(docsDescription.getFont().deriveFont(Font.ITALIC));
        panel.add(docsDescription, gbc);
        
        // Add vertical glue
        gbc.gridx = 0; gbc.gridy = row; gbc.weighty = 1.0;
        panel.add(Box.createVerticalGlue(), gbc);
        
        return wrapInScrollPane(panel);
    }
    
    private JPanel createPromptsPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = JBUI.insets(5);
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0;
        
        int row = 0;
        
        // System Prompt
        gbc.gridx = 0; gbc.gridy = row++; gbc.weighty = 0;
        panel.add(new TitledSeparator("System Prompt (General Assistant)"), gbc);
        
        gbc.gridx = 0; gbc.gridy = row++; gbc.weighty = 0.5;
        systemPromptArea = new JBTextArea(config.getSystemPrompt());
        systemPromptArea.setRows(10);
        systemPromptArea.setLineWrap(true);
        systemPromptArea.setWrapStyleWord(true);
        systemPromptArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        panel.add(new JBScrollPane(systemPromptArea), gbc);
        
        // Code System Prompt
        gbc.gridx = 0; gbc.gridy = row++; gbc.weighty = 0;
        panel.add(new TitledSeparator("Code System Prompt (Programming Assistant)"), gbc);
        
        gbc.gridx = 0; gbc.gridy = row++; gbc.weighty = 0.5;
        codeSystemPromptArea = new JBTextArea(config.getCodeSystemPrompt());
        codeSystemPromptArea.setRows(10);
        codeSystemPromptArea.setLineWrap(true);
        codeSystemPromptArea.setWrapStyleWord(true);
        codeSystemPromptArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        panel.add(new JBScrollPane(codeSystemPromptArea), gbc);
        
        // Reset buttons
        gbc.gridx = 0; gbc.gridy = row++; gbc.weighty = 0;
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        
        JButton resetSystemPromptBtn = new JButton("Reset System Prompt");
        resetSystemPromptBtn.addActionListener(e -> {
            systemPromptArea.setText(ConfigurationManager.DEFAULT_SYSTEM_PROMPT);
        });
        buttonPanel.add(resetSystemPromptBtn);
        
        JButton resetCodePromptBtn = new JButton("Reset Code Prompt");
        resetCodePromptBtn.addActionListener(e -> {
            codeSystemPromptArea.setText(ConfigurationManager.DEFAULT_CODE_SYSTEM_PROMPT);
        });
        buttonPanel.add(resetCodePromptBtn);
        
        panel.add(buttonPanel, gbc);
        
        return panel; // Already has scroll panes for text areas
    }
    
    private JPanel createCommitTemplatePanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(JBUI.Borders.empty(10));
        
        // Instructions
        JPanel topPanel = new JPanel(new BorderLayout());
        JBLabel instructions = new JBLabel(
            "<html><b>Git Commit Message Template</b><br>" +
            "Required placeholders: <code>{FILES_LIST}</code>, <code>{DIFFS}</code><br>" +
            "Optional: <code>{PROJECT_NAME}</code>, <code>{BRANCH_NAME}</code>, <code>{DATE}</code>, <code>{USER_NAME}</code></html>"
        );
        topPanel.add(instructions, BorderLayout.NORTH);
        
        // Template selector
        JPanel selectorPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        selectorPanel.add(new JBLabel("Load template:"));
        
        JComboBox<CommitTemplateExamples.TemplateExample> templateSelector = new JComboBox<>();
        templateSelector.addItem(new CommitTemplateExamples.TemplateExample("Current", config.getCommitPromptTemplate()));
        for (CommitTemplateExamples.TemplateExample example : CommitTemplateExamples.getAllTemplates()) {
            templateSelector.addItem(example);
        }
        templateSelector.addActionListener(e -> {
            CommitTemplateExamples.TemplateExample selected = 
                (CommitTemplateExamples.TemplateExample) templateSelector.getSelectedItem();
            if (selected != null && !selected.name.equals("Current")) {
                commitPromptTemplateArea.setText(selected.template);
            }
        });
        selectorPanel.add(templateSelector);
        
        topPanel.add(selectorPanel, BorderLayout.SOUTH);
        panel.add(topPanel, BorderLayout.NORTH);
        
        // Template editor
        commitPromptTemplateArea = new JBTextArea(config.getCommitPromptTemplate());
        commitPromptTemplateArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        commitPromptTemplateArea.setLineWrap(true);
        commitPromptTemplateArea.setWrapStyleWord(true);
        
        JBScrollPane scrollPane = new JBScrollPane(commitPromptTemplateArea);
        panel.add(scrollPane, BorderLayout.CENTER);
        
        // Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        
        JButton validateBtn = new JButton("Validate");
        validateBtn.addActionListener(e -> validateCommitTemplate());
        buttonPanel.add(validateBtn);
        
        JButton resetBtn = new JButton("Reset to Default");
        resetBtn.addActionListener(e -> {
            commitPromptTemplateArea.setText(ConfigurationManager.DEFAULT_COMMIT_PROMPT_TEMPLATE);
        });
        buttonPanel.add(resetBtn);
        
        JButton previewBtn = new JButton("Preview");
        previewBtn.addActionListener(e -> previewCommitTemplate());
        buttonPanel.add(previewBtn);
        
        JButton helpBtn = new JButton("Help");
        helpBtn.addActionListener(e -> showCommitTemplateHelp());
        buttonPanel.add(helpBtn);
        
        panel.add(buttonPanel, BorderLayout.SOUTH);
        
        return panel;
    }
    
    private JPanel wrapInScrollPane(JPanel panel) {
        JBScrollPane scrollPane = new JBScrollPane(panel);
        scrollPane.setBorder(null);
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(scrollPane, BorderLayout.CENTER);
        return wrapper;
    }
    
    @Override
    public boolean isModified() {
        return !apiUrlField.getText().equals(config.getApiUrl()) ||
               !authTokenField.getText().equals(config.getAuthTokenNoPrompt()) ||
               !testModelField.getText().equals(config.getTestModel()) ||
               !codeModelField.getText().equals(config.getCodeModel()) ||
               !maxIterationsSpinner.getValue().equals(config.getMaxIterations()) ||
               inlineCompletionCheckbox.isSelected() != config.isInlineCompletionEnabled() ||
               autoTriggerCheckbox.isSelected() != config.isAutoTriggerEnabled() ||
               backgroundContextCheckbox.isSelected() != config.isBackgroundContextEnabled() ||
               ragEnabledCheckbox.isSelected() != config.isRagEnabled() ||
               mcpEnabledCheckbox.isSelected() != config.isMcpEnabled() ||
               !mcpServerUriField.getText().equals(config.getMcpServerUri()) ||
               contextInjectionRadio.isSelected() != config.isContextInjectionEnabled() ||
               projectIndexRadio.isSelected() != config.isProjectIndexEnabled() ||
               !knowledgeIdField.getText().equals(config.getKnowledgeId() != null ? config.getKnowledgeId() : "") ||
               !systemPromptArea.getText().equals(config.getSystemPrompt()) ||
               !codeSystemPromptArea.getText().equals(config.getCodeSystemPrompt()) ||
               !commitPromptTemplateArea.getText().equals(config.getCommitPromptTemplate()) ||
               !docsPathField.getText().equals(config.getDocsPath()) ||
               docsSearchEnabledCheckbox.isSelected() != config.isDocsSearchEnabled();
    }
    
    @Override
    public void apply() {
        // Validate commit template before saving
        String newCommitTemplate = commitPromptTemplateArea.getText();
        CommitTemplateValidator.ValidationResult templateResult = 
            CommitTemplateValidator.validate(newCommitTemplate);
        
        if (!templateResult.isValid) {
            Messages.showErrorDialog(project, 
                "Commit template validation failed: " + templateResult.errorMessage, 
                "Invalid Template");
            return;
        }
        
        // Apply all settings
        config.setApiUrl(apiUrlField.getText().trim());
        config.setAuthToken(authTokenField.getText().trim());
        config.setTestModel(testModelField.getText().trim());
        config.setCodeModel(codeModelField.getText().trim());
        config.setMaxIterations((Integer) maxIterationsSpinner.getValue());
        config.setInlineCompletionEnabled(inlineCompletionCheckbox.isSelected());
        config.setAutoTriggerEnabled(autoTriggerCheckbox.isSelected());
        config.setBackgroundContextEnabled(backgroundContextCheckbox.isSelected());
        config.setRagEnabled(ragEnabledCheckbox.isSelected());
        config.setMcpEnabled(mcpEnabledCheckbox.isSelected());
        config.setMcpServerUri(mcpServerUriField.getText().trim());
        
        // Handle mutual exclusion for context settings
        if (contextInjectionRadio.isSelected()) {
            config.setContextInjectionEnabled(true);
        } else if (projectIndexRadio.isSelected()) {
            config.setProjectIndexEnabled(true);
        }
        
        String knowledgeId = knowledgeIdField.getText().trim();
        config.setKnowledgeId(knowledgeId.isEmpty() ? null : knowledgeId);
        
        config.setSystemPrompt(systemPromptArea.getText());
        config.setCodeSystemPrompt(codeSystemPromptArea.getText());
        config.setCommitPromptTemplate(newCommitTemplate);
        config.setDocsPath(docsPathField.getText().trim());
        config.setDocsSearchEnabled(docsSearchEnabledCheckbox.isSelected());
        
        // Save to file
        config.saveConfig();
        
        Messages.showInfoMessage(project, 
            "Settings saved successfully to zest-plugin.properties", 
            "Settings Saved");
    }
    
    @Override
    public void reset() {
        apiUrlField.setText(config.getApiUrl());
        authTokenField.setText(config.getAuthTokenNoPrompt());
        testModelField.setText(config.getTestModel());
        codeModelField.setText(config.getCodeModel());
        maxIterationsSpinner.setValue(config.getMaxIterations());
        inlineCompletionCheckbox.setSelected(config.isInlineCompletionEnabled());
        autoTriggerCheckbox.setSelected(config.isAutoTriggerEnabled());
        autoTriggerCheckbox.setEnabled(config.isInlineCompletionEnabled());
        backgroundContextCheckbox.setSelected(config.isBackgroundContextEnabled());
        ragEnabledCheckbox.setSelected(config.isRagEnabled());
        mcpEnabledCheckbox.setSelected(config.isMcpEnabled());
        mcpServerUriField.setText(config.getMcpServerUri());
        mcpServerUriField.setEnabled(config.isMcpEnabled());
        contextInjectionRadio.setSelected(config.isContextInjectionEnabled());
        projectIndexRadio.setSelected(config.isProjectIndexEnabled());
        knowledgeIdField.setText(config.getKnowledgeId() != null ? config.getKnowledgeId() : "");
        knowledgeIdField.setEnabled(config.isProjectIndexEnabled());
        systemPromptArea.setText(config.getSystemPrompt());
        codeSystemPromptArea.setText(config.getCodeSystemPrompt());
        commitPromptTemplateArea.setText(config.getCommitPromptTemplate());
        docsPathField.setText(config.getDocsPath());
        docsPathField.setEnabled(config.isDocsSearchEnabled());
        docsSearchEnabledCheckbox.setSelected(config.isDocsSearchEnabled());
    }
    
    private void validateCommitTemplate() {
        String template = commitPromptTemplateArea.getText();
        CommitTemplateValidator.ValidationResult result = 
            CommitTemplateValidator.validate(template);
        
        if (result.isValid) {
            Messages.showInfoMessage(project, "Template is valid!", "Validation Success");
        } else {
            Messages.showErrorDialog(project, result.errorMessage, "Validation Failed");
        }
    }
    
    private void previewCommitTemplate() {
        String template = commitPromptTemplateArea.getText();
        
        // Sample data
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
        
        // Show preview in a custom dialog
        JTextArea previewArea = new JTextArea(preview);
        previewArea.setEditable(false);
        previewArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        previewArea.setCaretPosition(0);
        
        JScrollPane scrollPane = new JScrollPane(previewArea);
        scrollPane.setPreferredSize(new Dimension(700, 500));
        
        // Create a custom dialog to show the preview
        DialogWrapper previewDialog = new DialogWrapper(project, false) {
            {
                setTitle("Commit Template Preview");
                init();
            }
            
            @Nullable
            @Override
            protected JComponent createCenterPanel() {
                return scrollPane;
            }
            
            @Override
            protected Action[] createActions() {
                return new Action[]{getOKAction()};
            }
        };
        
        previewDialog.show();
    }
    
    private void showCommitTemplateHelp() {
        String helpText = 
            "The commit template is used to generate AI-powered commit messages.\n\n" +
            "Required Placeholders:\n" +
            "• {FILES_LIST} - List of changed files with their status\n" +
            "• {DIFFS} - The actual code changes\n\n" +
            "Optional Placeholders (if implemented):\n" +
            "• {PROJECT_NAME} - Current project name\n" +
            "• {BRANCH_NAME} - Current git branch\n" +
            "• {DATE} - Current date\n" +
            "• {USER_NAME} - System username\n" +
            "• {FILES_COUNT} - Number of changed files\n\n" +
            "Tips:\n" +
            "• Be specific about the format you want\n" +
            "• Include examples in your template\n" +
            "• Test with the Preview button\n" +
            "• Use conventional commit format for consistency";
        
        Messages.showInfoMessage(project, helpText, "Commit Template Help");
    }
    
    private void indexProject() {
        // This would trigger the project indexing
        // For now, just show a message
        Messages.showInfoMessage(project, 
            "Project indexing would start here.\n" +
            "This feature requires the RAG system to be properly configured.", 
            "Index Project");
    }
}
