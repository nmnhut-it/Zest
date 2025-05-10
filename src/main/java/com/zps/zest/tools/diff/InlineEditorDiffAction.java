package com.zps.zest.tools.diff;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.tools.simple.SimpleDiffViewer;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Action to show diff directly in the editor using inline diff highlighting
 */
public class InlineEditorDiffAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getRequiredData(CommonDataKeys.PROJECT);
        Editor editor = e.getRequiredData(CommonDataKeys.EDITOR);
        Document document = editor.getDocument();
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);

        if (file == null) {
            return;
        }

        // For demo purposes, we'll just modify the code to have variable names improved
        // In a real implementation, this would be AI-generated
        String originalCode = document.getText();
        String modifiedCode = simulateAIImprovement(originalCode);

        // Show the inline diff directly in the editor
        showInlineEditorDiff(project, editor, originalCode, modifiedCode);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        // Enable the action if we have a project and editor
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        e.getPresentation().setDescription("Show inline diff highlighting in the editor");
        e.getPresentation().setText("Show text diff");
        e.getPresentation().setEnabled(project != null && editor != null);
    }

    private String simulateAIImprovement(String code) {
        // This is just a simulation of what an AI might return
        // Would be replaced with actual AI service call
        return code.replace("int x", "int customerCount")
                .replace("String s", "String customerName")
                .replace("double d", "double totalAmount");
    }

    /**
     * Shows the diff directly in the editor using inline highlighting
     */
    private void showInlineEditorDiff(Project project, Editor originalEditor, String originalCode, String modifiedCode) {
        // Create a diff highlighter
        InlineDiffHighlighter diffHighlighter = new InlineDiffHighlighter(project, originalEditor);

        // Apply the diff highlighting
        diffHighlighter.highlight(originalCode, modifiedCode);

        // Show accept/reject UI
        showAcceptRejectUI(project, originalEditor, modifiedCode);
    }

    /**
     * Shows a floating UI with accept/reject buttons near the editor
     */
    private void showAcceptRejectUI(Project project, Editor editor, String newContent) {
        JBPanel<JBPanel<?>> panel = new JBPanel<>(new BorderLayout());
        panel.setBorder(JBUI.Borders.empty(10));

        JBLabel label = new JBLabel("AI suggested improvements to your variable names");
        label.setBorder(JBUI.Borders.emptyBottom(10));
        panel.add(label, BorderLayout.NORTH);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton rejectButton = new JButton("Reject");
        JButton acceptButton = new JButton("Accept All");
        acceptButton.setBackground(new JBColor(new Color(0, 120, 0), new Color(0, 120, 0)));
        acceptButton.setForeground(Color.WHITE);

        buttonPanel.add(rejectButton);
        buttonPanel.add(acceptButton);
        panel.add(buttonPanel, BorderLayout.SOUTH);

        JBPopup popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, acceptButton)
                .setRequestFocus(true)
                .setResizable(false)
                .setMovable(true)
                .setMinSize(new Dimension(300, 100))
                .createPopup();

        // Position the popup in the top-right of the editor
        popup.showInBestPositionFor(editor);

        // Set up button actions
        acceptButton.addActionListener(e -> {
            Document document = editor.getDocument();
            WriteCommandAction.runWriteCommandAction(project, () -> {
                document.setText(newContent);
            });
            popup.cancel();
            editor.getMarkupModel().removeAllHighlighters();

        });

        rejectButton.addActionListener(e -> {
            // Just close the popup and remove highlights
            popup.cancel();
            editor.getMarkupModel().removeAllHighlighters();
        });
    }
}

/**
 * Class to handle the inline diff highlighting in the editor
 */
class InlineDiffHighlighter {
    private final Project project;
    private final Editor editor;
    private final MarkupModel markupModel;

    /**
     * Constructor
     */
    public InlineDiffHighlighter(Project project, Editor editor) {
        this.project = project;
        this.editor = editor;
        this.markupModel = editor.getMarkupModel();
    }

    /**
     * Highlights differences between original and modified code
     */
    public void highlight(String originalCode, String modifiedCode) {
        // For simplicity, we'll use a line-by-line approach
        String[] originalLines = originalCode.split("\n");
        String[] modifiedLines = modifiedCode.split("\n");

        // Find differences
        List<Pair<Integer, String>> changedLines = findChangedLines(originalLines, modifiedLines);

        // Apply highlights
        applyHighlights(changedLines);
    }

    /**
     * Find which lines have changed between the original and modified code
     */
    private List<Pair<Integer, String>> findChangedLines(String[] originalLines, String[] modifiedLines) {
        List<Pair<Integer, String>> changedLines = new ArrayList<>();

        int minLength = Math.min(originalLines.length, modifiedLines.length);

        for (int i = 0; i < minLength; i++) {
            if (!originalLines[i].equals(modifiedLines[i])) {
                changedLines.add(Pair.create(i, modifiedLines[i]));
            }
        }

        return changedLines;
    }

    /**
     * Apply highlights to the editor
     */
    private void applyHighlights(List<Pair<Integer, String>> changedLines) {
        Document document = editor.getDocument();

        for (Pair<Integer, String> change : changedLines) {
            int lineNumber = change.first;
            String originalLine = document.getText(TextRange.from(document.getLineStartOffset(lineNumber), document.getLineEndOffset(lineNumber)));
            String newLine = change.second;

            // Show inline diff highlighting
            highlightDifferencesInLine(lineNumber, originalLine, newLine);
        }
    }

    /**
     * Highlight differences within a single line
     */
    private void highlightDifferencesInLine(int lineNumber, String originalLine, String newLine) {
        // For demonstration, we'll just highlight the entire changed line
        int startOffset = editor.getDocument().getLineStartOffset(lineNumber);
        int endOffset = editor.getDocument().getLineEndOffset(lineNumber);

        // Create a text attribute with a background color
        TextAttributes attributes = new TextAttributes();
        attributes.setBackgroundColor(new JBColor(new Color(255, 220, 220), new Color(80, 40, 40)));
        attributes.setEffectColor(JBColor.RED);

        // Add the highlighter
        markupModel.addRangeHighlighter(
                startOffset,
                endOffset,
                HighlighterLayer.SELECTION - 1,
                attributes,
                HighlighterTargetArea.EXACT_RANGE
        );

        // Create a tool tip with the new line
        TextAttributes tooltipAttributes = new TextAttributes();
        tooltipAttributes.setBackgroundColor(new JBColor(new Color(220, 255, 220), new Color(40, 80, 40)));
        tooltipAttributes.setEffectColor(JBColor.GREEN);
        tooltipAttributes.setFontType(Font.BOLD);

        // Add an inlay hint to show the proposed code
        // Note: In a real implementation, you would use InlayHintsProvider API,
        // but for simplicity, we'll use a range highlighter with a tool tip
        markupModel.addRangeHighlighter(
                startOffset,
                endOffset,
                HighlighterLayer.SELECTION - 2,
                tooltipAttributes,
                HighlighterTargetArea.EXACT_RANGE
        );
    }
}

/**
 * Alternative implementation using IntelliJ's built-in diff viewer
 * This shows a more traditional side-by-side diff view but still within the editor
 */
class SideBySideDiffImplementation {
    /**
     * Shows a side-by-side diff in the editor
     */
    public static void showSideBySideDiff(Project project, VirtualFile file, String originalContent, String modifiedContent) {
        // Create diff contents
        DocumentContent originalDoc = DiffContentFactory.getInstance().create(originalContent);
        DocumentContent modifiedDoc = DiffContentFactory.getInstance().create(modifiedContent);

        // Create diff request
        SimpleDiffRequest request = new SimpleDiffRequest(
                "Variable Name Improvements",
                originalDoc,
                modifiedDoc,
                "Original Code",
                "Improved Code"
        );


        // Show the diff in the current editor window
        DiffManager.getInstance().showDiff(project, request);
    }
}
