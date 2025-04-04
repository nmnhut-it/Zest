package com.zps.zest;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Project-level service for managing the interactive agent.
 */
@Service(Service.Level.PROJECT)
public final class InteractiveAgentService {
    private static final Logger LOG = Logger.getInstance(InteractiveAgentService.class);
    
    private final Project project;
    private InteractiveAgentPanel agentPanel;
    private final List<AgentListener> listeners = new ArrayList<>();
    
    /**
     * Gets the instance of the service for the specified project.
     *
     * @param project The project
     * @return The service instance
     */
    public static InteractiveAgentService getInstance(Project project) {
        return project.getService(InteractiveAgentService.class);
    }
    
    /**
     * Creates a new service instance.
     *
     * @param project The project
     */
    public InteractiveAgentService(Project project) {
        this.project = project;
        LOG.info("Interactive agent service created for project: " + project.getName());
    }
    
    /**
     * Registers the agent panel with the service.
     *
     * @param panel The agent panel
     */
    public void registerPanel(InteractiveAgentPanel panel) {
        this.agentPanel = panel;
        LOG.info("Agent panel registered");
    }
    
    /**
     * Shows the agent tool window and focuses it.
     */
    public void showAgentWindow() {
        ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
        ToolWindow toolWindow = toolWindowManager.getToolWindow("AI Assistant");
        
        if (toolWindow != null) {
            toolWindow.show(() -> {
                // Focus input field or other components as needed
            });
        } else {
            LOG.warn("Could not find AI Assistant tool window");
        }
    }
    
    /**
     * Adds a user message to the chat.
     *
     * @param message The message content
     */
    public void addUserMessage(String message) {
        if (agentPanel != null) {
            agentPanel.addUserMessage(message);
        }
    }
    
    /**
     * Adds an assistant message to the chat.
     *
     * @param message The message content
     */
    public void addAssistantMessage(String message) {
        if (agentPanel != null) {
            agentPanel.addAssistantMessage(message);
        }
    }
    
    /**
     * Adds a system message to the chat.
     *
     * @param message The message content
     */
    public void addSystemMessage(String message) {
        if (agentPanel != null) {
            agentPanel.addSystemMessage(message);
        }
    }
    
    /**
     * Gets the chat history.
     *
     * @return The list of chat messages
     */
    public List<InteractiveAgentPanel.ChatMessage> getChatHistory() {
        if (agentPanel != null) {
            return agentPanel.getChatHistory();
        }
        return new ArrayList<>();
    }
    
    /**
     * Notifies listeners that a response has been received.
     *
     * @param request The user request
     * @param response The assistant response
     */
    public void notifyResponseReceived(String request, String response) {
        for (AgentListener listener : listeners) {
            listener.onResponseReceived(request, response);
        }
    }
    
    /**
     * Adds a listener for agent events.
     *
     * @param listener The listener to add
     */
    public void addListener(AgentListener listener) {
        listeners.add(listener);
    }
    
    /**
     * Removes a listener for agent events.
     *
     * @param listener The listener to remove
     */
    public void removeListener(AgentListener listener) {
        listeners.remove(listener);
    }
    
    /**
     * Interface for listening to agent events.
     */
    public interface AgentListener {
        /**
         * Called when the assistant responds to a user request.
         *
         * @param request The user request
         * @param response The assistant response
         */
        void onResponseReceived(String request, String response);
    }
}