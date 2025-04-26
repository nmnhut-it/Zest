package com.zps.zest.mcp;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.zps.zest.ConfigurationManager;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Dialog for configuring MCP settings.
 */
public class McpConfigurationDialog extends DialogWrapper {
    private final Project project;
    private final ConfigurationManager configManager;
    
    private JBTextField serverUriField;
    private JBCheckBox enableMcpCheckbox;
    
    public McpConfigurationDialog(Project project) {
        super(project);
        this.project = project;
        this.configManager = new ConfigurationManager(project);
        
        setTitle("MCP Configuration");
        setResizable(true);
        init();
    }
    
    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        
        // Enable MCP checkbox
        enableMcpCheckbox = new JBCheckBox("Enable MCP (Model Context Protocol)", configManager.isMcpEnabled());
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(5, 5, 15, 5);
        panel.add(enableMcpCheckbox, c);
        
        // Server URI label
        JBLabel serverUriLabel = new JBLabel("MCP Server URI:");
        c.gridx = 0;
        c.gridy = 1;
        c.gridwidth = 1;
        c.insets = new Insets(5, 5, 5, 5);
        panel.add(serverUriLabel, c);
        
        // Server URI field
        serverUriField = new JBTextField(configManager.getMcpServerUri(), 30);
        c.gridx = 1;
        c.gridy = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        panel.add(serverUriField, c);
        
        // Description label
        JBLabel descLabel = new JBLabel("<html>MCP enables standardized integration with AI models.<br>" +
                "Enable this option to use MCP for tool execution with compatible LLMs.</html>");
        descLabel.setForeground(Color.GRAY);
        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 2;
        c.insets = new Insets(20, 5, 5, 5);
        panel.add(descLabel, c);
        
        return panel;
    }
    
    @Override
    protected void doOKAction() {
        // Save settings
        configManager.setMcpEnabled(enableMcpCheckbox.isSelected());
        configManager.setMcpServerUri(serverUriField.getText().trim());
        configManager.saveConfig();
        
        super.doOKAction();
    }
}