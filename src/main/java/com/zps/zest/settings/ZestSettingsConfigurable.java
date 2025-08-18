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
    
    // Inline Completion RAG/AST Settings
    private JBCheckBox inlineRagEnabledCheckbox;
    private JBCheckBox astPatternMatchingCheckbox;
    private JSpinner maxRagContextSizeSpinner;
    private JSpinner embeddingCacheSizeSpinner;
    
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
    
    // Project Configuration
    private JBTextArea projectRulesArea;
    private JBTextArea customPromptsArea;
    
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
        tabbedPane.addTab("Project", createProjectPanel());
        
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
        
        // Inline Completion RAG/AST Settings
        builder.addSeparator();
        builder.addComponent(new JLabel("Advanced Inline Completion:"));
        
        inlineRagEnabledCheckbox = new JBCheckBox("Enable RAG for inline completion", config.isInlineCompletionRagEnabled());
        inlineRagEnabledCheckbox.setEnabled(config.isInlineCompletionEnabled());
        builder.addComponent(inlineRagEnabledCheckbox);
        
        astPatternMatchingCheckbox = new JBCheckBox("Enable AST pattern matching", config.isAstPatternMatchingEnabled());
        astPatternMatchingCheckbox.setEnabled(config.isInlineCompletionEnabled());
        builder.addComponent(astPatternMatchingCheckbox);
        
        maxRagContextSizeSpinner = new JSpinner(new SpinnerNumberModel(
            config.getMaxRagContextSize(), 100, 5000, 100));
        maxRagContextSizeSpinner.setEnabled(config.isInlineCompletionEnabled() && config.isInlineCompletionRagEnabled());
        JPanel ragSizePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        ragSizePanel.add(new JLabel("Max RAG context size:"));
        ragSizePanel.add(maxRagContextSizeSpinner);
        ragSizePanel.add(new JLabel("characters"));
        builder.addComponent(ragSizePanel);
        
        embeddingCacheSizeSpinner = new JSpinner(new SpinnerNumberModel(
            config.getEmbeddingCacheSize(), 10, 500, 10));
        embeddingCacheSizeSpinner.setEnabled(config.isInlineCompletionEnabled() && config.isInlineCompletionRagEnabled());
        JPanel cachePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        cachePanel.add(new JLabel("Embedding cache size:"));
        cachePanel.add(embeddingCacheSizeSpinner);
        cachePanel.add(new JLabel("files"));
        builder.addComponent(cachePanel);
        
        inlineCompletionCheckbox.addItemListener(e -> {
            boolean enabled = e.getStateChange() == ItemEvent.SELECTED;
            autoTriggerCheckbox.setEnabled(enabled);
            continuousCompletionCheckbox.setEnabled(enabled);
            inlineRagEnabledCheckbox.setEnabled(enabled);
            astPatternMatchingCheckbox.setEnabled(enabled);
            maxRagContextSizeSpinner.setEnabled(enabled && inlineRagEnabledCheckbox.isSelected());
            embeddingCacheSizeSpinner.setEnabled(enabled && inlineRagEnabledCheckbox.isSelected());
        });
        
        inlineRagEnabledCheckbox.addItemListener(e -> {
            boolean ragEnabled = e.getStateChange() == ItemEvent.SELECTED;
            maxRagContextSizeSpinner.setEnabled(inlineCompletionCheckbox.isSelected() && ragEnabled);
            embeddingCacheSizeSpinner.setEnabled(inlineCompletionCheckbox.isSelected() && ragEnabled);
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
        
        JButton migrateBtn = new JButton("Update All Prompts");
        migrateBtn.setToolTipText("Update all prompts to latest concise versions");
        migrateBtn.addActionListener(e -> migrateAllPrompts());
        buttonPanel.add(migrateBtn);
        
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
               inlineRagEnabledCheckbox.isSelected() != config.isInlineCompletionRagEnabled() ||
               astPatternMatchingCheckbox.isSelected() != config.isAstPatternMatchingEnabled() ||
               !maxRagContextSizeSpinner.getValue().equals(config.getMaxRagContextSize()) ||
               !embeddingCacheSizeSpinner.getValue().equals(config.getEmbeddingCacheSize()) ||
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
               docsSearchEnabledCheckbox.isSelected() != config.isDocsSearchEnabled() ||
               isProjectConfigurationModified();
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
        config.setInlineCompletionRagEnabled(inlineRagEnabledCheckbox.isSelected());
        config.setAstPatternMatchingEnabled(astPatternMatchingCheckbox.isSelected());
        config.setMaxRagContextSize((Integer) maxRagContextSizeSpinner.getValue());
        config.setEmbeddingCacheSize((Integer) embeddingCacheSizeSpinner.getValue());
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
        
        // Save project configuration
        saveProjectConfiguration();
        
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
        
        inlineRagEnabledCheckbox.setSelected(config.isInlineCompletionRagEnabled());
        inlineRagEnabledCheckbox.setEnabled(config.isInlineCompletionEnabled());
        astPatternMatchingCheckbox.setSelected(config.isAstPatternMatchingEnabled());
        astPatternMatchingCheckbox.setEnabled(config.isInlineCompletionEnabled());
        maxRagContextSizeSpinner.setValue(config.getMaxRagContextSize());
        maxRagContextSizeSpinner.setEnabled(config.isInlineCompletionEnabled() && config.isInlineCompletionRagEnabled());
        embeddingCacheSizeSpinner.setValue(config.getEmbeddingCacheSize());
        embeddingCacheSizeSpinner.setEnabled(config.isInlineCompletionEnabled() && config.isInlineCompletionRagEnabled());
        
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
        
        // Reset project configuration
        resetProjectConfiguration();
    }
    
    private void resetProjectConfiguration() {
        // Reset project rules
        String rulesContent = config.readZestConfigFile("rules.md");
        if (rulesContent != null) {
            projectRulesArea.setText(rulesContent);
        } else {
            projectRulesArea.setText(getDefaultRulesContent());
        }
        
        // Reset custom prompts
        String promptsContent = config.readZestConfigFile("custom_prompts.md");
        if (promptsContent != null) {
            customPromptsArea.setText(promptsContent);
        } else {
            customPromptsArea.setText(getDefaultCustomPromptsContent());
        }
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
    
    private void migrateAllPrompts() {
        int result = Messages.showYesNoDialog(
            project,
            "This will update all prompts to the latest concise versions.\n" +
            "Your current prompts will be replaced.\n\n" +
            "Do you want to continue?",
            "Update Prompts",
            Messages.getQuestionIcon()
        );
        
        if (result == Messages.YES) {
            // Update all prompts to latest defaults
            systemPromptArea.setText(ConfigurationManager.DEFAULT_SYSTEM_PROMPT);
            codeSystemPromptArea.setText(ConfigurationManager.DEFAULT_CODE_SYSTEM_PROMPT);
            commitPromptTemplateArea.setText(ConfigurationManager.DEFAULT_COMMIT_PROMPT_TEMPLATE);
            
            Messages.showInfoMessage(
                project,
                "All prompts have been updated to the latest concise versions.\n" +
                "Click Apply to save the changes.",
                "Prompts Updated"
            );
        }
    }
    
    private JPanel createProjectPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        
        // Create tabbed pane for project configuration
        JBTabbedPane projectTabs = new JBTabbedPane(JTabbedPane.TOP);
        
        // Project Rules Tab
        projectRulesArea = createPromptTextArea();
        String rulesContent = config.readZestConfigFile("rules.md");
        if (rulesContent != null) {
            projectRulesArea.setText(rulesContent);
        } else {
            projectRulesArea.setText(getDefaultRulesContent());
        }
        projectTabs.addTab("Rules", createProjectRulesPanel());
        
        // Custom Prompts Tab
        customPromptsArea = createPromptTextArea();
        String promptsContent = config.readZestConfigFile("custom_prompts.md");
        if (promptsContent != null) {
            customPromptsArea.setText(promptsContent);
        } else {
            customPromptsArea.setText(getDefaultCustomPromptsContent());
        }
        projectTabs.addTab("Custom Prompts", createCustomPromptsPanel());
        
        panel.add(projectTabs, BorderLayout.CENTER);
        return panel;
    }
    
    private JPanel createProjectRulesPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(10));
        
        // Header
        JPanel headerPanel = new JPanel(new BorderLayout());
        JLabel headerLabel = new JLabel("Project-specific rules for AI assistance");
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD));
        headerPanel.add(headerLabel, BorderLayout.NORTH);
        
        JLabel descLabel = new JLabel("<html>Define coding standards, domain knowledge, and project-specific requirements.<br>" +
            "These rules will be included in all AI prompts for this project.</html>");
        descLabel.setFont(descLabel.getFont().deriveFont(Font.PLAIN, 11f));
        descLabel.setForeground(UIUtil.getContextHelpForeground());
        descLabel.setBorder(JBUI.Borders.emptyTop(5));
        headerPanel.add(descLabel, BorderLayout.CENTER);
        
        panel.add(headerPanel, BorderLayout.NORTH);
        
        // Text area with scroll
        JBScrollPane scrollPane = new JBScrollPane(projectRulesArea);
        scrollPane.setPreferredSize(new Dimension(700, 400));
        panel.add(scrollPane, BorderLayout.CENTER);
        
        // Buttons panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        
        JButton resetBtn = new JButton("Reset to Default");
        resetBtn.addActionListener(e -> {
            projectRulesArea.setText(getDefaultRulesContent());
        });
        buttonPanel.add(resetBtn);
        
        JButton saveBtn = new JButton("Save Rules");
        saveBtn.addActionListener(e -> {
            saveProjectRules();
        });
        buttonPanel.add(saveBtn);
        
        panel.add(buttonPanel, BorderLayout.SOUTH);
        
        return panel;
    }
    
    private JPanel createCustomPromptsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(10));
        
        // Header
        JPanel headerPanel = new JPanel(new BorderLayout());
        JLabel headerLabel = new JLabel("Custom prompts for block rewrite");
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD));
        headerPanel.add(headerLabel, BorderLayout.NORTH);
        
        JLabel descLabel = new JLabel("<html>Define custom prompts accessible via Shift+1-9 in block rewrite dialog.<br>" +
            "Format: ## Shift+1: Title followed by the prompt description.</html>");
        descLabel.setFont(descLabel.getFont().deriveFont(Font.PLAIN, 11f));
        descLabel.setForeground(UIUtil.getContextHelpForeground());
        descLabel.setBorder(JBUI.Borders.emptyTop(5));
        headerPanel.add(descLabel, BorderLayout.CENTER);
        
        panel.add(headerPanel, BorderLayout.NORTH);
        
        // Text area with scroll
        JBScrollPane scrollPane = new JBScrollPane(customPromptsArea);
        scrollPane.setPreferredSize(new Dimension(700, 400));
        panel.add(scrollPane, BorderLayout.CENTER);
        
        // Buttons panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        
        JButton resetBtn = new JButton("Reset to Default");
        resetBtn.addActionListener(e -> {
            customPromptsArea.setText(getDefaultCustomPromptsContent());
        });
        buttonPanel.add(resetBtn);
        
        JButton saveBtn = new JButton("Save Prompts");
        saveBtn.addActionListener(e -> {
            saveCustomPrompts();
        });
        buttonPanel.add(saveBtn);
        
        panel.add(buttonPanel, BorderLayout.SOUTH);
        
        return panel;
    }
    
    private boolean isProjectConfigurationModified() {
        // Check if project rules are modified
        String currentRules = config.readZestConfigFile("rules.md");
        if (currentRules == null) currentRules = getDefaultRulesContent();
        boolean rulesModified = !projectRulesArea.getText().equals(currentRules);
        
        // Check if custom prompts are modified
        String currentPrompts = config.readZestConfigFile("custom_prompts.md");
        if (currentPrompts == null) currentPrompts = getDefaultCustomPromptsContent();
        boolean promptsModified = !customPromptsArea.getText().equals(currentPrompts);
        
        return rulesModified || promptsModified;
    }
    
    private void saveProjectConfiguration() {
        try {
            config.ensureZestFolderExists();
            
            // Save project rules
            config.writeZestConfigFile("rules.md", projectRulesArea.getText());
            
            // Save custom prompts
            config.writeZestConfigFile("custom_prompts.md", customPromptsArea.getText());
            
        } catch (Exception e) {
            Messages.showErrorDialog(project, 
                "Error saving project configuration: " + e.getMessage(), 
                "Save Error");
        }
    }
    
    private void saveProjectRules() {
        try {
            config.ensureZestFolderExists();
            boolean success = config.writeZestConfigFile("rules.md", projectRulesArea.getText());
            if (success) {
                Messages.showInfoMessage(project, 
                    "Project rules saved successfully to .zest/rules.md", 
                    "Rules Saved");
            } else {
                Messages.showErrorDialog(project, 
                    "Failed to save project rules", 
                    "Save Error");
            }
        } catch (Exception e) {
            Messages.showErrorDialog(project, 
                "Error saving project rules: " + e.getMessage(), 
                "Save Error");
        }
    }
    
    private void saveCustomPrompts() {
        try {
            config.ensureZestFolderExists();
            boolean success = config.writeZestConfigFile("custom_prompts.md", customPromptsArea.getText());
            if (success) {
                Messages.showInfoMessage(project, 
                    "Custom prompts saved successfully to .zest/custom_prompts.md", 
                    "Prompts Saved");
            } else {
                Messages.showErrorDialog(project, 
                    "Failed to save custom prompts", 
                    "Save Error");
            }
        } catch (Exception e) {
            Messages.showErrorDialog(project, 
                "Error saving custom prompts: " + e.getMessage(), 
                "Save Error");
        }
    }
    
    private String getDefaultRulesContent() {
        return "# Zest Custom Rules\n\n" +
            "Define your custom LLM rules below. These rules will be included at the top of all prompts sent to the LLM.\n" +
            "You can use this to:\n" +
            "- Define coding standards specific to your project\n" +
            "- Add domain-specific knowledge\n" +
            "- Set preferred coding patterns\n" +
            "- Include project-specific requirements\n\n" +
            "## Example Rules:\n\n" +
            "<!-- \n" +
            "- Always use camelCase for variable names\n" +
            "- Prefer const over let for immutable values\n" +
            "- Include JSDoc comments for all public methods\n" +
            "- Follow the project's error handling patterns\n" +
            "-->\n\n" +
            "## Your Rules:\n\n";
    }
    
    private String getDefaultCustomPromptsContent() {
        return "# Custom Prompts for Zest\n\n" +
            "Define your own prompts for quick access using Shift+1 through Shift+9.\n\n" +
            "## Shift+1: Add Comments\n" +
            "Add detailed comments explaining the logic\n\n" +
            "## Shift+2: Optimize Performance\n" +
            "Optimize this code for better performance\n\n" +
            "## Shift+3: Add Error Handling\n" +
            "Add comprehensive error handling\n\n" +
            "## Shift+4: Add Unit Tests\n" +
            "Generate unit tests for this method\n\n" +
            "## Shift+5: Make Thread Safe\n" +
            "Make this code thread-safe\n\n" +
            "## Shift+6: Follow Best Practices\n" +
            "Refactor to follow coding best practices\n\n" +
            "## Shift+7: Add Documentation\n" +
            "Add comprehensive documentation\n\n" +
            "## Shift+8: Simplify Logic\n" +
            "Simplify and make more readable\n\n" +
            "## Shift+9: Add Logging\n" +
            "Add appropriate logging statements\n";
    }
}
