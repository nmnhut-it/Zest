package com.zps.zest.testgen.agents;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.zps.zest.testgen.model.*;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Pure PSI-based test merger that doesn't use any LLM.
 * Merges multiple GeneratedTest objects into a single MergedTestClass.
 * 
 * ⚠️ LIMITATIONS:
 * - Does NOT check for existing test files
 * - May create duplicate test methods if test file already exists
 * - No intelligent conflict resolution
 * - Does not merge with existing test code
 * 
 * ✅ BEST FOR:
 * - Creating brand new test files from scratch
 * - Situations where LLM usage should be minimized
 * - Fast local processing without API calls
 * - When you're certain no test file exists
 * 
 * ❌ NOT SUITABLE FOR:
 * - Merging with existing test files
 * - Adding tests to existing test classes
 * - Complex conflict resolution scenarios
 * 
 * 💡 RECOMMENDATION:
 * For production use with existing test files, use TestMergerAgent instead.
 * This implementation is provided as a faster alternative for new test creation only.
 */
public class PSITestMergerAgent {
    private static final Logger LOG = Logger.getInstance(PSITestMergerAgent.class);
    private final Project project;
    
    public PSITestMergerAgent(@NotNull Project project) {
        this.project = project;
    }
    
    /**
     * Merge test methods into a complete test class using PSI.
     * Uses structured data from TestGenerationResult.
     */
    @NotNull
    public CompletableFuture<MergedTestClass> mergeTests(@NotNull TestGenerationResult result,
                                                         @NotNull TestContext context) {
        return CompletableFuture.supplyAsync(() -> {
            if (result.getMethodCount() == 0) {
                throw new RuntimeException("No tests to merge");
            }
            
            LOG.info("Starting PSI-based test merge for " + result.getMethodCount() + " test methods");
            
            // Merge using PSI with structured data
            return WriteCommandAction.runWriteCommandAction(project, (Computable<MergedTestClass>) () -> 
                buildMergedTestClass(result, context)
            );
        });
    }
    
    /**
     * Find existing test file by class and package name.
     */
    @Nullable
    private PsiJavaFile findExistingFile(@NotNull String className, @NotNull String packageName) {
        Collection<VirtualFile> files = FilenameIndex.getVirtualFilesByName(
            className + ".java", GlobalSearchScope.projectScope(project));
        
        for (VirtualFile file : files) {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
            if (psiFile instanceof PsiJavaFile && 
                packageName.equals(((PsiJavaFile) psiFile).getPackageName())) {
                return (PsiJavaFile) psiFile;
            }
        }
        return null;
    }
    
    /**
     * Collect compilation errors from the file.
     */
    @NotNull
    private String collectErrors(@NotNull PsiJavaFile file) {
        StringBuilder errors = new StringBuilder();
        Document doc = PsiDocumentManager.getInstance(project).getDocument(file);
        
        if (doc != null) {
            file.accept(new PsiRecursiveElementVisitor() {
                @Override
                public void visitErrorElement(PsiErrorElement element) {
                    super.visitErrorElement(element);
                    int line = doc.getLineNumber(element.getTextOffset()) + 1;
                    errors.append("Line ").append(line).append(": ")
                          .append(element.getErrorDescription()).append("\n");
                }
            });
        }
        
        return errors.toString();
    }
    
    /**
     * Build the merged test class using text generation to avoid PSI import issues.
     */
    private MergedTestClass buildMergedTestClass(@NotNull TestGenerationResult result,
                                                 @NotNull TestContext context) {
        // Direct access to all structured data
        String className = result.getClassName();
        String packageName = result.getPackageName();
        String framework = result.getFramework();
        List<String> imports = result.getImports();
        List<String> fieldDeclarations = result.getFieldDeclarations();
        String beforeEachCode = result.getBeforeEachCode();
        String afterEachCode = result.getAfterEachCode();
        List<GeneratedTestMethod> testMethods = result.getTestMethods();
        
        // Validate and clean up the data before processing
        ValidationResult validation = validateTestData(className, packageName, imports, fieldDeclarations, testMethods);
        if (!validation.isValid()) {
            LOG.warn("Test data validation issues found: " + validation.getIssues());
        }
        
        // Use cleaned data
        imports = validation.getCleanedImports();
        fieldDeclarations = validation.getCleanedFieldDeclarations();
        testMethods = validation.getCleanedTestMethods();
        
        // Check for existing test file
        PsiJavaFile existingFile = findExistingFile(className, packageName);
        boolean hasExistingFile = existingFile != null;
        
        // Build the complete file content as text
        StringBuilder fileContent = new StringBuilder();
        
        // Package declaration
        if (!packageName.isEmpty()) {
            fileContent.append("package ").append(packageName).append(";\n\n");
        }
        
        // Collect and add all imports
        Set<String> allImports = new HashSet<>();
        allImports.addAll(getFrameworkImports(framework));
        allImports.addAll(imports);
        
        for (String importStr : allImports) {
            String cleanImport = cleanImportStatement(importStr);
            if (!cleanImport.isEmpty()) {
                fileContent.append(createImportText(cleanImport)).append("\n");
            }
        }
        fileContent.append("\n");
        
        // Add merge comment if needed
        if (hasExistingFile) {
            fileContent.append("// FIXME: Merged ").append(testMethods.size())
                      .append(" new test methods - check for duplicates and fix compilation errors\n\n");
        }
        
        // Class declaration
        fileContent.append("public class ").append(className).append(" {\n\n");
        
        // Field declarations
        for (String field : fieldDeclarations) {
            String trimmedField = field.trim();
            if (!trimmedField.isEmpty()) {
                fileContent.append("    ").append(trimmedField);
                if (!trimmedField.endsWith(";")) {
                    fileContent.append(";");
                }
                fileContent.append("\n");
            }
        }
        if (!fieldDeclarations.isEmpty()) {
            fileContent.append("\n");
        }
        
        // Setup and teardown methods are now handled as regular test methods
        // The LLM generates complete methods with proper annotations
        
        // All methods (including setup, teardown, and test methods)
        for (GeneratedTestMethod method : testMethods) {
            // Add method annotations
            for (String annotation : method.getAnnotations()) {
                fileContent.append("    @").append(annotation).append("\n");
            }
            
            // Add method signature and body
            fileContent.append("    public void ").append(method.getMethodName()).append("() {\n");
            appendMethodBody(fileContent, method.getMethodBody());
            fileContent.append("    }\n\n");
        }
        
        fileContent.append("}\n");
        
        // Parse the text into PSI for formatting and error checking
        PsiJavaFile javaFile = (PsiJavaFile) PsiFileFactory.getInstance(project)
            .createFileFromText(className + ".java", JavaLanguage.INSTANCE, fileContent.toString());
        
        // Format the file
        formatFile(javaFile);
        
        // Collect any compilation errors
        String errors = collectErrors(javaFile);
        String finalContent = javaFile.getText();
        
        // If errors exist, prepend them as comment for LLM
        if (!errors.isEmpty()) {
            finalContent = "/* COMPILATION ERRORS:\n" + errors + "*/\n\n" + finalContent;
            LOG.info("Found compilation errors, including them in output for LLM fixing");
        }
        
        return new MergedTestClass(
            className,
            packageName,
            finalContent,
            className + ".java",
            result.getMethodCount(),
            framework
        );
    }
    
    
    /**
     * Append method body with proper indentation.
     */
    private void appendMethodBody(@NotNull StringBuilder sb, @NotNull String body) {
        String[] lines = body.split("\n");
        for (String line : lines) {
            String trimmedLine = line.trim();
            if (!trimmedLine.isEmpty()) {
                sb.append("        ").append(trimmedLine).append("\n");
            }
        }
    }
    
    /**
     * Create a new Java file with package declaration.
     */
    private PsiJavaFile createJavaFile(@NotNull String className, @NotNull String packageName) {
        PsiElementFactory factory = JavaPsiFacade.getInstance(project).getElementFactory();
        
        StringBuilder content = new StringBuilder();
        if (!packageName.isEmpty()) {
            content.append("package ").append(packageName).append(";\n\n");
        }
        content.append("public class ").append(className).append(" {\n}\n");
        
        return (PsiJavaFile) PsiFileFactory.getInstance(project)
            .createFileFromText(className + ".java", JavaLanguage.INSTANCE, content.toString());
    }
    
    /**
     * Add field declarations to the test class.
     */
    private void addFieldDeclarations(@NotNull PsiClass testClass, @NotNull List<String> fieldDeclarations) {
        if (fieldDeclarations.isEmpty()) {
            return;
        }
        
        PsiElementFactory factory = JavaPsiFacade.getInstance(project).getElementFactory();
        for (String fieldDecl : fieldDeclarations) {
            try {
                // Ensure field declaration ends with semicolon
                String fieldText = fieldDecl.trim();
                if (!fieldText.endsWith(";")) {
                    fieldText = fieldText + ";";
                }
                
                PsiField field = factory.createFieldFromText(fieldText, testClass);
                testClass.add(field);
            } catch (Exception e) {
                LOG.warn("Failed to add field declaration: " + fieldDecl, e);
                // Notify user about field declaration failure
                String errorMsg = "Failed to add field: " + fieldDecl + " - " + e.getMessage();
                if (project != null) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        com.intellij.openapi.ui.Messages.showWarningDialog(
                            project,
                            errorMsg,
                            "Field Declaration Warning"
                        );
                    });
                }
            }
        }
    }
    
    /**
     * Add @BeforeEach method to the test class.
     */
    private void addBeforeEachMethod(@NotNull PsiClass testClass, @NotNull String setupCode, @NotNull String framework) {
        PsiElementFactory factory = JavaPsiFacade.getInstance(project).getElementFactory();
        String annotation = framework.contains("JUnit5") ? "@BeforeEach" : "@Before";
        
        StringBuilder methodText = new StringBuilder();
        methodText.append(annotation).append("\n");
        methodText.append("public void setUp() {\n");
        methodText.append("    ").append(setupCode.replace("\n", "\n    "));
        if (!setupCode.trim().endsWith("}")) {
            methodText.append("\n}");
        }
        
        try {
            PsiMethod method = factory.createMethodFromText(methodText.toString(), testClass);
            testClass.add(method);
        } catch (Exception e) {
            LOG.warn("Failed to add setup method", e);
        }
    }
    
    /**
     * Add @AfterEach method to the test class.
     */
    private void addAfterEachMethod(@NotNull PsiClass testClass, @NotNull String teardownCode, @NotNull String framework) {
        PsiElementFactory factory = JavaPsiFacade.getInstance(project).getElementFactory();
        String annotation = framework.contains("JUnit5") ? "@AfterEach" : "@After";
        
        StringBuilder methodText = new StringBuilder();
        methodText.append(annotation).append("\n");
        methodText.append("public void tearDown() {\n");
        methodText.append("    ").append(teardownCode.replace("\n", "\n    "));
        if (!teardownCode.trim().endsWith("}")) {
            methodText.append("\n}");
        }
        
        try {
            PsiMethod method = factory.createMethodFromText(methodText.toString(), testClass);
            testClass.add(method);
        } catch (Exception e) {
            LOG.warn("Failed to add teardown method", e);
        }
    }

    /**
     * Clean import statement.
     */
    private String cleanImportStatement(@NotNull String importStr) {
        String clean = importStr.trim();
        if (clean.startsWith("import ")) {
            clean = clean.substring(7);
        }
        if (clean.endsWith(";")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean.trim();
    }
    
    /**
     * Create import text from a clean import string.
     * Handles both regular and static imports.
     */
    private String createImportText(@NotNull String cleanImport) {
        if (cleanImport.contains("static ")) {
            return "import static " + cleanImport.replace("static ", "").trim() + ";";
        } else {
            return "import " + cleanImport + ";";
        }
    }
    
    /**
     * Get framework-specific imports as a collection of strings.
     */
    private Set<String> getFrameworkImports(@NotNull String framework) {
        Set<String> imports = new HashSet<>();
        
        if (framework.contains("JUnit5") || framework.contains("Jupiter")) {
            imports.add("org.junit.jupiter.api.Test");
            imports.add("org.junit.jupiter.api.BeforeEach");
            imports.add("org.junit.jupiter.api.AfterEach");
            imports.add("static org.junit.jupiter.api.Assertions.*");
        } else if (framework.contains("JUnit4")) {
            imports.add("org.junit.Test");
            imports.add("org.junit.Before");
            imports.add("org.junit.After");
            imports.add("static org.junit.Assert.*");
        } else if (framework.contains("TestNG")) {
            imports.add("org.testng.annotations.Test");
            imports.add("org.testng.annotations.BeforeMethod");
            imports.add("org.testng.annotations.AfterMethod");
            imports.add("static org.testng.Assert.*");
        }
        
        return imports;
    }
    
    /**
     * Create the test class.
     */
    private PsiClass createTestClass(@NotNull PsiJavaFile javaFile, @NotNull String className) {
        PsiClass[] classes = javaFile.getClasses();
        if (classes.length > 0) {
            return classes[0];
        }
        
        // Create new class if not exists
        PsiElementFactory factory = JavaPsiFacade.getInstance(project).getElementFactory();
        PsiClass testClass = factory.createClass(className);
        javaFile.add(testClass);
        return testClass;
    }
    
    
    /**
     * Add setup and teardown methods if needed.
     */
    private void addSetupTeardownMethods(@NotNull PsiClass testClass, @NotNull String framework) {
        PsiElementFactory factory = JavaPsiFacade.getInstance(project).getElementFactory();
        
        // Check if we already have setup/teardown methods
        boolean hasSetup = false;
        boolean hasTeardown = false;
        
        for (PsiMethod method : testClass.getMethods()) {
            String name = method.getName();
            if (name.equals("setUp") || name.equals("setup")) {
                hasSetup = true;
            }
            if (name.equals("tearDown") || name.equals("cleanup")) {
                hasTeardown = true;
            }
        }
        
        // Add setup method if needed
        if (!hasSetup && needsSetupMethod(testClass)) {
            String annotation = framework.contains("JUnit5") ? "@BeforeEach" : "@Before";
            String methodText = annotation + "\npublic void setUp() {\n    // Setup test fixtures\n}";
            PsiMethod setupMethod = factory.createMethodFromText(methodText, testClass);
            testClass.add(setupMethod);
        }
    }
    
    /**
     * Check if the test class needs a setup method.
     */
    private boolean needsSetupMethod(@NotNull PsiClass testClass) {
        // Simple heuristic: if we have fields that need initialization
        return testClass.getFields().length > 0;
    }
    
    /**
     * Add a test method to the class using GeneratedTestMethod.
     */
    private void addTestMethod(@NotNull PsiClass testClass, 
                              @NotNull GeneratedTestMethod method,
                              @NotNull String framework) {
        PsiElementFactory factory = JavaPsiFacade.getInstance(project).getElementFactory();
        
        try {
            // Build complete method with annotations
            StringBuilder methodText = new StringBuilder();
            
            // Add annotations
            for (String annotation : method.getAnnotations()) {
                if (!annotation.startsWith("@")) {
                    methodText.append("@");
                }
                methodText.append(annotation).append("\n");
            }
            
            // Add method signature and body
            methodText.append("public void ").append(method.getMethodName()).append("() {\n");
            
            // Add method body with proper indentation
            String body = method.getMethodBody();
            String[] lines = body.split("\n");
            for (String line : lines) {
                methodText.append("    ").append(line).append("\n");
            }
            
            methodText.append("}");
            
            PsiMethod psiMethod = factory.createMethodFromText(methodText.toString(), testClass);
            testClass.add(psiMethod);
        } catch (Exception e) {
            LOG.error("Failed to create test method: " + method.getMethodName(), e);
        }
    }
    
    
    /**
     * Format the Java file using IntelliJ's code formatter.
     */
    private void formatFile(@NotNull PsiJavaFile javaFile) {
        try {
            CodeStyleManager.getInstance(project).reformat(javaFile);
            JavaCodeStyleManager.getInstance(project).optimizeImports(javaFile);
            JavaCodeStyleManager.getInstance(project).shortenClassReferences(javaFile);
        } catch (Exception e) {
            LOG.warn("Failed to format file", e);
        }
    }
    
    /**
     * Validate and clean test data to prevent compilation issues.
     */
    private ValidationResult validateTestData(@NotNull String className, 
                                            @NotNull String packageName,
                                            @NotNull List<String> imports, 
                                            @NotNull List<String> fieldDeclarations,
                                            @NotNull List<GeneratedTestMethod> testMethods) {
        List<String> issues = new ArrayList<>();
        
        // Clean and deduplicate imports
        Set<String> cleanedImports = new LinkedHashSet<>();
        for (String importStr : imports) {
            String cleaned = cleanImportStatement(importStr);
            if (!cleaned.isEmpty() && !cleaned.equals(packageName + ".*") && !cleaned.startsWith("java.lang")) {
                cleanedImports.add(cleaned);
            }
        }
        
        // Validate and clean field declarations
        List<String> cleanedFields = new ArrayList<>();
        Set<String> fieldNames = new HashSet<>();
        for (String fieldDecl : fieldDeclarations) {
            String cleaned = validateAndCleanFieldDeclaration(fieldDecl);
            if (!cleaned.isEmpty()) {
                String fieldName = extractFieldName(cleaned);
                if (fieldName != null && !fieldNames.contains(fieldName)) {
                    cleanedFields.add(cleaned);
                    fieldNames.add(fieldName);
                } else if (fieldName != null) {
                    issues.add("Duplicate field name: " + fieldName);
                }
            } else {
                issues.add("Invalid field declaration: " + fieldDecl);
            }
        }
        
        // Validate and clean test methods
        List<GeneratedTestMethod> cleanedMethods = new ArrayList<>();
        Set<String> methodNames = new HashSet<>();
        for (GeneratedTestMethod method : testMethods) {
            String methodName = method.getMethodName();
            if (methodName == null || methodName.trim().isEmpty()) {
                issues.add("Empty method name found");
                continue;
            }
            
            if (methodNames.contains(methodName)) {
                // Rename duplicate method
                String uniqueName = generateUniqueMethodName(methodName, methodNames);
                issues.add("Duplicate method name '" + methodName + "' renamed to '" + uniqueName + "'");
                methodName = uniqueName;
            }
            
            if (method.getMethodBody() == null || method.getMethodBody().trim().isEmpty()) {
                issues.add("Empty method body for: " + methodName);
                continue;
            }
            
            methodNames.add(methodName);
            
            // Create cleaned method
            GeneratedTestMethod.Builder builder = new GeneratedTestMethod.Builder(methodName)
                .methodBody(method.getMethodBody())
                .addAnnotation("Test");
            
            if (method.getAssociatedScenario() != null) {
                builder.scenario(method.getAssociatedScenario());
            }
            
            cleanedMethods.add(builder.build());
        }
        
        return new ValidationResult(
            issues.isEmpty(),
            issues,
            new ArrayList<>(cleanedImports),
            cleanedFields,
            cleanedMethods
        );
    }
    
    /**
     * Validate and clean a field declaration.
     */
    private String validateAndCleanFieldDeclaration(@NotNull String fieldDecl) {
        String cleaned = fieldDecl.trim();
        
        // Ensure it ends with semicolon
        if (!cleaned.isEmpty() && !cleaned.endsWith(";")) {
            cleaned += ";";
        }
        
        // Basic validation - should contain at least type and name
        if (cleaned.split("\\s+").length < 2) {
            return "";
        }
        
        return cleaned;
    }
    
    /**
     * Extract field name from declaration.
     */
    private String extractFieldName(@NotNull String fieldDecl) {
        try {
            String[] parts = fieldDecl.replace(";", "").trim().split("\\s+");
            for (int i = parts.length - 1; i >= 0; i--) {
                String part = parts[i];
                if (!part.startsWith("@") && !isJavaKeyword(part) && isValidJavaIdentifier(part)) {
                    return part;
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to extract field name from: " + fieldDecl, e);
        }
        return null;
    }
    
    /**
     * Generate unique method name by appending number.
     */
    private String generateUniqueMethodName(@NotNull String baseName, @NotNull Set<String> existingNames) {
        int counter = 2;
        String candidate = baseName + counter;
        while (existingNames.contains(candidate)) {
            counter++;
            candidate = baseName + counter;
        }
        return candidate;
    }
    
    /**
     * Check if string is a Java keyword.
     */
    private boolean isJavaKeyword(@NotNull String word) {
        Set<String> keywords = Set.of("public", "private", "protected", "static", "final", 
                                     "abstract", "synchronized", "volatile", "transient");
        return keywords.contains(word);
    }
    
    /**
     * Check if string is a valid Java identifier.
     */
    private boolean isValidJavaIdentifier(@NotNull String identifier) {
        if (identifier.isEmpty() || !Character.isJavaIdentifierStart(identifier.charAt(0))) {
            return false;
        }
        
        for (int i = 1; i < identifier.length(); i++) {
            if (!Character.isJavaIdentifierPart(identifier.charAt(i))) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Result of validation with cleaned data.
     */
    private static class ValidationResult {
        private final boolean valid;
        private final List<String> issues;
        private final List<String> cleanedImports;
        private final List<String> cleanedFieldDeclarations;
        private final List<GeneratedTestMethod> cleanedTestMethods;
        
        public ValidationResult(boolean valid, List<String> issues, 
                              List<String> cleanedImports, 
                              List<String> cleanedFieldDeclarations,
                              List<GeneratedTestMethod> cleanedTestMethods) {
            this.valid = valid;
            this.issues = issues;
            this.cleanedImports = cleanedImports;
            this.cleanedFieldDeclarations = cleanedFieldDeclarations;
            this.cleanedTestMethods = cleanedTestMethods;
        }
        
        public boolean isValid() { return valid; }
        public List<String> getIssues() { return issues; }
        public List<String> getCleanedImports() { return cleanedImports; }
        public List<String> getCleanedFieldDeclarations() { return cleanedFieldDeclarations; }
        public List<GeneratedTestMethod> getCleanedTestMethods() { return cleanedTestMethods; }
    }
}