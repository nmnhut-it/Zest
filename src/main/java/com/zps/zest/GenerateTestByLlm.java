package com.zps.zest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
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
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
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
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GenerateTestByLlm extends AnAction {
    private static final String CONFIG_FILE_NAME = "ollama-plugin.properties";
    private static final String DEFAULT_API_URL = "https://ollama.zingplay.com/api/generate";
    private static final String DEFAULT_MODEL = "deepseek-r1:32b";
    private static final int DEFAULT_MAX_ITERATIONS = 3;

    private String apiUrl;
    private String model;
    private int maxIterations;
    private String authToken;

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        // Load configuration
        loadConfig(project);

        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);

        if (editor == null || psiFile == null) {
            Messages.showErrorDialog("No editor or file found", "Error");
            return;
        }

        // First try to get the class directly from the cursor position
        PsiElement element = psiFile.findElementAt(editor.getCaretModel().getOffset());
        PsiClass targetClass = PsiTreeUtil.getParentOfType(element,PsiClass.class);

// If not found directly, try to get it from a method
        if (targetClass == null) {
            PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
            if (method != null) {
                targetClass = method.getContainingClass();
            } else {
                targetClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);
            }
        } else {
            targetClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);
        }

        if (targetClass == null) {
            Messages.showErrorDialog("Please place cursor inside a class or method", "No Class Selected");
            return;
        }



        // Extract information needed for test generation
        String className = targetClass.getName();

        String packageName = ((PsiJavaFile) psiFile).getPackageName();

        // Collect imports from the current file
        StringBuilder importBuilder = new StringBuilder();
        for (PsiImportStatement importStatement : ((PsiJavaFile) psiFile).getImportList().getImportStatements()) {
            importBuilder.append(getTextOfPsiElement(importStatement)).append("\n");
        }
        String imports = importBuilder.toString();

        // Get method signature
        StringBuilder signatureBuilder = new StringBuilder();

        // Collect class-level information
        PsiClass finalTargetClass = targetClass;
        String classContext = ApplicationManager.getApplication().runReadAction((Computable<String>) ()->collectClassContext(project, finalTargetClass));

        // Generate test using Ollama in background
        runTestGenerationInBackground(project, psiFile, targetClass, packageName, className, imports, classContext);
    }
    private void runTestGenerationInBackground(Project project, PsiFile sourceFile, PsiClass targetClass,
                                               String packageName, String className,
                                               String imports, String classContext) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Generating Class Test Suite with Ollama", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);
                indicator.setText("Preparing to generate class tests...");
                indicator.setFraction(0.1);

                // Check if project uses JUnit 4 or JUnit 5
                indicator.setText("Detecting JUnit version...");
                indicator.setFraction(0.2);
                PsiClass junit5Test = JavaPsiFacade.getInstance(project).findClass(
                        "org.junit.jupiter.api.Test", GlobalSearchScope.allScope(project));
                String junitVersion = junit5Test != null ? "JUnit 5" : "JUnit 4";

                // Create prompt for test generation
                indicator.setText("Creating prompt for Ollama...");
                indicator.setFraction(0.3);
                String prompt = PromptDrafter.createPrompt(packageName, className , imports, junitVersion, classContext);

                // Call Ollama API
                indicator.setText("Calling Ollama API...");
                indicator.setFraction(0.5);
                String response = null;
                for (int i = 0; i < 3; ++i) {
                    try {
                        response = callOpenWebUIApi(prompt);
                        if (response != null) {
                            indicator.setText("Calling Ollama API: SUCCESS...");
                            indicator.setFraction(0.6);
                            break;
                        }
                    } catch (IOException e) {
                        indicator.setText("Calling Ollama API: FAILED - retry " + (i + 1));
                        indicator.setFraction(0.6);
                        response = null;
                    }
                }

                if (response == null || response.isEmpty()) {
                    showMessageOnUIThread(project, "Failed to generate test code", "API Error", true);
                    return;
                }

                // Extract the test code from the response
                indicator.setText("Processing response...");
                indicator.setFraction(0.7);
                String testCode = extractCodeFromResponse(response);
                if (testCode == null || testCode.isEmpty()) {
                    showMessageOnUIThread(project, "Failed to extract test code from response", "Processing Error", true);
                    return;
                }

                // Create the test file
                indicator.setText("Creating test class file...");
                indicator.setFraction(0.8);
                String testFilePath = createTestFile(project, sourceFile, targetClass, testCode);
                if (testFilePath == null) {
                    showMessageOnUIThread(project, "Failed to create test file", "File Creation Error", true);
                    return;
                }

                // Optimize imports
                indicator.setText("Optimizing imports...");
                indicator.setFraction(0.9);
                optimizeImports(project, sourceFile, testFilePath);

                indicator.setText("Test class generated successfully!");
                indicator.setFraction(1.0);

                // Show success message and open the file
                ApplicationManager.getApplication().invokeLater(() -> {
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

// Refresh and open the test file
                    VirtualFile testVFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(testFilePath);
                    if (testVFile != null) {
                        FileEditorManager.getInstance(project).openFile(testVFile, true);
                    }
                });
            }
        });
    }
    private void showMessageOnUIThread(Project project, String message, String title, boolean isError) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (isError) {
                Messages.showErrorDialog(project, message, title);
            } else {
                Messages.showInfoMessage(project, message, title);
            }
        });
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
            if (superClass != null && !superClass.getQualifiedName().equals("java.lang.Object")) {
                relatedClasses.add(superClass);
            }

        // Add field types
        PsiField[] fields = targetClass.getFields();
        for (PsiField field : fields) {
            PsiType fieldType = field.getType();
            if (fieldType instanceof PsiClassType) {
                PsiClass fieldClass = ((PsiClassType) fieldType).resolve();
                if (fieldClass != null && !fieldClass.getQualifiedName().startsWith("java.lang")) {
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
    private String callOpenWebUIApi(String prompt) throws IOException {
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

            // OpenWebUI uses a different payload format compared to Ollama
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

                    // Parse the JSON response - OpenWebUI has a different response structure
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
    private static String getTextOfPsiElement(PsiElement fields) {
        return ApplicationManager.getApplication().runReadAction((ThrowableComputable<String, RuntimeException>) ()->{
            return fields.getText();
        });
    }

    private String callOllamaApi(String prompt) throws IOException {
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

    private void optimizeImports(Project project, PsiFile sourceFile, String filePath) {
        VirtualFile testFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath);
        if (testFile == null) return;

        ApplicationManager.getApplication().invokeAndWait(() -> {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(testFile);
            if (psiFile instanceof PsiJavaFile) {
                ApplicationManager.getApplication().runWriteAction(() -> {
                    copyAndOptimizeImports(project, sourceFile, filePath);
                    JavaCodeStyleManager.getInstance(project).optimizeImports(psiFile);
                    JavaCodeStyleManager.getInstance(project).shortenClassReferences(psiFile);

                    // Save changes
                    Document document = FileDocumentManager.getInstance().getDocument(testFile);
                    if (document != null) {
                        FileDocumentManager.getInstance().saveDocument(document);
                        testFile.refresh(false, false);
                    }
                });
            }
        });
    }

    private void loadConfig(Project project) {
        // Default values
        apiUrl = DEFAULT_API_URL;
        model = DEFAULT_MODEL;
        maxIterations = DEFAULT_MAX_ITERATIONS;
        authToken = "";

        // Try to load from config file
        try {
            File configFile = new File(project.getBasePath(), CONFIG_FILE_NAME);
            if (configFile.exists()) {
                Properties props = new Properties();
                try (FileInputStream fis = new FileInputStream(configFile)) {
                    props.load(fis);
                }

                apiUrl = props.getProperty("apiUrl", DEFAULT_API_URL);
                model = props.getProperty("model", DEFAULT_MODEL);
                authToken = props.getProperty("authToken", "");

                try {
                    maxIterations = Integer.parseInt(props.getProperty("maxIterations", String.valueOf(DEFAULT_MAX_ITERATIONS)));
                } catch (NumberFormatException e) {
                    maxIterations = DEFAULT_MAX_ITERATIONS;
                }
            } else {
                // Create default config file
                createDefaultConfigFile(project);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void copyAndOptimizeImports(Project project, PsiFile sourceFile, String testFilePath) {
        VirtualFile testFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(testFilePath);
        if (testFile == null || !(sourceFile instanceof PsiJavaFile)) return;

        ApplicationManager.getApplication().invokeLater(() -> {
            PsiFile testPsiFile = PsiManager.getInstance(project).findFile(testFile);
            if (testPsiFile instanceof PsiJavaFile) {
                ApplicationManager.getApplication().runWriteAction(() -> {
                    try {
                        // Get import lists
                        PsiImportList sourceImports = ((PsiJavaFile) sourceFile).getImportList();
                        PsiImportList testImports = ((PsiJavaFile) testPsiFile).getImportList();
                        PsiElementFactory factory = JavaPsiFacade.getInstance(project).getElementFactory();

                        // Detect JUnit version
                        boolean isJUnit5 = JavaPsiFacade.getInstance(project).findClass(
                                "org.junit.jupiter.api.Test", GlobalSearchScope.allScope(project)) != null;

                        // Add essential JUnit imports
                        if (isJUnit5) {
                            // JUnit 5 core imports - ensure Assertions class is imported
                            PsiClass assertionsClass = JavaPsiFacade.getInstance(project)
                                    .findClass("org.junit.jupiter.api.Assertions", GlobalSearchScope.allScope(project));
                            if (assertionsClass != null) {
                                testImports.add(factory.createImportStatement(assertionsClass));
                            }

                            // Common JUnit 5 imports
                            addImportIfExists(testImports, factory, project, "org.junit.jupiter.api.Test");
                            addImportIfExists(testImports, factory, project, "org.junit.jupiter.api.BeforeEach");
                            addImportIfExists(testImports, factory, project, "org.junit.jupiter.api.AfterEach");
                            addImportIfExists(testImports, factory, project, "org.junit.jupiter.api.DisplayName");
                        } else {
                            // JUnit 4 core imports - ensure Assert class is imported
                            PsiClass assertClass = JavaPsiFacade.getInstance(project)
                                    .findClass("org.junit.Assert", GlobalSearchScope.allScope(project));
                            if (assertClass != null) {
                                testImports.add(factory.createImportStatement(assertClass));
                            }

                            // Common JUnit 4 imports
                            addImportIfExists(testImports, factory, project, "org.junit.Test");
                            addImportIfExists(testImports, factory, project, "org.junit.Before");
                            addImportIfExists(testImports, factory, project, "org.junit.After");
                        }

                        // Add common Mockito imports
                        addImportIfExists(testImports, factory, project, "org.mockito.Mockito");
                        addImportIfExists(testImports, factory, project, "org.mockito.Mock");

                        // Copy source imports
                        for (PsiImportStatement importStmt : sourceImports.getImportStatements()) {
                            testImports.add(importStmt.copy());
                        }

                        // Copy source static imports
                        for (PsiImportStaticStatement staticImport : sourceImports.getImportStaticStatements()) {
                            testImports.add(staticImport.copy());
                        }

                        // Optimize imports
                        JavaCodeStyleManager.getInstance(project).optimizeImports(testPsiFile);
                        JavaCodeStyleManager.getInstance(project).shortenClassReferences(testPsiFile);
                        PsiDocumentManager.getInstance(project).commitDocument(
                                PsiDocumentManager.getInstance(project).getDocument(testPsiFile));

                        // Save changes
                        FileDocumentManager.getInstance().saveDocument(
                                FileDocumentManager.getInstance().getDocument(testFile));

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }
        });
    }

    // Helper method to add an import if the class exists
    private void addImportIfExists(PsiImportList importList, PsiElementFactory factory, Project project, String qualifiedName) {
        PsiClass psiClass = JavaPsiFacade.getInstance(project).findClass(qualifiedName, GlobalSearchScope.allScope(project));
        if (psiClass != null) {
            importList.add(factory.createImportStatement(psiClass));
        }
    }

    private void createDefaultConfigFile(Project project) {
        try {
            File configFile = new File(project.getBasePath(), CONFIG_FILE_NAME);
            Properties props = new Properties();
            props.setProperty("apiUrl", DEFAULT_API_URL);
            props.setProperty("model", DEFAULT_MODEL);
            props.setProperty("maxIterations", String.valueOf(DEFAULT_MAX_ITERATIONS));
            props.setProperty("authToken", "");

            try (FileOutputStream fos = new FileOutputStream(configFile)) {
                props.store(fos, "Ollama Test Generator Plugin Configuration");
            }

            Messages.showInfoMessage("Created default configuration file at: " + configFile.getPath() +
                            "\nPlease update with your API authorization if needed.",
                    "Configuration Created");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}