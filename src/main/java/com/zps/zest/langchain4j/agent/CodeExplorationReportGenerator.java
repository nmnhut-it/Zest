package com.zps.zest.langchain4j.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates comprehensive reports from code exploration results.
 */
public class CodeExplorationReportGenerator {
    private static final Logger LOG = Logger.getInstance(CodeExplorationReportGenerator.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    private final Project project;
    
    public CodeExplorationReportGenerator(@NotNull Project project) {
        this.project = project;
    }
    
    /**
     * Generates a comprehensive report from exploration results.
     * Works with both original and improved ToolCallingAutonomousAgent.
     */
    public CodeExplorationReport generateReport(
            String originalQuery,
            ToolCallingAutonomousAgent.ExplorationResult explorationResult) {
        return generateReportInternal(originalQuery, 
            new OriginalExplorationResultAdapter(explorationResult));
    }
    
    /**
     * Generates a comprehensive report from improved agent exploration results.
     */
    public CodeExplorationReport generateReport(
            String originalQuery,
            ImprovedToolCallingAutonomousAgent.ExplorationResult explorationResult) {
        return generateReportInternal(originalQuery, 
            new ImprovedExplorationResultAdapter(explorationResult));
    }
    
    /**
     * Internal method that works with the adapter interface.
     */
    private CodeExplorationReport generateReportInternal(
            String originalQuery,
            ExplorationResultAdapter explorationResult) {
        
        CodeExplorationReport report = new CodeExplorationReport();
        report.setOriginalQuery(originalQuery);
        report.setTimestamp(new Date());
        
        // Extract all discovered code elements
        Set<String> discoveredElements = extractDiscoveredElements(explorationResult);
        report.setDiscoveredElements(new ArrayList<>(discoveredElements));
        
        // Collect all code pieces
        List<CodeExplorationReport.CodePiece> codePieces = collectCodePieces(explorationResult);
        report.setCodePieces(codePieces);
        
        // Extract relationships
        Map<String, List<String>> relationships = extractRelationships(explorationResult);
        report.setRelationships(relationships);
        
        // Generate structured context
        String structuredContext = generateStructuredContext(codePieces, relationships);
        report.setStructuredContext(structuredContext);
        
        // Add exploration summary
        report.setExplorationSummary(explorationResult.getSummary());
        
        // Generate final comprehensive context for coding tasks
        String codingContext = generateCodingContext(report);
        report.setCodingContext(codingContext);
        
        return report;
    }
    
    /**
     * Extracts all discovered code elements from exploration results.
     */
    private Set<String> extractDiscoveredElements(ExplorationResultAdapter result) {
        Set<String> elements = new HashSet<>();
        
        for (ExplorationRoundAdapter round : result.getRounds()) {
            for (ToolExecutionAdapter execution : round.getToolExecutions()) {
                if (execution.isSuccess() && execution.getResult() != null) {
                    // Extract class names, method signatures, etc.
                    elements.addAll(extractElementsFromText(execution.getResult()));
                }
            }
        }
        
        return elements;
    }
    
    /**
     * Collects all code pieces discovered during exploration.
     */
    private List<CodeExplorationReport.CodePiece> collectCodePieces(ExplorationResultAdapter result) {
        Map<String, CodeExplorationReport.CodePiece> codePieceMap = new HashMap<>();
        
        for (ExplorationRoundAdapter round : result.getRounds()) {
            for (ToolExecutionAdapter execution : round.getToolExecutions()) {
                if (!execution.isSuccess()) continue;
                
                String toolName = execution.getToolName();
                String resultContent = execution.getResult();
                
                // Extract code from different tool results
                switch (toolName) {
                    case "read_file":
                        extractFileContent(execution, codePieceMap);
                        break;
                    case "find_methods":
                        extractMethodSignatures(execution, codePieceMap);
                        break;
                    case "search_code":
                    case "find_similar":
                        extractSearchResults(execution, codePieceMap);
                        break;
                    case "get_class_info":
                        extractClassInfo(execution, codePieceMap);
                        break;
                }
            }
        }
        
        return new ArrayList<>(codePieceMap.values());
    }
    
    /**
     * Extracts file content from read_file tool execution.
     */
    private void extractFileContent(ToolExecutionAdapter execution, 
                                  Map<String, CodeExplorationReport.CodePiece> codePieceMap) {
        JsonObject params = execution.getParameters();
        String filePath = params.has("filePath") ? params.get("filePath").getAsString() : null;
        
        if (filePath != null && execution.getResult().contains("```")) {
            // Extract code block
            String code = extractCodeBlock(execution.getResult());
            if (!code.isEmpty()) {
                CodeExplorationReport.CodePiece piece = new CodeExplorationReport.CodePiece();
                piece.setId(filePath);
                piece.setType("file");
                piece.setFilePath(filePath);
                piece.setContent(code);
                piece.setLanguage(detectLanguage(filePath));
                codePieceMap.put(piece.getId(), piece);
            }
        }
    }
    
    /**
     * Extracts method signatures from find_methods tool execution.
     */
    private void extractMethodSignatures(ToolExecutionAdapter execution,
                                       Map<String, CodeExplorationReport.CodePiece> codePieceMap) {
        String result = execution.getResult();
        String className = extractClassNameFromResult(result);
        
        if (className != null) {
            // Extract method signatures
            List<String> methods = extractMethodsFromResult(result);
            
            for (String method : methods) {
                String methodId = className + "#" + extractMethodName(method);
                
                CodeExplorationReport.CodePiece piece = new CodeExplorationReport.CodePiece();
                piece.setId(methodId);
                piece.setType("method_signature");
                piece.setClassName(className);
                piece.setContent(method);
                piece.setLanguage("java");
                
                codePieceMap.putIfAbsent(methodId, piece);
            }
        }
    }
    
    /**
     * Extracts code from search results.
     */
    private void extractSearchResults(ToolExecutionAdapter execution,
                                    Map<String, CodeExplorationReport.CodePiece> codePieceMap) {
        String result = execution.getResult();
        
        // Extract all code blocks from search results
        List<String> codeBlocks = extractAllCodeBlocks(result);
        List<String> elementIds = extractElementIds(result);
        
        for (int i = 0; i < codeBlocks.size() && i < elementIds.size(); i++) {
            String elementId = elementIds.get(i);
            String code = codeBlocks.get(i);
            
            CodeExplorationReport.CodePiece piece = new CodeExplorationReport.CodePiece();
            piece.setId(elementId);
            piece.setType(detectCodeType(code));
            piece.setContent(code);
            piece.setLanguage("java");
            
            // Extract file path if mentioned
            String filePath = extractFilePathFromResult(result, elementId);
            if (filePath != null) {
                piece.setFilePath(filePath);
            }
            
            codePieceMap.putIfAbsent(elementId, piece);
        }
    }
    
    /**
     * Extracts class information.
     */
    private void extractClassInfo(ToolExecutionAdapter execution,
                                Map<String, CodeExplorationReport.CodePiece> codePieceMap) {
        JsonObject params = execution.getParameters();
        String className = params.has("className") ? params.get("className").getAsString() : null;
        
        if (className != null) {
            CodeExplorationReport.CodePiece piece = new CodeExplorationReport.CodePiece();
            piece.setId(className);
            piece.setType("class_info");
            piece.setClassName(className);
            piece.setContent(execution.getResult());
            piece.setLanguage("text");
            
            codePieceMap.putIfAbsent(className, piece);
        }
    }
    
    /**
     * Extracts relationships between code elements.
     */
    private Map<String, List<String>> extractRelationships(
            ExplorationResultAdapter result) {
        Map<String, List<String>> relationships = new HashMap<>();
        
        for (ExplorationRoundAdapter round : result.getRounds()) {
            for (ToolExecutionAdapter execution : round.getToolExecutions()) {
                if (execution.getToolName().equals("find_relationships") && execution.isSuccess()) {
                    parseRelationships(execution, relationships);
                } else if (execution.getToolName().equals("find_callers") && execution.isSuccess()) {
                    parseCallers(execution, relationships);
                } else if (execution.getToolName().equals("find_implementations") && execution.isSuccess()) {
                    parseImplementations(execution, relationships);
                }
            }
        }
        
        return relationships;
    }
    
    /**
     * Generates structured context from code pieces and relationships.
     */
    private String generateStructuredContext(List<CodeExplorationReport.CodePiece> codePieces,
                                           Map<String, List<String>> relationships) {
        StringBuilder context = new StringBuilder();
        
        context.append("## Code Structure Overview\n\n");
        
        // Group code pieces by type
        Map<String, List<CodeExplorationReport.CodePiece>> byType = codePieces.stream()
            .collect(Collectors.groupingBy(CodeExplorationReport.CodePiece::getType));
        
        // Files
        List<CodeExplorationReport.CodePiece> files = byType.getOrDefault("file", Collections.emptyList());
        if (!files.isEmpty()) {
            context.append("### Files (" + files.size() + ")\n");
            for (CodeExplorationReport.CodePiece file : files) {
                context.append("- `" + file.getFilePath() + "`\n");
            }
            context.append("\n");
        }
        
        // Classes
        List<CodeExplorationReport.CodePiece> classes = codePieces.stream()
            .filter(p -> p.getClassName() != null)
            .collect(Collectors.toList());
        
        Map<String, List<CodeExplorationReport.CodePiece>> byClass = classes.stream()
            .collect(Collectors.groupingBy(CodeExplorationReport.CodePiece::getClassName));
        
        if (!byClass.isEmpty()) {
            context.append("### Classes (" + byClass.size() + ")\n");
            for (Map.Entry<String, List<CodeExplorationReport.CodePiece>> entry : byClass.entrySet()) {
                context.append("- **" + entry.getKey() + "** (" + 
                             entry.getValue().size() + " elements)\n");
            }
            context.append("\n");
        }
        
        // Relationships
        if (!relationships.isEmpty()) {
            context.append("### Key Relationships\n");
            for (Map.Entry<String, List<String>> entry : relationships.entrySet()) {
                context.append("- **" + entry.getKey() + "**:\n");
                for (String related : entry.getValue()) {
                    context.append("  - " + related + "\n");
                }
            }
            context.append("\n");
        }
        
        return context.toString();
    }
    
    /**
     * Generates comprehensive context for coding tasks.
     */
    private String generateCodingContext(CodeExplorationReport report) {
        StringBuilder context = new StringBuilder();
        
        context.append("# Code Context for: " + report.getOriginalQuery() + "\n\n");
        
        // Add structured overview
        context.append(report.getStructuredContext());
        context.append("\n");
        
        // Add key insights from exploration
        if (report.getExplorationSummary() != null) {
            context.append("## Key Insights\n\n");
            context.append(report.getExplorationSummary());
            context.append("\n\n");
        }
        
        // Add all relevant code pieces
        context.append("## Relevant Code\n\n");
        
        // Sort code pieces by relevance (files first, then classes, then methods)
        List<CodeExplorationReport.CodePiece> sortedPieces = report.getCodePieces().stream()
            .sorted((a, b) -> {
                int typeOrder = getTypeOrder(a.getType()) - getTypeOrder(b.getType());
                if (typeOrder != 0) return typeOrder;
                return a.getId().compareTo(b.getId());
            })
            .collect(Collectors.toList());
        
        for (CodeExplorationReport.CodePiece piece : sortedPieces) {
            context.append("### " + piece.getId() + "\n");
            
            if (piece.getFilePath() != null) {
                context.append("**File:** `" + piece.getFilePath() + "`\n");
            }
            
            if (piece.getClassName() != null && !piece.getId().equals(piece.getClassName())) {
                context.append("**Class:** `" + piece.getClassName() + "`\n");
            }
            
            context.append("**Type:** " + piece.getType() + "\n\n");
            
            context.append("```" + piece.getLanguage() + "\n");
            context.append(piece.getContent());
            context.append("\n```\n\n");
        }
        
        // Add relationships as comments
        if (!report.getRelationships().isEmpty()) {
            context.append("## Relationships and Dependencies\n\n");
            context.append("```\n");
            for (Map.Entry<String, List<String>> entry : report.getRelationships().entrySet()) {
                context.append(entry.getKey() + ":\n");
                for (String related : entry.getValue()) {
                    context.append("  → " + related + "\n");
                }
                context.append("\n");
            }
            context.append("```\n");
        }
        
        return context.toString();
    }
    
    private int getTypeOrder(String type) {
        switch (type) {
            case "file": return 1;
            case "class": return 2;
            case "interface": return 3;
            case "method": return 4;
            case "method_signature": return 5;
            default: return 10;
        }
    }
    
    // Helper methods for extraction
    
    private Set<String> extractElementsFromText(String text) {
        Set<String> elements = new HashSet<>();
        
        // Extract class names (e.g., com.example.ClassName)
        java.util.regex.Pattern classPattern = 
            java.util.regex.Pattern.compile("\\b([a-z]+\\.)+[A-Z][a-zA-Z0-9]+\\b");
        java.util.regex.Matcher matcher = classPattern.matcher(text);
        while (matcher.find()) {
            elements.add(matcher.group());
        }
        
        // Extract method references (e.g., ClassName#methodName)
        java.util.regex.Pattern methodPattern = 
            java.util.regex.Pattern.compile("\\b[A-Z][a-zA-Z0-9]+#[a-z][a-zA-Z0-9]+\\b");
        matcher = methodPattern.matcher(text);
        while (matcher.find()) {
            elements.add(matcher.group());
        }
        
        return elements;
    }
    
    private String extractCodeBlock(String text) {
        int start = text.indexOf("```");
        if (start < 0) return "";
        
        // Skip language identifier
        int lineEnd = text.indexOf('\n', start);
        if (lineEnd < 0) return "";
        
        int end = text.indexOf("```", lineEnd);
        if (end < 0) return "";
        
        return text.substring(lineEnd + 1, end).trim();
    }
    
    private List<String> extractAllCodeBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        int pos = 0;
        
        while (true) {
            int start = text.indexOf("```", pos);
            if (start < 0) break;
            
            int lineEnd = text.indexOf('\n', start);
            if (lineEnd < 0) break;
            
            int end = text.indexOf("```", lineEnd);
            if (end < 0) break;
            
            blocks.add(text.substring(lineEnd + 1, end).trim());
            pos = end + 3;
        }
        
        return blocks;
    }
    
    private List<String> extractElementIds(String text) {
        List<String> ids = new ArrayList<>();
        
        // Look for patterns like "### Result 1: ElementId" or "- **ElementId**"
        String[] lines = text.split("\n");
        for (String line : lines) {
            if (line.matches(".*Result \\d+: (.+)")) {
                ids.add(line.replaceAll(".*Result \\d+: ", "").trim());
            } else if (line.matches("- \\*\\*(.+)\\*\\*.*")) {
                String id = line.replaceAll("- \\*\\*(.+)\\*\\*.*", "$1").trim();
                if (id.contains("#") || id.contains(".")) {
                    ids.add(id);
                }
            }
        }
        
        return ids;
    }
    
    private String detectLanguage(String filePath) {
        if (filePath.endsWith(".java")) return "java";
        if (filePath.endsWith(".kt")) return "kotlin";
        if (filePath.endsWith(".xml")) return "xml";
        if (filePath.endsWith(".json")) return "json";
        return "text";
    }
    
    private String detectCodeType(String code) {
        if (code.contains("class ") && code.contains("{")) return "class";
        if (code.contains("interface ") && code.contains("{")) return "interface";
        if (code.contains("(") && code.contains(")") && !code.contains("{")) return "method_signature";
        if (code.contains("(") && code.contains(")") && code.contains("{")) return "method";
        return "code";
    }
    
    private String extractClassNameFromResult(String result) {
        // Extract from "Methods in class 'com.example.ClassName':"
        java.util.regex.Pattern pattern = 
            java.util.regex.Pattern.compile("Methods in (?:class|interface) '([^']+)'");
        java.util.regex.Matcher matcher = pattern.matcher(result);
        
        if (matcher.find()) {
            return matcher.group(1);
        }
        
        return null;
    }
    
    private List<String> extractMethodsFromResult(String result) {
        List<String> methods = new ArrayList<>();
        String[] lines = result.split("\n");
        
        for (String line : lines) {
            if (line.trim().startsWith("- ") && line.contains("(") && line.contains(")")) {
                methods.add(line.substring(2).trim());
            }
        }
        
        return methods;
    }
    
    private String extractMethodName(String methodSignature) {
        int parenIndex = methodSignature.indexOf('(');
        if (parenIndex > 0) {
            String beforeParen = methodSignature.substring(0, parenIndex);
            String[] parts = beforeParen.split("\\s+");
            return parts[parts.length - 1].replaceAll("\\*", "");
        }
        return methodSignature;
    }
    
    private String extractFilePathFromResult(String result, String elementId) {
        String[] lines = result.split("\n");
        boolean foundElement = false;
        
        for (String line : lines) {
            if (line.contains(elementId)) {
                foundElement = true;
            } else if (foundElement && line.contains("File:")) {
                return line.replaceAll(".*File:\\s*", "").trim();
            }
        }
        
        return null;
    }
    
    private void parseRelationships(ToolExecutionAdapter execution,
                                  Map<String, List<String>> relationships) {
        JsonObject params = execution.getParameters();
        String elementId = params.has("elementId") ? params.get("elementId").getAsString() : null;
        
        if (elementId != null) {
            List<String> related = relationships.computeIfAbsent(elementId, k -> new ArrayList<>());
            
            // Parse relationships from result
            String[] lines = execution.getResult().split("\n");
            for (String line : lines) {
                if (line.trim().startsWith("- ") && !line.contains("None found")) {
                    related.add(line.substring(2).trim());
                }
            }
        }
    }
    
    private void parseCallers(ToolExecutionAdapter execution,
                            Map<String, List<String>> relationships) {
        JsonObject params = execution.getParameters();
        String methodId = params.has("methodId") ? params.get("methodId").getAsString() : null;
        
        if (methodId != null) {
            List<String> callers = relationships.computeIfAbsent(methodId + " <- callers", 
                                                               k -> new ArrayList<>());
            
            String[] lines = execution.getResult().split("\n");
            for (String line : lines) {
                if (line.matches("- \\*\\*.+\\*\\*")) {
                    String caller = line.replaceAll("- \\*\\*(.+)\\*\\*.*", "$1").trim();
                    callers.add(caller);
                }
            }
        }
    }
    
    private void parseImplementations(ToolExecutionAdapter execution,
                                    Map<String, List<String>> relationships) {
        JsonObject params = execution.getParameters();
        String elementId = params.has("elementId") ? params.get("elementId").getAsString() : null;
        
        if (elementId != null) {
            List<String> implementations = relationships.computeIfAbsent(
                elementId + " <- implementations", k -> new ArrayList<>());
            
            String[] lines = execution.getResult().split("\n");
            for (String line : lines) {
                if (line.matches("- \\*\\*.+\\*\\*")) {
                    String impl = line.replaceAll("- \\*\\*(.+)\\*\\*.*", "$1").trim();
                    implementations.add(impl);
                }
            }
        }
    }
    
    // Adapter interfaces and implementations to work with both agent types
    
    private interface ExplorationResultAdapter {
        List<ExplorationRoundAdapter> getRounds();
        String getSummary();
    }
    
    private interface ExplorationRoundAdapter {
        String getName();
        List<ToolExecutionAdapter> getToolExecutions();
    }
    
    private interface ToolExecutionAdapter {
        String getToolName();
        JsonObject getParameters();
        String getResult();
        boolean isSuccess();
    }
    
    // Adapter for original ToolCallingAutonomousAgent
    private static class OriginalExplorationResultAdapter implements ExplorationResultAdapter {
        private final ToolCallingAutonomousAgent.ExplorationResult result;
        
        OriginalExplorationResultAdapter(ToolCallingAutonomousAgent.ExplorationResult result) {
            this.result = result;
        }
        
        @Override
        public List<ExplorationRoundAdapter> getRounds() {
            return result.getRounds().stream()
                .map(RoundAdapter::new)
                .collect(Collectors.toList());
        }
        
        @Override
        public String getSummary() {
            return result.getSummary();
        }
    }
    
    private static class RoundAdapter implements ExplorationRoundAdapter {
        private final ToolCallingAutonomousAgent.ExplorationRound round;
        
        RoundAdapter(ToolCallingAutonomousAgent.ExplorationRound round) {
            this.round = round;
        }
        
        @Override
        public String getName() {
            return round.getName();
        }
        
        @Override
        public List<ToolExecutionAdapter> getToolExecutions() {
            return round.getToolExecutions().stream()
                .map(ExecutionAdapter::new)
                .collect(Collectors.toList());
        }
    }
    
    private static class ExecutionAdapter implements ToolExecutionAdapter {
        private final ToolCallingAutonomousAgent.ToolExecution execution;
        
        ExecutionAdapter(ToolCallingAutonomousAgent.ToolExecution execution) {
            this.execution = execution;
        }
        
        @Override
        public String getToolName() { return execution.getToolName(); }
        @Override
        public JsonObject getParameters() { return execution.getParameters(); }
        @Override
        public String getResult() { return execution.getResult(); }
        @Override
        public boolean isSuccess() { return execution.isSuccess(); }
    }
    
    // Adapter for ImprovedToolCallingAutonomousAgent
    private static class ImprovedExplorationResultAdapter implements ExplorationResultAdapter {
        private final ImprovedToolCallingAutonomousAgent.ExplorationResult result;
        
        ImprovedExplorationResultAdapter(ImprovedToolCallingAutonomousAgent.ExplorationResult result) {
            this.result = result;
        }
        
        @Override
        public List<ExplorationRoundAdapter> getRounds() {
            return result.getRounds().stream()
                .map(ImprovedRoundAdapter::new)
                .collect(Collectors.toList());
        }
        
        @Override
        public String getSummary() {
            return result.getSummary();
        }
    }
    
    private static class ImprovedRoundAdapter implements ExplorationRoundAdapter {
        private final ImprovedToolCallingAutonomousAgent.ExplorationRound round;
        
        ImprovedRoundAdapter(ImprovedToolCallingAutonomousAgent.ExplorationRound round) {
            this.round = round;
        }
        
        @Override
        public String getName() {
            return round.getName();
        }
        
        @Override
        public List<ToolExecutionAdapter> getToolExecutions() {
            return round.getToolExecutions().stream()
                .map(ImprovedExecutionAdapter::new)
                .collect(Collectors.toList());
        }
    }
    
    private static class ImprovedExecutionAdapter implements ToolExecutionAdapter {
        private final ImprovedToolCallingAutonomousAgent.ToolExecution execution;
        
        ImprovedExecutionAdapter(ImprovedToolCallingAutonomousAgent.ToolExecution execution) {
            this.execution = execution;
        }
        
        @Override
        public String getToolName() { return execution.getToolName(); }
        @Override
        public JsonObject getParameters() { return execution.getParameters(); }
        @Override
        public String getResult() { return execution.getResult(); }
        @Override
        public boolean isSuccess() { return execution.isSuccess(); }
    }
}
