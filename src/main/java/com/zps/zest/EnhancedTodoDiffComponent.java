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
import com.intellij.util.Consumer;
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
 * - Preview of changes with accept/reject options for each change
 * - Custom tool window with additional actions
 * - Chat history for feedback and regeneration
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

            // Add custom action toolbar to the diff window
            if (wrapper.getWindow() instanceof JFrame) {
                JFrame frame = (JFrame) wrapper.getWindow();
                Container contentPane = frame.getContentPane();

                if (contentPane instanceof JComponent) {
                    JComponent component = (JComponent) contentPane;

                    // Create a panel for the action buttons
                    JPanel actionPanel = createActionPanel();

                    // Add the panel to the bottom of the diff window
                    component.add(actionPanel, BorderLayout.SOUTH);

                    // Force layout update
                    frame.validate();
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

        // Provide feedback and regenerate
        AnAction feedbackAction = new AnAction("Provide Feedback", "Give feedback and regenerate the implementation", AllIcons.Actions.IntentionBulb) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                showFeedbackDialog();
            }
        };

        // View chat history
        AnAction viewHistoryAction = new AnAction("View History", "View feedback history and previous implementations", AllIcons.Vcs.History) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                showChatHistoryDialog();
            }
        };

        actionGroup.add(applyAction);
        actionGroup.add(feedbackAction);
        actionGroup.add(viewHistoryAction);
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
     * Shows a dialog for providing feedback and requesting regeneration.
     */
    private void showFeedbackDialog() {
        // Create a dialog for feedback and regeneration
        DialogWrapper dialog = new DialogWrapper(project, true) {
            private JBTextArea feedbackTextArea;

            {
                init();
                setTitle("Provide Feedback for Regeneration");
                setSize(700, 500);
            }

            @Nullable
            @Override
            protected JComponent createCenterPanel() {
                JPanel panel = new JBPanel<>(new BorderLayout());
                panel.setBorder(JBUI.Borders.empty(10));

                // Create a panel with instructions and feedback input
                JPanel inputPanel = new JBPanel<>(new BorderLayout());

                JLabel instructionsLabel = new JBLabel("<html><body>" +
                        "Provide specific feedback about what you'd like to improve in the implementation.<br>" +
                        "Be detailed about any issues, improvements, or changes you want to see." +
                        "</body></html>");
                instructionsLabel.setBorder(JBUI.Borders.empty(0, 0, 10, 0));

                feedbackTextArea = new JBTextArea();
                feedbackTextArea.setRows(15);
                feedbackTextArea.setLineWrap(true);
                feedbackTextArea.setWrapStyleWord(true);
                JBScrollPane scrollPane = new JBScrollPane(feedbackTextArea);
                scrollPane.setBorder(new TitledBorder("Your Feedback"));

                inputPanel.add(instructionsLabel, BorderLayout.NORTH);
                inputPanel.add(scrollPane, BorderLayout.CENTER);

                panel.add(inputPanel, BorderLayout.CENTER);

                return panel;
            }

            @Override
            protected void doOKAction() {
                String feedback = feedbackTextArea.getText().trim();
                if (feedback.isEmpty()) {
                    Messages.showWarningDialog(
                            project,
                            "Please provide some feedback to guide the regeneration.",
                            "Empty Feedback");
                    return;
                }

                // Add feedback to chat history
                chatHistory.add(new ChatMessage(ChatMessageType.USER, feedback));

                // Call LLM to regenerate the implementation
                regenerateImplementation(feedback);

                super.doOKAction();
            }
        };

        dialog.show();
    }

    /**
     * Shows a dialog with the chat history.
     */
    private void showChatHistoryDialog() {
        // Create a dialog to show the chat history
        DialogWrapper dialog = new DialogWrapper(project, true) {
            {
                init();
                setTitle("Implementation History");
                setSize(800, 600);
            }

            @Nullable
            @Override
            protected JComponent createCenterPanel() {
                JPanel panel = new JBPanel<>(new BorderLayout());
                panel.setBorder(JBUI.Borders.empty(10));

                // Create a panel to display chat history
                JPanel historyPanel = new JBPanel<>();
                historyPanel.setLayout(new BoxLayout(historyPanel, BoxLayout.Y_AXIS));

                for (int i = 0; i < chatHistory.size(); i++) {
                    ChatMessage message = chatHistory.get(i);

                    JPanel messagePanel = new JBPanel<>(new BorderLayout());
                    messagePanel.setBorder(JBUI.Borders.empty(5));

                    Icon icon = null;
                    Color bgColor = null;

                    switch (message.getType()) {
                        case USER:
                            icon = AllIcons.Nodes.EntryPoints;
                            bgColor = new Color(240, 240, 255);
                            break;
                        case ASSISTANT:
                            icon = AllIcons.Nodes.Plugin;
                            bgColor = new Color(240, 255, 240);
                            break;
                        case SYSTEM:
                            icon = AllIcons.General.Information;
                            bgColor = new Color(245, 245, 245);
                            break;
                    }

                    messagePanel.setBackground(bgColor);

                    JLabel typeLabel = new JBLabel(message.getType().name(), icon, SwingConstants.LEFT);
                    typeLabel.setFont(typeLabel.getFont().deriveFont(Font.BOLD));

                    JPanel contentPanel = new JBPanel<>(new BorderLayout());
                    contentPanel.setBackground(bgColor);
                    contentPanel.setBorder(JBUI.Borders.empty(5));

                    // For code content, use a text area with scrolling
                    if (message.getType() == ChatMessageType.ASSISTANT ||
                            (message.getType() == ChatMessageType.USER && message.getContent().contains("\n"))) {
                        JBTextArea codeArea = new JBTextArea(message.getContent());
                        codeArea.setEditable(false);
                        codeArea.setBackground(bgColor);
                        codeArea.setRows(Math.min(15, message.getContent().split("\n").length + 1));

                        JBScrollPane scrollPane = new JBScrollPane(codeArea);
                        scrollPane.setBorder(null);
                        contentPanel.add(scrollPane, BorderLayout.CENTER);
                    } else {
                        JLabel contentLabel = new JBLabel("<html><body>" +
                                message.getContent().replace("\n", "<br>") +
                                "</body></html>");
                        contentPanel.add(contentLabel, BorderLayout.CENTER);
                    }

                    messagePanel.add(typeLabel, BorderLayout.NORTH);
                    messagePanel.add(contentPanel, BorderLayout.CENTER);

                    // For assistant messages (implementations), add a button to restore this version
                    if (message.getType() == ChatMessageType.ASSISTANT && i > 2) {
                        JButton restoreButton = new JButton("Restore This Version", AllIcons.Actions.Rollback);
                        restoreButton.addActionListener(e -> {
                            restoreImplementation(message.getContent());
                            this.close(DialogWrapper.OK_EXIT_CODE);
                        });

                        JPanel buttonPanel = new JBPanel<>(new FlowLayout(FlowLayout.RIGHT));
                        buttonPanel.setBackground(bgColor);
                        buttonPanel.add(restoreButton);

                        messagePanel.add(buttonPanel, BorderLayout.SOUTH);
                    }

                    historyPanel.add(messagePanel);

                    // Add a separator between messages
                    if (i < chatHistory.size() - 1) {
                        JSeparator separator = new JSeparator();
                        separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
                        historyPanel.add(separator);
                    }
                }

                JBScrollPane scrollPane = new JBScrollPane(historyPanel);
                panel.add(scrollPane, BorderLayout.CENTER);

                return panel;
            }
        };

        dialog.show();
    }

    /**
     * Regenerates the implementation based on user feedback.
     *
     * @param feedback The user's feedback for regeneration
     */
    private void regenerateImplementation(String feedback) {
        try {
            // Create configuration and context for LLM API call
            ConfigurationManager config = new ConfigurationManager(project);
            TestGenerationContext context = new TestGenerationContext();
            context.setProject(project);
            context.setConfig(config);

            // Create a prompt for the LLM that includes:
            // 1. The original code with TODOs
            // 2. The current implementation
            // 3. The user's feedback
            // 4. Any additional code context if available
            StringBuilder promptBuilder = new StringBuilder();
            promptBuilder.append("You are helping implement TODOs in Java code. A previous implementation was provided, ");
            promptBuilder.append("but the user wants improvements based on specific feedback.\n\n");

            promptBuilder.append("ORIGINAL CODE WITH TODOS:\n```java\n").append(originalCode).append("\n```\n\n");

            promptBuilder.append("CURRENT IMPLEMENTATION:\n```java\n").append(implementedCode).append("\n```\n\n");

            promptBuilder.append("USER FEEDBACK:\n").append(feedback).append("\n\n");

            if (codeContext != null && !codeContext.isEmpty()) {
                promptBuilder.append("ADDITIONAL CODE CONTEXT:\n").append(codeContext).append("\n\n");
            }

            promptBuilder.append("Please provide an improved implementation that addresses the user's feedback. ");
            promptBuilder.append("Return ONLY the complete implemented code without explanations or markdown formatting.\n");
            promptBuilder.append("Remember to preserve the overall structure of the code and only modify the TODO implementations ");
            promptBuilder.append("unless the feedback explicitly requests other changes.");

            // Set the prompt in the context
            context.setPrompt(promptBuilder.toString());

            // Call LLM API
            LlmApiCallStage apiCallStage = new LlmApiCallStage();
            apiCallStage.process(context);

            // Extract code from response
            CodeExtractionStage extractionStage = new CodeExtractionStage();
            extractionStage.process(context);

            String newImplementation = context.getTestCode();

            if (newImplementation == null || newImplementation.isEmpty()) {
                throw new PipelineExecutionException("LLM returned empty implementation");
            }

            // Add the new implementation to chat history
            chatHistory.add(new ChatMessage(ChatMessageType.ASSISTANT, newImplementation));

            // Update the implemented code
            implementedCode = newImplementation;

            // Update the diff view
            updateDiffView();

            // Show success message
            Messages.showInfoMessage(
                    project,
                    "Implementation has been regenerated based on your feedback.",
                    "Regeneration Complete");

        } catch (Exception e) {
            LOG.error("Error regenerating implementation: " + e.getMessage(), e);
            Messages.showErrorDialog(
                    project,
                    "Failed to regenerate implementation: " + e.getMessage(),
                    "Regeneration Failed");
        }
    }

    /**
     * Restores a previous implementation from the history.
     *
     * @param implementationCode The implementation code to restore
     */
    private void restoreImplementation(String implementationCode) {
        implementedCode = implementationCode;

        // Add a system message indicating restoration
        chatHistory.add(new ChatMessage(ChatMessageType.SYSTEM, "Restored previous implementation"));

        // Update the diff view
        updateDiffView();

        Messages.showInfoMessage(
                project,
                "Previous implementation has been restored.",
                "Implementation Restored");
    }

    /**
     * Updates the diff view with the current implementation.
     */
    private void updateDiffView() {
        // Close the current diff window
        if (diffWindow != null) {
            diffWindow.close();
        }

        // Show a new diff with the updated implementation
        showDiff();
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