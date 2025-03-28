package com.zps.zest;


import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage for loading configuration settings.
 */
class ConfigurationStage implements PipelineStage {
    @Override
    public void process(TestGenerationContext context) throws PipelineExecutionException {
        Project project = context.getProject();
        if (project == null) {
            throw new PipelineExecutionException("No project available");
        }

        // Load configuration
        ConfigurationManager config = new ConfigurationManager(project);
        context.setConfig(config);
    }
}

/**
 * Stage for detecting the target class for test generation.
 */
class TargetClassDetectionStage implements PipelineStage {
    @Override
    public void process(TestGenerationContext context) throws PipelineExecutionException {
        if (context.getEditor() == null || context.getPsiFile() == null) {
            throw new PipelineExecutionException("No editor or file found");
        }

        // First try to get the class directly from the cursor position
        PsiElement element = context.getPsiFile().findElementAt(
                context.getEditor().getCaretModel().getOffset());
        PsiClass targetClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);

        // If not found directly, try to get it from a method
        if (targetClass == null) {
            PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
            if (method != null) {
                targetClass = method.getContainingClass();
            }
        }

        if (targetClass == null) {
            throw new PipelineExecutionException("Please place cursor inside a class or method");
        }

        context.setTargetClass(targetClass);
        context.setClassName(targetClass.getName());

        if (context.getPsiFile() instanceof PsiJavaFile) {
            context.setPackageName(((PsiJavaFile) context.getPsiFile()).getPackageName());
        }
    }
}

/**
 * Stage for analyzing the class and gathering context information.
 */
class ClassAnalysisStage implements PipelineStage {
    @Override
    public void process(TestGenerationContext context) throws PipelineExecutionException {
        PsiFile psiFile = context.getPsiFile();
        PsiClass targetClass = context.getTargetClass();

        // Collect imports from the current file
        StringBuilder importBuilder = new StringBuilder();
        if (psiFile instanceof PsiJavaFile) {
            for (PsiImportStatement importStatement : ((PsiJavaFile) psiFile).getImportList().getImportStatements()) {
                importBuilder.append(getTextOfPsiElement(importStatement)).append("\n");
            }
        }
        context.setImports(importBuilder.toString());

        // Collect class-level information
        String classContext = ApplicationManager.getApplication().runReadAction(
                (Computable<String>) () -> collectClassContext(context.getProject(), targetClass));
        context.setClassContext(classContext);

        // Detect JUnit version
        boolean isJUnit5 = ApplicationManager.getApplication().runReadAction((Computable<Boolean>) () ->
                JavaPsiFacade.getInstance(context.getProject()).findClass(
                        "org.junit.jupiter.api.Test", GlobalSearchScope.allScope(context.getProject())) != null);

        context.setJunitVersion(isJUnit5 ? "JUnit 5" : "JUnit 4");
    }

    private String collectClassContext(Project project, PsiClass targetClass) {
        StringBuilder contextInfo = new StringBuilder();

        // Class structure info
        contextInfo.append("Class structure:\n```java\n");
        contextInfo.append(getTextOfPsiElement(targetClass));
        contextInfo.append("\n```\n\n");

        // Add class-level javadoc if available
        PsiDocComment classJavadoc = targetClass.getDocComment();
        if (classJavadoc != null) {
            contextInfo.append("Class JavaDoc:\n```java\n");
            contextInfo.append(getTextOfPsiElement(classJavadoc));
            contextInfo.append("\n```\n\n");
        }

        // Collect and include code from related classes
        contextInfo.append("Related Classes:\n");
        Set<PsiClass> relatedClasses = new HashSet<>();

        // Add implemented interfaces
        PsiClassType[] interfaces = targetClass.getImplementsListTypes();
        for (PsiClassType interfaceType : interfaces) {
            PsiClass interfaceClass = interfaceType.resolve();
            if (interfaceClass != null) {
                relatedClasses.add(interfaceClass);
            }
        }

        // Add superclass
        PsiClass superClass = targetClass.getSuperClass();
        if (superClass != null && !superClass.getQualifiedName().startsWith("java.")) {
            relatedClasses.add(superClass);
        }

        // Add field types
        PsiField[] fields = targetClass.getFields();
        for (PsiField field : fields) {
            PsiType fieldType = field.getType();
            if (fieldType instanceof PsiClassType) {
                PsiClass fieldClass = ((PsiClassType) fieldType).resolve();
                if (fieldClass != null && !fieldClass.getQualifiedName().startsWith("java.")) {
                    relatedClasses.add(fieldClass);
                }
            }
        }

        // Find classes used in method bodies
        targetClass.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitReferenceExpression(PsiReferenceExpression expression) {
                super.visitReferenceExpression(expression);
                PsiElement resolved = expression.resolve();
                if (resolved instanceof PsiClass) {
                    PsiClass resolvedClass = (PsiClass) resolved;
                    if (!resolvedClass.getQualifiedName().startsWith("java.") &&
                            !resolvedClass.getQualifiedName().startsWith("javax.")) {
                        relatedClasses.add(resolvedClass);
                    }
                }
            }

            @Override
            public void visitNewExpression(PsiNewExpression expression) {
                super.visitNewExpression(expression);
                PsiJavaCodeReferenceElement classRef = expression.getClassReference();
                if (classRef != null) {
                    PsiElement resolved = classRef.resolve();
                    if (resolved instanceof PsiClass) {
                        PsiClass resolvedClass = (PsiClass) resolved;
                        if (!resolvedClass.getQualifiedName().startsWith("java.") &&
                                !resolvedClass.getQualifiedName().startsWith("javax.")) {
                            relatedClasses.add(resolvedClass);
                        }
                    }
                }
            }

            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                super.visitMethod(method);

                PsiParameter[] parameters = method.getParameterList().getParameters();
                for (PsiParameter parameter : parameters) {
                    PsiType paramType = parameter.getType();
                    if (paramType instanceof PsiClassType) {
                        PsiClass paramClass = ((PsiClassType) paramType).resolve();
                        if (paramClass != null &&
                                !paramClass.getQualifiedName().startsWith("java.") &&
                                !paramClass.getQualifiedName().startsWith("javax.")) {
                            relatedClasses.add(paramClass);
                        }
                    }
                }
            }
            
        });

        // Add code snippets for each related class
        for (PsiClass cls : relatedClasses) {
            if (cls.getQualifiedName() == null) continue;

            contextInfo.append("Class: ").append(cls.getQualifiedName()).append("\n");

            // Include class structure (simplified for related classes)
            contextInfo.append("```java\n");
            // Just show class declaration and method signatures for brevity
            contextInfo.append("public ");
            if (cls.isInterface()) contextInfo.append("interface ");
            else contextInfo.append("class ");
            contextInfo.append(cls.getName());

            // Add extends
            PsiClass superClassType = cls.getSuperClass();
            if (superClassType != null && !Objects.equals(superClassType.getName(), "Object")) {
                contextInfo.append(" extends ").append(superClassType.getName());
            }

            // Add implements
            PsiClassType[] implementsTypes = cls.getImplementsListTypes();
            if (implementsTypes.length > 0) {
                contextInfo.append(" implements ");
                for (int i = 0; i < implementsTypes.length; i++) {
                    contextInfo.append(implementsTypes[i].getClassName());
                    if (i < implementsTypes.length - 1) {
                        contextInfo.append(", ");
                    }
                }
            }
            contextInfo.append(" {\n");

            // Add method signatures
            for (PsiMethod method : cls.getMethods()) {
                if (!method.isConstructor() && method.hasModifierProperty(PsiModifier.PUBLIC)) {
                    contextInfo.append("    ");
                    if (method.hasModifierProperty(PsiModifier.PUBLIC)) contextInfo.append("public ");
                    if (method.hasModifierProperty(PsiModifier.PROTECTED)) contextInfo.append("protected ");
                    if (method.hasModifierProperty(PsiModifier.STATIC)) contextInfo.append("static ");
                    if (method.hasModifierProperty(PsiModifier.FINAL)) contextInfo.append("final ");

                    PsiType returnType = method.getReturnType();
                    if (returnType != null) {
                        contextInfo.append(returnType.getPresentableText()).append(" ");
                    }

                    contextInfo.append(method.getName()).append("(");

                    PsiParameter[] parameters = method.getParameterList().getParameters();
                    for (int i = 0; i < parameters.length; i++) {
                        PsiParameter param = parameters[i];
                        contextInfo.append(param.getType().getPresentableText())
                                .append(" ")
                                .append(param.getName());
                        if (i < parameters.length - 1) {
                            contextInfo.append(", ");
                        }
                    }
                    contextInfo.append(");\n");
                }
            }
            contextInfo.append("}\n");
            contextInfo.append("```\n\n");
        }

        return contextInfo.toString();
    }

    private static String getTextOfPsiElement(PsiElement element) {
        return ApplicationManager.getApplication().runReadAction(
                (ThrowableComputable<String, RuntimeException>) () -> element.getText());
    }
}

/**
 * Stage for creating the prompt to send to the LLM.
 */
class PromptCreationStage implements PipelineStage {
    @Override
    public void process(TestGenerationContext context) throws PipelineExecutionException {
        // Create prompt for test generation using PromptDrafter
        String prompt = PromptDrafter.createPrompt(
                context.getPackageName(),
                context.getClassName(),
                context.getImports(),
                context.getJunitVersion(),
                context.getClassContext()
        );

        context.setPrompt(prompt);
    }
}

/**
 * Stage for making the API call to the LLM.
 */
class LlmApiCallStage implements PipelineStage {
    @Override
    public void process(TestGenerationContext context) throws PipelineExecutionException {
        // Run the API call in a background task
        Project project = context.getProject();

        Task.WithResult<String, Exception> task = new Task.WithResult<String, Exception>(
                project, "Calling LLM API", true) {
            @Override
            protected String compute(@NotNull ProgressIndicator indicator) throws Exception {
                indicator.setIndeterminate(false);
                indicator.setText("Calling LLM API...");
                indicator.setFraction(0.5);

                // Try up to 3 times
                ConfigurationManager config = context.getConfig();
                String response = null;
                for (int i = 0; i < 3; i++) {
                    try {
                        response = callLlmApi(
                                config.getApiUrl(),
                                config.getModel(),
                                config.getAuthToken(),
                                context.getPrompt()
                        );
                        if (response != null) {
                            indicator.setText("LLM API call successful");
                            indicator.setFraction(1.0);
                            break;
                        }
                    } catch (IOException e) {
                        if (i == 2) {
                            throw e; // Rethrow on last attempt
                        }
                        indicator.setText("Calling LLM API: FAILED - retry " + (i + 1));
                    }
                }

                return response;
            }
        };

        try {
            String response = ProgressManager.getInstance().run(task);
            if (response == null || response.isEmpty()) {
                throw new PipelineExecutionException("Failed to generate test code");
            }
            context.setApiResponse(response);
        } catch (Exception e) {
            throw new PipelineExecutionException("API call failed: " + e.getMessage(), e);
        }
    }

    private String callLlmApi(String apiUrl, String model, String authToken, String prompt) throws IOException {
        // Determine which API format to use based on URL
        if (apiUrl.contains("openwebui") || apiUrl.contains("zingplay")) {
            return callOpenWebUIApi(apiUrl, model, authToken, prompt);
        } else {
            return callOllamaApi(apiUrl, model, authToken, prompt);
        }
    }

    private String callOpenWebUIApi(String apiUrl, String model, String authToken, String prompt) throws IOException {
        try {
            URL url = new URL(apiUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            if (authToken != null && !authToken.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + authToken);
            }
            connection.setConnectTimeout(480_000);
            connection.setReadTimeout(120_000);
            connection.setDoOutput(true);

            // OpenWebUI uses a different payload format
            String payload = String.format(
                    "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\",\"content\":\"%s\"}],\"stream\":false}",
                    model,
                    escapeJson(prompt)
            );

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = payload.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) {
                        response.append(line);
                    }

                    // Parse the JSON response
                    JsonObject jsonObject = JsonParser.parseString(response.toString()).getAsJsonObject();
                    if (jsonObject.has("choices") && jsonObject.getAsJsonArray("choices").size() > 0) {
                        JsonObject messageObject = jsonObject.getAsJsonArray("choices")
                                .get(0).getAsJsonObject()
                                .getAsJsonObject("message");

                        if (messageObject.has("content")) {
                            return messageObject.get("content").getAsString();
                        }
                    }
                    return null;
                }
            } else {
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) {
                        response.append(line);
                    }
                    System.err.println("API Error: " + response);
                }
                return null;
            }
        } catch (IOException ioException) {
            throw ioException;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String callOllamaApi(String apiUrl, String model, String authToken, String prompt) throws IOException {
        try {
            URL url = new URL(apiUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            if (authToken != null && !authToken.isEmpty()) {
                connection.setRequestProperty("Authorization", authToken);
            }
            connection.setConnectTimeout(480_000);
            connection.setReadTimeout(120_000);
            connection.setDoOutput(true);

            String payload = String.format(
                    "{\"model\":\"%s\",\"prompt\":\"%s\",\"stream\":false,\"options\":{\"num_predict\":%d}}",
                    model,
                    escapeJson(prompt),
                    32000
            );

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = payload.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) {
                        response.append(line);
                    }

                    // Parse the JSON response
                    JsonObject jsonObject = JsonParser.parseString(response.toString()).getAsJsonObject();
                    if (jsonObject.has("response")) {
                        return jsonObject.get("response").getAsString();
                    }
                    return null;
                }
            } else {
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) {
                        response.append(line);
                    }
                    System.err.println("API Error: " + response);
                }
                return null;
            }
        } catch (IOException ioException) {
            throw ioException;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String escapeJson(String input) {
        return input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}

/**
 * Stage for extracting the code from the LLM response.
 */
class CodeExtractionStage implements PipelineStage {
    @Override
    public void process(TestGenerationContext context) throws PipelineExecutionException {
        String response = context.getApiResponse();
        if (response == null || response.isEmpty()) {
            throw new PipelineExecutionException("Empty response from LLM API");
        }

        String testCode = extractCodeFromResponse(response);
        if (testCode == null || testCode.isEmpty()) {
            throw new PipelineExecutionException("Failed to extract test code from response");
        }

        context.setTestCode(testCode);
    }

    private String extractCodeFromResponse(String response) {
        if (response == null || response.isEmpty()) {
            return "";
        }

        // Handle unicode escape sequences
        response = unescapeUnicode(response);

        // First remove any <think>...</think> tags
        StringBuilder withoutThinkTags = new StringBuilder();
        boolean inThinkTag = false;
        int i = 0;

        while (i < response.length()) {
            if (i + 7 <= response.length() && response.substring(i, i + 7).equals("<think>")) {
                inThinkTag = true;
                i += 7;
            } else if (i + 8 <= response.length() && response.substring(i, i + 8).equals("</think>")) {
                inThinkTag = false;
                i += 8;
            } else if (!inThinkTag) {
                withoutThinkTags.append(response.charAt(i));
                i++;
            } else {
                i++;
            }
        }

        String cleanedResponse = withoutThinkTags.toString().trim();

        // Try to find Java code between ```java and ``` markers
        Pattern pattern = Pattern.compile("```(?:java)?[\\s\\n]*([\\s\\S]*?)[\\s\\n]*```", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(cleanedResponse);

        // If we find a code block, return its contents
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        // If no markdown code blocks found, return the cleaned response
        return cleanedResponse;
    }

    private String unescapeUnicode(String input) {
        // Handle common Unicode escape sequences
        return input.replace("\\u003c", "<")
                .replace("\\u003e", ">")
                .replace("\\u0027", "'")
                .replace("\\u0022", "\"")
                .replace("\\u002F", "/")
                .replace("\\u005C", "\\")
                .replace("\\u0026", "&")
                .replace("\\u0023", "#");
    }
}
/**
 * Stage for creating the test file.
 */
class TestFileCreationStage implements PipelineStage {
    @Override
    public void process(TestGenerationContext context) throws PipelineExecutionException {
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
                boolean overwrite = ApplicationManager.getApplication().runWriteIntentReadAction(() -> {
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
}

/**
 * Stage for optimizing imports in the test file.
 */
class ImportOptimizationStage implements PipelineStage {
    @Override
    public void process(TestGenerationContext context) throws PipelineExecutionException {
        // Open and optimize imports in UI thread
        String testFilePath = context.getTestFilePath();
        VirtualFile testFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(testFilePath);
        if (testFile == null) {
            throw new PipelineExecutionException("Could not find generated test file");
        }
         // Queue this for the UI thread
        ApplicationManager.getApplication().invokeLaterOnWriteThread(() -> {
            ImportOptimizer.optimizeImportsWhenIndexingComplete(context.getProject(), testFile);
            // Show success message and open the file
            showSuccessMessage(context.getProject(), testFile);
        });
    }

    // Helper method to add an import if the class exists
    private void addImportIfExists(PsiImportList importList, PsiElementFactory factory, Project project, String qualifiedName) {
        PsiClass psiClass = JavaPsiFacade.getInstance(project).findClass(qualifiedName, GlobalSearchScope.allScope(project));
        if (psiClass != null) {
            importList.add(factory.createImportStatement(psiClass));
        }
    }

    private void showSuccessMessage(Project project, VirtualFile testFile) {
        // Check for missing dependencies
        boolean mockitoMissing = JavaPsiFacade.getInstance(project).findClass(
                "org.mockito.Mockito", GlobalSearchScope.allScope(project)) == null;
        boolean microwwwMissing = JavaPsiFacade.getInstance(project).findClass(
                "com.github.microwww.redis.RedisServer", GlobalSearchScope.allScope(project)) == null;

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

        // Open the test file
        FileEditorManager.getInstance(project).openFile(testFile, true);
    }
}