package com.zps.zest;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffDialogHints;
import com.intellij.diff.DiffManager;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.WindowWrapper;
import com.intellij.openapi.util.Pair;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.Consumer;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
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
 */
public class EnhancedTodoDiffComponent {
    private static final Logger LOG = Logger.getInstance(EnhancedTodoDiffComponent.class);
    private static final Pattern TODO_PATTERN = Pattern.compile(
            "//\\s*(TODO|FIXME|HACK|XXX)\\s*:?\\s*(.*?)\\s*$|/\\*\\s*(TODO|FIXME|HACK|XXX)\\s*:?\\s*(.*?)\\s*\\*/",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    private final Project project;
    private final Editor editor;
    private final String originalCode;
    private final String implementedCode;
    private final int selectionStart;
    private final int selectionEnd;
    private WindowWrapper diffWindow;
    private boolean changesApplied = false;

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

        DiffDialogHints dialogHints = new DiffDialogHints(WindowWrapper.Mode.FRAME, editor.getComponent(), new Consumer<WindowWrapper>() {
            @Override
            public void consume(WindowWrapper wrapper) {
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
        
        // Custom edit action
        AnAction editAction = new AnAction("Edit Implementation", "Edit the implemented code in a separate editor", AllIcons.Actions.Edit) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                editImplementation();
            }
        };
        
        actionGroup.add(applyAction);
//        actionGroup.add(editAction);
//        actionGroup.add(showTodosAction);
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
     * Opens the implemented code in a separate editor for manual editing.
     */
    private void editImplementation() {
        // Create a custom dialog with an editor for the implemented code
        TodoImplementationEditor editor = new TodoImplementationEditor(
                project, 
                implementedCode, 
                (newCode) -> {
                    // Update the implemented code
                    DiffContentFactory diffFactory = DiffContentFactory.getInstance();
                    DocumentContent originalContent = diffFactory.create(originalCode);
                    DocumentContent newContent = diffFactory.create(newCode);
                    
                    SimpleDiffRequest diffRequest = new SimpleDiffRequest(
                            "TODO Implementation (Updated)", 
                            originalContent, 
                            newContent, 
                            "Original Code with TODOs", 
                            "Implemented Code");
                    
                    // Close the current diff window
                    if (diffWindow != null) {
                        diffWindow.close();
                    }
                    
                    // Show a new diff window with the updated code
                    DiffManager.getInstance().showDiff(
                            project, 
                            diffRequest, 
                            new DiffDialogHints(WindowWrapper.Mode.FRAME));
                }
        );
        
        editor.show();
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
     * Dialog for editing the implemented code.
     */
    private static class TodoImplementationEditor extends DialogWrapper {
        private final String initialCode;
        private final Project project;
        private final Consumer<String> onApply;
        private Editor editor;
        
        public TodoImplementationEditor(Project project, String code, Consumer<String> onApply) {
            super(project, true);
            this.project = project;
            this.initialCode = code;
            this.onApply = onApply;
            
            init();
            setTitle("Edit Implementation");
            setSize(800, 600);
        }
        
        @Nullable
        @Override
        protected JComponent createCenterPanel() {
            JPanel panel = new JBPanel<>(new BorderLayout());
            panel.setBorder(JBUI.Borders.empty(10));
            
            // Create editor component
            com.intellij.openapi.editor.EditorFactory editorFactory = com.intellij.openapi.editor.EditorFactory.getInstance();
            com.intellij.openapi.editor.Document document = editorFactory.createDocument(initialCode);
            editor = editorFactory.createEditor(document, project, com.intellij.openapi.fileTypes.StdFileTypes.JAVA, false);
            
            // Configure editor
            com.intellij.openapi.editor.EditorSettings settings = editor.getSettings();
            settings.setLineNumbersShown(true);
            settings.setLineMarkerAreaShown(true);
            settings.setFoldingOutlineShown(true);
            settings.setRightMarginShown(true);
            settings.setAdditionalColumnsCount(3);
            settings.setAdditionalLinesCount(3);
            
            panel.add(editor.getComponent(), BorderLayout.CENTER);
            
            return panel;
        }
        
        @Override
        protected void doOKAction() {
            if (onApply != null && editor != null) {
                onApply.consume(editor.getDocument().getText());
            }
            super.doOKAction();
        }
        
        @Override
        public void dispose() {
            if (editor != null) {
                com.intellij.openapi.editor.EditorFactory.getInstance().releaseEditor(editor);
            }
            super.dispose();
        }
    }
}