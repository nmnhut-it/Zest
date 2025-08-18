package com.zps.zest.testgen.agents;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.util.LLMService;
import com.zps.zest.testgen.model.*;
import com.zps.zest.testgen.util.ExistingTestAnalyzer;
import com.zps.zest.testgen.util.ModuleAnalysisService;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

/**
 * Agent responsible for merging generated tests into a single file and handling existing test files
 */
public class TestMergerAgent extends StreamingBaseAgent {
    private final TestMergingTools mergingTools;
    private final TestMergingAssistant assistant;
    
    public TestMergerAgent(@NotNull Project project,
                          @NotNull ZestLangChain4jService langChainService,
                          @NotNull LLMService llmService) {
        super(project, langChainService, llmService, "TestMergerAgent");
        this.mergingTools = new TestMergingTools(project);
        
        this.assistant = AgenticServices
                .agentBuilder(TestMergingAssistant.class)
                .chatModel(getChatModelWithStreaming())
                .maxSequentialToolsInvocations(100)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(200))
                .tools(mergingTools)
                .build();
    }
    
    public interface TestMergingAssistant {
        @dev.langchain4j.service.SystemMessage("""
        You are a test merging assistant responsible for creating complete test classes.
        
        CRITICAL WORKFLOW - Follow this exact order:
        
        1. FIRST: Call analyzeProjectStructure() to understand the build system and source layout
        2. SECOND: Call findExistingTestFile() to check for existing tests that we need to merge with
        3. THIRD: Call setOutputFilePath() with your reasoned file path choice based on analysis
           - Include full path: "src/test/java/com/example/service/UserServiceTest.java"
           - Include reasoning: "Maven project detected, using standard test layout, existing test found at X"
           - If existing test found, use its path for merging
           - If no existing test, choose optimal path based on project structure
        4. FOURTH: Create or merge the test class using PSI tools:
           - createOrUpdateTestClass for basic class structure
           - addImportToClass for all necessary imports
           - addFieldToClass for field declarations  
           - addSetupMethod/addTeardownMethod if needed
           - addMethodToClass for each test method
        
        PATH SELECTION RULES:
        - Maven projects: Use "src/test/java/package/ClassName.java"
        - Gradle projects: Use "src/test/java/package/ClassName.java" 
        - IntelliJ projects: Use existing test source roots or "test/package/ClassName.java"
        - If existing test found: ALWAYS use its exact path for merging
        - If no existing test: Choose path based on project conventions
        
        EXISTING TEST HANDLING:
        - If existing test file found: Merge new methods into existing file
        - Avoid creating duplicate method names
        - Respect existing test framework and patterns
        - Preserve existing imports and field declarations
        
        IMPORTANT:
        - Use PSI-based tools for all operations
        - Always provide reasoning for path decisions
        - Follow the project's detected code style and framework
        - Ensure all imports are properly added and deduplicated
        """)
        @dev.langchain4j.agentic.Agent
        String mergeTests(String request);
    }
    
    @NotNull
    public CompletableFuture<MergedTestClass> mergeTests(@NotNull TestGenerationResult result, 
                                                         @NotNull TestContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (result.getMethodCount() == 0) {
                    throw new RuntimeException("No tests to merge");
                }
                
                // Direct access to all structured data - no extraction needed!
                String className = result.getClassName();
                String packageName = result.getPackageName();
                String framework = result.getFramework();
                List<String> imports = result.getImports();
                List<String> fieldDeclarations = result.getFieldDeclarations();
                String beforeEachCode = result.getBeforeEachCode();
                String afterEachCode = result.getAfterEachCode();
                List<GeneratedTestMethod> methods = result.getTestMethods();
                
                // Build TestClassMetadata from result
                TestClassMetadata.Builder metadataBuilder = new TestClassMetadata.Builder()
                    .className(className)
                    .packageName(packageName)
                    .framework(framework)
                    .beforeEachCode(beforeEachCode)
                    .afterEachCode(afterEachCode);
                
                for (String importStr : imports) {
                    metadataBuilder.addImport(importStr);
                }
                for (String field : fieldDeclarations) {
                    metadataBuilder.addFieldDeclaration(field);
                }
                TestClassMetadata metadata = metadataBuilder.build();
                
                // Use LLM to build complete test class
                sendToUI("🔧 Building complete test class...\n");
                mergingTools.reset(metadata, methods);
                mergingTools.setToolNotifier(this::sendToUI);
                
                String mergeRequest = buildMergeRequest(result, context);
                assistant.mergeTests(mergeRequest);
                
                sendToUI("✅ Test class ready\n");
                
                // Create and return MergedTestClass
                String finalContent = mergingTools.getFinalContent();
                String fileName = className + ".java";
                String inferredPath = mergingTools.getInferredOutputPath();
                
                return new MergedTestClass(
                    className,
                    packageName,
                    finalContent,
                    fileName,
                    inferredPath,
                    methods.size(),
                    framework
                );
                
            } catch (Exception e) {
                LOG.error("Failed to merge tests", e);
                throw new RuntimeException("Test merging failed: " + e.getMessage());
            }
        });
    }
    
    
    private String buildMergeRequest(TestGenerationResult result, TestContext context) {
        StringBuilder request = new StringBuilder();
        request.append("Create a complete test class with the following components:\n\n");
        
        // Include structured information from result
        request.append("Package: ").append(result.getPackageName()).append("\n");
        request.append("Class: ").append(result.getClassName()).append("\n");
        request.append("Framework: ").append(result.getFramework()).append("\n");
        request.append("Test Methods: ").append(result.getMethodCount()).append("\n\n");
        
        // Add context information if available
        if (context != null) {
            request.append("Context Info:\n");
            request.append("- Framework detected: ").append(context.getFrameworkInfo()).append("\n");
            // Test path not available in context currently
            // request.append("- Existing test path: ").append(context.getTestPath()).append("\n\n");
        }
        
        request.append("Test Methods to include:\n");
        for (GeneratedTestMethod method : result.getTestMethods()) {
            request.append("- ").append(method.getMethodName());
            if (method.getAssociatedScenario() != null) {
                request.append(" (Scenario: ").append(method.getAssociatedScenario().getName()).append(")");
            }
            request.append("\n");
        }
        
        request.append("\nUse the PSI-based tools to construct the complete test class.");
        return request.toString();
    }
    
    public static class TestMergingTools {
        private final Project project;
        private PsiJavaFile testFile;
        private PsiClass testClass;
        private TestClassMetadata metadata;
        private final List<GeneratedTestMethod> testMethods = new ArrayList<>();
        private Consumer<String> toolNotifier;
        private final ExistingTestAnalyzer existingTestAnalyzer;
        private String inferredOutputPath;
        private String pathReasoning;
        
        public TestMergingTools(Project project) {
            this.project = project;
            this.existingTestAnalyzer = new ExistingTestAnalyzer(project);
        }
        
        public void reset(TestClassMetadata metadata, List<GeneratedTestMethod> methods) {
            this.metadata = metadata;
            this.testMethods.clear();
            this.testMethods.addAll(methods);
            this.testFile = null;
            this.testClass = null;
        }
        
        public void setToolNotifier(Consumer<String> notifier) {
            this.toolNotifier = notifier;
        }
        
        private void notifyTool(String toolName, String params) {
            if (toolNotifier != null) {
                SwingUtilities.invokeLater(() -> 
                    toolNotifier.accept(String.format("🔧 %s(%s)\n", toolName, params)));
            }
        }
        
        @Tool("Create or update the test class with package and class declaration")
        public String createOrUpdateTestClass(String className, String packageName) {
            notifyTool("createOrUpdateTestClass", packageName +"." + className);
            
            WriteCommandAction.runWriteCommandAction(project, () -> {
                PsiElementFactory factory = JavaPsiFacade.getInstance(project).getElementFactory();
                
                // Create the Java file with package and class
                String fileContent = "";
                if (!packageName.isEmpty()) {
                    fileContent = "package " + packageName + ";\n\n";
                }
                fileContent += "public class " + className + " {\n}";
                
                testFile = (PsiJavaFile) PsiFileFactory.getInstance(project)
                    .createFileFromText(className + ".java",
                        com.intellij.lang.java.JavaLanguage.INSTANCE, fileContent);
                
                testClass = testFile.getClasses()[0];
            });
            
            return "Test class created: " + className;
        }
        
        @Tool("Add an import statement to the test class")
        public String addImportToClass(String importStatement) {
            notifyTool("addImportToClass", importStatement);
            
            if (testFile == null) {
                return "Error: Test class not created yet";
            }
            
            WriteCommandAction.runWriteCommandAction(project, () -> {
                PsiImportList importList = testFile.getImportList();
                if (importList != null) {
                    PsiElementFactory factory = JavaPsiFacade.getInstance(project).getElementFactory();
                    
                    // Clean import statement
                    String cleanImport = importStatement;
                    if (cleanImport.startsWith("import ")) {
                        cleanImport = cleanImport.substring(7);
                    }
                    if (cleanImport.endsWith(";")) {
                        cleanImport = cleanImport.substring(0, cleanImport.length() - 1);
                    }
                    
                    try {
                        if (cleanImport.contains("static ") || cleanImport.contains("*")) {
                            // Create static import using createStatementFromText
                            String importText = "import static " + cleanImport.replace("static ", "") + ";";
                            PsiImportStaticStatement staticImport = (PsiImportStaticStatement)
                                factory.createStatementFromText(importText, testFile);
                            if (staticImport != null) {
                                importList.add(staticImport);
                            }
                        } else {
                            // Create regular import using createStatementFromText
                            String importText = "import " + cleanImport + ";";
                            PsiImportStatement newImport = (PsiImportStatement)
                                factory.createStatementFromText(importText, testFile);
                            if (newImport != null) {
                                importList.add(newImport);
                            }
                        }
                    } catch (Exception e) {
                        // Log error
                    }
                }
            });
            
            return "Import added: " + importStatement;
        }
        
        @Tool("Add a field declaration to the test class")
        public String addFieldToClass(String fieldDeclaration) {
            notifyTool("addFieldToClass", fieldDeclaration);
            
            if (testClass == null) {
                return "Error: Test class not created yet";
            }
            
            WriteCommandAction.runWriteCommandAction(project, () -> {
                PsiElementFactory factory = JavaPsiFacade.getInstance(project).getElementFactory();
                // Ensure field declaration ends with semicolon
                String fieldText = fieldDeclaration.trim();
                if (!fieldText.endsWith(";")) {
                    fieldText = fieldText + ";";
                }
                PsiField field = factory.createFieldFromText(fieldText, testClass);
                testClass.add(field);
            });
            
            return "Field added: " + fieldDeclaration;
        }
        
        @Tool("Add a test method to the class")
        public String addMethodToClass(String methodName, String methodBody, String annotations) {
            notifyTool("addMethodToClass", methodName);
            
            if (testClass == null) {
                return "Error: Test class not created yet";
            }
            
            WriteCommandAction.runWriteCommandAction(project, () -> {
                PsiElementFactory factory = JavaPsiFacade.getInstance(project).getElementFactory();
                
                // Build complete method with annotations
                StringBuilder methodText = new StringBuilder();
                if (annotations != null && !annotations.isEmpty()) {
                    methodText.append(annotations).append("\n");
                }
                methodText.append("public void ").append(methodName).append("() {\n");
                methodText.append(methodBody);
                methodText.append("\n}");
                
                PsiMethod method = factory.createMethodFromText(methodText.toString(), testClass);
                testClass.add(method);
            });
            
            return "Method added: " + methodName;
        }
        
        @Tool("Add a setup method (BeforeEach) to the class")
        public String addSetupMethod(String methodBody) {
            notifyTool("addSetupMethod", "BeforeEach");
            
            if (testClass == null) {
                return "Error: Test class not created yet";
            }
            
            WriteCommandAction.runWriteCommandAction(project, () -> {
                PsiElementFactory factory = JavaPsiFacade.getInstance(project).getElementFactory();
                String methodText = "@BeforeEach\npublic void setUp() {\n" + methodBody + "\n}";
                PsiMethod method = factory.createMethodFromText(methodText, testClass);
                testClass.add(method);
            });
            
            return "Setup method added";
        }
        
        @Tool("Add a teardown method (AfterEach) to the class")
        public String addTeardownMethod(String methodBody) {
            notifyTool("addTeardownMethod", "AfterEach");
            
            if (testClass == null) {
                return "Error: Test class not created yet";
            }
            
            WriteCommandAction.runWriteCommandAction(project, () -> {
                PsiElementFactory factory = JavaPsiFacade.getInstance(project).getElementFactory();
                String methodText = "@AfterEach\npublic void tearDown() {\n" + methodBody + "\n}";
                PsiMethod method = factory.createMethodFromText(methodText, testClass);
                testClass.add(method);
            });
            
            return "Teardown method added";
        }
        
        @Tool("Get the final merged test class content")
        public String getFinalContent() {
            notifyTool("getFinalContent", "Formatting and retrieving");
            
            if (testFile == null) {
                return "";
            }
            
            // Format the file
            WriteCommandAction.runWriteCommandAction(project, () -> {
                CodeStyleManager.getInstance(project).reformat(testFile);
                JavaCodeStyleManager.getInstance(project).optimizeImports(testFile);
            });
            
            return testFile.getText();
        }
        
        @Tool("Analyze project structure to understand build system and source layout")
        public String analyzeProjectStructure() {
            notifyTool("analyzeProjectStructure", "Detecting build system and source roots");
            
            ModuleAnalysisService.ProjectStructureInfo structureInfo = 
                ModuleAnalysisService.analyzeProject(project);
            
            return structureInfo.getAnalysisSummary();
        }
        
        @Tool("Find and analyze existing test files for the target class")
        public String findExistingTestFile(String targetClassName) {
            notifyTool("findExistingTestFile", targetClassName);
            
            try {
                ExistingTestAnalyzer.ExistingTestClass existingTest = 
                    existingTestAnalyzer.findExistingTestClass(targetClassName);
                
                if (existingTest == null) {
                    return String.format("✅ No existing test file found for %s\n" +
                                       "This is a clean slate - we can create a new test file.\n" +
                                       "Recommendation: Create new test file with standard naming convention.",
                                       targetClassName);
                }
                
                // Analyze what exists vs what we're generating
                StringBuilder analysis = new StringBuilder();
                analysis.append("🔍 Found existing test file for ").append(targetClassName).append(":\n");
                analysis.append("  • File: ").append(existingTest.getFilePath()).append("\n");
                analysis.append("  • Class: ").append(existingTest.getClassName()).append("\n");
                analysis.append("  • Package: ").append(existingTest.getPackageName()).append("\n");
                analysis.append("  • Framework: ").append(existingTest.getFramework()).append("\n");
                analysis.append("  • Existing methods: ").append(existingTest.getTestMethodCount()).append("\n");
                
                if (existingTest.canAddMethods()) {
                    analysis.append("  • Status: ✅ Can merge new tests into existing file\n");
                    analysis.append("\n⚠️  IMPORTANT: Use the existing file path for merging: ")
                           .append(existingTest.getFilePath()).append("\n");
                } else {
                    analysis.append("  • Status: ❌ Cannot modify existing file (read-only or locked)\n");
                    analysis.append("\n💡 Recommendation: Create new test file with different name\n");
                }
                
                // List existing test methods to avoid duplicates
                if (!existingTest.getTestMethods().isEmpty()) {
                    analysis.append("\nExisting test methods to avoid duplicating:\n");
                    for (ExistingTestAnalyzer.ExistingTestMethod method : existingTest.getTestMethods()) {
                        analysis.append("  • ").append(method.getMethodName()).append("\n");
                    }
                }
                
                return analysis.toString();
                
            } catch (Exception e) {
                return "❌ Error analyzing existing tests: " + e.getMessage() + 
                       "\nContinuing with new file creation...";
            }
        }
        
        @Tool("Set the output file path for the test class with reasoning")
        public String setOutputFilePath(String filePath, String reasoning) {
            notifyTool("setOutputFilePath", filePath);
            
            // Validate the path
            if (filePath == null || filePath.trim().isEmpty()) {
                return "❌ Error: File path cannot be empty";
            }
            
            // Store the inferred path and reasoning
            this.inferredOutputPath = filePath.trim();
            this.pathReasoning = reasoning != null ? reasoning.trim() : "No reasoning provided";
            
            // Validate path format
            if (!inferredOutputPath.endsWith(".java")) {
                return "⚠️  Warning: Path should end with .java - " + inferredOutputPath;
            }
            
            // Check if target directory exists or can be created
            String dirPath = inferredOutputPath.substring(0, inferredOutputPath.lastIndexOf('/'));
            File targetDir = new File(dirPath);
            
            StringBuilder result = new StringBuilder();
            result.append("📂 Output path set: ").append(inferredOutputPath).append("\n");
            result.append("💭 Reasoning: ").append(pathReasoning).append("\n");
            
            if (targetDir.exists()) {
                result.append("✅ Target directory exists: ").append(dirPath).append("\n");
            } else {
                result.append("📁 Target directory will be created: ").append(dirPath).append("\n");
            }
            
            // Check if file already exists
            File targetFile = new File(inferredOutputPath);
            if (targetFile.exists()) {
                result.append("⚠️  Warning: File already exists - will be overwritten\n");
            } else {
                result.append("✨ New file will be created\n");
            }
            
            return result.toString();
        }
        
        public String getInferredOutputPath() {
            return inferredOutputPath;
        }
        
        public String getPathReasoning() {
            return pathReasoning;
        }
    }
}