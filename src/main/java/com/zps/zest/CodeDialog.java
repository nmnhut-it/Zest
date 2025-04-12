package com.zps.zest;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.EditorSettings;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.highlighter.EditorHighlighter;
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/**
 * Dialog for displaying code with IntelliJ's native syntax highlighting
 */
public class CodeDialog extends DialogWrapper {
    private  String language;
    private  String code;
    private  Project project;
    private Editor editor;

    /**
     * Creates a new code dialog with proper syntax highlighting
     */
    public CodeDialog(@Nullable Project project, String language, String code) {
        super(project);
        this.project = project;
        this.language = language.isEmpty() ? "txt" : normalizeLanguage(language);
        this.code = code;

        setTitle("Code: " + this.language);
        setSize(800, 600);
        init();

    }
    @Override
    protected void dispose() {
        // Clean up editor resources
        if (editor != null) {
            EditorFactory.getInstance().releaseEditor(editor);
            editor = null;
        }
        super.dispose();
    }
    /**
     * Normalizes language name to match IntelliJ's file extensions
     */
    private String normalizeLanguage(String lang) {
        // Map common language names to file extensions
        switch (lang.toLowerCase()) {
            case "javascript": return "js";
            case "typescript": return "ts";
            case "python": return "py";
            case "java": return "java";
            case "json": return "json";
            case "html": return "html";
            case "css": return "css";
            case "xml": return "xml";
            case "markdown":
            case "md": return "md";
            case "yaml":
            case "yml": return "yml";
            case "bash":
            case "shell": return "sh";
            case "kotlin": return "kt";
            case "csharp":
            case "c#": return "cs";
            case "c++":
            case "cpp": return "cpp";
            case "c": return "c";
            case "go": return "go";
            case "rust": return "rs";
            case "php": return "php";
            case "ruby": return "rb";
            case "swift": return "swift";
            case "sql": return "sql";
            case "dart": return "dart";
            case "scala": return "scala";
            case "groovy": return "groovy";
            case "r": return "r";
            default: return lang;
        }
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel dialogPanel = new JBPanel<>(new BorderLayout());

        // Create document and editor
        EditorFactory editorFactory = EditorFactory.getInstance();
        Document document = editorFactory.createDocument(code);

        // Create editor with syntax highlighting
        editor = editorFactory.createEditor(document, project);

        // Configure editor settings
        EditorSettings settings = editor.getSettings();
        settings.setLineNumbersShown(true);
        settings.setFoldingOutlineShown(true);
        settings.setLineMarkerAreaShown(true);
        settings.setIndentGuidesShown(true);
        settings.setAdditionalLinesCount(3);
        settings.setAdditionalColumnsCount(3);
        settings.setRightMarginShown(true);

        // Apply syntax highlighting based on language
        if (editor instanceof EditorEx) {
            EditorEx editorEx = (EditorEx) editor;

            // Get file type based on language
            FileType fileType = FileTypeManager.getInstance().getFileTypeByExtension(language);
            if (fileType == PlainTextFileType.INSTANCE) {
                // Try by name for some common cases
                fileType = FileTypeManager.getInstance().getFileTypeByFileName("file." + language);
            }

            // Apply highlighter
            EditorHighlighter highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(
                    project, fileType);
            editorEx.setHighlighter(highlighter);

            // Use the editor's scheme
            editorEx.setColorsScheme(EditorColorsManager.getInstance().getGlobalScheme());
        }

        // Add action buttons at the top
        JPanel actionPanel = new JBPanel<>(new FlowLayout(FlowLayout.LEFT));

        // Copy button
        JButton copyButton = new JButton("Copy All", AllIcons.Actions.Copy);
        copyButton.addActionListener(e -> {
            java.awt.datatransfer.StringSelection selection =
                    new java.awt.datatransfer.StringSelection(code);
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
        });

        actionPanel.add(copyButton);
        actionPanel.add(new JLabel("  Language: " + language));

        // Add panels to dialog
        dialogPanel.add(actionPanel, BorderLayout.NORTH);
        dialogPanel.add(editor.getComponent(), BorderLayout.CENTER);

        return dialogPanel;
    }
}