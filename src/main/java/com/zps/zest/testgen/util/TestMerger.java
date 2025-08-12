package com.zps.zest.testgen.util;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.zps.zest.testgen.model.GeneratedTest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility for merging generated tests into existing test classes
 */
public class TestMerger {
    private static final Logger LOG = Logger.getInstance(TestMerger.class);
    
    private final Project project;
    private final PsiElementFactory elementFactory;
    private final JavaCodeStyleManager javaCodeStyleManager;
    private final CodeStyleManager codeStyleManager;
    
    public TestMerger(@NotNull Project project) {
        this.project = project;
        this.elementFactory = JavaPsiFacade.getInstance(project).getElementFactory();
        this.javaCodeStyleManager = JavaCodeStyleManager.getInstance(project);
        this.codeStyleManager = CodeStyleManager.getInstance(project);
    }
    
    /**
     * Merge generated tests into an existing test class
     */
    @NotNull
    public MergeResult mergeTestsIntoClass(
            @NotNull ExistingTestAnalyzer.ExistingTestClass existingTestClass,
            @NotNull List<GeneratedTest> generatedTests) {
        
        try {
            PsiClass targetClass = existingTestClass.getPsiClass();
            if (targetClass == null || !targetClass.isWritable()) {
                return new MergeResult(false, "Target test class is not writable", 0, Collections.emptyList());
            }
            
            List<String> addedMethods = new ArrayList<>();
            List<String> skippedMethods = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            
            WriteCommandAction.runWriteCommandAction(project, () -> {
                try {
                    // First, ensure all necessary imports are present
                    PsiFile containingFile = targetClass.getContainingFile();
                    if (containingFile instanceof PsiJavaFile) {
                        PsiJavaFile javaFile = (PsiJavaFile) containingFile;
                        
                        for (GeneratedTest test : generatedTests) {
                            // Extract imports from generated test
                            Set<String> requiredImports = extractRequiredImports(test.getTestCode());
                            addMissingImports(javaFile, requiredImports);
                        }
                    }
                    
                    // Now add test methods
                    for (GeneratedTest test : generatedTests) {
                        String methodCode = extractMethodFromGeneratedCode(test.getTestCode());
                        if (methodCode == null || methodCode.isEmpty()) {
                            warnings.add("Could not extract method from generated test: " + test.getTestName());
                            continue;
                        }
                        
                        // Check if method already exists
                        String methodName = extractMethodName(methodCode);
                        if (methodExists(targetClass, methodName)) {
                            skippedMethods.add(methodName);
                            LOG.info("Skipping existing method: " + methodName);
                            continue;
                        }
                        
                        // Create and add the method
                        try {
                            PsiMethod newMethod = createMethodFromCode(methodCode, test.getTestName());
                            if (newMethod != null) {
                                // Add method to class
                                PsiElement addedMethod = targetClass.add(newMethod);
                                
                                // Format the added method
                                codeStyleManager.reformat(addedMethod);
                                
                                addedMethods.add(methodName);
                                LOG.info("Added test method: " + methodName);
                            } else {
                                warnings.add("Failed to create method: " + methodName);
                            }
                        } catch (Exception e) {
                            warnings.add("Error adding method " + methodName + ": " + e.getMessage());
                            LOG.error("Failed to add method: " + methodName, e);
                        }
                    }
                    
                    // Optimize imports after adding all methods
                    if (containingFile instanceof PsiJavaFile) {
                        javaCodeStyleManager.optimizeImports(containingFile);
                    }
                    
                } catch (Exception e) {
                    LOG.error("Error during merge operation", e);
                    throw new RuntimeException("Merge operation failed: " + e.getMessage());
                }
            });
            
            String message = String.format(
                "Merge complete: %d methods added, %d skipped (already exist)",
                addedMethods.size(),
                skippedMethods.size()
            );
            
            if (!warnings.isEmpty()) {
                message += "\nWarnings: " + warnings.size();
            }
            
            return new MergeResult(true, message, addedMethods.size(), addedMethods, skippedMethods, warnings);
            
        } catch (Exception e) {
            LOG.error("Failed to merge tests into existing class", e);
            return new MergeResult(false, "Merge failed: " + e.getMessage(), 0, Collections.emptyList());
        }
    }
    
    /**
     * Extract required imports from generated test code
     */
    @NotNull
    private Set<String> extractRequiredImports(@NotNull String testCode) {
        Set<String> imports = new HashSet<>();
        
        // Pattern to match import statements
        Pattern importPattern = Pattern.compile("import\\s+([^;]+);");
        Matcher matcher = importPattern.matcher(testCode);
        
        while (matcher.find()) {
            String importStatement = matcher.group(1).trim();
            imports.add(importStatement);
        }
        
        // Add common test imports if not present
        imports.add("org.junit.jupiter.api.Test");
        imports.add("org.junit.jupiter.api.BeforeEach");
        imports.add("org.junit.jupiter.api.DisplayName");
        
        // Add assertion imports
        imports.add("org.junit.jupiter.api.Assertions.*");
        
        return imports;
    }
    
    /**
     * Add missing imports to the Java file
     */
    private void addMissingImports(@NotNull PsiJavaFile javaFile, @NotNull Set<String> requiredImports) {
        PsiImportList importList = javaFile.getImportList();
        if (importList == null) return;
        
        Set<String> existingImports = new HashSet<>();
        for (PsiImportStatement importStatement : importList.getImportStatements()) {
            String importText = importStatement.getQualifiedName();
            if (importText != null) {
                existingImports.add(importText);
            }
        }
        
        for (PsiImportStaticStatement staticImport : importList.getImportStaticStatements()) {
            String importText = staticImport.getReferenceName();
            if (importText != null) {
                existingImports.add(importText);
            }
        }
        
        // Add missing imports
        for (String requiredImport : requiredImports) {
            boolean isStatic = requiredImport.contains("*") || requiredImport.contains("assertEquals");
            String cleanImport = requiredImport.replace("static ", "");
            
            if (!existingImports.contains(cleanImport)) {
                try {
                    if (isStatic || requiredImport.startsWith("static ")) {
                        // For static imports, we need to parse and create the import differently
                        String importStatement = "import static " + cleanImport + ";";
                        PsiImportStaticStatement staticImport = (PsiImportStaticStatement) 
                            elementFactory.createStatementFromText(importStatement, null);
                        if (staticImport != null) {
                            importList.add(staticImport);
                        }
                    } else {
                        // For regular imports
                        String importStatement = "import " + cleanImport + ";";
                        PsiImportStatement newImport = (PsiImportStatement) 
                            elementFactory.createStatementFromText(importStatement, null);
                        if (newImport != null) {
                            importList.add(newImport);
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("Could not add import: " + requiredImport, e);
                }
            }
        }
    }
    
    /**
     * Extract just the method code from generated test code
     */
    @Nullable
    private String extractMethodFromGeneratedCode(@NotNull String testCode) {
        // Remove package and import statements
        String codeWithoutImports = testCode.replaceAll("package\\s+[^;]+;", "")
                                           .replaceAll("import\\s+[^;]+;", "");
        
        // Extract method(s) - looking for @Test annotation
        Pattern methodPattern = Pattern.compile(
            "(@\\w+(?:\\([^)]*\\))?\\s*)+" +  // Annotations
            "(public|protected|private)?\\s*" +  // Access modifier
            "(void)\\s+" +  // Return type
            "(\\w+)\\s*" +  // Method name
            "\\([^)]*\\)\\s*" +  // Parameters
            "(?:throws\\s+[^{]+)?\\s*" +  // Throws clause
            "\\{[^{}]*(?:\\{[^{}]*\\}[^{}]*)*\\}",  // Method body with nested blocks
            Pattern.DOTALL
        );
        
        Matcher matcher = methodPattern.matcher(codeWithoutImports);
        if (matcher.find()) {
            return matcher.group().trim();
        }
        
        // Try simpler pattern if complex one fails
        int testIndex = codeWithoutImports.indexOf("@Test");
        if (testIndex >= 0) {
            int methodStart = testIndex;
            int braceCount = 0;
            int methodEnd = -1;
            boolean inMethod = false;
            
            for (int i = testIndex; i < codeWithoutImports.length(); i++) {
                char c = codeWithoutImports.charAt(i);
                if (c == '{') {
                    braceCount++;
                    inMethod = true;
                } else if (c == '}') {
                    braceCount--;
                    if (inMethod && braceCount == 0) {
                        methodEnd = i + 1;
                        break;
                    }
                }
            }
            
            if (methodEnd > methodStart) {
                return codeWithoutImports.substring(methodStart, methodEnd).trim();
            }
        }
        
        return null;
    }
    
    /**
     * Extract method name from method code
     */
    @NotNull
    private String extractMethodName(@NotNull String methodCode) {
        Pattern pattern = Pattern.compile("void\\s+(\\w+)\\s*\\(");
        Matcher matcher = pattern.matcher(methodCode);
        
        if (matcher.find()) {
            return matcher.group(1);
        }
        
        // Fallback: try to find any method-like pattern
        pattern = Pattern.compile("(\\w+)\\s*\\(.*\\)\\s*\\{");
        matcher = pattern.matcher(methodCode);
        
        if (matcher.find()) {
            String name = matcher.group(1);
            // Filter out keywords
            if (!name.equals("if") && !name.equals("for") && !name.equals("while")) {
                return name;
            }
        }
        
        return "testMethod" + System.currentTimeMillis();
    }
    
    /**
     * Check if a method with the given name already exists
     */
    private boolean methodExists(@NotNull PsiClass psiClass, @NotNull String methodName) {
        for (PsiMethod method : psiClass.getMethods()) {
            if (method.getName().equals(methodName)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Create a PsiMethod from method code string
     */
    @Nullable
    private PsiMethod createMethodFromCode(@NotNull String methodCode, @NotNull String fallbackName) {
        try {
            // Clean up the method code
            String cleanedCode = methodCode.trim();
            
            // Ensure proper formatting
            if (!cleanedCode.startsWith("@")) {
                cleanedCode = "@Test\n" + cleanedCode;
            }
            
            // Try to parse as a method
            String dummyClass = "class Dummy { " + cleanedCode + " }";
            PsiClass dummyPsiClass = elementFactory.createClassFromText(dummyClass, null);
            
            PsiMethod[] methods = dummyPsiClass.getMethods();
            if (methods.length > 0) {
                return methods[0];
            }
            
        } catch (Exception e) {
            LOG.warn("Failed to create method from code: " + e.getMessage());
        }
        
        // Fallback: create a simple test method
        return createFallbackTestMethod(fallbackName);
    }
    
    /**
     * Create a fallback test method when parsing fails
     */
    @NotNull
    private PsiMethod createFallbackTestMethod(@NotNull String methodName) {
        String safeMethodName = methodName.replaceAll("[^a-zA-Z0-9_]", "_");
        
        String methodText = "@Test\n" +
                          "public void " + safeMethodName + "() {\n" +
                          "    // Generated test - implementation needed\n" +
                          "    fail(\"Test implementation needed\");\n" +
                          "}";
        
        return elementFactory.createMethodFromText(methodText, null);
    }
    
    /**
     * Result of merge operation
     */
    public static class MergeResult {
        private final boolean success;
        private final String message;
        private final int methodsAdded;
        private final List<String> addedMethods;
        private final List<String> skippedMethods;
        private final List<String> warnings;
        
        public MergeResult(boolean success, String message, int methodsAdded, List<String> addedMethods) {
            this(success, message, methodsAdded, addedMethods, Collections.emptyList(), Collections.emptyList());
        }
        
        public MergeResult(boolean success, String message, int methodsAdded, 
                         List<String> addedMethods, List<String> skippedMethods, List<String> warnings) {
            this.success = success;
            this.message = message;
            this.methodsAdded = methodsAdded;
            this.addedMethods = addedMethods != null ? addedMethods : Collections.emptyList();
            this.skippedMethods = skippedMethods != null ? skippedMethods : Collections.emptyList();
            this.warnings = warnings != null ? warnings : Collections.emptyList();
        }
        
        // Getters
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public int getMethodsAdded() { return methodsAdded; }
        public List<String> getAddedMethods() { return addedMethods; }
        public List<String> getSkippedMethods() { return skippedMethods; }
        public List<String> getWarnings() { return warnings; }
        
        public boolean hasWarnings() { return !warnings.isEmpty(); }
    }
}