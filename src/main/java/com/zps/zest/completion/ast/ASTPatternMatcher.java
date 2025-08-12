package com.zps.zest.completion.ast;

import com.intellij.openapi.diagnostic.Logger;
import org.treesitter.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * AST pattern matcher for finding similar code structures.
 * Uses tree-sitter to extract and compare structural patterns.
 */
public class ASTPatternMatcher {
    private static final Logger LOG = Logger.getInstance(ASTPatternMatcher.class);
    
    // Pattern similarity thresholds
    private static final double MIN_PATTERN_SIMILARITY = 0.7;
    private static final int MAX_PATTERN_DEPTH = 3;
    
    // Cache for parsed patterns
    private final Map<String, ASTPattern> patternCache = new HashMap<>();
    
    /**
     * Extract AST pattern from code at cursor position
     */
    public ASTPattern extractPatternAtCursor(String code, int cursorOffset, String language) {
        try {
            TSLanguage tsLanguage = getLanguageParser(language);
            if (tsLanguage == null) {
                return null;
            }
            
            TSParser parser = new TSParser();
            parser.setLanguage(tsLanguage);
            TSTree tree = parser.parseString(null, code);
            TSNode rootNode = tree.getRootNode();
            
            // Find node at cursor
            TSNode cursorNode = findNodeAtOffset(rootNode, cursorOffset);
            if (cursorNode == null) {
                return null;
            }
            
            // Extract pattern from cursor node and its context
            return extractPattern(cursorNode, code, MAX_PATTERN_DEPTH);
            
        } catch (Exception e) {
            LOG.debug("Failed to extract pattern at cursor", e);
            return null;
        }
    }
    
    /**
     * Find similar patterns in code
     */
    public List<PatternMatch> findSimilarPatterns(ASTPattern targetPattern, String code, String language) {
        List<PatternMatch> matches = new ArrayList<>();
        
        try {
            TSLanguage tsLanguage = getLanguageParser(language);
            if (tsLanguage == null || targetPattern == null) {
                return matches;
            }
            
            TSParser parser = new TSParser();
            parser.setLanguage(tsLanguage);
            TSTree tree = parser.parseString(null, code);
            TSNode rootNode = tree.getRootNode();
            
            // Traverse tree and find similar patterns
            findSimilarPatternsRecursive(rootNode, code, targetPattern, matches);
            
            // Sort by similarity score
            matches.sort((a, b) -> Double.compare(b.getSimilarity(), a.getSimilarity()));
            
        } catch (Exception e) {
            LOG.debug("Failed to find similar patterns", e);
        }
        
        return matches;
    }
    
    /**
     * Calculate similarity between two AST patterns
     */
    public double calculatePatternSimilarity(ASTPattern pattern1, ASTPattern pattern2) {
        if (pattern1 == null || pattern2 == null) {
            return 0.0;
        }
        
        // Structural similarity (node types and hierarchy)
        double structuralSim = calculateStructuralSimilarity(pattern1, pattern2);
        
        // Semantic similarity (identifiers, operators)
        double semanticSim = calculateSemanticSimilarity(pattern1, pattern2);
        
        // Control flow similarity
        double controlFlowSim = calculateControlFlowSimilarity(pattern1, pattern2);
        
        // Weighted combination
        return 0.5 * structuralSim + 0.3 * semanticSim + 0.2 * controlFlowSim;
    }
    
    /**
     * Extract pattern from AST node
     */
    private ASTPattern extractPattern(TSNode node, String code, int maxDepth) {
        ASTPattern pattern = new ASTPattern();
        pattern.setNodeType(node.getType());
        pattern.setDepth(maxDepth);
        
        // Extract structural information
        extractStructuralInfo(node, code, pattern, 0, maxDepth);
        
        // Extract semantic features
        extractSemanticFeatures(node, code, pattern);
        
        // Extract control flow
        extractControlFlowFeatures(node, code, pattern);
        
        return pattern;
    }
    
    private void extractStructuralInfo(TSNode node, String code, ASTPattern pattern, 
                                       int currentDepth, int maxDepth) {
        if (currentDepth >= maxDepth) {
            return;
        }
        
        // Add node type to structure
        pattern.addStructuralElement(node.getType(), currentDepth);
        
        // Process children
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode child = node.getChild(i);
            extractStructuralInfo(child, code, pattern, currentDepth + 1, maxDepth);
        }
    }
    
    private void extractSemanticFeatures(TSNode node, String code, ASTPattern pattern) {
        String nodeType = node.getType();
        
        // Extract identifiers
        if (nodeType.equals("identifier") || nodeType.equals("property_identifier")) {
            pattern.addIdentifier(getNodeText(node, code));
        }
        
        // Extract operators
        if (isOperatorNode(nodeType)) {
            pattern.addOperator(nodeType);
        }
        
        // Extract method calls
        if (nodeType.contains("call") || nodeType.contains("invocation")) {
            TSNode nameNode = findChildByType(node, "identifier");
            if (nameNode != null) {
                pattern.addMethodCall(getNodeText(nameNode, code));
            }
        }
        
        // Recursively extract from children
        for (int i = 0; i < node.getChildCount(); i++) {
            extractSemanticFeatures(node.getChild(i), code, pattern);
        }
    }
    
    private void extractControlFlowFeatures(TSNode node, String code, ASTPattern pattern) {
        String nodeType = node.getType();
        
        // Control flow structures
        if (nodeType.contains("if") || nodeType.contains("conditional")) {
            pattern.addControlFlow("conditional");
        } else if (nodeType.contains("for") || nodeType.contains("while") || nodeType.contains("loop")) {
            pattern.addControlFlow("loop");
        } else if (nodeType.contains("switch") || nodeType.contains("case")) {
            pattern.addControlFlow("switch");
        } else if (nodeType.contains("try") || nodeType.contains("catch")) {
            pattern.addControlFlow("exception");
        } else if (nodeType.contains("return")) {
            pattern.addControlFlow("return");
        }
        
        // Recursively extract from children
        for (int i = 0; i < node.getChildCount(); i++) {
            extractControlFlowFeatures(node.getChild(i), code, pattern);
        }
    }
    
    private void findSimilarPatternsRecursive(TSNode node, String code, ASTPattern targetPattern,
                                              List<PatternMatch> matches) {
        // Extract pattern for current node
        ASTPattern nodePattern = extractPattern(node, code, MAX_PATTERN_DEPTH);
        
        // Calculate similarity
        double similarity = calculatePatternSimilarity(targetPattern, nodePattern);
        
        if (similarity >= MIN_PATTERN_SIMILARITY) {
            int startLine = node.getStartPoint().getRow() + 1;
            int endLine = node.getEndPoint().getRow() + 1;
            String nodeText = getNodeText(node, code);
            
            matches.add(new PatternMatch(nodeText, similarity, startLine, endLine, nodePattern));
        }
        
        // Recursively check children
        for (int i = 0; i < node.getChildCount(); i++) {
            findSimilarPatternsRecursive(node.getChild(i), code, targetPattern, matches);
        }
    }
    
    private double calculateStructuralSimilarity(ASTPattern p1, ASTPattern p2) {
        List<String> structure1 = p1.getStructuralElements();
        List<String> structure2 = p2.getStructuralElements();
        
        if (structure1.isEmpty() || structure2.isEmpty()) {
            return 0.0;
        }
        
        // Calculate Jaccard similarity
        Set<String> set1 = new HashSet<>(structure1);
        Set<String> set2 = new HashSet<>(structure2);
        
        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);
        
        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);
        
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }
    
    private double calculateSemanticSimilarity(ASTPattern p1, ASTPattern p2) {
        double identifierSim = calculateSetSimilarity(p1.getIdentifiers(), p2.getIdentifiers());
        double operatorSim = calculateSetSimilarity(p1.getOperators(), p2.getOperators());
        double methodCallSim = calculateSetSimilarity(p1.getMethodCalls(), p2.getMethodCalls());
        
        return 0.4 * identifierSim + 0.3 * operatorSim + 0.3 * methodCallSim;
    }
    
    private double calculateControlFlowSimilarity(ASTPattern p1, ASTPattern p2) {
        return calculateSetSimilarity(p1.getControlFlows(), p2.getControlFlows());
    }
    
    private double calculateSetSimilarity(Set<String> set1, Set<String> set2) {
        if (set1.isEmpty() && set2.isEmpty()) {
            return 1.0;
        }
        if (set1.isEmpty() || set2.isEmpty()) {
            return 0.0;
        }
        
        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);
        
        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);
        
        return (double) intersection.size() / union.size();
    }
    
    private TSNode findNodeAtOffset(TSNode root, int offset) {
        if (offset < root.getStartByte() || offset > root.getEndByte()) {
            return null;
        }
        
        // Find deepest node containing offset
        for (int i = 0; i < root.getChildCount(); i++) {
            TSNode child = root.getChild(i);
            TSNode found = findNodeAtOffset(child, offset);
            if (found != null) {
                return found;
            }
        }
        
        return root;
    }
    
    private TSNode findChildByType(TSNode node, String type) {
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode child = node.getChild(i);
            if (child.getType().equals(type)) {
                return child;
            }
        }
        return null;
    }
    
    private String getNodeText(TSNode node, String code) {
        int start = node.getStartByte();
        int end = node.getEndByte();
        
        if (start < 0 || end > code.length() || start >= end) {
            return "";
        }
        
        return code.substring(start, end);
    }
    
    private boolean isOperatorNode(String nodeType) {
        return nodeType.contains("operator") || 
               nodeType.equals("+") || nodeType.equals("-") || 
               nodeType.equals("*") || nodeType.equals("/") ||
               nodeType.equals("&&") || nodeType.equals("||") ||
               nodeType.equals("==") || nodeType.equals("!=") ||
               nodeType.equals("<") || nodeType.equals(">");
    }
    
    private TSLanguage getLanguageParser(String language) {
        try {
            return switch (language.toLowerCase()) {
                case "java" -> new TreeSitterJava();
                case "kotlin", "kt" -> new TreeSitterKotlin();
                case "javascript", "js" -> new TreeSitterJavascript();
                case "typescript", "ts" -> new TreeSitterTypescript();
                default -> null;
            };
        } catch (Exception e) {
            LOG.debug("Failed to load language parser for: " + language, e);
            return null;
        }
    }
    
    /**
     * AST Pattern representation
     */
    public static class ASTPattern {
        private String nodeType;
        private int depth;
        private final List<String> structuralElements = new ArrayList<>();
        private final Set<String> identifiers = new HashSet<>();
        private final Set<String> operators = new HashSet<>();
        private final Set<String> methodCalls = new HashSet<>();
        private final Set<String> controlFlows = new HashSet<>();
        
        public void setNodeType(String nodeType) { this.nodeType = nodeType; }
        public void setDepth(int depth) { this.depth = depth; }
        
        public void addStructuralElement(String element, int depth) {
            structuralElements.add(depth + ":" + element);
        }
        
        public void addIdentifier(String id) { identifiers.add(id); }
        public void addOperator(String op) { operators.add(op); }
        public void addMethodCall(String method) { methodCalls.add(method); }
        public void addControlFlow(String flow) { controlFlows.add(flow); }
        
        public String getNodeType() { return nodeType; }
        public int getDepth() { return depth; }
        public List<String> getStructuralElements() { return structuralElements; }
        public Set<String> getIdentifiers() { return identifiers; }
        public Set<String> getOperators() { return operators; }
        public Set<String> getMethodCalls() { return methodCalls; }
        public Set<String> getControlFlows() { return controlFlows; }
        
        @Override
        public String toString() {
            return String.format("ASTPattern[type=%s, depth=%d, structure=%d, ids=%d, ops=%d, calls=%d]",
                nodeType, depth, structuralElements.size(), identifiers.size(), 
                operators.size(), methodCalls.size());
        }
    }
    
    /**
     * Pattern match result
     */
    public static class PatternMatch {
        private final String code;
        private final double similarity;
        private final int startLine;
        private final int endLine;
        private final ASTPattern pattern;
        
        public PatternMatch(String code, double similarity, int startLine, int endLine, ASTPattern pattern) {
            this.code = code;
            this.similarity = similarity;
            this.startLine = startLine;
            this.endLine = endLine;
            this.pattern = pattern;
        }
        
        public String getCode() { return code; }
        public double getSimilarity() { return similarity; }
        public int getStartLine() { return startLine; }
        public int getEndLine() { return endLine; }
        public ASTPattern getPattern() { return pattern; }
    }
}