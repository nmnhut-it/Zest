package com.zps.zest.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.*;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.zps.zest.settings.ConfigurationManager;
import com.zps.zest.validation.CommitTemplateValidator;
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
    
    // Feature Toggles (removed unused settings)
    
    // Inline Completion Settings
    private JBCheckBox inlineCompletionCheckbox;
    private JBCheckBox autoTriggerCheckbox;
    private JBCheckBox backgroundContextCheckbox;
    private JBCheckBox continuousCompletionCheckbox;
    private JBCheckBox streamingEnabledCheckbox;
    
    // Inline Completion RAG/AST Settings
    private JBCheckBox inlineRagEnabledCheckbox;
    private JBCheckBox astPatternMatchingCheckbox;
    private JSpinner maxRagContextSizeSpinner;
    private JSpinner embeddingCacheSizeSpinner;

    // Context Settings (removed unused settings)

    // Tool Server Settings
    private JBCheckBox toolServerEnabledCheckbox;

    // Metrics Settings
    private JBCheckBox metricsEnabledCheckbox;
    private JBTextField metricsServerUrlField;
    private JSpinner metricsBatchSizeSpinner;
    private JSpinner metricsBatchIntervalSpinner;
    private JSpinner metricsMaxQueueSizeSpinner;
    private JBCheckBox dualEvaluationEnabledCheckbox;
    private JBTextField dualEvaluationModelsField;
    private JBCheckBox aiSelfReviewEnabledCheckbox;

    // Prompt Section Configuration
    private JBCheckBox fileInfoSectionCheckbox;
    private JBCheckBox frameworkSectionCheckbox;
    private JBCheckBox contextAnalysisSectionCheckbox;
    private JBCheckBox vcsSectionCheckbox;
    private JBCheckBox relatedClassesSectionCheckbox;
    private JBCheckBox astPatternsSectionCheckbox;
    private JBCheckBox targetLineSectionCheckbox;
    
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
        
        // Removed unused context and documentation search settings
        
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

        // Chat Settings
        builder.addSeparator();
        streamingEnabledCheckbox = new JBCheckBox("Enable streaming chat responses", config.isStreamingEnabled());
        builder.addComponent(streamingEnabledCheckbox);

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

        // Tool Server Settings
        builder.addSeparator();
        builder.addComponent(new TitledSeparator("Tool API Server"));

        toolServerEnabledCheckbox = new JBCheckBox("Enable Tool API Server", config.isToolServerEnabled());
        builder.addComponent(toolServerEnabledCheckbox);

        builder.addComponent(createDescriptionLabel(
            "Automatically start REST API server for MCP/OpenWebUI tool integration (port 63342+)"));

        // Metrics Settings
        builder.addSeparator();
        builder.addComponent(new TitledSeparator("Metrics Configuration"));

        metricsEnabledCheckbox = new JBCheckBox("Enable metrics collection", config.isMetricsEnabled());
        builder.addComponent(metricsEnabledCheckbox);

        metricsServerUrlField = new JBTextField(config.getMetricsServerBaseUrl());
        metricsServerUrlField.setColumns(40);
        builder.addLabeledComponent("Metrics Server URL:", metricsServerUrlField);

        metricsBatchSizeSpinner = new JSpinner(new SpinnerNumberModel(config.getMetricsBatchSize(), 1, 100, 5));
        JPanel batchSizePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        batchSizePanel.add(new JLabel("Batch size:"));
        batchSizePanel.add(metricsBatchSizeSpinner);
        batchSizePanel.add(new JLabel("events"));
        builder.addComponent(batchSizePanel);

        metricsBatchIntervalSpinner = new JSpinner(new SpinnerNumberModel(config.getMetricsBatchIntervalSeconds(), 10, 300, 10));
        JPanel batchIntervalPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        batchIntervalPanel.add(new JLabel("Batch interval:"));
        batchIntervalPanel.add(metricsBatchIntervalSpinner);
        batchIntervalPanel.add(new JLabel("seconds"));
        builder.addComponent(batchIntervalPanel);

        metricsMaxQueueSizeSpinner = new JSpinner(new SpinnerNumberModel(config.getMetricsMaxQueueSize(), 100, 10000, 100));
        JPanel queueSizePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        queueSizePanel.add(new JLabel("Max queue size:"));
        queueSizePanel.add(metricsMaxQueueSizeSpinner);
        queueSizePanel.add(new JLabel("events"));
        builder.addComponent(queueSizePanel);

        // Dual Evaluation
        builder.addSeparator();
        builder.addComponent(new JLabel("Advanced Metrics:"));

        dualEvaluationEnabledCheckbox = new JBCheckBox("Enable dual evaluation (multi-AI comparison)", config.isDualEvaluationEnabled());
        builder.addComponent(dualEvaluationEnabledCheckbox);

        dualEvaluationModelsField = new JBTextField(config.getDualEvaluationModels());
        dualEvaluationModelsField.setColumns(40);
        builder.addLabeledComponent("Models for comparison:", dualEvaluationModelsField);
        builder.addComponent(createDescriptionLabel("Comma-separated model names (e.g., gpt-4o-mini,claude-3-5-sonnet)"));

        aiSelfReviewEnabledCheckbox = new JBCheckBox("Enable AI self-review before showing code", config.isAiSelfReviewEnabled());
        builder.addComponent(aiSelfReviewEnabledCheckbox);

        // Prompt Section Configuration
        builder.addSeparator();
        builder.addComponent(new TitledSeparator("Prompt Section Configuration"));
        builder.addComponent(createDescriptionLabel("Control which sections are included in lean completion prompts"));
        
        fileInfoSectionCheckbox = new JBCheckBox("Include file information section", config.isFileInfoSectionIncluded());
        builder.addComponent(fileInfoSectionCheckbox);
        
        frameworkSectionCheckbox = new JBCheckBox("Include framework-specific instructions", config.isFrameworkSectionIncluded());
        builder.addComponent(frameworkSectionCheckbox);
        
        contextAnalysisSectionCheckbox = new JBCheckBox("Include context analysis", config.isContextAnalysisSectionIncluded());
        builder.addComponent(contextAnalysisSectionCheckbox);
        
        vcsSectionCheckbox = new JBCheckBox("Include VCS context", config.isVcsSectionIncluded());
        builder.addComponent(vcsSectionCheckbox);
        
        relatedClassesSectionCheckbox = new JBCheckBox("Include related classes", config.isRelatedClassesSectionIncluded());
        builder.addComponent(relatedClassesSectionCheckbox);
        
        astPatternsSectionCheckbox = new JBCheckBox("Include AST patterns", config.isAstPatternsSectionIncluded());
        builder.addComponent(astPatternsSectionCheckbox);
        
        targetLineSectionCheckbox = new JBCheckBox("Include target line", config.isTargetLineSectionIncluded());
        builder.addComponent(targetLineSectionCheckbox);
        
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
//        commitPromptTemplateArea = createPromptTextArea();
//        commitPromptTemplateArea.setText(config.getCommitPromptTemplate());
//        promptTabs.addTab("Commit", createCommitTemplatePanel());
        
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
               streamingEnabledCheckbox.isSelected() != config.isStreamingEnabled() ||
               inlineRagEnabledCheckbox.isSelected() != config.isInlineCompletionRagEnabled() ||
               astPatternMatchingCheckbox.isSelected() != config.isAstPatternMatchingEnabled() ||
               !maxRagContextSizeSpinner.getValue().equals(config.getMaxRagContextSize()) ||
               !embeddingCacheSizeSpinner.getValue().equals(config.getEmbeddingCacheSize()) ||
               toolServerEnabledCheckbox.isSelected() != config.isToolServerEnabled() ||
               // Metrics settings
               metricsEnabledCheckbox.isSelected() != config.isMetricsEnabled() ||
               !metricsServerUrlField.getText().equals(config.getMetricsServerBaseUrl()) ||
               !metricsBatchSizeSpinner.getValue().equals(config.getMetricsBatchSize()) ||
               !metricsBatchIntervalSpinner.getValue().equals(config.getMetricsBatchIntervalSeconds()) ||
               !metricsMaxQueueSizeSpinner.getValue().equals(config.getMetricsMaxQueueSize()) ||
               dualEvaluationEnabledCheckbox.isSelected() != config.isDualEvaluationEnabled() ||
               !dualEvaluationModelsField.getText().equals(config.getDualEvaluationModels()) ||
               aiSelfReviewEnabledCheckbox.isSelected() != config.isAiSelfReviewEnabled() ||
               fileInfoSectionCheckbox.isSelected() != config.isFileInfoSectionIncluded() ||
               frameworkSectionCheckbox.isSelected() != config.isFrameworkSectionIncluded() ||
               contextAnalysisSectionCheckbox.isSelected() != config.isContextAnalysisSectionIncluded() ||
               vcsSectionCheckbox.isSelected() != config.isVcsSectionIncluded() ||
               relatedClassesSectionCheckbox.isSelected() != config.isRelatedClassesSectionIncluded() ||
               astPatternsSectionCheckbox.isSelected() != config.isAstPatternsSectionIncluded() ||
               targetLineSectionCheckbox.isSelected() != config.isTargetLineSectionIncluded() ||
               !systemPromptArea.getText().equals(config.getSystemPrompt()) ||
               !codeSystemPromptArea.getText().equals(config.getCodeSystemPrompt()) ||
               isProjectConfigurationModified();
    }
    
    @Override
    public void apply() {
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
        config.setStreamingEnabled(streamingEnabledCheckbox.isSelected());
        config.setInlineCompletionRagEnabled(inlineRagEnabledCheckbox.isSelected());
        config.setAstPatternMatchingEnabled(astPatternMatchingCheckbox.isSelected());
        config.setMaxRagContextSize((Integer) maxRagContextSizeSpinner.getValue());
        config.setEmbeddingCacheSize((Integer) embeddingCacheSizeSpinner.getValue());
        config.setToolServerEnabled(toolServerEnabledCheckbox.isSelected());

        // Metrics settings
        config.setMetricsEnabled(metricsEnabledCheckbox.isSelected());
        config.setMetricsServerBaseUrl(metricsServerUrlField.getText().trim());
        config.setMetricsBatchSize((Integer) metricsBatchSizeSpinner.getValue());
        config.setMetricsBatchIntervalSeconds((Integer) metricsBatchIntervalSpinner.getValue());
        config.setMetricsMaxQueueSize((Integer) metricsMaxQueueSizeSpinner.getValue());
        config.setDualEvaluationEnabled(dualEvaluationEnabledCheckbox.isSelected());
        config.setDualEvaluationModels(dualEvaluationModelsField.getText().trim());
        config.setAiSelfReviewEnabled(aiSelfReviewEnabledCheckbox.isSelected());

        config.setFileInfoSectionIncluded(fileInfoSectionCheckbox.isSelected());
        config.setFrameworkSectionIncluded(frameworkSectionCheckbox.isSelected());
        config.setContextAnalysisSectionIncluded(contextAnalysisSectionCheckbox.isSelected());
        config.setVcsSectionIncluded(vcsSectionCheckbox.isSelected());
        config.setRelatedClassesSectionIncluded(relatedClassesSectionCheckbox.isSelected());
        config.setAstPatternsSectionIncluded(astPatternsSectionCheckbox.isSelected());
        config.setTargetLineSectionIncluded(targetLineSectionCheckbox.isSelected());

        config.setSystemPrompt(systemPromptArea.getText());
        config.setCodeSystemPrompt(codeSystemPromptArea.getText());

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
        streamingEnabledCheckbox.setSelected(config.isStreamingEnabled());

        inlineRagEnabledCheckbox.setSelected(config.isInlineCompletionRagEnabled());
        inlineRagEnabledCheckbox.setEnabled(config.isInlineCompletionEnabled());
        astPatternMatchingCheckbox.setSelected(config.isAstPatternMatchingEnabled());
        astPatternMatchingCheckbox.setEnabled(config.isInlineCompletionEnabled());
        maxRagContextSizeSpinner.setValue(config.getMaxRagContextSize());
        maxRagContextSizeSpinner.setEnabled(config.isInlineCompletionEnabled() && config.isInlineCompletionRagEnabled());
        embeddingCacheSizeSpinner.setValue(config.getEmbeddingCacheSize());
        embeddingCacheSizeSpinner.setEnabled(config.isInlineCompletionEnabled() && config.isInlineCompletionRagEnabled());

        toolServerEnabledCheckbox.setSelected(config.isToolServerEnabled());

        // Metrics settings
        metricsEnabledCheckbox.setSelected(config.isMetricsEnabled());
        metricsServerUrlField.setText(config.getMetricsServerBaseUrl());
        metricsBatchSizeSpinner.setValue(config.getMetricsBatchSize());
        metricsBatchIntervalSpinner.setValue(config.getMetricsBatchIntervalSeconds());
        metricsMaxQueueSizeSpinner.setValue(config.getMetricsMaxQueueSize());
        dualEvaluationEnabledCheckbox.setSelected(config.isDualEvaluationEnabled());
        dualEvaluationModelsField.setText(config.getDualEvaluationModels());
        aiSelfReviewEnabledCheckbox.setSelected(config.isAiSelfReviewEnabled());

        fileInfoSectionCheckbox.setSelected(config.isFileInfoSectionIncluded());
        frameworkSectionCheckbox.setSelected(config.isFrameworkSectionIncluded());
        contextAnalysisSectionCheckbox.setSelected(config.isContextAnalysisSectionIncluded());
        vcsSectionCheckbox.setSelected(config.isVcsSectionIncluded());
        relatedClassesSectionCheckbox.setSelected(config.isRelatedClassesSectionIncluded());
        astPatternsSectionCheckbox.setSelected(config.isAstPatternsSectionIncluded());
        targetLineSectionCheckbox.setSelected(config.isTargetLineSectionIncluded());

        systemPromptArea.setText(config.getSystemPrompt());
        codeSystemPromptArea.setText(config.getCodeSystemPrompt());

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
            "";
    }
}
