package com.zps.zest;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Enhanced Swing-based interactive chat panel for the AI agent with improved UX and chat functionality.
 */
public class InteractiveAgentPanel {
    private static final Logger LOG = Logger.getInstance(InteractiveAgentPanel.class);
    public static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```(.*?)\\n([\\s\\S]*?)```", Pattern.MULTILINE);
    private static final Pattern TOOL_RESULT_PATTERN = Pattern.compile("### TOOL RESULT", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOOL_PATTERN = ToolParser.TOOL_PATTERN;

    private final Project project;
    private final SimpleToolWindowPanel mainPanel;
    private final ChatView chatView;
    private final InputPanel inputPanel;
    private final StatusBar statusBar;
    private final List<ChatMessage> chatHistory = new ArrayList<>();

    private boolean isProcessing = false;

    /**
     * Creates a new chat-like interactive agent panel.
     *
     * @param project The current project
     */
    public InteractiveAgentPanel(Project project) {
        this.project = project;
        this.mainPanel = new SimpleToolWindowPanel(true, true);

        // Create main components
        this.chatView = new ChatView(this);
        this.inputPanel = new InputPanel(this);
        this.statusBar = new StatusBar();

        // Set up toolbar
        ActionToolbar toolbar = createToolbar();
        mainPanel.setToolbar(toolbar.getComponent());

        // Set up main layout
        JPanel contentPanel = new JPanel(new BorderLayout());
        contentPanel.add(chatView.getComponent(), BorderLayout.CENTER);
        contentPanel.add(inputPanel.getComponent(), BorderLayout.SOUTH);
        contentPanel.add(statusBar.getComponent(), BorderLayout.NORTH);

        mainPanel.setContent(contentPanel);

        // Add welcome message
        addSystemMessage("Welcome to the Enhanced AI Coding Assistant. How can I help you with your code today?");
    }

    /**
     * Gets the content component for this panel.
     */
    public JComponent getContent() {
        return mainPanel;
    }

    /**
     * Creates the toolbar with actions.
     */
    private ActionToolbar createToolbar() {
        DefaultActionGroup group = new DefaultActionGroup();

        // New conversation action
        AnAction newAction = new AnAction("New Conversation", "Start a new conversation", AllIcons.Actions.New) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                newConversation();
            }
        };

        // Copy conversation action
        AnAction copyAction = new AnAction("Copy Conversation", "Copy conversation to clipboard", AllIcons.Actions.Copy) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                copyConversation();
            }
        };

        // Get code context action
        AnAction contextAction = new AnAction("Get Code Context", "Get context from current editor", AllIcons.Actions.Refresh) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                getCodeContext();
            }
        };

        // Add actions to group
        group.add(newAction);
        group.add(copyAction);
        group.addSeparator();
        group.add(contextAction);

        // Create toolbar
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(
                "InteractiveAgentToolbar", group, true);
        toolbar.setTargetComponent(mainPanel);

        return toolbar;
    }

    /**
     * Sends the user's message to the AI.
     */
    public void sendMessage() {
        if (isProcessing) {
            return;
        }

        String userMessage = inputPanel.getInputText().trim();
        if (userMessage.isEmpty()) {
            return;
        }

        // Add user message and clear input
        addUserMessage(userMessage);
        inputPanel.clearInput();

        // Get current editor
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();

        // Set UI to processing state
        setProcessingState(true);

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                // Get conversation history
                List<String> conversationHistory = getConversationHistoryForContext();

                // Create enhanced request processor
                EnhancedAgentRequestProcessor processor = new EnhancedAgentRequestProcessor(project);

                // Add system message to show processing
                addSystemMessage("Processing request with tools...");

                // Process the request with tools - this already handles tool execution internally
                processor.processRequestWithTools(userMessage, conversationHistory, editor)
                        .thenAccept(this::handleAIResponse);

            } catch (Exception e) {
                LOG.error("Error processing request", e);
                setProcessingState(false);
                addSystemMessage("Error: " + e.getMessage());
            }
        });
    }

    /**
     * Handle AI response, checking for tool results that need further processing
     */
    private void handleAIResponse(String response) {
        // Display the response
        addSystemMessage("AI assistant responded");
        addAssistantMessage(response);

        // Check if response contains a tool result that needs another message
        if (TOOL_RESULT_PATTERN.matcher(response).find()) {
            addSystemMessage("Tool result detected, processing follow-up...");

            // Give the UI a moment to update
            Timer timer = new Timer(500, e -> {
                // Send another message with the same context
                sendFollowUpMessage(response);
            });
            timer.setRepeats(false);
            timer.start();
        } else {
            setProcessingState(false);

            // Update services
            InteractiveAgentService.getInstance(project).notifyResponseReceived(
                    getLastUserMessage(), response);
        }
    }

    /**
     * Gets the last user message from chat history
     */
    private String getLastUserMessage() {
        for (int i = chatHistory.size() - 1; i >= 0; i--) {
            ChatMessage message = chatHistory.get(i);
            if (message.getType() == MessageType.USER) {
                return message.getContent();
            }
        }
        return "";
    }

    /**
     * Send a follow-up message when tool results are detected
     */
    private void sendFollowUpMessage(String toolResponse) {
        // Get current editor
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();

        try {
            // Get conversation history
            List<String> conversationHistory = getConversationHistoryForContext();

            // Create enhanced request processor
            EnhancedAgentRequestProcessor processor = new EnhancedAgentRequestProcessor(project);

            // Process the request with the same context plus the tool result
            processor.processRequestWithTools("Continue with the previous tool result",
                            conversationHistory, editor)
                    .thenAccept((result) -> {
                        addAssistantMessage(result);
                        setProcessingState(false);

                        // Update services
                        InteractiveAgentService.getInstance(project).notifyResponseReceived(
                                getLastUserMessage(), result);
                    });

        } catch (Exception e) {
            LOG.error("Error processing follow-up request", e);
            setProcessingState(false);
            addSystemMessage("Error processing follow-up: " + e.getMessage());
        }
    }

    /**
     * Gets conversation history for context with system prompts.
     */
    private List<String> getConversationHistoryForContext() {
        List<String> history = new ArrayList<>();

        // Last 10 messages to avoid context overflow
        int startIndex = Math.max(0, chatHistory.size() - 10);
        for (int i = startIndex; i < chatHistory.size(); i++) {
            ChatMessage message = chatHistory.get(i);
            if (message.getType() != MessageType.SYSTEM) {
                String role = message.getType() == MessageType.USER ? "USER" : "ASSISTANT";
                history.add(role + ": " + message.getContent());
            }
        }

        return history;
    }

    /**
     * Sets the UI state for processing.
     */
    private void setProcessingState(boolean processing) {
        isProcessing = processing;
        statusBar.setProcessing(processing);
        inputPanel.setEnabled(!processing);
    }

    /**
     * Adds a user message to the chat.
     */
    public void addUserMessage(String message) {
        ChatMessage chatMessage = new ChatMessage(MessageType.USER, message, LocalDateTime.now());
        chatHistory.add(chatMessage);
        chatView.addMessage(chatMessage);
    }

    /**
     * Adds an assistant message to the chat.
     */
    public void addAssistantMessage(String message) {
        ChatMessage chatMessage = new ChatMessage(MessageType.ASSISTANT, message, LocalDateTime.now());
        chatHistory.add(chatMessage);
        chatView.addMessage(chatMessage);
    }

    /**
     * Adds a system message to the chat.
     */
    public void addSystemMessage(String message) {
        ChatMessage chatMessage = new ChatMessage(MessageType.SYSTEM, message, LocalDateTime.now());
        chatHistory.add(chatMessage);
        chatView.addMessage(chatMessage);
    }

    /**
     * Starts a new conversation.
     */
    private void newConversation() {
        int option = Messages.showYesNoDialog(
                mainPanel,
                "Start a new conversation? This will clear the chat history.",
                "New Conversation",
                Messages.getQuestionIcon()
        );

        if (option == Messages.YES) {
            chatHistory.clear();
            chatView.clearChat();
            addSystemMessage("Started a new conversation. How can I help you?");
        }
    }

    /**
     * Copies the conversation to the clipboard.
     */
    private void copyConversation() {
        StringBuilder text = new StringBuilder();

        for (ChatMessage message : chatHistory) {
            String sender = "";

            switch (message.getType()) {
                case USER:
                    sender = "You";
                    break;
                case ASSISTANT:
                    sender = "ZPS - AI Assistant";
                    break;
                case SYSTEM:
                    sender = "System";
                    break;
            }

            text.append(sender).append(": ").append(message.getContent()).append("\n\n");
        }

        // Copy to clipboard
        Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new StringSelection(text.toString()), null);

        // Show confirmation
        addSystemMessage("Conversation copied to clipboard.");
    }

    /**
     * Gets context from the current editor.
     */
    private void getCodeContext() {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            addSystemMessage("No editor is currently open.");
            return;
        }

        // Get selected text or visible content
        String selectedText = editor.getSelectionModel().getSelectedText();
        String contextText;

        if (selectedText != null && !selectedText.isEmpty()) {
            contextText = selectedText;
            addSystemMessage("Added selected code as context.");
        } else {
            // Get visible content
            contextText = editor.getDocument().getText();
            addSystemMessage("Added current file as context.");
        }

        // Add to input field
        inputPanel.appendText("\n\nContext for reference:\n```\n" + contextText + "\n```\n");
    }

    /**
     * Returns the chat history.
     */
    public List<ChatMessage> getChatHistory() {
        return new ArrayList<>(chatHistory);
    }

    /**
     * Returns the project
     */
    public Project getProject() {
        return project;
    }

    /**
     * Enum representing the type of message.
     */
    public enum MessageType {
        USER,
        ASSISTANT,
        SYSTEM
    }

    /**
     * Represents a chat message.
     */
    public static class ChatMessage {
        private final MessageType type;
        private final String content;
        private final LocalDateTime timestamp;

        public ChatMessage(MessageType type, String content, LocalDateTime timestamp) {
            this.type = type;
            this.content = content;
            this.timestamp = timestamp;
        }

        public MessageType getType() {
            return type;
        }

        public String getContent() {
            return content;
        }

        public LocalDateTime getTimestamp() {
            return timestamp;
        }
    }
}