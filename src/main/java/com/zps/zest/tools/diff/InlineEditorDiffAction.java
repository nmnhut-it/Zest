package com.zps.zest.tools.diff;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.contents.DocumentContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

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
