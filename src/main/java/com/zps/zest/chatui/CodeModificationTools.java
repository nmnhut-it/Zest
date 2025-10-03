package com.zps.zest.chatui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.zps.zest.completion.diff.MethodRewriteDiffDialogV2;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tools for modifying code files with diff preview and user confirmation.
 * Provides safe code replacement and file creation with validation.
 */
public class CodeModificationTools {
    private static final Logger LOG = Logger.getInstance(CodeModificationTools.class);
    private static final long MAX_FILE_SIZE = 1024 * 1024; // 1MB

    private final Project project;
    private final JCEFChatDialog chatDialog;

    public CodeModificationTools(@NotNull Project project, @Nullable JCEFChatDialog chatDialog) {
        this.project = project;
        this.chatDialog = chatDialog;
    }

    @Tool("Replace code in a file with diff preview. Shows before/after comparison, user accepts with TAB or rejects with ESC. " +
          "Search pattern must be unique (appears exactly once). Include surrounding context to ensure uniqueness.")
    public String replaceCodeInFile(
            @dev.langchain4j.agent.tool.P("File path relative to project root (e.g., 'src/main/java/UserService.java'). Absolute paths are auto-converted to relative.") String filePath,
            @dev.langchain4j.agent.tool.P("Code to find - must be unique in file. Include 2-3 surrounding lines for uniqueness. Use exact match from file.") String searchPattern,
            @dev.langchain4j.agent.tool.P("New code to replace with") String replacement,
            @dev.langchain4j.agent.tool.P("Use regex matching (default: false). Only use if pattern matching needed. Escape special chars: . → \\\\. ( → \\\\(") Boolean useRegex
    ) {
        try {
            LOG.info("replaceCodeInFile: " + filePath + ", useRegex=" + useRegex);

            // Normalize path to relative (handles absolute paths)
            filePath = normalizeToRelativePath(project, filePath);

            // Default useRegex to false if null
            boolean isRegex = useRegex != null && useRegex;

            // Find the file
            VirtualFile virtualFile = findFile(filePath);
            if (virtualFile == null) {
                return "❌ File not found: " + filePath + "\nCheck path is relative to project root.";
            }

            // Get or open editor for the file FIRST
            Editor editor = getOrOpenEditor(virtualFile);
            if (editor == null) {
                return "❌ Could not open editor for file: " + filePath;
            }

            // Get current content from Document (not VirtualFile) to ensure consistency
            String currentContent = ApplicationManager.getApplication().runReadAction(
                (Computable<String>) () -> editor.getDocument().getText()
            );

            // Find matches
            List<MatchResult> matches = isRegex ?
                    findRegexMatches(currentContent, searchPattern) :
                    findLiteralMatches(currentContent, searchPattern);

            // Validate uniqueness
            if (matches.isEmpty()) {
                return "❌ Search pattern not found in file.\nPattern:\n```\n" + searchPattern +
                       "\n```\nCheck the pattern matches exactly.";
            }
            if (matches.size() > 1) {
                return "❌ Search pattern matches " + matches.size() + " times (must be unique).\n" +
                       "Add more context to make pattern unique.";
            }

            // Calculate offsets for diff dialog
            MatchResult matchResult = matches.get(0);
            int startOffset = matchResult.startOffset;
            int endOffset = matchResult.endOffset;

            // Create modified content
            String originalCode = currentContent.substring(startOffset, endOffset);
            String modifiedContent = replacement;

            // Show diff dialog and wait for user decision
            CompletableFuture<Boolean> resultFuture = new CompletableFuture<>();

            ApplicationManager.getApplication().invokeLater(() -> {
                // Use modal dialog with chat as parent if available
                if (chatDialog != null) {
                    MethodRewriteDiffDialogV2.Companion.showWithParent(
                        chatDialog.getContentPane(),
                        project,
                        editor,
                        startOffset,
                        endOffset,
                        originalCode,
                        modifiedContent,
                        virtualFile.getFileType(),
                        virtualFile.getName(),
                        (kotlin.jvm.functions.Function0<kotlin.Unit>) () -> {
                            // onAccept - Apply the change
                            applyReplacement(editor, startOffset, endOffset, modifiedContent);
                            resultFuture.complete(true);
                            return kotlin.Unit.INSTANCE;
                        },
                        (kotlin.jvm.functions.Function0<kotlin.Unit>) () -> {
                            // onReject
                            resultFuture.complete(false);
                            return kotlin.Unit.INSTANCE;
                        }
                    );
                } else {
                    // Fallback to non-modal if no parent available
                    MethodRewriteDiffDialogV2.Companion.show(
                        project,
                        editor,
                        startOffset,
                        endOffset,
                        originalCode,
                        modifiedContent,
                        virtualFile.getFileType(),
                        virtualFile.getName(),
                        (kotlin.jvm.functions.Function0<kotlin.Unit>) () -> {
                            applyReplacement(editor, startOffset, endOffset, modifiedContent);
                            resultFuture.complete(true);
                            return kotlin.Unit.INSTANCE;
                        },
                        (kotlin.jvm.functions.Function0<kotlin.Unit>) () -> {
                            resultFuture.complete(false);
                            return kotlin.Unit.INSTANCE;
                        }
                    );
                }
            });

            // Wait for user decision (timeout after 5 minutes)
            Boolean accepted = resultFuture.get(5, TimeUnit.MINUTES);

            if (accepted) {
                return "✅ Code replacement applied successfully in " + filePath;
            } else {
                return "❌ Code replacement rejected by user";
            }

        } catch (Exception e) {
            LOG.error("Failed to replace code in file", e);
            return "❌ Error: " + e.getMessage();
        }
    }

    @Tool("Replace code by line numbers with boundary validation - most reliable method. Read file first with readFile() tool, " +
          "then specify line numbers and boundary text from those lines for validation. Lines are 1-indexed (first line = 1).")
    public String replaceCodeByLines(
            @P("File path relative to project root (e.g., 'src/main/java/UserService.java'). Absolute paths auto-converted.") String filePath,
            @P("Start line number (1-indexed, inclusive). First line to replace. See readFile() output for line numbers.") int startLine,
            @P("Text that must appear on start line (for validation). Copy from readFile() output to ensure correct location.") String startBoundary,
            @P("End line number (1-indexed, inclusive). Last line to replace.") int endLine,
            @P("Text that must appear on end line (for validation). Copy from readFile() output to ensure correct location.") String endBoundary,
            @P("New code to replace with") String replacement
    ) {
        try {
            LOG.info("replaceCodeByLines: " + filePath + ", lines " + startLine + "-" + endLine);

            // Normalize path to relative (handles absolute paths)
            filePath = normalizeToRelativePath(project, filePath);

            // Find the file
            VirtualFile virtualFile = findFile(filePath);
            if (virtualFile == null) {
                return "❌ File not found: " + filePath + "\nCheck path is relative to project root.";
            }

            // Get or open editor for the file FIRST
            Editor editor = getOrOpenEditor(virtualFile);
            if (editor == null) {
                return "❌ Could not open editor for file: " + filePath;
            }

            // Get Document, validate line numbers, and validate boundaries in read action
            int[] offsets = ApplicationManager.getApplication().runReadAction(
                (Computable<int[]>) () -> {
                    com.intellij.openapi.editor.Document document = editor.getDocument();
                    int totalLines = document.getLineCount();

                    // Validate line numbers (1-indexed user input)
                    if (startLine < 1) {
                        throw new IllegalArgumentException("Start line must be >= 1 (lines are 1-indexed)");
                    }
                    if (endLine > totalLines) {
                        throw new IllegalArgumentException("End line " + endLine + " exceeds file length (" + totalLines + " lines)");
                    }
                    if (startLine > endLine) {
                        throw new IllegalArgumentException("Start line (" + startLine + ") must be <= end line (" + endLine + ")");
                    }

                    // Convert 1-indexed to 0-indexed and get offsets using Document API
                    int startLineOffset = document.getLineStartOffset(startLine - 1);
                    int startLineEndOffset = document.getLineEndOffset(startLine - 1);
                    int endLineOffset = document.getLineStartOffset(endLine - 1);
                    int endLineEndOffset = document.getLineEndOffset(endLine - 1);

                    // Extract actual text on start and end lines
                    String actualStartLine = document.getText(new com.intellij.openapi.util.TextRange(startLineOffset, startLineEndOffset));
                    String actualEndLine = document.getText(new com.intellij.openapi.util.TextRange(endLineOffset, endLineEndOffset));

                    // Validate boundaries
                    if (!actualStartLine.contains(startBoundary)) {
                        throw new IllegalArgumentException(
                            "Boundary validation failed on line " + startLine + ":\n" +
                            "Expected to find: \"" + startBoundary + "\"\n" +
                            "Actual line text: \"" + actualStartLine + "\"\n" +
                            "File may have changed. Re-read file and try again."
                        );
                    }

                    if (!actualEndLine.contains(endBoundary)) {
                        throw new IllegalArgumentException(
                            "Boundary validation failed on line " + endLine + ":\n" +
                            "Expected to find: \"" + endBoundary + "\"\n" +
                            "Actual line text: \"" + actualEndLine + "\"\n" +
                            "File may have changed. Re-read file and try again."
                        );
                    }

                    // Return offsets for the full range (start of first line to end of last line)
                    return new int[]{startLineOffset, endLineEndOffset};
                }
            );

            int startOffset = offsets[0];
            int endOffset = offsets[1];

            // Get current content for preview
            String currentContent = ApplicationManager.getApplication().runReadAction(
                (Computable<String>) () -> editor.getDocument().getText()
            );

            // Get the original code being replaced
            String originalCode = currentContent.substring(startOffset, endOffset);

            // Show diff dialog and wait for user decision
            CompletableFuture<Boolean> resultFuture = new CompletableFuture<>();

            ApplicationManager.getApplication().invokeLater(() -> {
                // Use modal dialog with chat as parent if available
                if (chatDialog != null) {
                    MethodRewriteDiffDialogV2.Companion.showWithParent(
                        chatDialog.getContentPane(),
                        project,
                        editor,
                        startOffset,
                        endOffset,
                        originalCode,
                        replacement,
                        virtualFile.getFileType(),
                        virtualFile.getName(),
                        (kotlin.jvm.functions.Function0<kotlin.Unit>) () -> {
                            // onAccept - Apply the change
                            applyReplacement(editor, startOffset, endOffset, replacement);
                            resultFuture.complete(true);
                            return kotlin.Unit.INSTANCE;
                        },
                        (kotlin.jvm.functions.Function0<kotlin.Unit>) () -> {
                            // onReject
                            resultFuture.complete(false);
                            return kotlin.Unit.INSTANCE;
                        }
                    );
                } else {
                    // Fallback to non-modal if no parent available
                    MethodRewriteDiffDialogV2.Companion.show(
                        project,
                        editor,
                        startOffset,
                        endOffset,
                        originalCode,
                        replacement,
                        virtualFile.getFileType(),
                        virtualFile.getName(),
                        (kotlin.jvm.functions.Function0<kotlin.Unit>) () -> {
                            applyReplacement(editor, startOffset, endOffset, replacement);
                            resultFuture.complete(true);
                            return kotlin.Unit.INSTANCE;
                        },
                        (kotlin.jvm.functions.Function0<kotlin.Unit>) () -> {
                            resultFuture.complete(false);
                            return kotlin.Unit.INSTANCE;
                        }
                    );
                }
            });

            // Wait for user decision (timeout after 5 minutes)
            Boolean accepted = resultFuture.get(5, TimeUnit.MINUTES);

            if (accepted) {
                return "✅ Code replacement applied successfully in " + filePath + " (lines " + startLine + "-" + endLine + ")";
            } else {
                return "❌ Code replacement rejected by user";
            }

        } catch (Exception e) {
            LOG.error("Failed to replace code by lines", e);
            return "❌ Error: " + e.getMessage();
        }
    }

    @Tool("Create a new file with the specified content. Creates parent directories if needed. " +
          "Returns error if file already exists - use replaceCodeInFile for existing files.")
    public String createNewFile(
            @dev.langchain4j.agent.tool.P("File path relative to project root (e.g., 'src/main/java/com/example/NewClass.java'). Absolute paths are auto-converted to relative.") String filePath,
            @dev.langchain4j.agent.tool.P("Complete file content. Include package declarations, imports, and proper formatting.") String content
    ) {
        try {
            LOG.info("createNewFile: " + filePath);

            // Normalize path to relative (handles absolute paths)
            filePath = normalizeToRelativePath(project, filePath);

            String basePath = project.getBasePath();
            if (basePath == null) {
                return "❌ Could not determine project base path";
            }

            File fullPath = new File(basePath, filePath);

            // Check if file already exists
            if (fullPath.exists()) {
                return "❌ File already exists: " + filePath + "\nUse replaceCodeInFile to modify existing files.";
            }

            // Create parent directories if needed
            File parentDir = fullPath.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            // Write file on EDT with WriteCommandAction
            CompletableFuture<String> resultFuture = new CompletableFuture<>();

            String finalFilePath = filePath;
            ApplicationManager.getApplication().invokeLater(() -> {
                WriteCommandAction.runWriteCommandAction(project, () -> {
                    try {
                        java.nio.file.Files.write(fullPath.toPath(), content.getBytes(StandardCharsets.UTF_8));

                        // Refresh VFS to make file visible
                        VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(fullPath);
                        if (virtualFile != null) {
                            virtualFile.refresh(false, false);
                            int lineCount = content.split("\n").length;
                            resultFuture.complete("✅ File created successfully: " + virtualFile.getPath() + "\n📄 " + lineCount + " lines written");
                        } else {
                            resultFuture.complete("⚠️ File created but not found in VFS: " + finalFilePath);
                        }
                    } catch (IOException e) {
                        resultFuture.completeExceptionally(e);
                    }
                });
            });

            // Wait for file creation to complete
            return resultFuture.get(30, TimeUnit.SECONDS);

        } catch (Exception e) {
            LOG.error("Failed to create new file", e);
            return "❌ Error creating file: " + e.getMessage();
        }
    }

    /**
     * Normalize file path to be relative to project root.
     * Handles absolute paths, relative paths, and mixed path separators.
     *
     * @param project The IntelliJ project
     * @param filePath The file path (can be absolute or relative)
     * @return Normalized relative path with forward slashes
     */
    private static String normalizeToRelativePath(@NotNull Project project, @NotNull String filePath) {
        String basePath = project.getBasePath();
        if (basePath == null) {
            return filePath.replace('\\', '/');
        }

        // Normalize separators
        String normalizedBasePath = basePath.replace('\\', '/');
        String normalizedFilePath = filePath.replace('\\', '/');

        // Strip project base path if present
        if (normalizedFilePath.startsWith(normalizedBasePath)) {
            String relative = normalizedFilePath.substring(normalizedBasePath.length());
            return relative.startsWith("/") ? relative.substring(1) : relative;
        }

        // Already relative
        return normalizedFilePath;
    }

    @Nullable
    private VirtualFile findFile(String relativePath) {
        String basePath = project.getBasePath();
        if (basePath == null) {
            return null;
        }

        String normalizedPath = relativePath.replace('\\', '/');
        String fullPath = basePath + "/" + normalizedPath;
        return LocalFileSystem.getInstance().findFileByPath(fullPath);
    }

    private static class MatchResult {
        final int startOffset;
        final int endOffset;

        MatchResult(int startOffset, int endOffset) {
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }
    }

    private List<MatchResult> findLiteralMatches(String content, String pattern) {
        List<MatchResult> results = new ArrayList<>();
        int index = 0;
        while (index >= 0) {
            index = content.indexOf(pattern, index);
            if (index >= 0) {
                results.add(new MatchResult(index, index + pattern.length()));
                index += pattern.length();
            }
        }
        return results;
    }

    private List<MatchResult> findRegexMatches(String content, String pattern) {
        List<MatchResult> results = new ArrayList<>();
        try {
            Pattern regex = Pattern.compile(pattern);
            Matcher matcher = regex.matcher(content);
            while (matcher.find()) {
                results.add(new MatchResult(matcher.start(), matcher.end()));
            }
        } catch (Exception e) {
            LOG.warn("Invalid regex pattern: " + pattern, e);
        }
        return results;
    }

    @Nullable
    private Editor getOrOpenEditor(VirtualFile virtualFile) {
        FileEditorManager fileEditorManager = FileEditorManager.getInstance(project);

        // Try to get selected text editor
        Editor selectedEditor = fileEditorManager.getSelectedTextEditor();
        if (selectedEditor != null) {
            return selectedEditor;
        }

        // Open the file on EDT and get its editor
        CompletableFuture<Editor> editorFuture = new CompletableFuture<>();

        ApplicationManager.getApplication().invokeLater(() -> {
            fileEditorManager.openFile(virtualFile, true);
            Editor editor = fileEditorManager.getSelectedTextEditor();
            editorFuture.complete(editor);
        });

        try {
            return editorFuture.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warn("Failed to open editor for file: " + virtualFile.getPath(), e);
            return null;
        }
    }

    private void applyReplacement(Editor editor, int startOffset, int endOffset, String newContent) {
        // MUST be on EDT for write operations
        ApplicationManager.getApplication().invokeLater(() -> {
            WriteCommandAction.runWriteCommandAction(project, () -> {
                // Replace the code
                editor.getDocument().replaceString(startOffset, endOffset, newContent);

                // Commit document to sync PSI
                PsiDocumentManager psiDocManager = PsiDocumentManager.getInstance(project);
                psiDocManager.commitDocument(editor.getDocument());

                // Reformat the changed range
                com.intellij.psi.PsiFile psiFile = psiDocManager.getPsiFile(editor.getDocument());
                if (psiFile != null) {
                    try {
                        int newEndOffset = startOffset + newContent.length();
                        com.intellij.psi.codeStyle.CodeStyleManager.getInstance(project)
                                .reformatRange(psiFile, startOffset, newEndOffset);
                    } catch (Exception e) {
                        LOG.warn("Failed to reformat code after replacement", e);
                    }
                }
            });
        });
    }
}
