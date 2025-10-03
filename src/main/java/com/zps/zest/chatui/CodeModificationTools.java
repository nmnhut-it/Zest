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

    @Tool("""
        Replace code in a file with diff preview before applying changes.

        REPLACEMENT STRATEGY:
        1. Use unique search patterns - include surrounding context (2-3 lines)
        2. Prefer literal string search (useRegex=false) for clarity
        3. Use regex only when pattern matching is needed

        Parameters:
        - filePath: Relative to project root (e.g., "src/main/java/UserService.java")
        - searchPattern: Code to find (MUST be unique in file)
        - replacement: New code to replace with
        - useRegex: Use regex matching (default: false)

        UNIQUENESS REQUIREMENTS:
        - Pattern must appear exactly once in the file
        - Include enough context to ensure uniqueness
        - Tool will error if pattern matches multiple times or not at all

        REGEX TIPS (when useRegex=true):
        - Escape special chars: . → \\., ( → \\(, ) → \\), [ → \\[
        - Use \\s+ for flexible whitespace matching
        - Use .* sparingly (be specific)

        WORKFLOW:
        1. Tool finds the pattern in file
        2. Shows diff dialog with before/after preview
        3. User reviews and accepts (TAB) or rejects (ESC)
        4. Returns success/failure message

        Returns: Success message if applied, error if rejected or pattern not unique
    """)
    public String replaceCodeInFile(
            String filePath,
            String searchPattern,
            String replacement,
            Boolean useRegex
    ) {
        try {
            LOG.info("replaceCodeInFile: " + filePath + ", useRegex=" + useRegex);

            // Default useRegex to false if null
            boolean isRegex = useRegex != null && useRegex;

            // Find the file
            VirtualFile virtualFile = findFile(filePath);
            if (virtualFile == null) {
                return "❌ File not found: " + filePath + "\nCheck path is relative to project root.";
            }

            // Get current file content
            String currentContent = ApplicationManager.getApplication().runReadAction(
                (Computable<String>) () -> {
                    try {
                        return new String(virtualFile.contentsToByteArray(), virtualFile.getCharset());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
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

            // Get or open editor for the file
            Editor editor = getOrOpenEditor(virtualFile);
            if (editor == null) {
                return "❌ Could not open editor for file: " + filePath;
            }

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

    @Tool("""
        Create a new file at the specified path with given content.

        Parameters:
        - filePath: Relative to project root (e.g., "src/main/java/com/example/NewClass.java")
        - content: Complete file content

        BEST PRACTICES:
        - Use proper package declarations for Java/Kotlin files
        - Include necessary imports
        - Follow project directory structure conventions
        - File extension determines file type (.java, .kt, .xml, etc.)

        PATH EXAMPLES:
        - "src/main/java/com/example/UserValidator.java" - Java class
        - "src/main/kotlin/com/example/UserDto.kt" - Kotlin class
        - "src/main/resources/config/app.properties" - Config file
        - "src/test/java/com/example/UserTest.java" - Test class

        Returns: Success message with full file path, or error if file exists
    """)
    public String createNewFile(String filePath, String content) {
        try {
            LOG.info("createNewFile: " + filePath);

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
                            resultFuture.complete("⚠️ File created but not found in VFS: " + filePath);
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
