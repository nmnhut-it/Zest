package com.zps.zest;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.text.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enhanced Swing-based interactive panel for the AI agent with improved UX and chat functionality.
 */
public class InteractiveAgentPanel {
    private static final Logger LOG = Logger.getInstance(InteractiveAgentPanel.class);
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```(.*?)\\n([\\s\\S]*?)```", Pattern.MULTILINE);
    private static final Pattern TOOL_PATTERN = ToolParser.TOOL_PATTERN;

    private final Project project;
    private final SimpleToolWindowPanel panel;
    private final JPanel chatPanel;
    private final JTextArea inputArea;
    private final JButton sendButton;
    private final JProgressBar progressBar;
    private final JLabel statusLabel;
    private final JScrollPane chatScrollPane;
    private final List<ChatMessage> chatHistory = new ArrayList<>();

    private boolean isProcessing = false;
    private boolean isDarkTheme = false;

    /**
     * Creates a new Swing-based interactive agent panel.
     *
     * @param project The current project
     */
    public InteractiveAgentPanel(Project project) {
        this.project = project;
        this.panel = new SimpleToolWindowPanel(true, true);

        // Detect theme
        isDarkTheme = !JBColor.isBright();

        // Set up the chat panel with Swing components
        chatPanel = new JBPanel<>();
        chatPanel.setLayout(new BoxLayout(chatPanel, BoxLayout.Y_AXIS));

        // Customize chat panel background
        Color chatBgColor = JBColor.namedColor("Editor.backgroundColor",
                isDarkTheme ? new Color(43, 43, 43) : new Color(247, 247, 247));
        chatPanel.setBackground(chatBgColor);

        // Add scroll pane for chat
        chatScrollPane = new JBScrollPane(chatPanel);
        chatScrollPane.setBorder(JBUI.Borders.empty());
        chatScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        chatScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        // Set up input area
        inputArea = new JTextArea();
        inputArea.setRows(6);
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
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

        // Chat panel
        splitter.setFirstComponent(chatScrollPane);

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

        // Add actions to group
        group.add(newAction);
        group.add(copyAction);
        group.addSeparator();
        group.add(contextAction);

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
                        .thenAccept((result)->{
                            // Display the final response that already includes tool results
                            addSystemMessage("AI assistant responded");

                            addAssistantMessage(result);

                            setProcessingState(false);

                            // Update services
                            InteractiveAgentService.getInstance(project).notifyResponseReceived(userMessage, result);
                        });


            } catch (Exception e) {
                LOG.error("Error processing request", e);
                e.printStackTrace();
                setProcessingState(false);
                addSystemMessage("Error: " + e.getMessage());
            }
        });
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
    }

    /**
     * Adds a system message to the chat.
     */
    public void addSystemMessage(String message) {
        chatHistory.add(new ChatMessage(MessageType.SYSTEM, message, LocalDateTime.now()));
        updateChatDisplay();
    }


    /**
     * Styles a message panel according to the message type.
     */
    private void styleMessagePanel(JPanel panel, Color bgColor, Color borderColor, String senderText) {
        panel.setBackground(bgColor);
        panel.setBorder(new CompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, borderColor),
                new EmptyBorder(8, 12, 8, 12)
        ));

        // Add sender label at the top
        JLabel senderLabel = new JLabel(senderText);
        senderLabel.setFont(senderLabel.getFont().deriveFont(Font.BOLD));
        panel.add(senderLabel, BorderLayout.NORTH);
    }

    /**
     * Processes content to handle code blocks and formatting.
     */
    private void processContent(String content, JPanel contentPanel) {
        // Use BoxLayout for vertical stacking
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));

        // Find code blocks
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(content);
        int lastEnd = 0;
        boolean found = false;

        while (matcher.find()) {
            found = true;

            // Add text before code block
            String textBefore = content.substring(lastEnd, matcher.start());
            if (!textBefore.isEmpty()) {
                addTextComponent(contentPanel, textBefore);
            }

            // Extract and add code block
            String language = matcher.group(1).trim();
            String codeContent = matcher.group(2);

            addCodeBlock(contentPanel, language, codeContent);

            lastEnd = matcher.end();
        }

        // Add remaining text after last code block
        if (found && lastEnd < content.length()) {
            addTextComponent(contentPanel, content.substring(lastEnd));
        } else if (!found) {
            // No code blocks found, add the entire content
            addTextComponent(contentPanel, content);
        }
    }

    /**
     * Updates the chat display with Swing components with proper z-ordering.
     */
    private void updateChatDisplay() {
        // Remove all existing components first
        chatPanel.removeAll();

        // Use a more robust layout manager that respects z-order
        chatPanel.setLayout(new BoxLayout(chatPanel, BoxLayout.Y_AXIS));

        // Get colors for theming
        isDarkTheme = !JBColor.isBright();

        Color userBgColor = JBColor.namedColor("Plugins.lightSelectionBackground",
                isDarkTheme ? new Color(60, 63, 65) : new Color(230, 247, 255));

        Color userBorderColor = JBColor.namedColor("Button.focusedBorderColor",
                isDarkTheme ? new Color(106, 135, 89) : new Color(26, 115, 232));

        Color aiBgColor = JBColor.namedColor("EditorPane.inactiveBackground",
                isDarkTheme ? new Color(49, 51, 53) : new Color(240, 240, 240));

        Color aiBorderColor = JBColor.namedColor("Component.focusColor",
                isDarkTheme ? new Color(204, 120, 50) : new Color(80, 178, 192));

        Color systemColor = JBColor.namedColor("Label.disabledForeground",
                isDarkTheme ? new Color(180, 180, 180) : new Color(120, 120, 120));

        // Process each message
        for (ChatMessage message : chatHistory) {
            JPanel messagePanel = new JBPanel<>();
            messagePanel.setLayout(new BorderLayout());
            messagePanel.setBorder(JBUI.Borders.empty(8, 12));

            // Style based on message type
            switch (message.getType()) {
                case USER:
                    styleMessagePanel(messagePanel, userBgColor, userBorderColor, "You:");
                    break;
                case ASSISTANT:
                    styleMessagePanel(messagePanel, aiBgColor, aiBorderColor, "ZPS - AI Assistant:");
                    break;
                case SYSTEM:
                    // System messages get special styling
                    JLabel systemLabel = new JLabel(message.getContent());
                    systemLabel.setForeground(systemColor);
                    systemLabel.setFont(systemLabel.getFont().deriveFont(Font.ITALIC));
                    systemLabel.setHorizontalAlignment(SwingConstants.CENTER);
                    systemLabel.setBorder(JBUI.Borders.empty(4));

                    messagePanel.add(systemLabel, BorderLayout.CENTER);
                    messagePanel.setBackground(null); // transparent
                    messagePanel.setBorder(JBUI.Borders.empty(4));

                    chatPanel.add(messagePanel);
                    chatPanel.add(Box.createVerticalStrut(5));
                    continue;
            }

            // Create content panel for the message text and code blocks
            JPanel contentPanel = new JBPanel<>();
            contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
            contentPanel.setOpaque(false);
            contentPanel.setBorder(JBUI.Borders.emptyTop(5));

            // Get content and process it
            String content = message.getContent();
            processMessageContent(content, contentPanel);

            // Add timestamp
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");
            JLabel timeLabel = new JLabel(message.getTimestamp().format(formatter));
            timeLabel.setForeground(systemColor);
            timeLabel.setFont(timeLabel.getFont().deriveFont(10.0f));
            timeLabel.setHorizontalAlignment(SwingConstants.RIGHT);

            // Add panels to message
            messagePanel.add(contentPanel, BorderLayout.CENTER);
            messagePanel.add(timeLabel, BorderLayout.SOUTH);

            // Add to chat
            chatPanel.add(messagePanel);
            chatPanel.add(Box.createVerticalStrut(10));
        }

        // Add filler to push everything to the top
        chatPanel.add(Box.createVerticalGlue());

        // Refresh the panel
        chatPanel.revalidate();
        chatPanel.repaint();

        // Ensure scroll to bottom happens after layout
        SwingUtilities.invokeLater(() -> {
            JScrollBar vertical = chatScrollPane.getVerticalScrollBar();
            vertical.setValue(vertical.getMaximum());
        });
    }

    /**
     * Process message content to handle code blocks and regular text.
     */
    private void processMessageContent(String content, JPanel contentPanel) {
        // Find code blocks
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(content);
        int lastEnd = 0;
        boolean found = false;

        while (matcher.find()) {
            found = true;

            // Add text before code block
            String textBefore = content.substring(lastEnd, matcher.start());
            if (!textBefore.isEmpty()) {
                addTextComponent(contentPanel, textBefore);
            }

            // Extract and add code block
            String language = matcher.group(1).trim();
            String codeContent = matcher.group(2);

            addCodeBlock(contentPanel, language, codeContent);

            lastEnd = matcher.end();
        }

        // Add remaining text after last code block
        if (found && lastEnd < content.length()) {
            addTextComponent(contentPanel, content.substring(lastEnd));
        } else if (!found) {
            // No code blocks found, add the entire content
            addTextComponent(contentPanel, content);
        }
    }

    /**
     * Adds a collapsible code block component with proper hierarchical structure.
     */
    private void addCodeBlock(JPanel parent, String language, String code) {
        // Create primary container panel with BorderLayout
        JPanel codeBlockContainer = new JBPanel<>(new BorderLayout());

        // Get colors for code
        Color codeBgColor = JBColor.namedColor("EditorPane.background",
                isDarkTheme ? new Color(45, 45, 45) : new Color(248, 248, 248));
        Color codeBorderColor = JBColor.namedColor("Border.color",
                isDarkTheme ? new Color(85, 85, 85) : new Color(221, 221, 221));
        Color codeTextColor = JBColor.namedColor("Label.foreground",
                isDarkTheme ? new Color(220, 220, 220) : new Color(0, 0, 0));

        // Style container
        codeBlockContainer.setBorder(BorderFactory.createLineBorder(codeBorderColor, 1));
        codeBlockContainer.setBackground(codeBgColor);

        // Create header panel
        JPanel headerPanel = new JBPanel<>(new BorderLayout());
        headerPanel.setBackground(codeBgColor);
        headerPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, codeBorderColor));

        // Language label
        JLabel langLabel = new JLabel(language.isEmpty() ? "Code" : language);
        langLabel.setForeground(codeTextColor);
        langLabel.setBorder(JBUI.Borders.empty(4, 8));
        headerPanel.add(langLabel, BorderLayout.WEST);

        // Action buttons panel
        JPanel actionPanel = new JBPanel<>(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        actionPanel.setOpaque(false);

        // Copy button
        JButton copyButton = new JButton(AllIcons.Actions.Copy);
        copyButton.setToolTipText("Copy code");
        copyButton.setBorderPainted(false);
        copyButton.setContentAreaFilled(false);

        // Toggle button
        JButton toggleButton = new JButton(AllIcons.Actions.Expandall);
        toggleButton.setToolTipText("Expand code");
        toggleButton.setBorderPainted(false);
        toggleButton.setContentAreaFilled(false);

        actionPanel.add(copyButton);
        actionPanel.add(toggleButton);
        headerPanel.add(actionPanel, BorderLayout.EAST);

        // Add header to container
        codeBlockContainer.add(headerPanel, BorderLayout.NORTH);

        // Create code content
        JTextArea codeArea = new JTextArea(code);
        codeArea.setEditable(false);
        codeArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        codeArea.setBackground(codeBgColor);
        codeArea.setForeground(codeTextColor);
        codeArea.setBorder(JBUI.Borders.empty(8));

        // Create scroll pane for code
        JScrollPane codeScrollPane = new JScrollPane(codeArea);
        codeScrollPane.setBorder(JBUI.Borders.empty());

        // Initially make the code area invisible - header still shows
        codeScrollPane.setVisible(false);

        // Add code area to container
        codeBlockContainer.add(codeScrollPane, BorderLayout.CENTER);

        // Copy button action
        copyButton.addActionListener(e -> {
            StringSelection selection = new StringSelection(code);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
            statusLabel.setText("Code copied to clipboard");

            // Reset status after 3 seconds
            Timer timer = new Timer(3000, event -> statusLabel.setText("Ready"));
            timer.setRepeats(false);
            timer.start();
        });

        // Toggle button action
        toggleButton.addActionListener(e -> {
            boolean isVisible = codeScrollPane.isVisible();

            // Toggle visibility
            codeScrollPane.setVisible(!isVisible);

            // Update toggle button
            if (isVisible) {
                toggleButton.setIcon(AllIcons.Actions.Expandall);
                toggleButton.setToolTipText("Expand code");
            } else {
                toggleButton.setIcon(AllIcons.Actions.Collapseall);
                toggleButton.setToolTipText("Collapse code");
            }

            // Calculate proper height based on content
            if (!isVisible) {
                // When expanding, calculate height based on content
                int lineCount = code.split("\n").length;
                int height = Math.min(lineCount * 18 + 20, 220); // 18px per line + padding
                codeScrollPane.setPreferredSize(new Dimension(codeBlockContainer.getWidth(), height));
            } else {
                // When collapsing, reset preferred size
                codeScrollPane.setPreferredSize(null);
            }

            // Force update layout at all levels
            codeScrollPane.revalidate();
            codeBlockContainer.revalidate();
            parent.revalidate();
            parent.repaint();
            chatPanel.revalidate();
            chatPanel.repaint();

            // Scroll to ensure visibility - must happen after layout update
            SwingUtilities.invokeLater(() -> {
                // Make container visible by scrolling to its position
                Rectangle bounds = codeBlockContainer.getBounds();
                codeBlockContainer.scrollRectToVisible(bounds);

                // Force scroll pane update
                chatScrollPane.revalidate();
                chatScrollPane.repaint();
            });
        });

        // Configure overall size constraints
        codeBlockContainer.setAlignmentX(Component.LEFT_ALIGNMENT);
        codeBlockContainer.setMaximumSize(new Dimension(Integer.MAX_VALUE, codeBlockContainer.getPreferredSize().height));

        // Add to parent
        parent.add(codeBlockContainer);
        parent.add(Box.createVerticalStrut(8));
    }

    /**
     * Adds a text component to display regular text.
     */
    private void addTextComponent(JPanel parent, String text) {
        JTextPane textPane = new JTextPane();
        textPane.setContentType("text/plain");
        textPane.setText(text);
        textPane.setEditable(false);
        textPane.setOpaque(false);

        // Set text color based on theme
        Color textColor = JBColor.namedColor("Label.foreground",
                isDarkTheme ? new Color(220, 220, 220) : new Color(0, 0, 0));
        textPane.setForeground(textColor);

        // Process inline code
        processInlineCode(textPane);

        // Configure size constraints
        textPane.setAlignmentX(Component.LEFT_ALIGNMENT);
        Dimension preferredSize = textPane.getPreferredSize();
        textPane.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferredSize.height));

        // Add to parent
        parent.add(textPane);
        parent.add(Box.createVerticalStrut(5));
    }
    /**
     * Processes inline code in a text component.
     */
    private void processInlineCode(JTextPane textPane) {
        String text = textPane.getText();
        StyledDocument doc = textPane.getStyledDocument();

        // Create style for inline code
        Style codeStyle = textPane.addStyle("InlineCode", null);

        // Get colors for inline code
        Color codeBgColor = JBColor.namedColor("EditorPane.background",
                isDarkTheme ? new Color(45, 45, 45) : new Color(248, 248, 248));
        Color codeTextColor = JBColor.namedColor("Label.foreground",
                isDarkTheme ? new Color(220, 220, 220) : new Color(0, 0, 0));

        StyleConstants.setBackground(codeStyle, codeBgColor);
        StyleConstants.setForeground(codeStyle, codeTextColor);
        StyleConstants.setFontFamily(codeStyle, Font.MONOSPACED);

        // Find inline code with regex
        Pattern inlinePattern = Pattern.compile("`([^`]+)`");
        Matcher inlineMatcher = inlinePattern.matcher(text);

        // Clear the document first
        try {
            doc.remove(0, doc.getLength());

            int lastEnd = 0;

            while (inlineMatcher.find()) {
                // Add text before inline code
                doc.insertString(doc.getLength(), text.substring(lastEnd, inlineMatcher.start()), null);

                // Add the inline code with style
                doc.insertString(doc.getLength(), inlineMatcher.group(1), codeStyle);

                lastEnd = inlineMatcher.end();
            }

            // Add remaining text
            if (lastEnd < text.length()) {
                doc.insertString(doc.getLength(), text.substring(lastEnd), null);
            }

        } catch (BadLocationException e) {
            LOG.error("Error processing inline code", e);
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
        inputArea.setText(inputArea.getText() + "\n\nContext for reference:\n```\n" + contextText + "\n```\n");
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