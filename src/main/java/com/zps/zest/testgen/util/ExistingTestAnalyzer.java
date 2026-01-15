package com.zps.zest.testgen.util;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.zps.zest.core.ClassAnalyzer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Analyzes existing test classes and provides integration capabilities
 */
public class ExistingTestAnalyzer {
    private static final Logger LOG = Logger.getInstance(ExistingTestAnalyzer.class);
    
    private final Project project;
    
    public ExistingTestAnalyzer(@NotNull Project project) {
        this.project = project;
    }
    
    /**
     * Find existing test class for a given source class
     */
    @Nullable
    public ExistingTestClass findExistingTestClass(@NotNull String sourceClassName) {
        try {
            // Common test naming patterns
            String[] testNamePatterns = {
                sourceClassName + "Test",
                sourceClassName + "Tests", 
                "Test" + sourceClassName,
                sourceClassName + "TestCase",
                sourceClassName + "Spec"
            };
            
            for (String pattern : testNamePatterns) {
                PsiClass[] testClasses = PsiShortNamesCache.getInstance(project)
                    .getClassesByName(pattern, GlobalSearchScope.projectScope(project));
                    
                for (PsiClass testClass : testClasses) {
                    if (isTestClass(testClass)) {
                        return analyzeTestClass(testClass, sourceClassName);
                    }
                }
            }
            
            // Search by package structure
            return findTestByPackageConvention(sourceClassName);
            
        } catch (Exception e) {
            LOG.warn("Failed to find existing test class for: " + sourceClassName, e);
            return null;
        }
    }
    
    /**
     * Find all existing test classes in the project
     */
    @NotNull
    public List<ExistingTestClass> findAllTestClasses() {
        List<ExistingTestClass> testClasses = new ArrayList<>();
        
        try {
            // Wrap file index access in read action to avoid threading issues
            ApplicationManager.getApplication().runReadAction(() -> {
                try {
                    // Search for files containing "Test"
                    Collection<VirtualFile> testFiles = FilenameIndex.getAllFilesByExt(
                        project, "java", GlobalSearchScope.projectScope(project)
                    );
                    
                    PsiManager psiManager = PsiManager.getInstance(project);
                    
                    for (VirtualFile file : testFiles) {
                        if (isTestFile(file)) {
                            PsiFile psiFile = psiManager.findFile(file);
                            if (psiFile instanceof PsiJavaFile) {
                                PsiJavaFile javaFile = (PsiJavaFile) psiFile;
                                for (PsiClass psiClass : javaFile.getClasses()) {
                                    if (isTestClass(psiClass)) {
                                        ExistingTestClass testClass = analyzeTestClass(psiClass, null);
                                        testClasses.add(testClass);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to find all test classes in read action", e);
                }
            });
            
        } catch (Exception e) {
            LOG.warn("Failed to find all test classes", e);
        }
        
        return testClasses;
    }
    
    /**
     * Analyze what's missing in an existing test class
     */
    @NotNull
    public TestGapAnalysis analyzeTestGaps(@NotNull ExistingTestClass existingTest, @NotNull String sourceClassName) {
        try {
            // Find the source class
            PsiClass sourceClass = findSourceClass(sourceClassName);
            if (sourceClass == null) {
                return new TestGapAnalysis(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
            }
            
            // Analyze source class methods
            List<PsiMethod> sourceMethods = Arrays.asList(sourceClass.getMethods())
                .stream()
                .filter(method -> !method.isConstructor())
                .filter(method -> method.hasModifierProperty(PsiModifier.PUBLIC) || 
                                method.hasModifierProperty(PsiModifier.PROTECTED))
                .collect(Collectors.toList());
            
            // Find missing methods
            List<String> missingMethods = new ArrayList<>();
            List<String> partiallyTestedMethods = new ArrayList<>();
            List<String> wellTestedMethods = new ArrayList<>();
            
            for (PsiMethod sourceMethod : sourceMethods) {
                String methodName = sourceMethod.getName();
                List<ExistingTestMethod> testMethods = existingTest.getTestMethods().stream()
                    .filter(tm -> tm.getTestedMethodName().equals(methodName))
                    .collect(Collectors.toList());
                
                if (testMethods.isEmpty()) {
                    missingMethods.add(methodName);
                } else if (testMethods.size() < 3) { // Heuristic: less than 3 tests = partial
                    partiallyTestedMethods.add(methodName);
                } else {
                    wellTestedMethods.add(methodName);
                }
            }
            
            return new TestGapAnalysis(missingMethods, partiallyTestedMethods, wellTestedMethods);
            
        } catch (Exception e) {
            LOG.warn("Failed to analyze test gaps", e);
            return new TestGapAnalysis(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        }
    }
    
    private boolean isTestFile(@NotNull VirtualFile file) {
        String fileName = file.getName().toLowerCase();
        String path = file.getPath().toLowerCase();
        
        return fileName.contains("test") || 
               path.contains("/test/") || 
               path.contains("\\test\\") ||
               fileName.endsWith("test.java") ||
               fileName.endsWith("tests.java") ||
               fileName.endsWith("spec.java");
    }
    
    private boolean isTestClass(@NotNull PsiClass psiClass) {
        // Check annotations
        PsiAnnotation[] annotations = psiClass.getAnnotations();
        for (PsiAnnotation annotation : annotations) {
            String annotationName = annotation.getQualifiedName();
            if (annotationName != null && annotationName.contains("Test")) {
                return true;
            }
        }
        
        // Check methods for test annotations
        for (PsiMethod method : psiClass.getMethods()) {
            for (PsiAnnotation annotation : method.getAnnotations()) {
                String annotationName = annotation.getQualifiedName();
                if (annotationName != null && 
                    (annotationName.contains("Test") || annotationName.equals("org.junit.Test"))) {
                    return true;
                }
            }
        }
        
        // Check class name
        String className = psiClass.getName();
        return className != null && (className.contains("Test") || className.contains("Spec"));
    }
    
    private ExistingTestClass analyzeTestClass(@NotNull PsiClass testClass, @Nullable String sourceClassName) {
        String testClassName = testClass.getName();
        String packageName = "";
        
        PsiFile containingFile = testClass.getContainingFile();
        if (containingFile instanceof PsiJavaFile) {
            packageName = ((PsiJavaFile) containingFile).getPackageName();
        }
        
        // Analyze test methods
        List<ExistingTestMethod> testMethods = new ArrayList<>();
        for (PsiMethod method : testClass.getMethods()) {
            if (isTestMethod(method)) {
                ExistingTestMethod testMethod = analyzeTestMethod(method);
                testMethods.add(testMethod);
            }
        }
        
        // Determine framework
        String framework = detectTestFramework(testClass);
        
        // Extract tested class name if not provided
        if (sourceClassName == null) {
            sourceClassName = inferSourceClassName(testClassName);
        }
        
        return new ExistingTestClass(
            testClassName,
            packageName,
            testClass.getContainingFile().getVirtualFile().getPath(),
            framework,
            sourceClassName,
            testMethods,
            testClass
        );
    }
    
    private boolean isTestMethod(@NotNull PsiMethod method) {
        // Check for test annotations
        for (PsiAnnotation annotation : method.getAnnotations()) {
            String annotationName = annotation.getQualifiedName();
            if (annotationName != null && 
                (annotationName.contains("Test") || 
                 annotationName.equals("org.junit.Test") ||
                 annotationName.equals("org.junit.jupiter.api.Test"))) {
                return true;
            }
        }
        
        // Check method name pattern
        String methodName = method.getName();
        return methodName.startsWith("test") || methodName.startsWith("should") || methodName.contains("Test");
    }
    
    private ExistingTestMethod analyzeTestMethod(@NotNull PsiMethod method) {
        String methodName = method.getName();
        String testedMethodName = inferTestedMethodName(methodName);
        String description = extractTestDescription(method);
        
        // Analyze test type based on content
        String methodText = method.getText();
        TestMethodType type = inferTestType(methodText);
        
        return new ExistingTestMethod(
            methodName,
            testedMethodName,
            description,
            type,
            method
        );
    }
    
    private String inferTestedMethodName(@NotNull String testMethodName) {
        // Remove test prefixes and suffixes
        String name = testMethodName;
        
        if (name.startsWith("test")) {
            name = name.substring(4);
        }
        if (name.startsWith("should")) {
            // Extract method name from "shouldDoSomethingWhenCondition"
            name = name.substring(6);
            if (name.contains("When")) {
                name = name.substring(0, name.indexOf("When"));
            }
        }
        
        // Convert to camelCase
        if (!name.isEmpty()) {
            name = Character.toLowerCase(name.charAt(0)) + name.substring(1);
        }
        
        return name;
    }
    
    private String extractTestDescription(@NotNull PsiMethod method) {
        // Try to extract from JavaDoc
        PsiDocComment docComment = method.getDocComment();
        if (docComment != null) {
            String docText = docComment.getText();
            // Simple extraction - just get first sentence
            String[] lines = docText.split("\n");
            for (String line : lines) {
                line = line.trim().replaceAll("^\\*+\\s*", "");
                if (!line.isEmpty() && !line.startsWith("@")) {
                    return line;
                }
            }
        }
        
        // Fallback to method name
        return method.getName().replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase();
    }
    
    private TestMethodType inferTestType(@NotNull String methodText) {
        String lowerText = methodText.toLowerCase();
        
        if (lowerText.contains("mock") || lowerText.contains("@mock")) {
            return TestMethodType.UNIT;
        }
        if (lowerText.contains("integration") || lowerText.contains("@springboottest")) {
            return TestMethodType.INTEGRATION;
        }
        if (lowerText.contains("exception") || lowerText.contains("error") || lowerText.contains("fail")) {
            return TestMethodType.ERROR_HANDLING;
        }
        if (lowerText.contains("edge") || lowerText.contains("boundary") || lowerText.contains("null")) {
            return TestMethodType.EDGE_CASE;
        }
        
        return TestMethodType.UNIT; // Default
    }
    
    private String detectTestFramework(@NotNull PsiClass testClass) {
        PsiFile containingFile = testClass.getContainingFile();
        if (!(containingFile instanceof PsiJavaFile)) {
            return "Unknown";
        }
        
        PsiJavaFile javaFile = (PsiJavaFile) containingFile;
        for (PsiImportStatement importStatement : javaFile.getImportList().getImportStatements()) {
            String importText = importStatement.getText();
            if (importText.contains("org.junit.jupiter")) {
                return "JUnit 5";
            }
            if (importText.contains("org.junit.Test")) {
                return "JUnit 4";
            }
            if (importText.contains("org.testng")) {
                return "TestNG";
            }
        }
        
        return "JUnit 5"; // Default assumption
    }
    
    private String inferSourceClassName(@NotNull String testClassName) {
        if (testClassName.endsWith("Test")) {
            return testClassName.substring(0, testClassName.length() - 4);
        }
        if (testClassName.endsWith("Tests")) {
            return testClassName.substring(0, testClassName.length() - 5);
        }
        if (testClassName.startsWith("Test")) {
            return testClassName.substring(4);
        }
        if (testClassName.endsWith("TestCase")) {
            return testClassName.substring(0, testClassName.length() - 8);
        }
        if (testClassName.endsWith("Spec")) {
            return testClassName.substring(0, testClassName.length() - 4);
        }
        
        return testClassName; // Fallback
    }
    
    private ExistingTestClass findTestByPackageConvention(@NotNull String sourceClassName) {
        // This would implement package-based test discovery
        // For now, return null - could be enhanced later
        return null;
    }
    
    private PsiClass findSourceClass(@NotNull String className) {
        PsiClass[] classes = PsiShortNamesCache.getInstance(project)
            .getClassesByName(className, GlobalSearchScope.projectScope(project));
        
        return classes.length > 0 ? classes[0] : null;
    }
    
    // Data classes
    
    public static class ExistingTestClass {
        private final String className;
        private final String packageName;
        private final String filePath;
        private final String framework;
        private final String sourceClassName;
        private final List<ExistingTestMethod> testMethods;
        private final PsiClass psiClass;
        
        public ExistingTestClass(String className, String packageName, String filePath, 
                               String framework, String sourceClassName, 
                               List<ExistingTestMethod> testMethods, PsiClass psiClass) {
            this.className = className;
            this.packageName = packageName;
            this.filePath = filePath;
            this.framework = framework;
            this.sourceClassName = sourceClassName;
            this.testMethods = testMethods;
            this.psiClass = psiClass;
        }
        
        // Getters
        public String getClassName() { return className; }
        public String getPackageName() { return packageName; }
        public String getFilePath() { return filePath; }
        public String getFramework() { return framework; }
        public String getSourceClassName() { return sourceClassName; }
        public List<ExistingTestMethod> getTestMethods() { return testMethods; }
        public PsiClass getPsiClass() { return psiClass; }
        
        public boolean canAddMethods() {
            return psiClass != null && psiClass.isWritable();
        }
        
        public int getTestMethodCount() { return testMethods.size(); }
    }
    
    public static class ExistingTestMethod {
        private final String methodName;
        private final String testedMethodName;
        private final String description;
        private final TestMethodType type;
        private final PsiMethod psiMethod;
        
        public ExistingTestMethod(String methodName, String testedMethodName, String description,
                                TestMethodType type, PsiMethod psiMethod) {
            this.methodName = methodName;
            this.testedMethodName = testedMethodName;
            this.description = description;
            this.type = type;
            this.psiMethod = psiMethod;
        }
        
        // Getters
        public String getMethodName() { return methodName; }
        public String getTestedMethodName() { return testedMethodName; }
        public String getDescription() { return description; }
        public TestMethodType getType() { return type; }
        public PsiMethod getPsiMethod() { return psiMethod; }
    }
    
    public static class TestGapAnalysis {
        private final List<String> missingMethods;
        private final List<String> partiallyTestedMethods;
        private final List<String> wellTestedMethods;
        
        public TestGapAnalysis(List<String> missingMethods, List<String> partiallyTestedMethods, 
                             List<String> wellTestedMethods) {
            this.missingMethods = missingMethods;
            this.partiallyTestedMethods = partiallyTestedMethods;
            this.wellTestedMethods = wellTestedMethods;
        }
        
        // Getters
        public List<String> getMissingMethods() { return missingMethods; }
        public List<String> getPartiallyTestedMethods() { return partiallyTestedMethods; }
        public List<String> getWellTestedMethods() { return wellTestedMethods; }
        
        public boolean hasGaps() { 
            return !missingMethods.isEmpty() || !partiallyTestedMethods.isEmpty(); 
        }
        
        public int getTotalGaps() { 
            return missingMethods.size() + partiallyTestedMethods.size(); 
        }
    }
    
    public enum TestMethodType {
        UNIT("Unit Test"),
        INTEGRATION("Integration Test"), 
        EDGE_CASE("Edge Case Test"),
        ERROR_HANDLING("Error Handling Test");
        
        private final String displayName;
        
        TestMethodType(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() { return displayName; }
    }
}