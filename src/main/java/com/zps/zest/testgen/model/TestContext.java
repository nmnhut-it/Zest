package com.zps.zest.testgen.model;

import com.zps.zest.langchain4j.ZestLangChain4jService.ContextItem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

public class TestContext {
    private final List<ContextItem> codeContext;
    private final List<String> relatedFiles;
    private final Map<String, String> dependencies;
    private final List<String> existingTestPatterns;
    private final String frameworkInfo;
    private final Map<String, Object> additionalMetadata;
    
    // NEW: Store the actual implementation code
    private final String targetClassCode;
    private final String targetMethodCode;
    
    // NEW: Store imports from analyzed classes
    private final Set<String> collectedImports;
    
    // Cache for built context strings
    private String cachedFullContext = null;
    private String cachedMergerContext = null;
    
    public TestContext(@NotNull List<ContextItem> codeContext,
                      @NotNull List<String> relatedFiles,
                      @NotNull Map<String, String> dependencies,
                      @NotNull List<String> existingTestPatterns,
                      @NotNull String frameworkInfo,
                      @NotNull Map<String, Object> additionalMetadata) {
        this(codeContext, relatedFiles, dependencies, existingTestPatterns, 
             frameworkInfo, additionalMetadata, null, null, new HashSet<>());
    }
    
    public TestContext(@NotNull List<ContextItem> codeContext,
                      @NotNull List<String> relatedFiles,
                      @NotNull Map<String, String> dependencies,
                      @NotNull List<String> existingTestPatterns,
                      @NotNull String frameworkInfo,
                      @NotNull Map<String, Object> additionalMetadata,
                      @Nullable String targetClassCode,
                      @Nullable String targetMethodCode,
                      @NotNull Set<String> collectedImports) {
        this.codeContext = new ArrayList<>(codeContext);
        this.relatedFiles = new ArrayList<>(relatedFiles);
        this.dependencies = Map.copyOf(dependencies);
        this.existingTestPatterns = new ArrayList<>(existingTestPatterns);
        this.frameworkInfo = frameworkInfo;
        this.additionalMetadata = Map.copyOf(additionalMetadata);
        this.targetClassCode = targetClassCode;
        this.targetMethodCode = targetMethodCode;
        this.collectedImports = new HashSet<>(collectedImports);
    }
    
    @NotNull
    public List<ContextItem> getCodeContext() {
        return new ArrayList<>(codeContext);
    }
    
    @NotNull
    public List<String> getRelatedFiles() {
        return new ArrayList<>(relatedFiles);
    }
    
    @NotNull
    public Map<String, String> getDependencies() {
        return Map.copyOf(dependencies);
    }
    
    @NotNull
    public List<String> getExistingTestPatterns() {
        return new ArrayList<>(existingTestPatterns);
    }
    
    @NotNull
    public String getFrameworkInfo() {
        return frameworkInfo;
    }
    
    @NotNull
    public Map<String, Object> getAdditionalMetadata() {
        return Map.copyOf(additionalMetadata);
    }
    
    @Nullable
    public String getTargetClassCode() {
        return targetClassCode;
    }
    
    @Nullable
    public String getTargetMethodCode() {
        return targetMethodCode;
    }
    
    @NotNull
    public Set<String> getCollectedImports() {
        return new HashSet<>(collectedImports);
    }
    
    public boolean hasTestFramework() {
        return !frameworkInfo.isEmpty();
    }
    
    public boolean hasExistingPatterns() {
        return !existingTestPatterns.isEmpty();
    }
    
    public boolean hasExistingTests() {
        // Check if we have existing test patterns or test files in related files
        return !existingTestPatterns.isEmpty() || 
               relatedFiles.stream().anyMatch(f -> f.contains("Test") || f.contains("test"));
    }
    
    public int getContextItemCount() {
        return codeContext.size();
    }
    
    @Override
    public String toString() {
        return "TestContext{" +
               "codeContextItems=" + codeContext.size() +
               ", relatedFiles=" + relatedFiles.size() +
               ", dependencies=" + dependencies.size() +
               ", existingPatterns=" + existingTestPatterns.size() +
               ", hasFramework=" + hasTestFramework() +
               ", hasTargetClassCode=" + (targetClassCode != null) +
               ", hasTargetMethodCode=" + (targetMethodCode != null) +
               ", collectedImports=" + collectedImports.size() +
               '}';
    }
    
    /**
     * Build TestContext from gathered data (moved from ContextAgent)
     */
    @NotNull
    public static TestContext fromGatheredData(@NotNull Map<String, Object> gatheredData,
                                               @NotNull String targetPath,
                                               @NotNull String targetFileName) {
        // Extract gathered data
        @SuppressWarnings("unchecked")
        Map<String, String> analyzedClasses = (Map<String, String>) gatheredData.getOrDefault("analyzedClasses", new java.util.HashMap<>());
        @SuppressWarnings("unchecked")
        List<String> contextNotes = (List<String>) gatheredData.getOrDefault("contextNotes", new ArrayList<>());
        @SuppressWarnings("unchecked")
        Map<String, String> readFiles = (Map<String, String>) gatheredData.getOrDefault("readFiles", new java.util.HashMap<>());

        // Find target class code
        String targetClassCode = null;
        String targetMethodCode = null;

        for (Map.Entry<String, String> entry : analyzedClasses.entrySet()) {
            if (entry.getKey().contains(targetPath)) {
                targetClassCode = entry.getValue();
                break;
            }
        }

        // Convert to context items and extract imports
        List<ContextItem> contextItems = new ArrayList<>();
        Set<String> collectedImports = new HashSet<>();
        
        // Add analyzed Java classes and extract imports
        for (Map.Entry<String, String> entry : analyzedClasses.entrySet()) {
            String classContent = entry.getValue();
            contextItems.add(new ContextItem(
                    "class-" + entry.getKey().hashCode(),
                    entry.getKey(),
                    classContent,
                    entry.getKey(),
                    null,
                    entry.getKey().contains(targetPath) ? 1.0 : 0.8
            ));
            
            // Extract imports from class content
            extractImportsFromContent(classContent, collectedImports);
        }
        
        // Add read files (configs, scripts, etc.)
        for (Map.Entry<String, String> entry : readFiles.entrySet()) {
            contextItems.add(new ContextItem(
                    "file-" + entry.getKey().hashCode(),
                    entry.getKey(),
                    entry.getValue(),
                    entry.getKey(),
                    null,
                    0.7  // Lower relevance score for non-Java files
            ));
        }
        
        // Add context notes as a special context item
        if (!contextNotes.isEmpty()) {
            String notesContent = "=== Context Gathering Notes ===\n" + 
                                String.join("\n\n", contextNotes);
            contextItems.add(new ContextItem(
                    "notes",
                    "Context Notes",
                    notesContent,
                    "Agent Notes",
                    null,
                    0.9  // High relevance for insights
            ));
        }

        // Simple pattern detection
        List<String> patterns = new ArrayList<>();
        String allContent = String.join("\n", analyzedClasses.values());
        if (allContent.contains("@Test")) patterns.add("JUnit tests");
        if (allContent.contains("@Mock")) patterns.add("Mockito");
        if (allContent.contains("assertEquals")) patterns.add("Assertions");
        
        // Build metadata
        Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put("targetFile", targetFileName);
        metadata.put("gatheredData", gatheredData);
        // Add read files to metadata for CoordinatorAgent to use
        if (readFiles != null && !readFiles.isEmpty()) {
            metadata.put("gatheredFiles", new java.util.HashMap<>(readFiles));
        }

        return new TestContext(
                contextItems,
                new ArrayList<>(analyzedClasses.keySet()),
                new java.util.HashMap<>(),
                patterns,
                "JUnit 5",
                metadata,
                targetClassCode,
                targetMethodCode,
                collectedImports
        );
    }

    /**
     * Build context info for TestWriterAgent - handles all scenarios at once
     */
    @NotNull
    public String buildTestWriterContext(@NotNull String targetClass, @NotNull List<String> targetMethod,
                                         @NotNull List<TestPlan.TestScenario> scenarios) {
        StringBuilder info = new StringBuilder();
        
        info.append("Test Generation Context:\n");
        info.append("Target Class: ").append(targetClass).append("\n");
        info.append("Target Method: ").append(targetMethod.stream().collect(Collectors.joining(", "))).append("\n");
        info.append("Testing Framework: ").append(frameworkInfo).append("\n\n");
        
        // Add all scenarios information
        if (scenarios != null && !scenarios.isEmpty()) {
            info.append("=== ALL TEST SCENARIOS TO IMPLEMENT (").append(scenarios.size()).append(" total) ===\n");
            for (int i = 0; i < scenarios.size(); i++) {
                TestPlan.TestScenario scenario = scenarios.get(i);
                info.append("\n").append(i + 1).append(". ").append(scenario.getName()).append("\n");
                info.append("   Type: ").append(scenario.getType().getDisplayName()).append("\n");
                info.append("   Priority: ").append(scenario.getPriority().getDisplayName()).append("\n");
                info.append("   Description: ").append(scenario.getDescription()).append("\n");
                if (!scenario.getInputs().isEmpty()) {
                    info.append("   Expected Inputs: ").append(String.join(", ", scenario.getInputs())).append("\n");
                }
                info.append("   Expected Outcome: ").append(scenario.getExpectedOutcome()).append("\n");
            }
            info.append("\n");
        }
        
        // Extract gathered data from metadata to get ALL analyzed classes and files
        Map<String, String> allAnalyzedClasses = new HashMap<>();
        Map<String, String> allReadFiles = new HashMap<>();
        List<String> allContextNotes = new ArrayList<>();
        
        if (additionalMetadata != null && additionalMetadata.containsKey("gatheredData")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> gatheredData = (Map<String, Object>) additionalMetadata.get("gatheredData");
            if (gatheredData != null) {
                // Get all analyzed classes
                @SuppressWarnings("unchecked")
                Map<String, String> analyzedClasses = (Map<String, String>) gatheredData.getOrDefault("analyzedClasses", new HashMap<>());
                allAnalyzedClasses.putAll(analyzedClasses);
                
                // Get all read files (configs, scripts, etc.)
                @SuppressWarnings("unchecked")
                Map<String, String> readFiles = (Map<String, String>) gatheredData.getOrDefault("readFiles", new HashMap<>());
                allReadFiles.putAll(readFiles);
                
                // Get context notes
                @SuppressWarnings("unchecked")
                List<String> contextNotes = (List<String>) gatheredData.getOrDefault("contextNotes", new ArrayList<>());
                allContextNotes.addAll(contextNotes);
            }
        }
        
        // Add context notes first as they contain important insights
        if (!allContextNotes.isEmpty()) {
            info.append("=== CONTEXT INSIGHTS ===\n");
            for (String note : allContextNotes) {
                info.append("• ").append(note).append("\n");
            }
            info.append("\n");
        }
        
        // Note: Target class is already included via preAnalyzeClass in the request, no need to duplicate here
        
        // Add method-specific implementation if available
        if (targetMethodCode != null && !targetMethodCode.isEmpty()) {
            info.append("Target Method Implementation:\n");
            info.append("```java\n");
            info.append(targetMethodCode);
            info.append("\n```\n\n");
        }
        
        // Add relevant dependencies with their implementations
        if (!dependencies.isEmpty()) {
            info.append("=== DEPENDENCIES ===\n");
            dependencies.forEach((key, value) -> {
                info.append("- ").append(key).append(" (").append(value).append(")\n");
                
                // Try to find dependency implementation in context
                for (ContextItem item : codeContext) {
                    if (item.getContent().contains("class " + key) || 
                        item.getContent().contains("interface " + key)) {
                        info.append("  Implementation:\n```java\n");
                        info.append(item.getContent());
                        info.append("\n```\n");
                        break;
                    }
                }
            });
            info.append("\n");
        }
        
        // Add existing test patterns with full examples
        if (!existingTestPatterns.isEmpty()) {
            info.append("=== EXISTING TEST PATTERNS TO FOLLOW ===\n");
            for (String pattern : existingTestPatterns) {
                info.append("Pattern: ").append(pattern).append("\n");
            }
            
            // Add actual test examples if available
            for (ContextItem item : codeContext) {
                if (item.getFilePath().contains("Test.java") || item.getFilePath().contains("test/")) {
                    info.append("\nExample Test from ").append(item.getFilePath()).append(":\n");
                    info.append("```java\n");
                    info.append(item.getContent());
                    info.append("\n```\n");
                    break; // Just show one example to avoid too much context
                }
            }
            info.append("\n");
        }
        
        // Add ALL analyzed classes from gathered data
        if (!allAnalyzedClasses.isEmpty()) {
            info.append("=== ALL ANALYZED CLASSES ===\n");
            for (Map.Entry<String, String> entry : allAnalyzedClasses.entrySet()) {
                info.append("From ").append(entry.getKey()).append(":\n");
                // Analyzed classes are already well-formatted, don't wrap in backticks
                info.append(entry.getValue());
                info.append("\n\n");
            }
        }
        
        // Add configuration files and scripts from gathered data
        if (!allReadFiles.isEmpty()) {
            info.append("=== CONFIGURATION AND RESOURCE FILES ===\n");
            for (Map.Entry<String, String> entry : allReadFiles.entrySet()) {
                String fileName = entry.getKey();
                String content = entry.getValue();
                
                // Determine file type for syntax highlighting
                String syntaxType = "text";
                if (fileName.endsWith(".properties")) syntaxType = "properties";
                else if (fileName.endsWith(".xml")) syntaxType = "xml";
                else if (fileName.endsWith(".json")) syntaxType = "json";
                else if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) syntaxType = "yaml";
                else if (fileName.endsWith(".sql")) syntaxType = "sql";
                else if (fileName.endsWith(".sh")) syntaxType = "bash";
                
                info.append("File: ").append(fileName).append("\n");
                info.append("```").append(syntaxType).append("\n");
                info.append(content);
                info.append("\n```\n\n");
            }
        }
        
        // Add other relevant code context (related classes, utilities, etc.) - Include ALL, not just 3
        if (!codeContext.isEmpty()) {
            info.append("=== OTHER RELEVANT CODE FROM CONTEXT ===\n");
            for (ContextItem item : codeContext) {
                // Skip if already included above
                if (item.getFilePath().contains(targetClass.replace(".", "/")) ||
                    item.getFilePath().contains("Test.java")) {
                    continue;
                }
                
                // Check if already included in analyzed classes
                boolean alreadyIncluded = false;
                for (String analyzedPath : allAnalyzedClasses.keySet()) {
                    if (analyzedPath.equals(item.getFilePath())) {
                        alreadyIncluded = true;
                        break;
                    }
                }
                if (alreadyIncluded) continue;
                
                info.append("From ").append(item.getFilePath()).append(":\n");
                info.append("```java\n");
                info.append(item.getContent());
                info.append("\n```\n\n");
            }
        }
        
        return info.toString();
    }
    
    private static String getSimpleClassName(String fullClassName) {
        int lastDot = fullClassName.lastIndexOf('.');
        return lastDot >= 0 ? fullClassName.substring(lastDot + 1) : fullClassName;
    }
    
    /**
     * Extract import statements from Java code content
     */
    private static void extractImportsFromContent(String content, Set<String> imports) {
        if (content == null || content.isEmpty()) return;
        
        String[] lines = content.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("import ")) {
                // Extract the import statement
                String importStmt = line.substring(7); // Remove "import "
                importStmt = importStmt.replace(";", "").trim();
                if (!importStmt.isEmpty()) {
                    imports.add(importStmt);
                }
            } else if (line.startsWith("package ")) {
                // Extract package to help infer imports for classes in same package
                String pkg = line.substring(8).replace(";", "").trim();
                if (!pkg.isEmpty()) {
                    imports.add("_package:" + pkg); // Special marker for package
                }
            }
        }
    }
    
    /**
     * Build context string for TestMergerAgent with import information
     */
    @NotNull
    public String buildMergerContext() {
        if (cachedMergerContext != null) {
            return cachedMergerContext;
        }
        
        StringBuilder mergerContext = new StringBuilder();
        
        mergerContext.append("=== TEST MERGING CONTEXT ===\n\n");
        
        // Add collected imports
        if (!collectedImports.isEmpty()) {
            mergerContext.append("=== IMPORTS FROM ANALYZED CLASSES ===\n");
            Set<String> regularImports = new HashSet<>();
            String targetPackage = null;
            
            for (String imp : collectedImports) {
                if (imp.startsWith("_package:")) {
                    targetPackage = imp.substring(9);
                } else {
                    regularImports.add(imp);
                }
            }
            
            if (targetPackage != null) {
                mergerContext.append("Target package: ").append(targetPackage).append("\n");
            }
            
            for (String imp : regularImports) {
                mergerContext.append("import ").append(imp).append(";\n");
            }
            mergerContext.append("\n");
        }
        
        // Add dependencies that may require imports
        if (!dependencies.isEmpty()) {
            mergerContext.append("=== DEPENDENCIES REQUIRING IMPORTS ===\n");
            for (Map.Entry<String, String> dep : dependencies.entrySet()) {
                mergerContext.append("- ").append(dep.getKey());
                if (!dep.getValue().isEmpty()) {
                    mergerContext.append(" (").append(dep.getValue()).append(")");
                }
                mergerContext.append("\n");
            }
            mergerContext.append("\n");
        }
        
        // Add test framework and patterns
        mergerContext.append("=== TEST FRAMEWORK AND PATTERNS ===\n");
        mergerContext.append("Framework: ").append(frameworkInfo).append("\n");
        if (!existingTestPatterns.isEmpty()) {
            mergerContext.append("Patterns detected: ").append(String.join(", ", existingTestPatterns)).append("\n");
        }
        mergerContext.append("\n");
        
        // Add class and package information from context
        mergerContext.append("=== REFERENCED CLASSES ===\n");
        for (ContextItem item : codeContext) {
            String content = item.getContent();
            // Extract class declaration
            if (content.contains("class ") || content.contains("interface ") || content.contains("enum ")) {
                String[] lines = content.split("\n");
                for (String line : lines) {
                    if (line.contains("public class ") || line.contains("public interface ") || 
                        line.contains("public enum ") || line.contains("class ") || 
                        line.contains("interface ") || line.contains("enum ")) {
                        mergerContext.append("From ").append(item.getFilePath()).append(": ");
                        mergerContext.append(line.trim()).append("\n");
                        break;
                    }
                }
            }
        }
        mergerContext.append("\n");
        
        cachedMergerContext = mergerContext.toString();
        return cachedMergerContext;
    }
}