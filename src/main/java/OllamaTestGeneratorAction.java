
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.Query;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OllamaTestGeneratorAction extends AnAction {
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

        // Get the selected method
        PsiElement element = psiFile.findElementAt(editor.getCaretModel().getOffset());
        PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);

        if (method == null) {
            Messages.showErrorDialog("Please place cursor inside a method", "No Method Selected");
            return;
        }

        // Extract method information
        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) {
            Messages.showErrorDialog("Method is not contained in a class", "Error");
            return;
        }

        // Extract information needed for test generation
        String className = containingClass.getName();
        String methodName = method.getName();
        String packageName = ((PsiJavaFile) psiFile).getPackageName();
        String methodBody = method.getBody() != null ? method.getBody().getText() : "";

        // Collect imports from the current file
        StringBuilder importBuilder = new StringBuilder();
        for (PsiImportStatement importStatement : ((PsiJavaFile) psiFile).getImportList().getImportStatements()) {
            importBuilder.append(importStatement.getText()).append("\n");
        }
        String imports = importBuilder.toString();

        // Get method signature
        StringBuilder signatureBuilder = new StringBuilder();

        // Add modifiers
        PsiModifierList modifierList = method.getModifierList();
        for (String modifier : new String[]{"public", "protected", "private", "static", "final", "abstract"}) {
            if (modifierList.hasModifierProperty(modifier)) {
                signatureBuilder.append(modifier).append(" ");
            }
        }

        // Add return type
        PsiType returnType = method.getReturnType();
        if (returnType != null) {
            signatureBuilder.append(returnType.getPresentableText()).append(" ");
        }

        // Add method name and parameters
        signatureBuilder.append(methodName).append("(");
        PsiParameter[] parameters = method.getParameterList().getParameters();
        for (int i = 0; i < parameters.length; i++) {
            PsiParameter parameter = parameters[i];
            signatureBuilder.append(parameter.getType().getPresentableText())
                    .append(" ")
                    .append(parameter.getName());

            if (i < parameters.length - 1) {
                signatureBuilder.append(", ");
            }
        }
        signatureBuilder.append(")");

        // Add throws clause
        PsiClassType[] throwsList = method.getThrowsList().getReferencedTypes();
        if (throwsList.length > 0) {
            signatureBuilder.append(" throws ");
            for (int i = 0; i < throwsList.length; i++) {
                signatureBuilder.append(throwsList[i].getPresentableText());
                if (i < throwsList.length - 1) {
                    signatureBuilder.append(", ");
                }
            }
        }
        String methodSignature = signatureBuilder.toString();

        // Generate test using Ollama in background
        runTestGenerationInBackground(project, psiFile, containingClass, packageName, className,
                methodName, methodSignature, methodBody, imports, method);
    }

    private void runTestGenerationInBackground(Project project, PsiFile sourceFile, PsiClass containingClass,
                                               String packageName, String className, String methodName,
                                               String methodSignature, String methodBody, String imports, PsiMethod method) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Generating Unit Test with Ollama", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);
                indicator.setText("Preparing to generate test...");
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
                String methodContext1 = OllamaTestGeneratorAction.this.collectMethodContext(project, method);
                String prompt = createPrompt(packageName, className, methodName, methodSignature,
                        methodBody, imports, junitVersion, methodContext1);

                // Call Ollama API
                indicator.setText("Calling Ollama API...");
                indicator.setFraction(0.5);
                String response = callOllamaApi(prompt);
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
                indicator.setText("Creating test file...");
                indicator.setFraction(0.8);
                String testFilePath = createTestFile(project, sourceFile, containingClass, testCode);
                if (testFilePath == null) {
                    showMessageOnUIThread(project, "Failed to create test file", "File Creation Error", true);
                    return;
                }

                // Optimize imports
                indicator.setText("Optimizing imports...");
                indicator.setFraction(0.9);
                optimizeImports(project, sourceFile, testFilePath);

                indicator.setText("Test generated successfully!");
                indicator.setFraction(1.0);

                // Show success message and open the file
                ApplicationManager.getApplication().invokeLater(() -> {
                    Messages.showInfoMessage(project, "Test generated successfully!", "Success");

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

    private String createPrompt(String packageName, String className, String methodName,
                                String methodSignature, String methodBody, String imports, String junitVersion, String methodContext1) {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("Generate a ").append(junitVersion).append(" test for the following Java method:\n\n");
        promptBuilder.append("Package: ").append(packageName).append("\n");
        promptBuilder.append("Class: ").append(className).append("\n");
        promptBuilder.append("Method: ").append(methodSignature).append("\n\n");
        promptBuilder.append("Method body:\n").append(methodBody).append("\n\n");
        promptBuilder.append("Imports:\n").append(imports).append("\n\n");
        String methodContext = methodContext1;
        promptBuilder.append("Method Context:\n").append(methodContext).append("\n\n");
        promptBuilder.append("Requirements:\n");
        if (junitVersion.equals("JUnit 5")) {
            promptBuilder.append("1. Use JUnit 5 annotations (@Test, @DisplayName, etc.)\n");
        } else {
            promptBuilder.append("1. Use JUnit 4 annotations (@Test, @Before, etc.)\n");
        }
        promptBuilder.append("2. Create the appropriate test class name (").append(className).append("Test)\n");
        promptBuilder.append("3. Include all necessary imports. Prioritize the imports I give you. \n");
        promptBuilder.append("4. Test both normal cases and edge cases\n");
        promptBuilder.append("5. Use assertions to verify expected behavior\n");
        promptBuilder.append("6. Include comments explaining test logic\n");
        promptBuilder.append("7. Use Mockito if needed for dependencies\n");
        promptBuilder.append("8. MAKE SURE THE CODE IS COMPLETE. DO NOT PROVIDE PARTIAL CODE\n\n");

        promptBuilder.append("Return ONLY the complete test class code without explanations or markdown formatting.");

        return promptBuilder.toString();
    }

    private String collectMethodContext(Project project, PsiMethod method) {
        StringBuilder contextInfo = new StringBuilder();

        // Collect method references and usages
        contextInfo.append("Method References and Usage:\n");
        Query<PsiReference> references = ReferencesSearch.search(method, GlobalSearchScope.projectScope(project));
        int refCount = 0;

        for (PsiReference reference : references.findAll()) {
            if (refCount >= 5) break; // Limit to 5 references to keep prompt size reasonable

            PsiElement element = reference.getElement();
            PsiMethod callingMethod = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
            PsiClass callingClass = callingMethod != null ? callingMethod.getContainingClass() : null;

            if (callingClass != null && callingMethod != null) {
                contextInfo.append("- Called from: ").append(callingClass.getQualifiedName())
                        .append(".").append(callingMethod.getName()).append("\n");

                // Add context around the method call
                PsiStatement statement = PsiTreeUtil.getParentOfType(element, PsiStatement.class);
                if (statement != null) {
                    contextInfo.append("  Context: ").append(statement.getText()).append("\n");
                }
            }
            refCount++;
        }

        // Collect and include code from related classes
        contextInfo.append("\nRelated Classes Code:\n");
        Set<PsiClass> relatedClasses = new HashSet<>();

        // Add return type class
        PsiType returnType = method.getReturnType();
        if (returnType instanceof PsiClassType) {
            PsiClass returnClass = ((PsiClassType) returnType).resolve();
            if (returnClass != null && !returnClass.getQualifiedName().startsWith("java.lang")) {
                relatedClasses.add(returnClass);
            }
        }

        // Add parameter type classes
        for (PsiParameter param : method.getParameterList().getParameters()) {
            PsiType paramType = param.getType();
            if (paramType instanceof PsiClassType) {
                PsiClass paramClass = ((PsiClassType) paramType).resolve();
                if (paramClass != null && !paramClass.getQualifiedName().startsWith("java.lang")) {
                    relatedClasses.add(paramClass);
                }
            }
        }

        // Find classes used in method body
        if (method.getBody() != null) {
            method.getBody().accept(new JavaRecursiveElementVisitor() {
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
        }

        // Add code snippets for each related class
        for (PsiClass cls : relatedClasses) {
            contextInfo.append("Class: ").append(cls.getQualifiedName()).append("\n");

            // Include constructors
            PsiMethod[] constructors = cls.getConstructors();
            if (constructors.length > 0) {
                contextInfo.append("- Constructors:\n");
                int ctorCount = 0;
                for (PsiMethod constructor : constructors) {
                    if (ctorCount >= 2) break; // Limit constructors
                    if (constructor.hasModifierProperty(PsiModifier.PUBLIC)) {
                        contextInfo.append("  ```\n  ").append(constructor.getText()).append("\n  ```\n");
                        ctorCount++;
                    }
                }
            }

            // Include fields
            PsiField[] fields = cls.getFields();
            if (fields.length > 0) {
                contextInfo.append("- Fields:\n");
                for (int i = 0; i < Math.min(5, fields.length); i++) {
                    contextInfo.append("  ").append(fields[i].getText()).append("\n");
                }
            }

            // Include public methods that might be relevant for testing
            PsiMethod[] methods = cls.getMethods();
            if (methods.length > 0) {
                contextInfo.append("- Methods:\n");
                int methodCount = 0;
                for (PsiMethod m : methods) {
                    if (methodCount >= 3) break; // Limit methods
                    if (m.hasModifierProperty(PsiModifier.PUBLIC) && !m.isConstructor()) {
                        contextInfo.append("  ```\n  ").append(m.getText()).append("\n  ```\n");
                        methodCount++;
                    }
                }
            }

            contextInfo.append("\n");
        }

        return contextInfo.toString();
    }

    private String callOllamaApi(String prompt) {
        try {
            URL url = new URL(apiUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            if (authToken != null && !authToken.isEmpty()) {
                connection.setRequestProperty("Authorization", authToken);
            }
            connection.setConnectTimeout(100000);
            connection.setReadTimeout(60000);
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

        ApplicationManager.getApplication().invokeLater(() -> {
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
                        // Get all imports from source file
                        PsiImportList sourceImports = ((PsiJavaFile) sourceFile).getImportList();
                        PsiImportList testImports = ((PsiJavaFile) testPsiFile).getImportList();

                        // Add JUnit and Mockito imports
//                        PsiElementFactory factory = JavaPsiFacade.getInstance(project).getElementFactory();
//                        testImports.add(factory.createImportStatementOnDemand("org.junit.jupiter.api"));
//                        testImports.add(factory.createImportStatementOnDemand("org.mockito"));
//                        testImports.add(factory.createImportStatement(
//                                JavaPsiFacade.getElementFactory(project).createFQClassNameReferenceElement(
//                                        "org.mockito.Mockito", GlobalSearchScope.projectScope(project)).resolve().));

                        // Copy all imports from source to test
                        for (PsiImportStatement importStmt : sourceImports.getImportStatements()) {
                            WriteCommandAction.runWriteCommandAction(project, ()->{
                                testImports.add(importStmt.copy());
                            });
                        }

                        // Copy static imports too
                        for (PsiImportStaticStatement staticImport : sourceImports.getImportStaticStatements()) {
                            WriteCommandAction.runWriteCommandAction(project, ()->{
                                testImports.add(staticImport.copy());
                            });
                        }

                        // Optimize imports to remove unused ones
                        WriteCommandAction.runWriteCommandAction(project, ()-> {

                            JavaCodeStyleManager.getInstance(project).optimizeImports(testPsiFile);
                            JavaCodeStyleManager.getInstance(project).shortenClassReferences(testPsiFile);
                            PsiDocumentManager.getInstance(project).commitDocument(PsiDocumentManager.getInstance(project).getDocument(testPsiFile));

                            // Save changes
                            FileDocumentManager.getInstance().saveDocument(
                                    FileDocumentManager.getInstance().getDocument(testFile)
                            );
                        });
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            }
        });
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