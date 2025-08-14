package com.zps.zest.testgen.agents;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.ZestChatLanguageModel;
import com.zps.zest.langchain4j.util.LLMService;
import com.zps.zest.testgen.model.*;

import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ValidatorAgent extends StreamingBaseAgent {
    
    private static final Pattern IMPORT_PATTERN = Pattern.compile("import\\s+([\\w\\.\\*]+);");
    private static final Pattern CLASS_REFERENCE_PATTERN = Pattern.compile("\\b([A-Z][a-zA-Z0-9]*(?:\\.[A-Z][a-zA-Z0-9]*)*)\\b");
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```(?:java)?\\s*\\n(.*?)```", Pattern.DOTALL);
    
    private final ValidationAssistant assistant;
    
    /**
     * Interface for the AI assistant that validates and fixes tests
     */
    interface ValidationAssistant {
        @SystemMessage(
            "You are a test validation assistant. " +
            "Your job is to validate generated tests and fix compilation errors. " +
            "When you find issues, provide fixes in this format:\n" +
            "ISSUES_FOUND:\n" +
            "- list issues\n" +
            "FIXES_APPLIED:\n" +
            "- list fixes\n" +
            "FIXED_CODE:\n" +
            "[corrected Java code]\n\n" +
            "Output ONLY clean, compilable Java code in the FIXED_CODE section."
        )
        String validateAndFix(String testCode);
        
        @SystemMessage(
            "Analyze the provided test code for compilation errors. " +
            "Output ONLY a list of errors or 'No errors found'."
        )
        String analyzeErrors(String testCode);
    }
    
    public ValidatorAgent(@NotNull Project project,
                         @NotNull ZestLangChain4jService langChainService,
                         @NotNull LLMService llmService) {
        super(project, langChainService, llmService, "ValidatorAgent");
        
        // Create AI assistant using AiServices
        ChatModel model = new ZestChatLanguageModel(llmService);
        this.assistant = AiServices.builder(ValidationAssistant.class)
                .chatModel(model)
            .chatMemory(MessageWindowChatMemory.withMaxMessages(5))
            .build();
    }
    
    /**
     * Validate and fix generated tests using AI Services
     */
    @NotNull
    public CompletableFuture<ValidationResult> validateTests(@NotNull List<GeneratedTest> tests, @NotNull TestContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOG.info("[ValidatorAgent] Validating " + tests.size() + " generated tests");
                notifyStream("\n🔍 Validating generated tests...\n");
                
                List<ValidationResult.ValidationIssue> allIssues = new ArrayList<>();
                List<GeneratedTest> fixedTests = new ArrayList<>();
                List<String> appliedFixes = new ArrayList<>();
                
                for (GeneratedTest test : tests) {
                    // Perform quick local validation first
                    List<String> localIssues = performLocalValidation(test);
                    
                    if (localIssues.isEmpty()) {
                        // No issues found locally
                        fixedTests.add(test);
                        continue;
                    }
                    
                    // Build test code for validation
                    String testCode = test.getFullContent();
                    
                    // Use AI to validate and fix
                    String validationResult = assistant.validateAndFix(testCode);
                    
                    // Parse and process results
                    ValidationResult testResult = parseValidationResult(test, validationResult);
                    
                    allIssues.addAll(testResult.getIssues());
                    if (testResult.hasFixedTests()) {
                        fixedTests.addAll(testResult.getFixedTests());
                        appliedFixes.addAll(testResult.getAppliedFixes());
                    } else {
                        fixedTests.add(test); // No fixes needed
                    }
                }
                
                boolean successful = allIssues.stream()
                    .noneMatch(issue -> issue.getSeverity() == ValidationResult.ValidationIssue.Severity.ERROR);
                
                LOG.info("[ValidatorAgent] Validation completed. Issues: " + allIssues.size() + ", Fixed tests: " + fixedTests.size());
                notifyStream("✅ Validation complete\n");
                
                return new ValidationResult(successful, allIssues, fixedTests, appliedFixes);
                
            } catch (Exception e) {
                LOG.error("[ValidatorAgent] Validation failed", e);
                throw new RuntimeException("Test validation failed: " + e.getMessage());
            }
        });
    }
    
    /**
     * Perform local validation without LLM
     */
    private List<String> performLocalValidation(@NotNull GeneratedTest test) {
        List<String> issues = new ArrayList<>();
        String testCode = test.getFullContent();
        
        // Check imports
        List<String> importIssues = validateImports(testCode);
        issues.addAll(importIssues);
        
        // Check syntax structure
        List<String> syntaxIssues = validateSyntaxStructure(testCode);
        issues.addAll(syntaxIssues);
        
        // Check test framework usage
        List<String> frameworkIssues = validateTestFrameworkUsage(testCode);
        issues.addAll(frameworkIssues);
        
        return issues;
    }
    
    /**
     * Strip markdown formatting from text
     */
    private String stripMarkdown(@NotNull String text) {
        // Remove code blocks
        text = text.replaceAll("```(?:java)?\\s*\\n", "");
        text = text.replaceAll("\\n?```\\s*", "");
        
        // Remove backticks
        text = text.replaceAll("^`+|`+$", "");
        
        // Remove bold/italic
        text = text.replaceAll("\\*\\*(.*?)\\*\\*", "$1");
        text = text.replaceAll("__(.*?)__", "$1");
        text = text.replaceAll("\\*(.*?)\\*", "$1");
        text = text.replaceAll("_(.*?)_", "$1");
        
        return text.trim();
    }
    
    /**
     * Extract code from markdown code blocks
     */
    private String extractCodeFromMarkdown(@NotNull String text) {
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        // If no code blocks, strip markdown and return
        return stripMarkdown(text);
    }
    
    private List<String> validateImports(@NotNull String testCode) {
        List<String> issues = new ArrayList<>();
        
        Matcher importMatcher = IMPORT_PATTERN.matcher(testCode);
        while (importMatcher.find()) {
            String importStmt = importMatcher.group(1);
            
            // Check if it's a valid import
            if (importStmt.startsWith("java.") || importStmt.startsWith("javax.")) {
                continue; // Standard Java imports are assumed valid
            }
            
            // Check for common test framework imports
            if (isValidTestFrameworkImport(importStmt)) {
                continue;
            }
            
            // Check if class exists in project
            if (!doesClassExistInProject(importStmt)) {
                issues.add("Import not found: " + importStmt);
            }
        }
        
        // Check for missing essential imports
        if (testCode.contains("@Test") && !testCode.contains("import org.junit")) {
            issues.add("Missing JUnit import for @Test annotation");
        }
        
        if (testCode.contains("assertEquals") && !testCode.contains("import") && !testCode.contains("Assertions")) {
            issues.add("Missing assertion imports");
        }
        
        return issues;
    }
    
    private List<String> validateClassReferences(@NotNull String testCode) {
        List<String> issues = new ArrayList<>();
        
        Matcher classMatcher = CLASS_REFERENCE_PATTERN.matcher(testCode);
        Set<String> checkedClasses = new HashSet<>();
        
        while (classMatcher.find()) {
            String className = classMatcher.group(1);
            
            // Skip if already checked
            if (checkedClasses.contains(className)) {
                continue;
            }
            checkedClasses.add(className);
            
            // Skip common Java classes and primitives
            if (isStandardJavaClass(className)) {
                continue;
            }
            
            // Skip test framework classes
            if (isTestFrameworkClass(className)) {
                continue;
            }
            
            // Check if class exists
            if (!doesClassExistInProject(className) && !hasImportForClass(testCode, className)) {
                issues.add("Class not found or not imported: " + className);
            }
        }
        
        return issues;
    }
    
    private List<String> validateSyntaxStructure(@NotNull String testCode) {
        List<String> issues = new ArrayList<>();
        
        // Check basic syntax structure
        int braceCount = countCharacter(testCode, '{') - countCharacter(testCode, '}');
        if (braceCount != 0) {
            issues.add("Unmatched braces (difference: " + braceCount + ")");
        }
        
        int parenCount = countCharacter(testCode, '(') - countCharacter(testCode, ')');
        if (parenCount != 0) {
            issues.add("Unmatched parentheses (difference: " + parenCount + ")");
        }
        
        // Check for basic Java structure
        if (!testCode.contains("class ")) {
            issues.add("No class declaration found");
        }
        
        if (testCode.contains("@Test") && !testCode.contains("void ") && !testCode.contains("public ")) {
            issues.add("Test method should be public void");
        }
        
        return issues;
    }
    
    private List<String> validateTestFrameworkUsage(@NotNull String testCode) {
        List<String> issues = new ArrayList<>();
        
        // Check for proper test method structure
        if (testCode.contains("@Test")) {
            if (!testCode.contains("void ")) {
                issues.add("Test methods should return void");
            }
        }
        
        // Check for assertion usage
        boolean hasAssertions = testCode.contains("assert") || 
                               testCode.contains("assertEquals") || 
                               testCode.contains("assertTrue") ||
                               testCode.contains("assertThat");
        
        if (testCode.contains("@Test") && !hasAssertions && !testCode.contains("fail(")) {
            issues.add("Test method should contain assertions or explicit failure");
        }
        
        return issues;
    }
    
    private boolean isValidTestFrameworkImport(@NotNull String importStmt) {
        return importStmt.startsWith("org.junit.") ||
               importStmt.startsWith("org.testng.") ||
               importStmt.startsWith("org.mockito.") ||
               importStmt.startsWith("org.hamcrest.") ||
               importStmt.startsWith("static org.junit.") ||
               importStmt.startsWith("static org.mockito.") ||
               importStmt.startsWith("static org.hamcrest.");
    }
    
    private boolean doesClassExistInProject(@NotNull String className) {
        try {
            // Try to find the class in the project
            PsiClass[] classes = PsiShortNamesCache.getInstance(project)
                .getClassesByName(getSimpleClassName(className), GlobalSearchScope.allScope(project));
            
            return classes.length > 0;
            
        } catch (Exception e) {
            LOG.debug("Error checking class existence: " + className, e);
            return true; // Assume it exists if we can't check
        }
    }
    
    private boolean isStandardJavaClass(@NotNull String className) {
        return className.equals("String") || 
               className.equals("Object") ||
               className.equals("Integer") ||
               className.equals("Long") ||
               className.equals("Double") ||
               className.equals("Boolean") ||
               className.equals("List") ||
               className.equals("Map") ||
               className.equals("Set") ||
               className.equals("Collection") ||
               className.length() == 1; // Single letter (generics)
    }
    
    private boolean isTestFrameworkClass(@NotNull String className) {
        return className.equals("Test") ||
               className.equals("Mock") ||
               className.equals("Before") ||
               className.equals("After") ||
               className.equals("BeforeEach") ||
               className.equals("AfterEach") ||
               className.equals("Assertions") ||
               className.equals("MockitoAnnotations");
    }
    
    private boolean hasImportForClass(@NotNull String testCode, @NotNull String className) {
        return testCode.contains("import ") && testCode.contains(className);
    }
    
    private String getSimpleClassName(@NotNull String fullClassName) {
        int lastDot = fullClassName.lastIndexOf('.');
        return lastDot >= 0 ? fullClassName.substring(lastDot + 1) : fullClassName;
    }
    
    private int countCharacter(@NotNull String text, char ch) {
        return (int) text.chars().filter(c -> c == ch).count();
    }
    
    private ValidationResult parseValidationResult(@NotNull GeneratedTest test, @NotNull String validationResult) {
        try {
            List<ValidationResult.ValidationIssue> issues = new ArrayList<>();
            List<GeneratedTest> fixedTests = new ArrayList<>();
            List<String> appliedFixes = new ArrayList<>();
            
            String[] lines = validationResult.split("\n");
            String currentSection = "";
            StringBuilder fixedCode = new StringBuilder();
            
            for (String line : lines) {
                line = line.trim();
                
                if (line.equals("ISSUES_FOUND:")) {
                    currentSection = "ISSUES";
                } else if (line.equals("FIXES_APPLIED:")) {
                    currentSection = "FIXES";
                } else if (line.equals("FIXED_CODE:")) {
                    currentSection = "CODE";
                } else if (!line.isEmpty()) {
                    switch (currentSection) {
                        case "ISSUES":
                            if (line.startsWith("- ")) {
                                issues.add(parseIssue(line.substring(2), test.getTestName()));
                            }
                            break;
                        case "FIXES":
                            if (line.startsWith("- ")) {
                                appliedFixes.add(line.substring(2));
                            }
                            break;
                        case "CODE":
                            fixedCode.append(line).append("\n");
                            break;
                    }
                }
            }
            
            // If we have fixed code, create a new GeneratedTest with fixes
            if (fixedCode.length() > 0) {
                String cleanedCode = extractCodeFromMarkdown(fixedCode.toString());
                GeneratedTest fixedTest = createFixedTest(test, cleanedCode);
                fixedTests.add(fixedTest);
            }
            
            boolean successful = issues.stream().noneMatch(issue -> issue.getSeverity() == ValidationResult.ValidationIssue.Severity.ERROR);
            
            return new ValidationResult(successful, issues, fixedTests, appliedFixes);
            
        } catch (Exception e) {
            LOG.error("[ValidatorAgent] Failed to parse validation result", e);
            // Return result with error
            List<ValidationResult.ValidationIssue> errorIssues = List.of(
                new ValidationResult.ValidationIssue(
                    test.getTestName(),
                    "Validation parsing failed: " + e.getMessage(),
                    ValidationResult.ValidationIssue.Severity.ERROR,
                    "Manual review required",
                    false
                )
            );
            return new ValidationResult(false, errorIssues, List.of(), List.of());
        }
    }
    
    private ValidationResult.ValidationIssue parseIssue(@NotNull String issueText, @NotNull String testName) {
        ValidationResult.ValidationIssue.Severity severity = ValidationResult.ValidationIssue.Severity.WARNING;
        boolean fixable = true;
        
        // Determine severity based on keywords
        String lowerIssue = issueText.toLowerCase();
        if (lowerIssue.contains("error") || lowerIssue.contains("not found") || lowerIssue.contains("missing")) {
            severity = ValidationResult.ValidationIssue.Severity.ERROR;
        } else if (lowerIssue.contains("warning") || lowerIssue.contains("should")) {
            severity = ValidationResult.ValidationIssue.Severity.WARNING;
        }
        
        // Determine if fixable
        if (lowerIssue.contains("manual") || lowerIssue.contains("cannot")) {
            fixable = false;
        }
        
        String fixSuggestion = generateFixSuggestion(issueText);
        
        return new ValidationResult.ValidationIssue(testName, issueText, severity, fixSuggestion, fixable);
    }
    
    private String generateFixSuggestion(@NotNull String issueText) {
        String lowerIssue = issueText.toLowerCase();
        
        if (lowerIssue.contains("import not found")) {
            return "Add correct import statement or verify class path";
        } else if (lowerIssue.contains("class not found")) {
            return "Add import statement or verify class exists";
        } else if (lowerIssue.contains("unmatched")) {
            return "Check and balance brackets/parentheses";
        } else if (lowerIssue.contains("missing assertion")) {
            return "Add appropriate assertion or fail() statement";
        } else {
            return "Review and correct the identified issue";
        }
    }
    
    private GeneratedTest createFixedTest(@NotNull GeneratedTest original, @NotNull String fixedCode) {
        // Parse fixed code to extract components
        List<String> newImports = extractImportsFromCode(fixedCode);
        String newTestContent = extractClassContentFromCode(fixedCode);
        
        return new GeneratedTest(
            original.getTestName(),
            original.getTestClassName(),
            newTestContent,
            original.getScenario(),
            original.getFileName(),
            original.getPackageName(),
            newImports.isEmpty() ? original.getImports() : newImports,
            original.getAnnotations(),
            original.getFramework()
        );
    }
    
    private List<String> extractImportsFromCode(@NotNull String code) {
        List<String> imports = new ArrayList<>();
        Matcher importMatcher = IMPORT_PATTERN.matcher(code);
        
        while (importMatcher.find()) {
            imports.add(importMatcher.group(1));
        }
        
        return imports;
    }
    
    private String extractClassContentFromCode(@NotNull String code) {
        // First strip any markdown
        code = stripMarkdown(code);
        
        // Remove package and import statements, keep only class content
        String[] lines = code.split("\n");
        StringBuilder classContent = new StringBuilder();
        boolean inClassContent = false;
        
        for (String line : lines) {
            if (line.trim().startsWith("package ") || line.trim().startsWith("import ")) {
                continue;
            }
            if (line.trim().contains("class ") || inClassContent) {
                inClassContent = true;
                classContent.append(line).append("\n");
            }
        }
        
        return classContent.toString().trim();
    }
    
    // Simplified required overrides for StreamingBaseAgent
    @NotNull
    @Override
    protected AgentAction determineAction(@NotNull String reasoning, @NotNull String observation) {
        // Not used with AiServices pattern
        return new AgentAction(AgentAction.ActionType.COMPLETE, "Validation completed", reasoning);
    }
    
    @NotNull
    @Override
    protected String executeAction(@NotNull AgentAction action) {
        // Not used with AiServices pattern
        return action.getParameters();
    }
    
    @NotNull
    @Override
    protected String getAgentDescription() {
        return "a code validation agent that ensures generated tests are compilable, correct, and follow best practices";
    }
    
    @NotNull
    @Override
    protected List<AgentAction.ActionType> getAvailableActions() {
        return Arrays.asList(
            AgentAction.ActionType.ANALYZE,
            AgentAction.ActionType.VALIDATE,
            AgentAction.ActionType.GENERATE,
            AgentAction.ActionType.COMPLETE
        );
    }

}