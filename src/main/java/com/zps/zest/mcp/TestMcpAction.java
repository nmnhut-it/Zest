package com.zps.zest.mcp;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.zps.zest.InteractiveAgentService;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Action to test MCP client functionality.
 */
public class TestMcpAction extends AnAction {
    private static final Logger LOG = Logger.getInstance(TestMcpAction.class);
    
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        
        // Get MCP service
        McpService mcpService = McpService.getInstance(project);
        
        // Run test in background task
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Testing MCP Connection", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText("Initializing MCP client...");
                indicator.setIndeterminate(true);
                InteractiveAgentService agentService = InteractiveAgentService.getInstance(project);

                try {
                    // Get the interactive agent service

                    // Show the agent window
                    ApplicationManager.getApplication().invokeLater(() -> {
                        agentService.showAgentWindow();
                        agentService.addSystemMessage("Testing MCP connection...");
                    });
                    
                    // Initialize the client
                    CompletableFuture<Void> initFuture = mcpService.initializeClient();
                    
                    // Wait for initialization (with timeout)
                    initFuture.get(10, TimeUnit.SECONDS);
                    
                    indicator.setText("Testing simple prompt...");
                    
                    ApplicationManager.getApplication().invokeLater(() -> {
                        agentService.addSystemMessage("MCP client initialized successfully.");
                        agentService.addSystemMessage("Sending test prompt...");
                    });
                    
                    // Test a simple prompt
                    CompletableFuture<String> promptFuture = mcpService.sendPrompt(
                            "Say hello and introduce yourself as an MCP-enabled AI assistant",
                            true,
                            chunk -> {
                                // Update agent panel with streaming response
                                ApplicationManager.getApplication().invokeLater(() -> {
                                    agentService.addAssistantMessage(chunk);
                                });
                            });
                    
                    // Wait for prompt response (with timeout)
                    String response = promptFuture.get(30, TimeUnit.SECONDS);
                    
                    // Show success message
                    ApplicationManager.getApplication().invokeLater(() -> {
                        agentService.addSystemMessage("MCP test completed successfully!");
                        
                        // Show available tools if any
                        int toolCount = mcpService.getAvailableTools().size();
                        if (toolCount > 0) {
                            agentService.addSystemMessage("Found " + toolCount + " available tools.");
                        } else {
                            agentService.addSystemMessage("No tools available from the MCP server.");
                        }
                    });
                    
                } catch (Exception ex) {
                    LOG.error("MCP test failed", ex);
                    
                    // Show error message in the agent panel
                    ApplicationManager.getApplication().invokeLater(() -> {
                        agentService.addSystemMessage("MCP test failed: " + ex.getMessage());
                    });
                    
                    // Show popup error message
                    ApplicationManager.getApplication().invokeLater(() -> {
                        Messages.showErrorDialog(
                                project,
                                "Error testing MCP: " + ex.getMessage(),
                                "MCP Test Failed"
                        );
                    });
                }
            }
        });
    }
    
    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null);
    }
}