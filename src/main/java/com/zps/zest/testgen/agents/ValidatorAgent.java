package com.zps.zest.testgen.agents;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.util.LLMService;
import com.zps.zest.testgen.model.*;

import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ValidatorAgent extends BaseAgent {
    
    private static final Pattern IMPORT_PATTERN = Pattern.compile("import\\s+([\\w\\.\\*]+);");
    private static final Pattern CLASS_REFERENCE_PATTERN = Pattern.compile("\\b([A-Z][a-zA-Z0-9]*(?:\\.[A-Z][a-zA-Z0-9]*)*)\\b");
    
    public ValidatorAgent(@NotNull Project project,
                         @NotNull ZestLangChain4jService langChainService,
                         @NotNull LLMService llmService) {
        super(project, langChainService, llmService, "ValidatorAgent");
    }
    
    /**
     * Validate and fix generated tests
     */
    @NotNull
    public CompletableFuture<ValidationResult> validateTests(@NotNull List<GeneratedTest> tests, @NotNull TestContext context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOG.info("[ValidatorAgent] Validating " + tests.size() + " generated tests");
                
                List<ValidationResult.ValidationIssue> allIssues = new ArrayList<>();
                List<GeneratedTest> fixedTests = new ArrayList<>();
                List<String> appliedFixes = new ArrayList<>();
                
                for (GeneratedTest test : tests) {
                    String task = "Validate and fix test code for compilation errors, missing imports, and code quality issues. " +
                                 "Test: " + test.getTestName() + " in class " + test.getTestClassName();
                    
                    String testInfo = buildTestValidationContext(test, context);
                    
                    // Execute ReAct workflow for validation
                    String validationResult = executeReActTask(task, testInfo).join();
                    
                    // Parse validation results
                    ValidationResult testResult = parseValidationResult(test, validationResult, context);
                    
                    allIssues.addAll(testResult.getIssues());
                    if (testResult.hasFixedTests()) {
                        fixedTests.addAll(testResult.getFixedTests());
                        appliedFixes.addAll(testResult.getAppliedFixes());
                    } else {
                        fixedTests.add(test); // No fixes needed
                    }
                }
                
                boolean successful = allIssues.stream().noneMatch(issue -> issue.getSeverity() == ValidationResult.ValidationIssue.Severity.ERROR);
                
                LOG.info("[ValidatorAgent] Validation completed. Issues: " + allIssues.size() + ", Fixed tests: " + fixedTests.size());
                
                return new ValidationResult(successful, allIssues, fixedTests, appliedFixes);
                
            } catch (Exception e) {
                LOG.error("[ValidatorAgent] Validation failed", e);
                throw new RuntimeException("Test validation failed: " + e.getMessage());
            }
        });
    }
    
    private String buildTestValidationContext(@NotNull GeneratedTest test, @NotNull TestContext context) {
        StringBuilder info = new StringBuilder();
        
        info.append("Test Validation Context:\n");
        info.append("Test Class: ").append(test.getTestClassName()).append("\n");
        info.append("Test Method: ").append(test.getTestName()).append("\n");
        info.append("Package: ").append(test.getPackageName()).append("\n");
        info.append("Framework: ").append(test.getFramework()).append("\n\n");
        
        info.append("Test Code to Validate:\n");
        info.append(test.getFullContent()).append("\n\n");
        
        info.append("Available Dependencies:\n");
        context.getDependencies().forEach((key, value) -> 
            info.append("- ").append(key).append(" (").append(value).append(")\n"));
        info.append("\n");
        
        return info.toString();
    }
    
    @NotNull
    @Override
    protected AgentAction determineAction(@NotNull String reasoning, @NotNull String observation) {
        String lowerReasoning = reasoning.toLowerCase();
        
        if (lowerReasoning.contains("analyze") || lowerReasoning.contains("check") || lowerReasoning.contains("examine")) {
            return new AgentAction(AgentAction.ActionType.ANALYZE, "Analyze code for issues", reasoning);
        } else if (lowerReasoning.contains("validate") || lowerReasoning.contains("verify") || lowerReasoning.contains("inspect")) {
            return new AgentAction(AgentAction.ActionType.VALIDATE, "Validate code compilation and correctness", reasoning);
        } else if (lowerReasoning.contains("fix") || lowerReasoning.contains("correct") || lowerReasoning.contains("repair")) {
            return new AgentAction(AgentAction.ActionType.GENERATE, "Generate fixes for identified issues", reasoning);
        } else if (lowerReasoning.contains("complete") || lowerReasoning.contains("done") || lowerReasoning.contains("finished")) {
            return new AgentAction(AgentAction.ActionType.COMPLETE, "Validation completed", reasoning);
        } else {
            return new AgentAction(AgentAction.ActionType.ANALYZE, "Analyze current validation state", reasoning);
        }
    }
    
    @NotNull
    @Override
    protected String executeAction(@NotNull AgentAction action) {
        switch (action.getType()) {
            case ANALYZE:
                return performCodeAnalysis(action.getParameters());
            case VALIDATE:
                return performCodeValidation(action.getParameters());
            case GENERATE:
                return generateCodeFixes(action.getParameters());
            case COMPLETE:
                return action.getParameters();
            default:
                return "Unknown action: " + action.getType();
        }
    }
    
    private String performCodeAnalysis(@NotNull String parameters) {
        String prompt = "Analyze the following test code for potential issues:\n\n" +
                       parameters + "\n\n" +
                       "Check for:\n" +
                       "1. Syntax errors and compilation issues\n" +
                       "2. Missing or incorrect imports\n" +
                       "3. Invalid method signatures\n" +
                       "4. Incorrect annotations\n" +
                       "5. Missing dependencies\n" +
                       "6. Code quality issues\n" +
                       "7. Best practice violations\n\n" +
                       "Provide a detailed analysis:";
        
        return queryLLM(prompt, 1500);
    }
    
    private String performCodeValidation(@NotNull String parameters) {
        StringBuilder validation = new StringBuilder();
        validation.append("Code Validation Results:\n\n");
        
        try {
            // Extract test code from parameters
            String testCode = extractTestCode(parameters);
            
            // Validate imports
            List<String> importIssues = validateImports(testCode);
            if (!importIssues.isEmpty()) {
                validation.append("Import Issues:\n");
                importIssues.forEach(issue -> validation.append("- ").append(issue).append("\n"));
                validation.append("\n");
            }
            
            // Validate class references
            List<String> classIssues = validateClassReferences(testCode);
            if (!classIssues.isEmpty()) {
                validation.append("Class Reference Issues:\n");
                classIssues.forEach(issue -> validation.append("- ").append(issue).append("\n"));
                validation.append("\n");
            }
            
            // Validate syntax structure
            List<String> syntaxIssues = validateSyntaxStructure(testCode);
            if (!syntaxIssues.isEmpty()) {
                validation.append("Syntax Issues:\n");
                syntaxIssues.forEach(issue -> validation.append("- ").append(issue).append("\n"));
                validation.append("\n");
            }
            
            // Validate test framework usage
            List<String> frameworkIssues = validateTestFrameworkUsage(testCode);
            if (!frameworkIssues.isEmpty()) {
                validation.append("Test Framework Issues:\n");
                frameworkIssues.forEach(issue -> validation.append("- ").append(issue).append("\n"));
                validation.append("\n");
            }
            
            if (importIssues.isEmpty() && classIssues.isEmpty() && syntaxIssues.isEmpty() && frameworkIssues.isEmpty()) {
                validation.append("✅ No validation issues found - code appears to be correct!\n");
            }
            
        } catch (Exception e) {
            LOG.warn("[ValidatorAgent] Code validation failed", e);
            validation.append("Validation failed: ").append(e.getMessage()).append("\n");
        }
        
        return validation.toString();
    }
    
    private String generateCodeFixes(@NotNull String parameters) {
        String prompt = "Based on the validation analysis, generate fixed code:\n\n" +
                       parameters + "\n\n" +
                       "Provide fixes for the identified issues. Format the response as:\n" +
                       "ISSUES_FOUND:\n" +
                       "[list of issues]\n" +
                       "FIXES_APPLIED:\n" +
                       "[list of fixes]\n" +
                       "FIXED_CODE:\n" +
                       "[complete fixed code]\n\n" +
                       "Fixed Code:";
        
        return queryLLM(prompt, 2000);
    }
    
    private String extractTestCode(@NotNull String parameters) {
        // Extract the actual test code from the parameters
        if (parameters.contains("Test Code to Validate:")) {
            int startIndex = parameters.indexOf("Test Code to Validate:") + "Test Code to Validate:".length();
            int endIndex = parameters.indexOf("\n\nAvailable Dependencies:");
            if (endIndex == -1) endIndex = parameters.length();
            return parameters.substring(startIndex, endIndex).trim();
        }
        return parameters;
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
    
    private ValidationResult parseValidationResult(@NotNull GeneratedTest test, @NotNull String validationResult, @NotNull TestContext context) {
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
                GeneratedTest fixedTest = createFixedTest(test, fixedCode.toString());
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