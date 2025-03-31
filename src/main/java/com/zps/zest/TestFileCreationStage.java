package com.zps.zest;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Stage for creating the test file.
 */
class TestFileCreationStage implements PipelineStage {
    @Override
    public void process(CodeContext context) throws PipelineExecutionException {
        String testFilePath = createTestFile(
                context.getProject(),
                context.getPsiFile(),
                context.getTargetClass(),
                context.getTestCode()
        );

        if (testFilePath == null) {
            throw new PipelineExecutionException("Failed to create test file");
        }

        context.setTestFilePath(testFilePath);
        // Refresh file and apply formatting
        VirtualFile testFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(testFilePath);
        if (testFile != null) {
            // Format code and optimize imports
            WriteCommandAction.runWriteCommandAction(context.getProject(), ()->{
                reformatCodeAndOptimizeImports(context.getProject(), testFile);

            });

            // Notify user of success and open file
            ApplicationManager.getApplication().invokeLater(() -> {
                showSuccessMessage(context.getProject(), testFile);
                // Open the file in the editor
                FileEditorManager.getInstance(context.getProject()).openFile(testFile, true);
            });
        }
    }

    private String createTestFile(Project project, PsiFile psiFile, PsiClass containingClass, String testCode) {
        try {
            // Determine the test directory
            String srcPath = psiFile.getVirtualFile().getParent().getPath();
            String baseDir = project.getBasePath();

            // Attempt to find the test directory
            String testDirPath;
            if (srcPath.contains("/src/main/")) {
                testDirPath = srcPath.replace("/src/main/", "/src/test/");
            } else {
                // If not in a standard maven/gradle structure, create a test directory
                testDirPath = baseDir + "/src/test/" +
                        ((PsiJavaFile) psiFile).getPackageName().replace('.', '/');
            }

            // Create test directory if it doesn't exist
            Path testDir = Paths.get(testDirPath);
            Files.createDirectories(testDir);

            // Create the test file
            String testFileName = containingClass.getName() + "Test.java";
            Path testFilePath = testDir.resolve(testFileName);

            // Check if file already exists
            boolean shouldWrite = true;
            if (Files.exists(testFilePath)) {
                // Ask user if they want to overwrite
                String finalTestFileName = testFileName;
                boolean overwrite = true || ApplicationManager.getApplication().runWriteIntentReadAction(() -> {
                    return Messages.showYesNoDialog(
                            project,
                            "Test file " + finalTestFileName + " already exists. Do you want to overwrite it?",
                            "File Already Exists",
                            Messages.getQuestionIcon()
                    ) == Messages.YES;
                });

                if (!overwrite) {
                    // Create a new file with timestamp
                    String timestamp = String.valueOf(System.currentTimeMillis());
                    testFileName = containingClass.getName() + "Test_" + timestamp + ".java";
                    testFilePath = testDir.resolve(testFileName);
                }
            }

            Files.write(testFilePath, testCode.getBytes(StandardCharsets.UTF_8));
            return testFilePath.toString();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Shows a success message and checks for missing dependencies.
     */
    private void showSuccessMessage(Project project, VirtualFile testFile) {
        // Check for missing dependencies - this can be customized based on your needs
        boolean mockitoMissing = false;
        boolean microwwwMissing = false;

        StringBuilder message = new StringBuilder("Test class generated successfully!");
        if (mockitoMissing || microwwwMissing) {
            message.append("\n\nWarning: Missing dependencies detected:");
            if (mockitoMissing) {
                message.append("\n- Mockito is not in the classpath. Add it to your project dependencies.");
            }
            if (microwwwMissing) {
                message.append("\n- com.github.microwww.redis is not in the classpath. Add it to your project dependencies.");
            }
        }

        Messages.showInfoMessage(project, message.toString(), "Success");
    } /**
     * Reformats code and optimizes imports using IntelliJ's code formatting tools.
     * This method ensures the operation runs in a write thread for proper synchronization.
     *
     * @param project The project
     * @param file The file to reformat
     */
    private static final Logger LOG = Logger.getInstance(TestFileCreationStage.class);

    private void reformatCodeAndOptimizeImports(Project project, VirtualFile file) {
        if (project == null || file == null || !file.exists()) {
            LOG.warn("Cannot reformat code: invalid project or file");
            return;
        }

        // Get the PSI file
        PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
        if (!(psiFile instanceof PsiJavaFile)) {
            LOG.warn("Cannot reformat code: not a Java file");
            return;
        }

        // Make sure indexing is complete
        DumbService dumbService = DumbService.getInstance(project);
        if (dumbService.isDumb()) {
            LOG.info("Indexing in progress, scheduling code formatting for later");
            dumbService.smartInvokeLater(() -> {
                if (!project.isDisposed() && file.isValid()) {
                    WriteCommandAction.runWriteCommandAction(project, ()->{
                    reformatCodeAndOptimizeImports(project, file);
                    });
                }
            });
            return;
        }

        // Run in a write command
        WriteCommandAction.runWriteCommandAction(project, "Format Generated Test", null, () -> {
            try {
                // Get the document for the file
                @Nullable Document document =  FileDocumentManager.getInstance().getDocument(file);
                if (document == null) {
                    LOG.warn("Could not get document for file: " + file.getPath());
                    return;
                }

                // Format code
                CodeStyleManager codeStyleManager = CodeStyleManager.getInstance(project);
                PsiDocumentManager.getInstance(project).commitDocument(document);
                codeStyleManager.reformat(psiFile);

                // Optimize imports
                JavaCodeStyleManager javaStyleManager = JavaCodeStyleManager.getInstance(project);
                javaStyleManager.optimizeImports(psiFile);
                javaStyleManager.shortenClassReferences(psiFile);

                // Save the document
                FileDocumentManager.getInstance().saveDocument(document);

                LOG.info("Code formatting and import optimization completed");
            } catch (Exception e) {
                LOG.error("Error during code formatting: " + e.getMessage(), e);
            }
        });
    }
}