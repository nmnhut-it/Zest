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
            @dev.langchain4j.agent.tool.P("Code to find - must be unique in file. Include 2-3 surrounding lines for uniqueness. Use exact match from file, including whitespaces.") String searchPattern,
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
                    findFlexibleMatches(currentContent, searchPattern);

            // Validate uniqueness
            if (matches.isEmpty()) {
                StringBuilder error = new StringBuilder("❌ Search pattern not found in file.\nPattern:\n```\n")
                        .append(searchPattern).append("\n```\n");

                if (detectWhitespaceMismatch(currentContent, searchPattern)) {
                    error.append("\n💡 Pattern found with different whitespace. Flexible matching will handle this automatically.\n");
                }

                error.append("\n📄 File preview (first 200 chars):\n```\n")
                     .append(currentContent.substring(0, Math.min(200, currentContent.length())))
                     .append(currentContent.length() > 200 ? "..." : "")
                     .append("\n```");

                return error.toString();
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
                            resultFuture.complete(true);
                            return kotlin.Unit.INSTANCE;
                        },
                        (kotlin.jvm.functions.Function0<kotlin.Unit>) () -> {
                            resultFuture.complete(false);
                            return kotlin.Unit.INSTANCE;
                        }
                    );
                } else {
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

            Boolean accepted = resultFuture.get(5, TimeUnit.MINUTES);

            if (accepted) {
                CompletableFuture<Void> applyFuture = applyReplacement(editor, startOffset, endOffset, modifiedContent);
                applyFuture.get(30, TimeUnit.SECONDS);

                String updatedContent = ApplicationManager.getApplication().runReadAction(
                    (Computable<String>) () -> editor.getDocument().getText()
                );
                int newEndOffset = startOffset + modifiedContent.length();

                return formatChangeContext(filePath, updatedContent, startOffset, newEndOffset,
                                          originalCode, modifiedContent);
            } else {
                return "❌ Code replacement rejected by user";
            }

        } catch (Exception e) {
            LOG.error("Failed to replace code in file", e);
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

    /**
     * Normalize whitespace for flexible text matching.
     * Trims and collapses multiple whitespace characters (spaces, tabs, newlines) into single spaces.
     *
     * @param text The text to normalize
     * @return Normalized text with collapsed whitespace
     */
    private static String normalizeWhitespace(String text) {
        if (text == null) {
            return "";
        }
        // Trim and replace all whitespace sequences with single space
        return text.trim().replaceAll("\\s+", " ");
    }

    static class MatchResult {
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

    List<MatchResult> findFlexibleMatches(String content, String pattern) {
        List<MatchResult> strictMatches = findLiteralMatches(content, pattern);
        if (!strictMatches.isEmpty()) {
            return strictMatches;
        }

        String normalizedContent = normalizeWhitespace(content);
        String normalizedPattern = normalizeWhitespace(pattern);

        List<MatchResult> flexibleResults = new ArrayList<>();
        int normalizedIndex = 0;

        while (normalizedIndex >= 0) {
            normalizedIndex = normalizedContent.indexOf(normalizedPattern, normalizedIndex);
            if (normalizedIndex >= 0) {
                int originalStart = mapNormalizedToOriginalOffset(content, normalizedIndex);
                int originalEnd = mapNormalizedToOriginalOffset(content, normalizedIndex + normalizedPattern.length());
                flexibleResults.add(new MatchResult(originalStart, originalEnd));
                normalizedIndex += normalizedPattern.length();
            }
        }

        return flexibleResults;
    }

    private int mapNormalizedToOriginalOffset(String original, int normalizedOffset) {
        String normalized = normalizeWhitespace(original);
        if (normalizedOffset >= normalized.length()) {
            return original.length();
        }

        int origIdx = 0;
        int normIdx = 0;

        while (normIdx < normalizedOffset && origIdx < original.length()) {
            if (Character.isWhitespace(original.charAt(origIdx))) {
                origIdx++;
                continue;
            }
            normIdx++;
            origIdx++;
        }

        return origIdx;
    }

    boolean detectWhitespaceMismatch(String content, String pattern) {
        boolean hasStrictMatch = !findLiteralMatches(content, pattern).isEmpty();
        if (hasStrictMatch) {
            return false;
        }

        String normalizedContent = normalizeWhitespace(content);
        String normalizedPattern = normalizeWhitespace(pattern);
        return normalizedContent.contains(normalizedPattern);
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

    private CompletableFuture<Void> applyReplacement(Editor editor, int startOffset, int endOffset, String newContent) {
        CompletableFuture<Void> future = new CompletableFuture<>();

        ApplicationManager.getApplication().invokeLater(() -> {
            WriteCommandAction.runWriteCommandAction(project, () -> {
                try {
                    editor.getDocument().replaceString(startOffset, endOffset, newContent);

                    PsiDocumentManager psiDocManager = PsiDocumentManager.getInstance(project);
                    psiDocManager.commitDocument(editor.getDocument());

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

                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        });

        return future;
    }

    /**
     * Format the change context showing 5 lines before and after the modification
     */
    private String formatChangeContext(String filePath, String updatedContent, int startOffset, int endOffset,
                                      String oldCode, String newCode) {
        String[] lines = updatedContent.split("\n", -1);

        // Find which line the change starts and ends on
        int currentOffset = 0;
        int startLine = 0;
        int endLine = 0;

        for (int i = 0; i < lines.length; i++) {
            int lineLength = lines[i].length() + 1; // +1 for newline

            if (currentOffset <= startOffset && startOffset < currentOffset + lineLength) {
                startLine = i;
            }
            if (currentOffset <= endOffset && endOffset <= currentOffset + lineLength) {
                endLine = i;
                break;
            }

            currentOffset += lineLength;
        }

        // Get 5 lines before and after
        int contextStart = Math.max(0, startLine - 5);
        int contextEnd = Math.min(lines.length - 1, endLine + 5);

        StringBuilder result = new StringBuilder();
        result.append("✅ Code replacement applied in ").append(filePath);
        result.append(" (lines ").append(startLine + 1).append("-").append(endLine + 1).append(")\n\n");

        // Show context before
        for (int i = contextStart; i < startLine; i++) {
            result.append(String.format("%5d: %s\n", i + 1, lines[i]));
        }

        // Show the changed lines with old code commented
        String[] oldLines = oldCode.split("\n", -1);
        for (String oldLine : oldLines) {
            result.append(String.format("%5d: - %s\n", startLine + 1, oldLine));
        }

        // Show new code
        for (int i = startLine; i <= endLine; i++) {
            result.append(String.format("%5d: + %s\n", i + 1, lines[i]));
        }

        // Show context after
        for (int i = endLine + 1; i <= contextEnd; i++) {
            result.append(String.format("%5d: %s\n", i + 1, lines[i]));
        }

        return result.toString();
    }
}
