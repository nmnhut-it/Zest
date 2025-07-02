package com.zps.zest.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.*;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.zps.zest.ConfigurationManager;
import com.zps.zest.validation.CommitTemplateValidator;
import com.zps.zest.git.CommitTemplateExamples;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ItemEvent;

/**
 * Comprehensive settings panel for Zest Plugin configuration.
 * Manages all settings from ConfigurationManager in a single UI.
 */
public class ZestSettingsConfigurable implements Configurable {
    private final Project project;
    private final ConfigurationManager config;
    
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
    private JBCheckBox continuousCompletionCheckbox;
    
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
    
    public ZestSettingsConfigurable(Project project) {
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
        
        // Create tabbed pane with compact layout
        JBTabbedPane tabbedPane = new JBTabbedPane(JTabbedPane.TOP);
        tabbedPane.addTab("General", createGeneralPanel());
        tabbedPane.addTab("Models", createModelsPanel());
        tabbedPane.addTab("Features", createFeaturesPanel());
        tabbedPane.addTab("Prompts", createPromptsPanel());
        
        mainPanel.add(tabbedPane, BorderLayout.CENTER);
        mainPanel.setPreferredSize(new Dimension(800, 600));
        
        return mainPanel;
    }
    
    private JPanel createGeneralPanel() {
        FormBuilder builder = FormBuilder.createFormBuilder();
        
        // API Configuration
        builder.addComponent(new TitledSeparator("API Configuration"));
        
        apiUrlField = new JBTextField(config.getApiUrl());
        apiUrlField.setColumns(40); // Limit width
        builder.addLabeledComponent("API URL:", apiUrlField);
        
        authTokenField = new JBTextField(config.getAuthTokenNoPrompt());
        authTokenField.setColumns(30); // Limit width
        builder.addLabeledComponent("Auth Token:", authTokenField);
        
        // Context Settings
        builder.addSeparator();
        builder.addComponent(new TitledSeparator("Context Mode"));
        
        ButtonGroup contextGroup = new ButtonGroup();
        JPanel contextPanel = new JPanel(new GridLayout(2, 1, 0, 5));
        
        contextInjectionRadio = new JBRadioButton("Context injection (includes file contents in prompts)", 
                                                  config.isContextInjectionEnabled());
        contextGroup.add(contextInjectionRadio);
        contextPanel.add(contextInjectionRadio);
        
        projectIndexRadio = new JBRadioButton("Project index (uses knowledge base)", 
                                              config.isProjectIndexEnabled());
        contextGroup.add(projectIndexRadio);
        contextPanel.add(projectIndexRadio);
        
        // Add "None" state handling
        if (!config.isContextInjectionEnabled() && !config.isProjectIndexEnabled()) {
            contextGroup.clearSelection();
        }
        
        builder.addComponent(contextPanel);
        
        knowledgeIdField = new JBTextField(config.getKnowledgeId() != null ? config.getKnowledgeId() : "");
        knowledgeIdField.setColumns(30); // Limit width
        knowledgeIdField.setEnabled(config.isProjectIndexEnabled());
        builder.addLabeledComponent("Knowledge ID:", knowledgeIdField);
        
        // Enable knowledge ID field only when project index is selected
        projectIndexRadio.addItemListener(e -> {
            knowledgeIdField.setEnabled(e.getStateChange() == ItemEvent.SELECTED);
        });
        
        // Documentation Search
        builder.addSeparator();
        builder.addComponent(new TitledSeparator("Documentation Search"));
        
        docsSearchEnabledCheckbox = new JBCheckBox("Enable documentation search", config.isDocsSearchEnabled());
        builder.addComponent(docsSearchEnabledCheckbox);
        
        docsPathField = new JBTextField(config.getDocsPath());
        docsPathField.setColumns(30); // Limit width
        docsPathField.setEnabled(config.isDocsSearchEnabled());
        builder.addLabeledComponent("Docs folder:", docsPathField);
        
        docsSearchEnabledCheckbox.addItemListener(e -> {
            docsPathField.setEnabled(e.getStateChange() == ItemEvent.SELECTED);
        });
        
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(builder.getPanel(), BorderLayout.NORTH);
        panel.setBorder(JBUI.Borders.empty(10));
        return wrapInScrollPane(panel);
    }
    
    private JPanel createModelsPanel() {
        FormBuilder builder = FormBuilder.createFormBuilder();
        
        // Model Configuration
        builder.addComponent(new TitledSeparator("Model Configuration"));
        
        testModelField = new JBTextField(config.getTestModel());
        testModelField.setColumns(30); // Limit width
        builder.addLabeledComponent("Test Model:", testModelField);
        builder.addComponentToRightColumn(createDescriptionLabel("Model used for generating unit tests"));
        
        codeModelField = new JBTextField(config.getCodeModel());
        codeModelField.setColumns(30); // Limit width
        builder.addLabeledComponent("Code Model:", codeModelField);
        builder.addComponentToRightColumn(createDescriptionLabel("Model used for code generation and analysis"));
        
        maxIterationsSpinner = new JSpinner(new SpinnerNumberModel(config.getMaxIterations(), 1, 10, 1));
        JPanel spinnerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        spinnerPanel.add(maxIterationsSpinner);
        spinnerPanel.add(Box.createHorizontalStrut(10));
        spinnerPanel.add(createDescriptionLabel("Maximum number of refinement iterations"));
        builder.addLabeledComponent("Max Iterations:", spinnerPanel);
        
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(builder.getPanel(), BorderLayout.NORTH);
        panel.setBorder(JBUI.Borders.empty(10));
        return wrapInScrollPane(panel);
    }
    
    private JPanel createFeaturesPanel() {
        FormBuilder builder = FormBuilder.createFormBuilder();
        
        // Inline Completion
        builder.addComponent(new TitledSeparator("Inline Completion"));
        
        inlineCompletionCheckbox = new JBCheckBox("Enable inline completion", config.isInlineCompletionEnabled());
        builder.addComponent(inlineCompletionCheckbox);
        
        autoTriggerCheckbox = new JBCheckBox("Auto-trigger completion", config.isAutoTriggerEnabled());
        autoTriggerCheckbox.setEnabled(config.isInlineCompletionEnabled());
        builder.addComponent(autoTriggerCheckbox);
        
        continuousCompletionCheckbox = new JBCheckBox("Continuous completion (auto-trigger after acceptance)", config.isContinuousCompletionEnabled());
        continuousCompletionCheckbox.setEnabled(config.isInlineCompletionEnabled());
        builder.addComponent(continuousCompletionCheckbox);
        
        backgroundContextCheckbox = new JBCheckBox("Collect context in background", config.isBackgroundContextEnabled());
        builder.addComponent(backgroundContextCheckbox);
        
        inlineCompletionCheckbox.addItemListener(e -> {
            boolean enabled = e.getStateChange() == ItemEvent.SELECTED;
            autoTriggerCheckbox.setEnabled(enabled);
            continuousCompletionCheckbox.setEnabled(enabled);
        });
        
        // RAG Settings
        builder.addSeparator();
        builder.addComponent(new TitledSeparator("RAG (Retrieval-Augmented Generation)"));
        
        ragEnabledCheckbox = new JBCheckBox("Enable RAG", config.isRagEnabled());
        builder.addComponent(ragEnabledCheckbox);
        
        // MCP Settings
        builder.addSeparator();
        builder.addComponent(new TitledSeparator("MCP (Model Context Protocol)"));
        
        mcpEnabledCheckbox = new JBCheckBox("Enable MCP", config.isMcpEnabled());
        builder.addComponent(mcpEnabledCheckbox);
        
        mcpServerUriField = new JBTextField(config.getMcpServerUri());
        mcpServerUriField.setColumns(30); // Limit width
        mcpServerUriField.setEnabled(config.isMcpEnabled());
        builder.addLabeledComponent("MCP Server URI:", mcpServerUriField);
        
        mcpEnabledCheckbox.addItemListener(e -> {
            mcpServerUriField.setEnabled(e.getStateChange() == ItemEvent.SELECTED);
        });
        
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(builder.getPanel(), BorderLayout.NORTH);
        panel.setBorder(JBUI.Borders.empty(10));
        return wrapInScrollPane(panel);
    }
    
    private JPanel createPromptsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        
        // Create inner tabbed pane for different prompts
        JBTabbedPane promptTabs = new JBTabbedPane(JTabbedPane.TOP);
        
        // System Prompt Tab
        systemPromptArea = createPromptTextArea();
        systemPromptArea.setText(config.getSystemPrompt());
        promptTabs.addTab("System", createPromptPanel(
            systemPromptArea,
            "General assistant prompt",
            () -> systemPromptArea.setText(ConfigurationManager.DEFAULT_SYSTEM_PROMPT)
        ));
        
        // Code Prompt Tab
        codeSystemPromptArea = createPromptTextArea();
        codeSystemPromptArea.setText(config.getCodeSystemPrompt());
        promptTabs.addTab("Code", createPromptPanel(
            codeSystemPromptArea,
            "Programming assistant prompt",
            () -> codeSystemPromptArea.setText(ConfigurationManager.DEFAULT_CODE_SYSTEM_PROMPT)
        ));
        
        // Commit Template Tab
        commitPromptTemplateArea = createPromptTextArea();
        commitPromptTemplateArea.setText(config.getCommitPromptTemplate());
        promptTabs.addTab("Commit", createCommitTemplatePanel());
        
        panel.add(promptTabs, BorderLayout.CENTER);
        return panel;
    }
    
    private JPanel createPromptPanel(JTextArea textArea, String description, Runnable resetAction) {
        JPanel panel = new JPanel(new BorderLayout(0, 5));
        panel.setBorder(JBUI.Borders.empty(10));
        
        // Description
        JLabel descLabel = new JLabel(description);
        descLabel.setForeground(UIUtil.getContextHelpForeground());
        panel.add(descLabel, BorderLayout.NORTH);
        
        // Text area with scroll
        JBScrollPane scrollPane = new JBScrollPane(textArea);
        panel.add(scrollPane, BorderLayout.CENTER);
        
        // Reset button
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton resetButton = new JButton("Reset to Default");
        resetButton.addActionListener(e -> resetAction.run());
        buttonPanel.add(resetButton);
        panel.add(buttonPanel, BorderLayout.SOUTH);
        
        return panel;
    }
    
    private JPanel createCommitTemplatePanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 5));
        panel.setBorder(JBUI.Borders.empty(10));
        
        // Instructions
        JPanel topPanel = new JPanel(new BorderLayout());
        JBLabel instructions = new JBLabel(
            "<html>Required: <code>{FILES_LIST}</code>, <code>{DIFFS}</code><br>" +
            "Optional: <code>{PROJECT_NAME}</code>, <code>{BRANCH_NAME}</code>, etc.</html>"
        );
        instructions.setForeground(UIUtil.getContextHelpForeground());
        topPanel.add(instructions, BorderLayout.NORTH);
        
        // Template selector
        JPanel selectorPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        selectorPanel.add(new JLabel("Examples:"));
        
        JComboBox<CommitTemplateExamples.TemplateExample> templateSelector = new JComboBox<>();
        templateSelector.addItem(new CommitTemplateExamples.TemplateExample("Current", ""));
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
        JBScrollPane scrollPane = new JBScrollPane(commitPromptTemplateArea);
        panel.add(scrollPane, BorderLayout.CENTER);
        
        // Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        
        JButton validateBtn = new JButton("Validate");
        validateBtn.addActionListener(e -> validateCommitTemplate());
        buttonPanel.add(validateBtn);
        
        JButton resetBtn = new JButton("Reset");
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
    
    private JBTextArea createPromptTextArea() {
        JBTextArea textArea = new JBTextArea();
        textArea.setRows(15);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        return textArea;
    }
    
    private JLabel createDescriptionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(UIUtil.getContextHelpForeground());
        label.setFont(label.getFont().deriveFont(Font.ITALIC));
        return label;
    }
    
    private JPanel wrapInScrollPane(JPanel panel) {
        JBScrollPane scrollPane = new JBScrollPane(panel);
        scrollPane.setBorder(null);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
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
               continuousCompletionCheckbox.isSelected() != config.isContinuousCompletionEnabled() ||
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
        config.setContinuousCompletionEnabled(continuousCompletionCheckbox.isSelected());
        config.setRagEnabled(ragEnabledCheckbox.isSelected());
        config.setMcpEnabled(mcpEnabledCheckbox.isSelected());
        config.setMcpServerUri(mcpServerUriField.getText().trim());
        
        // Handle mutual exclusion for context settings
        if (contextInjectionRadio.isSelected()) {
            config.setContextInjectionEnabled(true);
        } else if (projectIndexRadio.isSelected()) {
            config.setProjectIndexEnabled(true);
        } else {
            // Neither selected - disable both
            config.setContextInjectionEnabled(false);
            config.setProjectIndexEnabled(false);
        }
        
        String knowledgeId = knowledgeIdField.getText().trim();
        config.setKnowledgeId(knowledgeId.isEmpty() ? null : knowledgeId);
        
        config.setSystemPrompt(systemPromptArea.getText());
        config.setCodeSystemPrompt(codeSystemPromptArea.getText());
        config.setCommitPromptTemplate(newCommitTemplate);
        config.setDocsPath(docsPathField.getText().trim());
        config.setDocsSearchEnabled(docsSearchEnabledCheckbox.isSelected());
        
        Messages.showInfoMessage(project, 
            "Settings saved successfully", 
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
        continuousCompletionCheckbox.setSelected(config.isContinuousCompletionEnabled());
        continuousCompletionCheckbox.setEnabled(config.isInlineCompletionEnabled());
        
        ragEnabledCheckbox.setSelected(config.isRagEnabled());
        mcpEnabledCheckbox.setSelected(config.isMcpEnabled());
        mcpServerUriField.setText(config.getMcpServerUri());
        mcpServerUriField.setEnabled(config.isMcpEnabled());
        
        // Reset context mode
        contextInjectionRadio.setSelected(config.isContextInjectionEnabled());
        projectIndexRadio.setSelected(config.isProjectIndexEnabled());
        knowledgeIdField.setText(config.getKnowledgeId() != null ? config.getKnowledgeId() : "");
        knowledgeIdField.setEnabled(config.isProjectIndexEnabled());
        
        systemPromptArea.setText(config.getSystemPrompt());
        codeSystemPromptArea.setText(config.getCodeSystemPrompt());
        commitPromptTemplateArea.setText(config.getCommitPromptTemplate());
        
        docsPathField.setText(config.getDocsPath());
        docsSearchEnabledCheckbox.setSelected(config.isDocsSearchEnabled());
        docsPathField.setEnabled(config.isDocsSearchEnabled());
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
        
        // Show preview in a dialog
        JTextArea previewArea = new JTextArea(preview);
        previewArea.setEditable(false);
        previewArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        previewArea.setCaretPosition(0);
        
        JScrollPane scrollPane = new JScrollPane(previewArea);
        scrollPane.setPreferredSize(new Dimension(700, 500));
        
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
            "Optional Placeholders:\n" +
            "• {PROJECT_NAME} - Current project name\n" +
            "• {BRANCH_NAME} - Current git branch\n" +
            "• {DATE} - Current date\n" +
            "• {USER_NAME} - System username\n\n" +
            "Tips:\n" +
            "• Be specific about the format you want\n" +
            "• Include examples in your template\n" +
            "• Test with the Preview button\n" +
            "• Use conventional commit format for consistency";
        
        Messages.showInfoMessage(project, helpText, "Commit Template Help");
    }
}
