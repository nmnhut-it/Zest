package com.zps.zest;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.compiler.CompileContext;
import com.intellij.openapi.compiler.CompileStatusNotification;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A pipeline stage that compiles the generated test, collects errors,
 * and attempts to fix compilation errors using the LLM.
 */
class CompilationFixingStage implements PipelineStage {

    private static final Logger LOG = Logger.getInstance(CompilationFixingStage.class);
    private static final int MAX_FIX_ATTEMPTS = 3;

    @Override
    public void process(TestGenerationContext context) throws PipelineExecutionException {
        Project project = context.getProject();
        String testFilePath = context.getTestFilePath();
        if (project == null || testFilePath == null) {
            throw new PipelineExecutionException("Project or test file path is null");
        }

        // File operations should be done on read thread
        VirtualFile testFile;
        try {
            testFile = ApplicationManager.getApplication().runReadAction(
                    (Computable<VirtualFile>) () -> LocalFileSystem.getInstance().refreshAndFindFileByPath(testFilePath)
            );
        } catch (Exception e) {
            throw new PipelineExecutionException("Error finding test file: " + e.getMessage(), e);
        }

        if (testFile == null) {
            throw new PipelineExecutionException("Cannot find test file at: " + testFilePath);
        }

        // Compile the test file
        List<String> compilationErrors = compileTestFile(project, testFile);

        // If there are compilation errors, fix them
        if (!compilationErrors.isEmpty()) {
            LOG.info("Found " + compilationErrors.size() + " compilation errors. Attempting to fix...");
            fixCompilationErrors(context, testFile, compilationErrors);
        } else {
            LOG.info("No compilation errors found.");
        }
    }

    /**
     * Compiles the test file and collects any compilation errors.
     *
     * @param project The current project
     * @param testFile The test file to compile
     * @return A list of compilation error messages
     */
    private List<String> compileTestFile(Project project, VirtualFile testFile)
            throws PipelineExecutionException {
        List<String> errors = new ArrayList<>();
        CompletableFuture<List<String>> future = new CompletableFuture<>();

        // Use invokeAndWait for UI operations to ensure we're on the EDT
        CompletableFuture<Void> saveOp = new CompletableFuture<>();
        ApplicationManager.getApplication().invokeAndWait(() -> {
            try {
                // Make sure all documents are saved
                FileDocumentManager.getInstance().saveAllDocuments();
                saveOp.complete(null);
            } catch (Exception e) {
                saveOp.completeExceptionally(e);
            }
        });

        try {
            // Wait for save operation to complete
            saveOp.get();
        } catch (Exception e) {
            throw new PipelineExecutionException("Error saving documents: " + e.getMessage(), e);
        }

        LOG.info("Compiling test file: " + testFile.getPath());

        // Compile operations can be done on any thread
        ApplicationManager.getApplication().invokeLater(() -> {
            // Compile the file
            CompilerManager.getInstance(project).compile(
                    new VirtualFile[]{testFile},
                    new CompileStatusNotification() {
                        @Override
                        public void finished(boolean aborted, int errors, int warnings, CompileContext compileContext) {
                            if (aborted) {
                                LOG.warn("Compilation was aborted");
                                future.complete(List.of("Compilation was aborted"));
                                return;
                            }

                            if (errors > 0) {
                                // Collect error messages
                                List<String> errorMessages = new ArrayList<>();
                                Arrays.stream(compileContext.getMessages(CompilerMessageCategory.ERROR)).forEach(
                                        message -> {
                                            LOG.info("Compilation error: " + message.getMessage());
                                            errorMessages.add(message.getMessage());
                                        }
                                );
                                future.complete(errorMessages);
                            } else {
                                LOG.info("Compilation successful");
                                future.complete(List.of());
                            }
                        }
                    }
            );
        });

        try {
            return future.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            String errorMsg = "Error during compilation: " + e.getMessage();
            LOG.error(errorMsg, e);
            throw new PipelineExecutionException(errorMsg, e);
        }
    }

    /**
     * Uses the LLM to fix compilation errors in the test file.
     *
     * @param context The pipeline context
     * @param testFile The test file to fix
     * @param errors The compilation errors
     */
    private void fixCompilationErrors(TestGenerationContext context, VirtualFile testFile,
                                      List<String> errors) throws PipelineExecutionException {
        for (int attempt = 0; attempt < MAX_FIX_ATTEMPTS; attempt++) {
            LOG.info("Fix attempt " + (attempt + 1) + " of " + MAX_FIX_ATTEMPTS);

            // Read current test file content
            String testContent = readFileContent(testFile);

            // Create prompt for LLM to fix compilation errors
            String fixPrompt = createFixCompilationErrorsPrompt(testContent, errors);

            // Call LLM to fix the errors - reusing existing LlmApiCallStage
            LlmApiCallStage apiCallStage = new LlmApiCallStage();

            // Setup a temporary context with our fix prompt
            TestGenerationContext tempContext = new TestGenerationContext();
            tempContext.setProject(context.getProject());
            tempContext.setConfig(context.getConfig());
            tempContext.setPrompt(fixPrompt);

            try {
                // Call the API
                apiCallStage.process(tempContext);

                // Extract code from response
                CodeExtractionStage extractionStage = new CodeExtractionStage();
                extractionStage.process(tempContext);

                String fixedTestCode = tempContext.getTestCode();
                if (fixedTestCode == null || fixedTestCode.isEmpty()) {
                    LOG.warn("LLM returned empty or null code");
                    continue;
                }

                // Update the test file with fixed code
                writeFileContent(testFile, fixedTestCode);

                // Recompile to check if errors are fixed
                List<String> remainingErrors = compileTestFile(context.getProject(), testFile);
                if (remainingErrors.isEmpty()) {
                    // All compilation errors fixed
                    LOG.info("Successfully fixed all compilation errors");
                    return;
                }

                // Update the errors list for the next attempt
                LOG.info("Fixed some errors but " + remainingErrors.size() + " remain");
                errors = remainingErrors;
            } catch (Exception e) {
                LOG.error("Error during fix attempt " + (attempt + 1), e);
                // Continue to the next attempt
            }
        }

        // We've run out of attempts but still have errors
        LOG.warn("Failed to fix all compilation errors after " + MAX_FIX_ATTEMPTS + " attempts");
        // We won't throw an exception here, so the pipeline can continue
    }

    /**
     * Creates a prompt for the LLM to fix compilation errors.
     */
    private String createFixCompilationErrorsPrompt(String testCode, List<String> errors) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Fix the following compilation errors in this Java test class:\n\n");

        prompt.append("ERRORS:\n");
        for (String error : errors) {
            prompt.append("- ").append(error).append("\n");
        }

        prompt.append("\nCURRENT TEST CODE:\n```java\n");
        prompt.append(testCode);
        prompt.append("\n```\n\n");

        prompt.append("Please fix all compilation errors and return ONLY the corrected Java code with no explanations or markdown formatting.");

        return prompt.toString();
    }

    /**
     * Reads the content of a file.
     */
    private String readFileContent(VirtualFile file) throws PipelineExecutionException {
        try {
            // Read operations need to be done on EDT or read thread
            return ApplicationManager.getApplication().runReadAction((Computable<String>) () -> {
                Document document = FileDocumentManager.getInstance().getDocument(file);
                if (document == null) {
                    throw new RuntimeException("Could not get document for file: " + file.getPath());
                }
                return document.getText();
            });
        } catch (Exception e) {
            throw new PipelineExecutionException("Error reading file: " + e.getMessage(), e);
        }
    }

    /**
     * Writes content to a file.
     */
    private void writeFileContent(VirtualFile file, String content) throws PipelineExecutionException {
        try {
            // Use invokeAndWait to ensure we're on the EDT thread for UI operations
            CompletableFuture<Void> future = new CompletableFuture<>();

            ApplicationManager.getApplication().invokeAndWait(() -> {
                try {
                    ApplicationManager.getApplication().runWriteAction(() -> {
                        Document document = FileDocumentManager.getInstance().getDocument(file);
                        if (document == null) {
                            future.completeExceptionally(new RuntimeException("Could not get document for file: " + file.getPath()));
                            return;
                        }
                        document.setText(content);
                        FileDocumentManager.getInstance().saveDocument(document);
                        future.complete(null);
                    });
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });

            // Wait for the operation to complete and propagate any exceptions
            future.get();
        } catch (Exception e) {
            throw new PipelineExecutionException("Error writing to file: " + e.getMessage(), e);
        }
    }
}