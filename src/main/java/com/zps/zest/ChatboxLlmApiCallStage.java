package com.zps.zest;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.zps.zest.browser.JavaScriptBridge;
import com.zps.zest.browser.WebBrowserService;
import com.zps.zest.browser.utils.ChatboxUtilities;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import com.intellij.openapi.diagnostic.Logger;

public class ChatboxLlmApiCallStage implements PipelineStage {
    private static final Logger LOG = Logger.getInstance(ChatboxLlmApiCallStage.class);
    private static final int TIMEOUT_SECONDS = 600; // 5 minutes (longer timeout for safety)
    private boolean useNativeFunctionCalling;

    public ChatboxLlmApiCallStage(boolean useNativeFunctionCalling1) {
        useNativeFunctionCalling1 = false;
    }

    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        Project project = context.getProject();
        if (project == null) {
            throw new PipelineExecutionException("No project available");
        }

        // Get configuration and browser service
        ConfigurationManager configManager = ConfigurationManager.getInstance(project);
        WebBrowserService browserService = WebBrowserService.getInstance(project);
        if (browserService == null) {
            throw new PipelineExecutionException("Browser service is not available");
        }

        JavaScriptBridge jsBridge = browserService.getBrowserPanel().getBrowserManager().getJavaScriptBridge();

        try {
            // Set up a response listener before sending the prompt
            CompletableFuture<String> responseFuture = jsBridge.waitForChatResponse(TIMEOUT_SECONDS);

            // Send the prompt using the same pattern as in the example
            boolean sent = sendPromptToChatBoxAndSubmit(project, context.getPrompt(), context, useNativeFunctionCalling);

            if (!sent) {
                throw new PipelineExecutionException("Failed to send prompt to chat box");
            }

            LOG.info("Prompt sent to chat box, waiting for response...");

            // Wait for the response
            String response = responseFuture.get(); // This blocks until we get a response or timeout

            LOG.info("Received response from chat box");

            // Store the response in the context
            context.setApiResponse(response);

        } catch (Exception e) {
            throw new PipelineExecutionException("Chat interaction failed: " + e.getMessage(), e);
        }
    }

    /**
     * Sends the generated prompt to the chat box, submits it, and activates the browser window.
     */
    private boolean sendPromptToChatBoxAndSubmit(Project project, String prompt, CodeContext context, boolean useNativeFunctionCalling) {
        LOG.info("Sending generated prompt to chat box and submitting");

        // Activate browser tool window and send prompt asynchronously
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("ZPS Chat");
        if (toolWindow != null) {
            // Create a CompletableFuture to track when the send operation is complete
            CompletableFuture<Boolean> sendCompleteFuture = new CompletableFuture<>();

            ApplicationManager.getApplication().invokeLater(() -> {
                toolWindow.activate(() -> {
                    // The ChatboxUtilities.sendTextAndSubmit method handles waiting for page load
                    WebBrowserService browserService = WebBrowserService.getInstance(project);

                    // Start a new chat
                    ChatboxUtilities.clickNewChatButton(project);

                    // Get the system prompt - determine which prompt to use based on stage type
                    String systemPrompt;
                    if (context.isUsingTestWrightModel()) {
                        String stageType = context.getCurrentStageType();
                        if ("TESTABILITY_ANALYSIS".equals(stageType)) {
                            // Load testability analysis system prompt
                            try {
                                java.io.InputStream inputStream = getClass().getResourceAsStream("/templates/testability_analysis_system_prompt.template");
                                if (inputStream != null) {
                                    systemPrompt = new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                                    inputStream.close();
                                    LOG.info("Loaded testability analysis system prompt from template");
                                } else {
                                    LOG.warn("Testability analysis system prompt template not found, using default");
                                    systemPrompt = ConfigurationManager.getInstance(project).getOpenWebUISystemPromptForCode();
                                }
                            } catch (Exception e) {
                                LOG.error("Error loading testability analysis system prompt", e);
                                systemPrompt = ConfigurationManager.getInstance(project).getOpenWebUISystemPromptForCode();
                            }
                        } else if ("TEST_PLANNING".equals(stageType)) {
                            // Load test planning system prompt
                            try {
                                java.io.InputStream inputStream = getClass().getResourceAsStream("/templates/test_planning_system_prompt.template");
                                if (inputStream != null) {
                                    systemPrompt = new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                                    inputStream.close();
                                    LOG.info("Loaded test planning system prompt from template");
                                } else {
                                    LOG.warn("Test planning system prompt template not found, using default");
                                    systemPrompt = ConfigurationManager.getInstance(project).getOpenWebUISystemPromptForCode();
                                }
                            } catch (Exception e) {
                                LOG.error("Error loading test planning system prompt", e);
                                systemPrompt = ConfigurationManager.getInstance(project).getOpenWebUISystemPromptForCode();
                            }
                        } else {
                            // Default test system prompt for other stages
                            try {
                                java.io.InputStream inputStream = getClass().getResourceAsStream("/templates/test_system_prompt.template");
                                if (inputStream != null) {
                                    systemPrompt = new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                                    inputStream.close();
                                    LOG.info("Loaded default test system prompt from template");
                                } else {
                                    LOG.warn("Default test system prompt template not found, using config");
                                    systemPrompt = ConfigurationManager.getInstance(project).getOpenWebUISystemPromptForCode();
                                }
                            } catch (Exception e) {
                                LOG.error("Error loading default test system prompt", e);
                                systemPrompt = ConfigurationManager.getInstance(project).getOpenWebUISystemPromptForCode();
                            }
                        }
                    } else {
                        // Use default system prompt for refactoring
                        systemPrompt = ConfigurationManager.getInstance(project).getOpenWebUISystemPromptForCode();
                    }

                    // Send the text and mark the operation as complete
                    boolean result = ChatboxUtilities.sendTextAndSubmit(project, prompt, false, systemPrompt, useNativeFunctionCalling);
                    sendCompleteFuture.complete(result);
                });
            });

            // Wait for the send operation to complete (with a reasonable timeout)
            try {
                return sendCompleteFuture.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOG.error("Error sending prompt to chat box", e);
                return false;
            }
        }

        return false;
    }
}