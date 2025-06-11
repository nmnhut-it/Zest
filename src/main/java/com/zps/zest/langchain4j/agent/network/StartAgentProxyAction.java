package com.zps.zest.langchain4j.agent.network;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;

/**
 * Action to start the Agent Proxy Server for external access.
 */
public class StartAgentProxyAction extends AnAction {
    
    private static JavalinProxyServer currentServer;
    private static AgentProxyServer delegateServer; // For monitoring
    private static AgentProxyMonitorWindowOptimized monitorWindow;
    
    public StartAgentProxyAction() {
        super("Start Agent Proxy Server", 
              "Start a network proxy for the code exploration agent", 
              null);
    }
    
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;
        
        // Check if server is already running
        if (currentServer != null) {
            // Show existing monitor window if hidden
            if (monitorWindow != null && !monitorWindow.isVisible()) {
                monitorWindow.setVisible(true);
                monitorWindow.toFront();
                return;
            }
            
            int result = Messages.showYesNoDialog(
                project,
                "Agent Proxy Server is already running. Do you want to stop it?",
                "Agent Proxy Server",
                Messages.getQuestionIcon()
            );
            
            if (result == Messages.YES) {
                stopServer();
            }
            return;
        }
        
        // Show configuration dialog
        AgentProxyDialog dialog = new AgentProxyDialog(project);
        if (dialog.showAndGet()) {
            int port = dialog.getPort();
            AgentProxyConfiguration config = dialog.getConfiguration();
            
            // Start server in background
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    // Use Javalin server instead
                    currentServer = new JavalinProxyServer(project, port, config);
                    delegateServer = currentServer.getDelegateServer(); // Get the delegate for monitoring
                    
                    // Ensure project is initialized before starting
                    if (!project.isInitialized()) {
//                        LOG.warn("Project not fully initialized, waiting...");
                        Thread.sleep(2000); // Wait 2 seconds
                    }
                    
                    currentServer.start();
                    
                    SwingUtilities.invokeLater(() -> {
                        // Show monitor window
                        monitorWindow = new AgentProxyMonitorWindowOptimized(project, delegateServer, port);
                        monitorWindow.setVisible(true);
                        
                        String message = """
                            Agent Proxy Server started successfully!
                            
                            Port: %d
                            OpenAPI spec: http://localhost:%d/zest/openapi.json
                            Swagger UI: http://localhost:%d/zest/docs
                            ReDoc: http://localhost:%d/zest/redoc
                            Monitor window is now open to track requests.
                            
                            You can now connect your MCP server or Open WebUI to this proxy.
                            """.formatted(port, port, port, port);
                        
                        Messages.showInfoMessage(project, message, "Agent Proxy Server Started");
                    });
                    
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        Messages.showErrorDialog(
                            project,
                            "Failed to start server: " + ex.getMessage(),
                            "Error Starting Agent Proxy Server"
                        );
                    });
                }
            });
        }
    }
    
    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        boolean enabled = project != null;
        
        e.getPresentation().setEnabled(enabled);
        
        if (currentServer != null) {
            e.getPresentation().setText("Show Agent Proxy Monitor");
            e.getPresentation().setDescription("Show the agent proxy monitoring window");
        } else {
            e.getPresentation().setText("Start Agent Proxy Server");
            e.getPresentation().setDescription("Start a network proxy for the code exploration agent");
        }
    }
    
    public static void stopServer() {
        if (currentServer != null) {
            currentServer.stop();
            currentServer = null;
        }
        if (delegateServer != null) {
            delegateServer.stop();
            delegateServer = null;
        }
        if (monitorWindow != null) {
            monitorWindow.dispose();
            monitorWindow = null;
        }
        Messages.showInfoMessage("Agent Proxy Server Stopped", "Server Stopped");
    }
    
    /**
     * Configuration dialog for the proxy server.
     */
    private static class AgentProxyDialog extends com.intellij.openapi.ui.DialogWrapper {
        private JSpinner portSpinner;
        private ComboBox<String> configPresetCombo;
        private JCheckBox includeTestsCheckBox;
        private JCheckBox deepExplorationCheckBox;
        private JSpinner maxToolCallsSpinner;
        private JSpinner timeoutSpinner;
        
        protected AgentProxyDialog(Project project) {
            super(project);
            setTitle("Configure Agent Proxy Server");
            init();
        }
        
        @Override
        protected JComponent createCenterPanel() {
            JPanel panel = new JPanel(new GridBagLayout());
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = JBUI.insets(5);
            gbc.fill = GridBagConstraints.HORIZONTAL;
            
            // Port configuration
            gbc.gridx = 0; gbc.gridy = 0;
            panel.add(new JLabel("Port:"), gbc);
            
            gbc.gridx = 1;
            portSpinner = new JSpinner(new SpinnerNumberModel(8765, 1024, 65535, 1));
            panel.add(portSpinner, gbc);
            
            // Configuration preset
            gbc.gridx = 0; gbc.gridy = 1;
            panel.add(new JLabel("Configuration:"), gbc);
            
            gbc.gridx = 1;
            configPresetCombo = new ComboBox<>(new String[]{
                "Default (Balanced)",
                "Quick Augmentation (Fast)",
                "Deep Exploration (Thorough)"
            });
            configPresetCombo.addActionListener(e -> updateUIFromPreset());
            panel.add(configPresetCombo, gbc);
            
            // Separator
            gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2;
            panel.add(new JSeparator(), gbc);
            
            // Advanced options
            gbc.gridwidth = 1;
            gbc.gridx = 0; gbc.gridy = 3;
            panel.add(new JLabel("Max Tool Calls:"), gbc);
            
            gbc.gridx = 1;
            maxToolCallsSpinner = new JSpinner(new SpinnerNumberModel(10, 1, 50, 1));
            panel.add(maxToolCallsSpinner, gbc);
            
            gbc.gridx = 0; gbc.gridy = 4;
            panel.add(new JLabel("Timeout (seconds):"), gbc);
            
            gbc.gridx = 1;
            timeoutSpinner = new JSpinner(new SpinnerNumberModel(3600, 60, 7200, 300));  // Default 1 hour, range 1 min to 2 hours
            panel.add(timeoutSpinner, gbc);
            
            gbc.gridx = 0; gbc.gridy = 5; gbc.gridwidth = 2;
            includeTestsCheckBox = new JCheckBox("Include test files");
            panel.add(includeTestsCheckBox, gbc);
            
            gbc.gridy = 6;
            deepExplorationCheckBox = new JCheckBox("Deep exploration");
            panel.add(deepExplorationCheckBox, gbc);
            
            // Info panel
            gbc.gridy = 7;
            JTextArea infoArea = new JTextArea("""
                The proxy server allows external tools (like MCP servers) to access
                the code exploration agent. Configure the settings based on your needs:
                - Quick: Fast responses, less thorough
                - Default: Balanced performance
                - Deep: Comprehensive exploration, slower
                """);
            infoArea.setEditable(false);
            infoArea.setBackground(panel.getBackground());
            panel.add(infoArea, gbc);
            
            return panel;
        }
        
        private void updateUIFromPreset() {
            String preset = (String) configPresetCombo.getSelectedItem();
            if (preset == null) return;
            
            switch (preset) {
                case "Quick Augmentation (Fast)":
                    maxToolCallsSpinner.setValue(5);
                    timeoutSpinner.setValue(1800);  // 30 minutes
                    includeTestsCheckBox.setSelected(false);
                    deepExplorationCheckBox.setSelected(false);
                    break;
                case "Deep Exploration (Thorough)":
                    maxToolCallsSpinner.setValue(20);
                    timeoutSpinner.setValue(3600);  // 1 hour
                    includeTestsCheckBox.setSelected(true);
                    deepExplorationCheckBox.setSelected(true);
                    break;
                default: // Default (Balanced)
                    maxToolCallsSpinner.setValue(10);
                    timeoutSpinner.setValue(3600);  // 1 hour
                    includeTestsCheckBox.setSelected(false);
                    deepExplorationCheckBox.setSelected(false);
                    break;
            }
        }
        
        public int getPort() {
            return (int) portSpinner.getValue();
        }
        
        public AgentProxyConfiguration getConfiguration() {
            AgentProxyConfiguration config = new AgentProxyConfiguration();
            config.setMaxToolCalls((int) maxToolCallsSpinner.getValue());
            config.setTimeoutSeconds((int) timeoutSpinner.getValue());
            config.setIncludeTests(includeTestsCheckBox.isSelected());
            config.setDeepExploration(deepExplorationCheckBox.isSelected());
            return config;
        }
    }
}
