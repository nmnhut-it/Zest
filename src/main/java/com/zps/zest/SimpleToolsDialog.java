package com.zps.zest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.zps.zest.tools.AgentTool;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Simple dialog for testing agent tools.
 */
public class SimpleToolsDialog extends DialogWrapper {
    private static final Logger LOG = Logger.getInstance(SimpleToolsDialog.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    private final Project project;
    private final AgentToolRegistry toolRegistry;
    private JComboBox<String> toolSelector;
    private JBTextArea paramsArea;
    private JBTextArea resultArea;

    public SimpleToolsDialog(Project project) {
        super(project);
        this.project = project;
        this.toolRegistry = new AgentToolRegistry(project);
        
        setTitle("Test Agent Tools");
        init();
    }

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        JBPanel<JBPanel<?>> panel = new JBPanel<>(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(10));
        panel.setPreferredSize(new Dimension(600, 500));
        
        // Tool selector
        toolSelector = new JComboBox<>();
        for (String toolName : toolRegistry.getToolNames()) {
            toolSelector.addItem(toolName);
        }
        
        // Parameters area
        paramsArea = new JBTextArea(5, 30);
        paramsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        
        // Result area
        resultArea = new JBTextArea(10, 30);
        resultArea.setEditable(false);
        resultArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        
        // Load example button
        JButton loadExampleButton = new JButton("Load Example");
        loadExampleButton.addActionListener(e -> {
            String selectedTool = (String)toolSelector.getSelectedItem();
            if (selectedTool != null) {
                AgentTool tool = toolRegistry.getTool(selectedTool);
                if (tool != null) {
                    JsonObject exampleParams = tool.getExampleParams();
                    paramsArea.setText(GSON.toJson(exampleParams));
                }
            }
        });
        
        // Execute button
        JButton executeButton = new JButton("Execute Tool");
        executeButton.addActionListener(e -> {
            String selectedTool = (String)toolSelector.getSelectedItem();
            if (selectedTool != null) {
                try {
                    AgentTool tool = toolRegistry.getTool(selectedTool);
                    if (tool != null) {
                        // Parse parameters
                        String paramsText = paramsArea.getText().trim();
                        JsonObject params;
                        if (paramsText.isEmpty()) {
                            params = new JsonObject();
                        } else {
                            params = GSON.fromJson(paramsText, JsonObject.class);
                        }
                        
                        // Execute tool
                        String result = tool.execute(params);
                        resultArea.setText(result);
                    } else {
                        resultArea.setText("Tool not found: " + selectedTool);
                    }
                } catch (Exception ex) {
                    LOG.error("Error executing tool", ex);
                    resultArea.setText("Error: " + ex.getMessage());
                }
            }
        });
        
        // Layout components
        JBPanel<JBPanel<?>> topPanel = new JBPanel<>(new BorderLayout());
        topPanel.add(new JLabel("Select Tool:"), BorderLayout.WEST);
        topPanel.add(toolSelector, BorderLayout.CENTER);
        
        JBPanel<JBPanel<?>> paramsPanel = new JBPanel<>(new BorderLayout());
        paramsPanel.setBorder(BorderFactory.createTitledBorder("Parameters (JSON)"));
        paramsPanel.add(new JBScrollPane(paramsArea), BorderLayout.CENTER);
        
        JBPanel<JBPanel<?>> buttonPanel = new JBPanel<>(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(loadExampleButton);
        buttonPanel.add(executeButton);
        
        JBPanel<JBPanel<?>> resultPanel = new JBPanel<>(new BorderLayout());
        resultPanel.setBorder(BorderFactory.createTitledBorder("Results"));
        resultPanel.add(new JBScrollPane(resultArea), BorderLayout.CENTER);
        
        // Assemble main panel
        JBPanel<JBPanel<?>> inputPanel = new JBPanel<>(new BorderLayout());
        inputPanel.add(topPanel, BorderLayout.NORTH);
        inputPanel.add(paramsPanel, BorderLayout.CENTER);
        inputPanel.add(buttonPanel, BorderLayout.SOUTH);
        
        panel.add(inputPanel, BorderLayout.NORTH);
        panel.add(resultPanel, BorderLayout.CENTER);
        
        return panel;
    }
    
    public static void showDialog(Project project) {
        new SimpleToolsDialog(project).show();
    }
}