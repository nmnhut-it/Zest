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
import com.intellij.ui.Gray;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enhanced interactive panel for the AI agent with improved UX and chat functionality.
 */
public class InteractiveAgentPanel {
    private static final Logger LOG = Logger.getInstance(InteractiveAgentPanel.class);
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```(.*?)\\n([\\s\\S]*?)```", Pattern.MULTILINE);

    private final Project project;
    private final SimpleToolWindowPanel panel;
    private final JEditorPane chatDisplay;
    private final JTextArea inputArea;
    private final JButton sendButton;
    private final JProgressBar progressBar;
    private final JLabel statusLabel;
    private final List<ChatMessage> chatHistory = new ArrayList<>();
    private final List<String> systemPrompts = new ArrayList<>();

    private boolean isProcessing = false;
    private boolean isDarkTheme = false;

    /**
     * Creates a new enhanced interactive agent panel.
     *
     * @param project The current project
     */
    public InteractiveAgentPanel(Project project) {
        this.project = project;
        this.panel = new SimpleToolWindowPanel(true, true);

        // Detect theme
        isDarkTheme = JBColor.isBright();

        // Set up the chat display with HTML
        chatDisplay = new JEditorPane("text/html", "");
        chatDisplay.setEditable(false);
        chatDisplay.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        chatDisplay.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
// For chat display main text color - using Label.foreground for theme-aware colors


        // Add Cursor-style system prompt
        addDefaultSystemPrompt();

        // Set up input area
        inputArea = new JTextArea();
        inputArea.setRows(6);
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        inputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        inputArea.setBorder(JBUI.Borders.empty(8));
        inputArea.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER && e.isControlDown()) {
                    sendMessage();
                    e.consume();
                }
            }
        });

        // For input area
        Color inputBgColor = JBColor.namedColor("TextArea.background", JBColor.background());
        inputArea.setBackground(inputBgColor);

        // Set up progress and status
        progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);
        statusLabel = new JLabel("Ready");
        statusLabel.setBorder(JBUI.Borders.emptyLeft(5));

        // Set up send button
        sendButton = new JButton("Send", AllIcons.Actions.Execute);
        sendButton.addActionListener(e -> sendMessage());

        // Create panel layout
        JBSplitter splitter = new JBSplitter(true, 0.7f);
        splitter.setDividerWidth(1);

        // Chat display panel
        JBScrollPane chatScrollPane = new JBScrollPane(chatDisplay);
        chatScrollPane.setBorder(JBUI.Borders.empty());
        splitter.setFirstComponent(chatScrollPane);
        chatScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        chatScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);


        // Input panel
        JPanel inputPanel = createInputPanel();
        splitter.setSecondComponent(inputPanel);

        // Status panel
        JPanel statusPanel = new JBPanel<>(new BorderLayout());
        statusPanel.add(progressBar, BorderLayout.CENTER);
        statusPanel.add(statusLabel, BorderLayout.EAST);
        statusPanel.setBorder(JBUI.Borders.empty(2, 5));

        // Add toolbar
        ActionToolbar toolbar = createToolbar();
        panel.setToolbar(toolbar.getComponent());

        // Main panel layout
        JPanel mainPanel = new JBPanel<>(new BorderLayout());
        mainPanel.add(splitter, BorderLayout.CENTER);
        mainPanel.add(statusPanel, BorderLayout.SOUTH);
        panel.setContent(mainPanel);

        // Add welcome message
        addSystemMessage("Welcome to the Enhanced AI Coding Assistant. How can I help you with your code today?");
    }

    /**
     * Adds default system prompt with Cursor-style.
     */
    private void addDefaultSystemPrompt() {
//        String cursorPrompt = "You are a powerful agentic AI coding assistant, powered by Claude 3.7 Sonnet. " +
//                "You operate exclusively in IntelliJ IDEA. " +
//                "You are pair programming with the user to solve their coding task. " +
//                "Each time the user sends a message, we may attach information about their current state. " +
//                "Your main goal is to follow the user's instructions at each message.";
//
//        systemPrompts.add(cursorPrompt);
    }

    /**
     * Gets the content component for this panel.
     */
    public JComponent getContent() {
        return panel;
    }

    /**
     * Creates the input panel.
     */
    private JPanel createInputPanel() {
        JPanel inputPanel = new JBPanel<>(new BorderLayout());
        JScrollPane inputScrollPane = new JBScrollPane(inputArea);
        inputScrollPane.setBorder(JBUI.Borders.empty());

        // Action panel
        JPanel actionPanel = new JBPanel<>(new BorderLayout());

        // Code snippet button
        JButton codeButton = new JButton(AllIcons.Actions.EditSource);
        codeButton.setToolTipText("Insert code snippet");
        codeButton.setBorderPainted(false);
        codeButton.setContentAreaFilled(false);
        codeButton.addActionListener(e -> inputArea.insert("```\n// Your code here\n```", inputArea.getCaretPosition()));

        // Buttons on right
        JPanel rightButtons = new JBPanel<>(new FlowLayout(FlowLayout.RIGHT));
        JButton clearButton = new JButton("Clear", AllIcons.Actions.Cancel);
        clearButton.addActionListener(e -> inputArea.setText(""));

        rightButtons.add(clearButton);
        rightButtons.add(sendButton);

        // Add buttons
        JPanel leftButtons = new JBPanel<>(new FlowLayout(FlowLayout.LEFT));
        leftButtons.add(codeButton);

        actionPanel.add(leftButtons, BorderLayout.WEST);
        actionPanel.add(rightButtons, BorderLayout.EAST);
        actionPanel.setBorder(JBUI.Borders.empty(5));

        // Assemble input panel
        inputPanel.add(inputScrollPane, BorderLayout.CENTER);
        inputPanel.add(actionPanel, BorderLayout.SOUTH);

        Border titledBorder = BorderFactory.createTitledBorder("Your Message (Ctrl+Enter to send)");
        inputPanel.setBorder(titledBorder);

        return inputPanel;
    }
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

        // System prompts action
        AnAction promptsAction = new AnAction("System Prompts", "Manage system prompts", AllIcons.General.Settings) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                manageSystemPrompts();
            }
        };

        // Test tools action
        AnAction testToolsAction = new TestToolsAction();

        // Add actions to group
        group.add(newAction);
        group.add(copyAction);
        group.addSeparator();
        group.add(contextAction);
        group.addSeparator();
        group.add(promptsAction);
        group.add(testToolsAction);

        // Create toolbar
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(
                "InteractiveAgentToolbar", group, true);
        toolbar.setTargetComponent(panel);

        return toolbar;
    }
    /**
     * Sends the user's message to the AI.
     */
    private void sendMessage() {
        if (isProcessing) {
            return;
        }

        String userMessage = inputArea.getText().trim();
        if (userMessage.isEmpty()) {
            return;
        }

        // Add user message and clear input
        addUserMessage(userMessage);
        inputArea.setText("");

        // Get current editor
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();

        // Set UI to processing state
        setProcessingState(true);

        // Create tools and processor
        EnhancedAgentRequestProcessor processor = new EnhancedAgentRequestProcessor(project);

        // Process request in background
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                // Get conversation history
                List<String> conversationHistory = getConversationHistoryForContext();

                // Process the request with history and tools
                AtomicReference<String> r = new AtomicReference<>();
                String response = processor.processRequestWithTools(userMessage, conversationHistory, editor);
                // Check for follow-up question
                if (response.contains("{{FOLLOW_UP_QUESTION:")) {
                    handleFollowUpQuestion(response);
                } else {
                    addAssistantMessage(response);
                }

                setProcessingState(false);

                // Update services
                InteractiveAgentService.getInstance(project).notifyResponseReceived(userMessage, response);
            } catch (Exception e) {
                LOG.error("Error processing request", e);
                e.printStackTrace();
            }
        }) ;
    }

    /**
     * Gets conversation history for context with system prompts.
     */
    private List<String> getConversationHistoryForContext() {
        List<String> history = new ArrayList<>();

        // Include system prompts at the beginning
        if (!systemPrompts.isEmpty()) {
            for (String prompt : systemPrompts) {
                history.add("SYSTEM: " + prompt);
            }
        }

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
     * Dialog to manage system prompts.
     */
    private void manageSystemPrompts() {
        JPanel promptsPanel = new JPanel(new BorderLayout());

        // Create list model and JList for prompts
        DefaultListModel<String> listModel = new DefaultListModel<>();
        for (String prompt : systemPrompts) {
            listModel.addElement(prompt.length() > 50 ? prompt.substring(0, 50) + "..." : prompt);
        }

        JList<String> promptsList = new JList<>(listModel);
        promptsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane scrollPane = new JScrollPane(promptsList);

        // Buttons panel
        JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton addButton = new JButton("Add", AllIcons.General.Add);
        JButton editButton = new JButton("Edit", AllIcons.Actions.Edit);
        JButton removeButton = new JButton("Remove", AllIcons.General.Remove);

        buttonsPanel.add(addButton);
        buttonsPanel.add(editButton);
        buttonsPanel.add(removeButton);

        // Add action listeners
        addButton.addActionListener(e -> {
            String newPrompt = Messages.showInputDialog(panel,
                    "Enter new system prompt:",
                    "Add System Prompt",
                    Messages.getQuestionIcon());

            if (newPrompt != null && !newPrompt.trim().isEmpty()) {
                systemPrompts.add(newPrompt);
                listModel.addElement(newPrompt.length() > 50 ? newPrompt.substring(0, 50) + "..." : newPrompt);
            }
        });

        editButton.addActionListener(e -> {
            int selectedIndex = promptsList.getSelectedIndex();
            if (selectedIndex >= 0) {
                String editedPrompt = Messages.showInputDialog(panel,
                        "Edit system prompt:",
                        "Edit System Prompt",
                        Messages.getQuestionIcon(),
                        systemPrompts.get(selectedIndex),
                        null);

                if (editedPrompt != null && !editedPrompt.trim().isEmpty()) {
                    systemPrompts.set(selectedIndex, editedPrompt);
                    listModel.set(selectedIndex,
                            editedPrompt.length() > 50 ? editedPrompt.substring(0, 50) + "..." : editedPrompt);
                }
            }
        });

        removeButton.addActionListener(e -> {
            int selectedIndex = promptsList.getSelectedIndex();
            if (selectedIndex >= 0) {
                systemPrompts.remove(selectedIndex);
                listModel.remove(selectedIndex);
            }
        });

        // Assemble panel
        promptsPanel.add(scrollPane, BorderLayout.CENTER);
        promptsPanel.add(buttonsPanel, BorderLayout.SOUTH);
        promptsPanel.setPreferredSize(new Dimension(400, 300));

        // Show dialog
        Messages.showMessageDialog(promptsPanel, "Manage System Prompts", "Zest", Messages.getInformationIcon());
    }

    /**
     * Handles a follow-up question from the AI.
     */
    private void handleFollowUpQuestion(String response) {
        // Extract the follow-up question
        int startIndex = response.indexOf("{{FOLLOW_UP_QUESTION:") + "{{FOLLOW_UP_QUESTION:".length();
        int endIndex = response.indexOf("}}", startIndex);

        if (startIndex >= "{{FOLLOW_UP_QUESTION:".length() && endIndex > startIndex) {
            String question = response.substring(startIndex, endIndex);

            // Clean response
            String cleanResponse = response.replace("{{FOLLOW_UP_QUESTION:" + question + "}}", "");
            addAssistantMessage(cleanResponse);
            addSystemMessage("AI is asking: " + question);

            // Show options dialog
            String[] options = {"Yes", "No", "Custom Answer"};
            int choice = Messages.showDialog(
                    panel,
                    question,
                    "Follow-up Question",
                    options,
                    0,
                    Messages.getQuestionIcon()
            );

            // Handle choice
            switch (choice) {
                case 0: // Yes
                    addUserMessage("Yes");
                    sendFollowUpResponse("Yes");
                    break;
                case 1: // No
                    addUserMessage("No");
                    sendFollowUpResponse("No");
                    break;
                case 2: // Custom
                    String customAnswer = Messages.showInputDialog(
                            panel,
                            "Enter your answer:",
                            "Follow-up Question",
                            Messages.getQuestionIcon()
                    );
                    if (customAnswer != null && !customAnswer.trim().isEmpty()) {
                        addUserMessage(customAnswer);
                        sendFollowUpResponse(customAnswer);
                    }
                    break;
            }
        } else {
            // If parsing fails, just add the response as is
            addAssistantMessage(response);
        }
    }

    /**
     * Sends a follow-up response.
     */
    private void sendFollowUpResponse(String message) {
        // Set UI to processing state
        setProcessingState(true);
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();

        // Process in background
        CompletableFuture.supplyAsync(() -> {
            try {
                AgentTools tools = new AgentTools(project);
                EnhancedAgentRequestProcessor processor = new EnhancedAgentRequestProcessor(project);
                List<String> conversationHistory = getConversationHistoryForContext();

                AtomicReference<String> response = new AtomicReference<>();
                ApplicationManager.getApplication().invokeAndWait(() -> {
                    try {
                        response.set(processor.processFollowUp(message, conversationHistory, editor));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

                return response.get();
            } catch (Exception e) {
                LOG.error("Error processing follow-up", e);
                return "Error: " + e.getMessage();
            }
        }).thenAccept(response -> {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (response.contains("{{FOLLOW_UP_QUESTION:")) {
                    handleFollowUpQuestion(response);
                } else {
                    addAssistantMessage(response);
                }
                setProcessingState(false);
            });
        });
    }

    /**
     * Sets the UI state for processing.
     */
    private void setProcessingState(boolean processing) {
        isProcessing = processing;
        progressBar.setVisible(processing);
        statusLabel.setText(processing ? "Processing..." : "Ready");
        sendButton.setEnabled(!processing);
        inputArea.setEnabled(!processing);
    }

    /**
     * Adds a user message to the chat.
     */
    public void addUserMessage(String message) {
        chatHistory.add(new ChatMessage(MessageType.USER, message, LocalDateTime.now()));
        updateChatDisplay();
    }

    /**
     * Adds an assistant message to the chat.
     */
    public void addAssistantMessage(String message) {
        chatHistory.add(new ChatMessage(MessageType.ASSISTANT, message, LocalDateTime.now()));
        updateChatDisplay();
        processCodeCommands(message);
    }

    /**
     * Adds a system message to the chat.
     */
    public void addSystemMessage(String message) {
        chatHistory.add(new ChatMessage(MessageType.SYSTEM, message, LocalDateTime.now()));
        updateChatDisplay();
    }

    /**
     * Updates the chat display with theme-aware styling.
     */
    private void updateChatDisplay() {
        StringBuilder html = new StringBuilder();

        // Correct theme detection
        isDarkTheme = !JBColor.isBright(); // Inverted - isBright() returns false for dark themes

        // Convert JBColor objects to hex strings for CSS
        Color textColor = JBColor.namedColor("Label.foreground",
                isDarkTheme ? new Color(220, 220, 220) : new Color(0, 0, 0));
        String textColorStr = String.format("#%06x", textColor.getRGB() & 0xFFFFFF);

        // Theme-specific colors
        Color bgColor = JBColor.namedColor("Editor.backgroundColor",
                isDarkTheme ? new Color(43, 43, 43) : new Color(247, 247, 247));
        String bgColorStr = String.format("#%06x", bgColor.getRGB() & 0xFFFFFF);

        Color userBgColor = JBColor.namedColor("Plugins.lightSelectionBackground",
                isDarkTheme ? new Color(60, 63, 65) : new Color(230, 247, 255));
        String userBgStr = String.format("#%06x", userBgColor.getRGB() & 0xFFFFFF);

        Color userBorderColor = JBColor.namedColor("Button.focusedBorderColor",
                isDarkTheme ? new Color(106, 135, 89) : new Color(26, 115, 232));
        String userBorderStr = String.format("#%06x", userBorderColor.getRGB() & 0xFFFFFF);

        Color aiBgColor = JBColor.namedColor("EditorPane.inactiveBackground",
                isDarkTheme ? new Color(49, 51, 53) : new Color(240, 240, 240));
        String aiBgStr = String.format("#%06x", aiBgColor.getRGB() & 0xFFFFFF);

        Color aiBorderColor = JBColor.namedColor("Component.focusColor",
                isDarkTheme ? new Color(204, 120, 50) : new Color(80, 178, 192));
        String aiBorderStr = String.format("#%06x", aiBorderColor.getRGB() & 0xFFFFFF);

        Color systemColor = JBColor.namedColor("Label.disabledForeground",
                isDarkTheme ? new Color(180, 180, 180) : new Color(120, 120, 120));
        String systemColorStr = String.format("#%06x", systemColor.getRGB() & 0xFFFFFF);

        Color codeBgColor = JBColor.namedColor("EditorPane.background",
                isDarkTheme ? new Color(45, 45, 45) : new Color(248, 248, 248));
        String codeBgStr = String.format("#%06x", codeBgColor.getRGB() & 0xFFFFFF);

        Color codeBorderColor = JBColor.namedColor("Border.color",
                isDarkTheme ? new Color(85, 85, 85) : new Color(221, 221, 221));
        String codeBorderStr = String.format("#%06x", codeBorderColor.getRGB() & 0xFFFFFF);

        // Base styles
        html.append("<html><head><style>");
        html.append("body { font-family: system-ui, -apple-system, sans-serif; ")
                .append("margin: 0; padding: 10px; line-height: 1.5; ")
                .append("background-color: ").append(bgColorStr).append("; ")
                .append("color: ").append(textColorStr).append("; }");

        // Message styles with explicit text colors
        html.append(".message { margin-bottom: 15px; padding: 12px; border-radius: 8px; }");
        html.append(".user { background-color: ").append(userBgStr)
                .append("; border-left: 3px solid ").append(userBorderStr)
                .append("; color: ").append(textColorStr).append("; }");
        html.append(".assistant { background-color: ").append(aiBgStr)
                .append("; border-left: 3px solid ").append(aiBorderStr)
                .append("; color: ").append(textColorStr).append("; }");
        html.append(".system { color: ").append(systemColorStr).append("; font-style: italic; text-align: center; }");

        // Force all text elements to use our color
        html.append("div, p, span, strong { color: ").append(textColorStr).append("; }");

        // Code styles with better contrast
        html.append("pre { background-color: ").append(codeBgStr).append("; ")
                .append("border: 1px solid ").append(codeBorderStr).append("; ")
                .append("padding: 10px; border-radius: 4px; overflow-x: auto; color: ").append(textColorStr).append("; }");
        html.append("code { font-family: 'JetBrains Mono', monospace; padding: 2px 4px; border-radius: 3px; color: ").append(textColorStr).append("; }");
        html.append(".timestamp { font-size: 0.8em; color: ").append(systemColorStr).append("; margin-top: 5px; text-align: right; }");

        html.append("</style></head><body>");


        // Rest of your function remains the same
        for (ChatMessage message : chatHistory) {
            String cssClass = "";
            String senderLabel = "";

            switch (message.getType()) {
                case USER:
                    cssClass = "user";
                    senderLabel = "You";
                    break;
                case ASSISTANT:
                    cssClass = "assistant";
                    senderLabel = "ZPS - AI Assistant";
                    break;
                case SYSTEM:
                    cssClass = "system";
                    senderLabel = "System";
                    break;
            }

            html.append("<div class=\"message ").append(cssClass).append("\">");

            if (message.getType() != MessageType.SYSTEM) {
                html.append("<strong>").append(senderLabel).append(":</strong> ");
            }

            // Format content with code block support
            String content = message.getContent();

            // Handle code blocks
            Matcher matcher = CODE_BLOCK_PATTERN.matcher(content);
            StringBuffer sb = new StringBuffer();

            while (matcher.find()) {
                String code = matcher.group(2);
                matcher.appendReplacement(sb, "<pre><code>" + escapeHtml(code) + "</code></pre>");
            }
            matcher.appendTail(sb);

            // Handle inline code
            String processed = sb.toString().replaceAll("`([^`]+)`", "<code>$1</code>");

            // Replace newlines with <br>
            processed = processed.replace("\n", "<br>");

            html.append(processed);

            // Add timestamp
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");
            html.append("<div class=\"timestamp\">").append(message.getTimestamp().format(formatter)).append("</div>");
            html.append("</div>");
        }

        html.append("</body></html>");
        chatDisplay.setText(html.toString());

        // Scroll to bottom
        SwingUtilities.invokeLater(() -> {
            JScrollPane scrollPane = (JScrollPane) chatDisplay.getParent().getParent();
            JScrollBar vertical = scrollPane.getVerticalScrollBar();
            vertical.setValue(vertical.getMaximum());
        });
    }
    /**
     * Escape HTML special characters.
     */
    private String escapeHtml(String content) {
        return content.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * Process code commands in the assistant's response.
     */
    private void processCodeCommands(String message) {
        // Check for code replacement command
        if (message.contains("{{REPLACE_SELECTION:")) {
            int start = message.indexOf("{{REPLACE_SELECTION:") + "{{REPLACE_SELECTION:".length();
            int end = message.indexOf("}}", start);

            if (start > 0 && end > start) {
                String code = message.substring(start, end);
                applyCodeChange("Replace Selection", code, true);
            }
        }

        // Check for code insertion command
        if (message.contains("{{INSERT_AT_CURSOR:")) {
            int start = message.indexOf("{{INSERT_AT_CURSOR:") + "{{INSERT_AT_CURSOR:".length();
            int end = message.indexOf("}}", start);

            if (start > 0 && end > start) {
                String code = message.substring(start, end);
                applyCodeChange("Insert at Cursor", code, false);
            }
        }

        // Check for file creation command
        if (message.contains("{{CREATE_FILE:")) {
            int pathStart = message.indexOf("{{CREATE_FILE:") + "{{CREATE_FILE:".length();
            int pathEnd = message.indexOf(":", pathStart);

            if (pathStart > 0 && pathEnd > pathStart) {
                String filePath = message.substring(pathStart, pathEnd);
                int contentStart = pathEnd + 1;
                int contentEnd = message.indexOf("}}", contentStart);

                if (contentEnd > contentStart) {
                    String fileContent = message.substring(contentStart, contentEnd);
                    createFile(filePath, fileContent);
                }
            }
        }
    }

    /**
     * Applies a code change.
     */
    private void applyCodeChange(String title, String code, boolean isReplace) {
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) return;

        // Show dialog
        int option = Messages.showYesNoDialog(
                project,
                code,
                title,
                "Apply",
                "Cancel",
                Messages.getQuestionIcon()
        );

        if (option == Messages.YES) {
            // Apply the code change
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project, () -> {
                if (isReplace) {
                    // Replace selection or insert at cursor if no selection
                    if (editor.getSelectionModel().hasSelection()) {
                        int start = editor.getSelectionModel().getSelectionStart();
                        int end = editor.getSelectionModel().getSelectionEnd();
                        editor.getDocument().replaceString(start, end, code);
                    } else {
                        int offset = editor.getCaretModel().getOffset();
                        editor.getDocument().insertString(offset, code);
                    }
                } else {
                    // Insert at cursor
                    int offset = editor.getCaretModel().getOffset();
                    editor.getDocument().insertString(offset, code);
                }
            });

            addSystemMessage("Code changes applied successfully!");
        }
    }

    /**
     * Creates a new file.
     */
    private void createFile(String filePath, String fileContent) {
        // Show dialog
        int option = Messages.showYesNoDialog(
                project,
                "Create new file at: " + filePath,
                "Create File",
                "Create",
                "Cancel",
                Messages.getQuestionIcon()
        );

        if (option == Messages.YES) {
            try {
                // Create the file
                String basePath = project.getBasePath();
                String fullPath = basePath + "/" + filePath;

                // Create directories if needed
                Path path = Paths.get(fullPath);
                Files.createDirectories(path.getParent());

                // Write file content
                Files.write(path, fileContent.getBytes(StandardCharsets.UTF_8));

                // Refresh VFS
                com.intellij.openapi.vfs.VirtualFile file = ApplicationManager.getApplication().runReadAction(
                        (com.intellij.openapi.util.Computable<com.intellij.openapi.vfs.VirtualFile>) () ->
                                com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(fullPath));

                if (file != null) {
                    // Open the file in editor
                    ApplicationManager.getApplication().invokeLater(() -> {
                        FileEditorManager.getInstance(project).openFile(file, true);
                    });

                    addSystemMessage("File created successfully: " + filePath);
                }
            } catch (Exception e) {
                LOG.error("Error creating file", e);
                addSystemMessage("Error creating file: " + e.getMessage());
            }
        }
    }

    /**
     * Starts a new conversation.
     */
    private void newConversation() {
        int option = Messages.showYesNoDialog(
                panel,
                "Start a new conversation? This will clear the chat history.",
                "New Conversation",
                Messages.getQuestionIcon()
        );

        if (option == Messages.YES) {
            chatHistory.clear();
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
        java.awt.Toolkit.getDefaultToolkit()
                .getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(text.toString()), null);

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
        inputArea.setText(inputArea.getText() + "\n\nContext for reference:\n```\n" + contextText + "\n```\n");
    }

    /**
     * Gets the system prompts.
     */
    public List<String> getSystemPrompts() {
        return new ArrayList<>(systemPrompts);
    }

    /**
     * Sets the system prompts.
     */
    public void setSystemPrompts(List<String> prompts) {
        systemPrompts.clear();
        if (prompts != null) {
            systemPrompts.addAll(prompts);
        }
        if (systemPrompts.isEmpty()) {
            addDefaultSystemPrompt();
        }
    }

    /**
     * Returns the chat history.
     */
    public List<ChatMessage> getChatHistory() {
        return new ArrayList<>(chatHistory);
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