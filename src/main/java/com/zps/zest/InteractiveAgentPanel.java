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
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enhanced Swing-based interactive chat panel for the AI agent with improved UX and chat functionality.
 */
public class InteractiveAgentPanel {
    private static final Logger LOG = Logger.getInstance(InteractiveAgentPanel.class);
    public static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```(.*?)\\n([\\s\\S]*?)```", Pattern.MULTILINE);
    private static final Pattern TOOL_RESULT_PATTERN = Pattern.compile("### TOOL RESULT", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOLLOW_UP_QUESTION_PATTERN = Pattern.compile("### FOLLOW_UP_QUESTION\\n(.*?)\\n### END_FOLLOW_UP_QUESTION", Pattern.DOTALL);

    private final Project project;
    private final SimpleToolWindowPanel mainPanel;
    private final ChatView chatView;
    private final InputPanel inputPanel;
    private final StatusBar statusBar;
    private final List<ChatMessage> chatHistory = new ArrayList<>();
    private boolean waitingForFollowUp = false;
    private String pendingFollowUpQuestion = null;
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
        String[] greetings = {
                "Hello, Code Wizard! May your keys be mighty!",
                "Greetings, Bug Slayer! Let’s squash some glitches today!",
                "Hey, Debug Ninja! Code smart, not hard!",
                "Welcome, Syntax Sorcerer! May your scripts run smoothly!",
                "Hello, Algorithm Artist! Ready to craft some magic?",
                "Greetings, Code Samurai! Cut through that code jungle!",
                "Hey, Loop Legend! Let’s make some efficient cycles today!",
                "Welcome, Compile Conqueror! Break those errors wide open!",
                "Hello, Logic Luminary! Illuminate the darkest code paths!",
                "Greetings, Pixel Pioneer! Let’s pixelate some great front-ends!"
        };

        Random random = new Random();
        int randomIndex = random.nextInt(greetings.length);
        String randomGreeting = greetings[randomIndex];

        addSystemMessage(randomGreeting);
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

        // Add this to the createToolbar() method in InteractiveAgentPanel.java
        AnAction reviewCodeAction = new AnAction("Review Current File", "Review the code of the current file",
                AllIcons.Actions.Preview) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                reviewCurrentFile();
            }
        };   // Add this to the createToolbar() method in InteractiveAgentPanel.java
        AnAction testToolsAction = new TestToolsAction();


        // Add actions to group
        group.add(newAction);
//        group.add(copyAction);
        group.addSeparator();
        group.add(reviewCodeAction);
        group.addSeparator();
        group.add(testToolsAction);
//        group.add(contextAction);

        // Create toolbar
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(
                "InteractiveAgentToolbar", group, true);
        toolbar.setTargetComponent(mainPanel);

        return toolbar;
    }
    // Add this method to InteractiveAgentPanel.java
    private void reviewCurrentFile() {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            addSystemMessage("No editor is currently open.");
            return;
        }

        VirtualFile[] selectedFiles = FileEditorManager.getInstance(project).getSelectedFiles();
        String fileName = selectedFiles[0].getName();
        String extension = selectedFiles[0].getExtension();
        String fileContent = editor.getDocument().getText();

        addSystemMessage("Reviewing current file: " + fileName);
        String message = "Please review this code and suggest improvements:\n\n```"+extension+"\n" + fileContent + "\n```";

        // Add user message and clear input
        addUserMessage(message);

        // Get current editor and process the request
        setProcessingState(true);

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                // Get conversation history
                List<String> conversationHistory = getConversationHistoryForContext();

                // Create enhanced request processor
                EnhancedAgentRequestProcessor processor = new EnhancedAgentRequestProcessor(project);

                // Process the request
                processor.processRequestWithTools(message, conversationHistory, editor)
                        .thenAccept(this::handleAIResponse);

            } catch (Exception e) {
                LOG.error("Error processing code review", e);
                setProcessingState(false);
                addSystemMessage("Error: " + e.getMessage());
            }
        });
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

        // Check if this is a response to a follow-up question
        final boolean isFollowUpResponse = waitingForFollowUp;
        final String followUpQuestion = pendingFollowUpQuestion;

        // Reset follow-up state
        waitingForFollowUp = false;
        pendingFollowUpQuestion = null;

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                // Get conversation history
                List<String> conversationHistory = getConversationHistoryForContext();

                // Create enhanced request processor
                EnhancedAgentRequestProcessor processor = new EnhancedAgentRequestProcessor(project);

                // Add system message to show processing
                addSystemMessage("Processing request with tools...");

                CompletableFuture<String> responseFuture;

                if (isFollowUpResponse) {
                    // If this is a follow-up response, include the question for context
                    String enhancedMessage = "Follow-up question: \"" + followUpQuestion + "\"\nUser response: \"" + userMessage + "\"";
                    responseFuture = processor.processRequestWithTools(enhancedMessage, conversationHistory, editor);
                } else {
                    // Process as normal request
                    responseFuture = processor.processRequestWithTools(userMessage, conversationHistory, editor);
                }

                // Handle the response
                responseFuture.thenAccept(this::handleAIResponse);

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
        // Check if response contains a follow-up question
        Matcher followUpMatcher = FOLLOW_UP_QUESTION_PATTERN.matcher(response);
        if (followUpMatcher.find()) {
            String question = followUpMatcher.group(1);
            handleFollowUpQuestion(question, response);
            return;
        }
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
     * Handle a follow-up question from the AI
     */
    private void handleFollowUpQuestion(String question, String fullResponse) {
        // Replace the follow-up question pattern with just the question text in the displayed message
        String displayResponse = fullResponse.replaceAll(
                "### FOLLOW_UP_QUESTION\\n(.*?)\\n### END_FOLLOW_UP_QUESTION",
                "I need some additional information: $1");

        // Display the assistant's message with the question
        addAssistantMessage(displayResponse);

        // Set UI state to indicate waiting for follow-up
        waitingForFollowUp = true;
        pendingFollowUpQuestion = question;

        // Update status bar to show waiting for user response
        statusBar.setStatus("Waiting for your response to the follow-up question...");
        setProcessingState(false);

        // Focus the input field
//        inputPanel.();
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