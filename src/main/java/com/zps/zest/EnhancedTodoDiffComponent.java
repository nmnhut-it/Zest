package com.zps.zest;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffDialogHints;
import com.intellij.diff.DiffManager;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.WindowWrapper;
import com.intellij.openapi.util.Pair;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enhanced diff viewer for TODO implementation with improved UX features:
 * - Highlighting of TODOs in the original code
 * - Smart diff showing only modified regions
 * - Integrated chat box for feedback and regeneration
 * - Custom tool window with additional actions
 * - Chat history displayed directly in the diff view
 */
public class EnhancedTodoDiffComponent {
    private static final Logger LOG = Logger.getInstance(EnhancedTodoDiffComponent.class);
    private static final Pattern TODO_PATTERN = Pattern.compile(
            "//\\s*(TODO|FIXME|HACK|XXX)\\s*:?\\s*(.*?)\\s*$|/\\*\\s*(TODO|FIXME|HACK|XXX)\\s*:?\\s*(.*?)\\s*\\*/",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    private final Project project;
    private final Editor editor;
    private String originalCode;
    private String implementedCode;
    private final int selectionStart;
    private final int selectionEnd;
    private WindowWrapper diffWindow;
    private boolean changesApplied = false;

    // Chat components
    private JPanel chatPanel;
    private JBTextArea chatInputArea;
    private JPanel chatHistoryPanel;
    private JButton sendButton;
    private JButton regenerateButton;
    private JFrame diffFrame;

    // Chat history storage
    private final List<ChatMessage> chatHistory = new ArrayList<>();
    private String codeContext;

    /**
     * Creates a new enhanced diff component for TODO implementation.
     *
     * @param project The current project
     * @param editor The editor containing the TODOs
     * @param originalCode The original code with TODOs
     * @param implementedCode The implemented code with TODOs replaced
     * @param selectionStart The start offset of the selection
     * @param selectionEnd The end offset of the selection
     */
    public EnhancedTodoDiffComponent(
            @NotNull Project project,
            @NotNull Editor editor,
            @NotNull String originalCode,
            @NotNull String implementedCode,
            int selectionStart,
            int selectionEnd) {
        this.project = project;
        this.editor = editor;
        this.originalCode = originalCode;
        this.implementedCode = implementedCode;
        this.selectionStart = selectionStart;
        this.selectionEnd = selectionEnd;

        // Initialize chat history with the original implementation request
        chatHistory.add(new ChatMessage(ChatMessageType.SYSTEM, "Original code with TODOs"));
        chatHistory.add(new ChatMessage(ChatMessageType.USER, originalCode));
        chatHistory.add(new ChatMessage(ChatMessageType.ASSISTANT, implementedCode));
    }

    /**
     * Sets additional context for the code to improve regeneration.
     *
     * @param context Additional code context
     */
    public void setCodeContext(String context) {
        this.codeContext = context;
    }

    /**
     * Shows the diff dialog with enhanced features.
     */
    public void showDiff() {
        // Create diff contents
        DiffContentFactory diffFactory = DiffContentFactory.getInstance();
        DocumentContent originalContent = diffFactory.create(originalCode);
        DocumentContent newContent = diffFactory.create(implementedCode);

        // Create diff request
        SimpleDiffRequest diffRequest = new SimpleDiffRequest(
                "TODO Implementation",
                originalContent,
                newContent,
                "Original Code with TODOs",
                "Implemented Code");

        // Show diff using DiffManager
        DiffManager diffManager = DiffManager.getInstance();
        diffWindow = null;

        DiffDialogHints dialogHints = new DiffDialogHints(WindowWrapper.Mode.FRAME, editor.getComponent(), wrapper -> {
            diffWindow = wrapper;

            // Add custom action toolbar and chat panel to the diff window
            if (wrapper.getWindow() instanceof JFrame) {
                diffFrame = (JFrame) wrapper.getWindow();
                Container contentPane = diffFrame.getContentPane();

                if (contentPane instanceof JComponent) {
                    JComponent component = (JComponent) contentPane;

                    // Create main container for our custom UI
                    JPanel customContainer = new JPanel(new BorderLayout());

                    // Create a panel for the action buttons
                    JPanel actionPanel = createActionPanel();

                    // Create the chat panel (right side)
                    chatPanel = createChatPanel();

                    // Add components to the custom container
                    customContainer.add(chatPanel, BorderLayout.EAST);
                    customContainer.add(actionPanel, BorderLayout.SOUTH);

                    // Add our custom container to the content pane
                    component.add(customContainer, BorderLayout.EAST);

                    // Update the UI
                    diffFrame.setSize(new Dimension(
                            diffFrame.getWidth() + 350, // Extra width for chat panel
                            diffFrame.getHeight()
                    ));

                    // Force layout update
                    diffFrame.validate();

                    // Update the chat history display
                    updateChatHistoryPanel();
                }
            }
        });

        diffManager.showDiff(project, diffRequest, dialogHints);
    }

    /**
     * Creates a panel with action buttons for the diff window.
     */
    private JPanel createActionPanel() {
        JPanel panel = new JBPanel<>(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(0, 10, 10, 10));

        // Create toolbar with custom actions
        DefaultActionGroup actionGroup = new DefaultActionGroup();

        // Apply changes action
        AnAction applyAction = new AnAction("Apply Changes", "Apply the implemented code changes", AllIcons.Actions.CheckOut) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                applyChanges();
                closeWindow();
            }
        };

        // Cancel action
        AnAction cancelAction = new AnAction("Cancel", "Discard changes and close", AllIcons.Actions.Cancel) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                closeWindow();
            }
        };

        // Show all TODOs action
        AnAction showTodosAction = new AnAction("Show All TODOs", "Show a list of all TODOs in the code", AllIcons.General.TodoDefault) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                showTodoList();
            }
        };

        actionGroup.add(applyAction);
        actionGroup.addSeparator();
        actionGroup.add(showTodosAction);
        actionGroup.addSeparator();
        actionGroup.add(cancelAction);

        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("TodoDiffActions", actionGroup, true);
        toolbar.setTargetComponent(panel);

        panel.add(toolbar.getComponent(), BorderLayout.EAST);

        // Add a label with help text
        JBLabel helpLabel = new JBLabel("Review the implemented TODOs and choose an action");
        helpLabel.setBorder(JBUI.Borders.empty(5, 0, 0, 0));
        panel.add(helpLabel, BorderLayout.WEST);

        return panel;
    }

    /**
     * Creates the integrated chat panel for the diff view.
     */
    private JPanel createChatPanel() {
        JPanel panel = new JBPanel<>(new BorderLayout());
        panel.setPreferredSize(new Dimension(350, 0)); // Fixed width, height follows parent
        panel.setBorder(JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 1));

        // Chat history panel (scrollable)
        chatHistoryPanel = new JBPanel<>();
        chatHistoryPanel.setLayout(new BoxLayout(chatHistoryPanel, BoxLayout.Y_AXIS));
        JBScrollPane historyScrollPane = new JBScrollPane(chatHistoryPanel);
        historyScrollPane.setBorder(JBUI.Borders.empty());
        historyScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        // Chat input panel
        JPanel inputPanel = new JBPanel<>(new BorderLayout());
        inputPanel.setBorder(JBUI.Borders.emptyTop(5));

        // Chat input area
        chatInputArea = new JBTextArea(5, 20);
        chatInputArea.setLineWrap(true);
        chatInputArea.setWrapStyleWord(true);
        chatInputArea.setBorder(JBUI.Borders.empty(5));
        JBScrollPane inputScrollPane = new JBScrollPane(chatInputArea);
        inputScrollPane.setBorder(new TitledBorder("Your Feedback"));

        // Button panel
        JPanel buttonPanel = new JBPanel<>(new FlowLayout(FlowLayout.RIGHT));

        // Send button
        sendButton = new JButton("Send Feedback", AllIcons.Actions.Edit);
        sendButton.addActionListener(e -> sendFeedback());

        // Regenerate button
        regenerateButton = new JButton("Regenerate Code", AllIcons.Actions.Refresh);
        regenerateButton.addActionListener(e -> regenerateImplementation(null));

        buttonPanel.add(regenerateButton);
        buttonPanel.add(sendButton);

        // Assemble input panel
        inputPanel.add(inputScrollPane, BorderLayout.CENTER);
        inputPanel.add(buttonPanel, BorderLayout.SOUTH);

        // Header panel
        JPanel headerPanel = new JBPanel<>(new BorderLayout());
        headerPanel.setBorder(JBUI.Borders.empty(5, 10));
        JBLabel headerLabel = new JBLabel("Feedback & Chat", AllIcons.Nodes.Console, SwingConstants.LEFT);
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD, 14f));
        headerPanel.add(headerLabel, BorderLayout.CENTER);

        // Add components to main panel
        panel.add(headerPanel, BorderLayout.NORTH);
        panel.add(historyScrollPane, BorderLayout.CENTER);
        panel.add(inputPanel, BorderLayout.SOUTH);

        return panel;
    }

    /**
     * Updates the chat history panel with current messages.
     */
    private void updateChatHistoryPanel() {
        chatHistoryPanel.removeAll();

        for (ChatMessage message : chatHistory) {
            JPanel messagePanel = createMessagePanel(message);
            chatHistoryPanel.add(messagePanel);

            // Add a separator
            JSeparator separator = new JSeparator();
            separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
            chatHistoryPanel.add(separator);
        }

        // Refresh the panel
        chatHistoryPanel.revalidate();
        chatHistoryPanel.repaint();

        // Scroll to bottom
        SwingUtilities.invokeLater(() -> {
            JScrollPane scrollPane = (JScrollPane) chatHistoryPanel.getParent().getParent();
            JScrollBar vertical = scrollPane.getVerticalScrollBar();
            vertical.setValue(vertical.getMaximum());
        });
    }

    /**
     * Creates a panel for a single chat message.
     */
    private JPanel createMessagePanel(ChatMessage message) {
        JPanel panel = new JBPanel<>(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(5));

        Icon icon = null;
        Color bgColor = null;

        switch (message.getType()) {
            case USER:
                icon = AllIcons.Nodes.EntryPoints;
                bgColor = JBColor.namedColor("Panel.background", new JBColor(new Color(240, 242, 245), new Color(49, 51, 53)));
                break;
            case ASSISTANT:
                icon = AllIcons.Nodes.Controller;
                bgColor = JBColor.namedColor("Plugins.lightSelectionBackground", new JBColor(new Color(240, 247, 240), new Color(45, 53, 45)));
                break;
            case SYSTEM:
                icon = AllIcons.General.Information;
                bgColor = JBColor.namedColor("Tooltip.background", new JBColor(new Color(245, 245, 245), new Color(56, 56, 56)));
                break;
        }

        panel.setBackground(bgColor);

        // Header with icon and type
        JPanel headerPanel = new JBPanel<>(new BorderLayout());
        headerPanel.setBackground(bgColor);
        JLabel typeLabel = new JBLabel(message.getType() == ChatMessageType.SYSTEM ? "System" :
                (message.getType() == ChatMessageType.USER ? "You" : "Assistant"),
                icon, SwingConstants.LEFT);
        typeLabel.setFont(typeLabel.getFont().deriveFont(Font.BOLD));
        headerPanel.add(typeLabel, BorderLayout.WEST);

        // For code blocks, create a collapsible panel
        if (message.getType() == ChatMessageType.ASSISTANT && message.getContent().length() > 200) {
            // Collapsible content
            JPanel contentPanel = new JBPanel<>(new BorderLayout());
            contentPanel.setBackground(bgColor);

            // Create a snippet of the content
            String snippet = message.getContent().substring(0, Math.min(150, message.getContent().length())) + "...";
            JLabel snippetLabel = new JBLabel("<html><body><pre>" +
                    snippet.replace("<", "&lt;").replace(">", "&gt;") +
                    "</pre></body></html>");
            snippetLabel.setBorder(JBUI.Borders.empty(5));

            // Full content in a text area (initially not visible)
            JBTextArea fullContentArea = new JBTextArea(message.getContent());
            fullContentArea.setEditable(false);
            fullContentArea.setLineWrap(true);
            fullContentArea.setBackground(new Color(248, 250, 248));
            fullContentArea.setRows(10);
            JBScrollPane scrollPane = new JBScrollPane(fullContentArea);
            scrollPane.setBorder(JBUI.Borders.empty());
            scrollPane.setVisible(false);

            // Toggle button
            JButton toggleButton = new JButton("Show Full Code", AllIcons.Actions.ShowCode);
            toggleButton.addActionListener(e -> {
                boolean isVisible = scrollPane.isVisible();
                scrollPane.setVisible(!isVisible);
                snippetLabel.setVisible(isVisible);
                toggleButton.setText(isVisible ? "Show Full Code" : "Hide Code");
                toggleButton.setIcon(isVisible ? AllIcons.Actions.ShowCode : AllIcons.Actions.Cancel);
                panel.revalidate();
                panel.repaint();
            });

            // Add components
            contentPanel.add(snippetLabel, BorderLayout.NORTH);
            contentPanel.add(scrollPane, BorderLayout.CENTER);

            JPanel buttonPanel = new JBPanel<>(new FlowLayout(FlowLayout.LEFT));
            buttonPanel.setBackground(bgColor);
            buttonPanel.add(toggleButton);

            // For assistant messages, also add a button to apply this implementation
            if (message.getType() == ChatMessageType.ASSISTANT && chatHistory.indexOf(message) > 2) {
                JButton applyButton = new JButton("Apply This Version", AllIcons.Actions.CheckOut);
                applyButton.addActionListener(e -> {
                    implementedCode = message.getContent();
                    updateDiffView();
                });
                buttonPanel.add(applyButton);
            }

            contentPanel.add(buttonPanel, BorderLayout.SOUTH);
            panel.add(contentPanel, BorderLayout.CENTER);
        } else {
            // For shorter messages, just show the content directly
            String messageContent = message.getContent();
            JComponent contentComponent;

            if (message.getType() == ChatMessageType.USER && messageContent.contains("\n")) {
                // Multi-line user message gets a text area
                JBTextArea contentArea = new JBTextArea(messageContent);
                contentArea.setEditable(false);
                contentArea.setBackground(bgColor);
                contentArea.setRows(Math.min(5, messageContent.split("\n").length + 1));
                contentArea.setBorder(JBUI.Borders.empty(5));
                contentComponent = contentArea;
            } else {
                // Single line messages get a label
                JLabel contentLabel = new JBLabel("<html><body>" +
                        messageContent.replace("\n", "<br>").replace("<", "&lt;").replace(">", "&gt;") +
                        "</body></html>");
                contentLabel.setBorder(JBUI.Borders.empty(5));
                contentComponent = contentLabel;
            }

            panel.add(contentComponent, BorderLayout.CENTER);
        }

        panel.add(headerPanel, BorderLayout.NORTH);
        return panel;
    }

    /**
     * Sends user feedback to the LLM for code improvement.
     */
    private void sendFeedback() {
        String feedback = chatInputArea.getText().trim();
        if (feedback.isEmpty()) {
            Messages.showWarningDialog(
                    project,
                    "Please enter feedback before sending.",
                    "Empty Feedback"
            );
            return;
        }

        // Add feedback to chat history
        chatHistory.add(new ChatMessage(ChatMessageType.USER, feedback));
        updateChatHistoryPanel();

        // Clear input area
        chatInputArea.setText("");

        // Call LLM to regenerate implementation
        regenerateImplementation(feedback);
    }

    /**
     * Applies the implemented changes to the editor.
     */
    private void applyChanges() {
        WriteCommandAction.runWriteCommandAction(project, () -> {
            editor.getDocument().replaceString(selectionStart, selectionEnd, implementedCode);
        });

        changesApplied = true;
    }

    /**
     * Closes the diff window.
     */
    private void closeWindow() {
        if (diffWindow != null) {
            diffWindow.close();

            // Ensure focus returns to editor
            if (editor.getComponent().isShowing()) {
                editor.getContentComponent().requestFocusInWindow();
            }

            // If changes were not applied, show a confirmation dialog
            if (!changesApplied) {
                int result = Messages.showYesNoDialog(
                        project,
                        "Do you want to apply the implementation to your code?",
                        "Apply Changes",
                        "Apply",
                        "Discard",
                        AllIcons.Actions.Diff);

                if (result == Messages.YES) {
                    applyChanges();
                }
            }
        }
    }

    /**
     * Shows a dialog with a list of all TODOs found in the code.
     */
    private void showTodoList() {
        List<Pair<String, String>> todos = extractTodos(originalCode);

        // Create a dialog to show the TODOs
        DialogWrapper dialog = new DialogWrapper(project, true) {
            {
                init();
                setTitle("TODOs in Selected Code");
                setSize(600, 400);
            }

            @Nullable
            @Override
            protected JComponent createCenterPanel() {
                JPanel panel = new JBPanel<>(new BorderLayout());
                panel.setBorder(JBUI.Borders.empty(10));

                if (todos.isEmpty()) {
                    panel.add(new JBLabel("No TODOs found in the code."), BorderLayout.CENTER);
                    return panel;
                }

                // Create a panel with a list of TODOs
                JPanel todosPanel = new JBPanel<>(new GridLayout(todos.size(), 1, 0, 10));

                for (Pair<String, String> todo : todos) {
                    JPanel todoPanel = new JBPanel<>(new BorderLayout());
                    todoPanel.setBorder(JBUI.Borders.customLine(JBColor.border(), 1));

                    JLabel typeLabel = new JBLabel(todo.first, AllIcons.General.TodoDefault, SwingConstants.LEFT);
                    typeLabel.setFont(typeLabel.getFont().deriveFont(Font.BOLD));
                    typeLabel.setBorder(JBUI.Borders.empty(5, 10, 5, 10));

                    JLabel descLabel = new JBLabel(todo.second);
                    descLabel.setBorder(JBUI.Borders.empty(5, 10, 10, 10));

                    todoPanel.add(typeLabel, BorderLayout.NORTH);
                    todoPanel.add(descLabel, BorderLayout.CENTER);

                    todosPanel.add(todoPanel);
                }

                JBScrollPane scrollPane = new JBScrollPane(todosPanel);
                panel.add(scrollPane, BorderLayout.CENTER);

                return panel;
            }
        };

        dialog.show();
    }

    /**
     * Regenerates the implementation based on user feedback.
     *
     * @param feedback The user's feedback for regeneration (can be null for simple regeneration)
     */
    private void regenerateImplementation(String feedback) {
        try {
            // Disable UI elements during regeneration
            sendButton.setEnabled(false);
            regenerateButton.setEnabled(false);

            // Add a system message about regeneration
            String systemMessage = feedback == null ?
                    "Regenerating implementation..." :
                    "Regenerating implementation based on feedback...";
            chatHistory.add(new ChatMessage(ChatMessageType.SYSTEM, systemMessage));
            updateChatHistoryPanel();

            // Create configuration and context for LLM API call
            ConfigurationManager config = new ConfigurationManager(project);
            CodeContext context = new CodeContext();
            context.setProject(project);
            context.setConfig(config);

            // Create a prompt for the LLM
            StringBuilder promptBuilder = new StringBuilder();
            promptBuilder.append("You are helping implement TODOs in Java code. ");

            if (feedback == null) {
                promptBuilder.append("Please provide a new implementation for the TODOs.\n\n");
            } else {
                promptBuilder.append("A previous implementation was provided, ");
                promptBuilder.append("but the user wants improvements based on specific feedback.\n\n");
            }

            promptBuilder.append("ORIGINAL CODE WITH TODOS:\n```java\n").append(originalCode).append("\n```\n\n");

            if (feedback != null) {
                promptBuilder.append("CURRENT IMPLEMENTATION:\n```java\n").append(implementedCode).append("\n```\n\n");
                promptBuilder.append("USER FEEDBACK:\n").append(feedback).append("\n\n");
            }

            if (codeContext != null && !codeContext.isEmpty()) {
                promptBuilder.append("ADDITIONAL CODE CONTEXT:\n").append(codeContext).append("\n\n");
            }

            promptBuilder.append("Please provide an improved implementation that ");
            if (feedback != null) {
                promptBuilder.append("addresses the user's feedback. ");
            } else {
                promptBuilder.append("is well organized and efficient. ");
            }
            promptBuilder.append("Return ONLY the complete implemented code without explanations or markdown formatting.\n");
            promptBuilder.append("Remember to preserve the overall structure of the code and only modify the TODO implementations ");
            promptBuilder.append("unless the feedback explicitly requests other changes.");

            // Set the prompt in the context
            context.setPrompt(promptBuilder.toString());

            // Run in a background thread to keep UI responsive
            SwingWorker<String, Void> worker = new SwingWorker<String, Void>() {
                @Override
                protected String doInBackground() throws Exception {
                    // Call LLM API
                    LlmApiCallStage apiCallStage = new LlmApiCallStage();
                    apiCallStage.process(context);

                    // Extract code from response
                    CodeExtractionStage extractionStage = new CodeExtractionStage();
                    extractionStage.process(context);

                    return context.getTestCode();
                }

                @Override
                protected void done() {
                    try {
                        String newImplementation = get();

                        if (newImplementation == null || newImplementation.isEmpty()) {
                            throw new PipelineExecutionException("LLM returned empty implementation");
                        }

                        // Add the new implementation to chat history
                        chatHistory.add(new ChatMessage(ChatMessageType.ASSISTANT, newImplementation));
                        updateChatHistoryPanel();

                        // Update the implemented code
                        implementedCode = newImplementation;

                        // Update the diff view
                        updateDiffView();
                    } catch (Exception e) {
                        LOG.error("Error regenerating implementation: " + e.getMessage(), e);

                        // Add error message to chat
                        chatHistory.add(new ChatMessage(ChatMessageType.SYSTEM,
                                "Error: Failed to regenerate implementation: " + e.getMessage()));
                        updateChatHistoryPanel();

                        Messages.showErrorDialog(
                                project,
                                "Failed to regenerate implementation: " + e.getMessage(),
                                "Regeneration Failed");
                    } finally {
                        // Re-enable UI elements
                        sendButton.setEnabled(true);
                        regenerateButton.setEnabled(true);
                    }
                }
            };

            worker.execute();

        } catch (Exception e) {
            LOG.error("Error regenerating implementation: " + e.getMessage(), e);

            // Add error message to chat
            chatHistory.add(new ChatMessage(ChatMessageType.SYSTEM,
                    "Error: Failed to regenerate implementation: " + e.getMessage()));
            updateChatHistoryPanel();

            // Re-enable UI elements
            sendButton.setEnabled(true);
            regenerateButton.setEnabled(true);

            Messages.showErrorDialog(
                    project,
                    "Failed to regenerate implementation: " + e.getMessage(),
                    "Regeneration Failed");
        }
    }

    /**
     * Updates the diff view with the current implementation.
     */
    private void updateDiffView() {
        // Update the diff content without closing the window
        if (diffFrame != null && diffWindow != null) {
            try {
                // Create new diff contents
                DiffContentFactory diffFactory = DiffContentFactory.getInstance();
                DocumentContent originalContent = diffFactory.create(originalCode);
                DocumentContent newContent = diffFactory.create(implementedCode);

                // Create new diff request
                SimpleDiffRequest diffRequest = new SimpleDiffRequest(
                        "TODO Implementation (Updated)",
                        originalContent,
                        newContent,
                        "Original Code with TODOs",
                        "Implemented Code");

                // Close the current window
                diffWindow.close();

                // Show new diff
                showDiff();

            } catch (Exception e) {
                LOG.error("Error updating diff view: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Extracts all TODOs from the code.
     */
    private List<Pair<String, String>> extractTodos(String code) {
        List<Pair<String, String>> todos = new ArrayList<>();
        Matcher matcher = TODO_PATTERN.matcher(code);

        while (matcher.find()) {
            String todoType = matcher.group(1) != null ? matcher.group(1) : matcher.group(3);
            String todoText = matcher.group(2) != null ? matcher.group(2) : matcher.group(4);

            if (todoType != null && todoText != null) {
                todos.add(Pair.create(todoType, todoText));
            }
        }

        return todos;
    }

    /**
     * Enum for chat message types.
     */
    private enum ChatMessageType {
        USER,
        ASSISTANT,
        SYSTEM
    }

    /**
     * Class to represent a chat message.
     */
    private static class ChatMessage {
        private final ChatMessageType type;
        private final String content;

        public ChatMessage(ChatMessageType type, String content) {
            this.type = type;
            this.content = content;
        }

        public ChatMessageType getType() {
            return type;
        }

        public String getContent() {
            return content;
        }
    }
}