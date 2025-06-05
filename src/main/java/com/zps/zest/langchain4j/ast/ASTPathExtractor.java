package com.zps.zest.langchain4j.ast;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.*;

/**
 * Extracts AST paths from PSI elements for code embedding and similarity analysis.
 * AST paths represent the structural patterns in code that can be used for
 * semantic code search and understanding.
 */
public class ASTPathExtractor {
    
    private static final int MAX_PATH_LENGTH = 8;
    private static final int MAX_PATH_WIDTH = 2;
    
    /**
     * Extracts AST paths from a method.
     */
    public List<ASTPath> extractPaths(PsiMethod method) {
        List<ASTPath> paths = new ArrayList<>();
        
        if (method.getBody() != null) {
            extractPathsFromElement(method.getBody(), paths);
        }
        
        return paths;
    }
    
    /**
     * Extracts AST paths from a class.
     */
    public List<ASTPath> extractPaths(PsiClass clazz) {
        List<ASTPath> paths = new ArrayList<>();
        
        // Extract paths from all methods
        for (PsiMethod method : clazz.getMethods()) {
            if (method.getBody() != null) {
                extractPathsFromElement(method.getBody(), paths);
            }
        }
        
        // Extract paths from field initializers
        for (PsiField field : clazz.getFields()) {
            PsiExpression initializer = field.getInitializer();
            if (initializer != null) {
                extractPathsFromElement(initializer, paths);
            }
        }
        
        return paths;
    }
    
    /**
     * Recursively extracts paths from a PSI element.
     */
    private void extractPathsFromElement(PsiElement element, List<ASTPath> paths) {
        if (element == null) return;
        
        // Get all leaf nodes (terminals)
        List<PsiElement> leaves = new ArrayList<>();
        collectLeaves(element, leaves);
        
        // For each pair of leaves, find their paths
        for (int i = 0; i < leaves.size(); i++) {
            for (int j = i + 1; j < Math.min(i + MAX_PATH_WIDTH, leaves.size()); j++) {
                ASTPath path = findPath(leaves.get(i), leaves.get(j));
                if (path != null && path.isValid()) {
                    paths.add(path);
                }
            }
        }
    }
    
    /**
     * Collects all leaf nodes in the AST.
     */
    private void collectLeaves(PsiElement element, List<PsiElement> leaves) {
        if (element.getChildren().length == 0) {
            // It's a leaf
            if (isInterestingLeaf(element)) {
                leaves.add(element);
            }
        } else {
            for (PsiElement child : element.getChildren()) {
                collectLeaves(child, leaves);
            }
        }
    }
    
    /**
     * Determines if a leaf node is interesting for path extraction.
     */
    private boolean isInterestingLeaf(PsiElement element) {
        if (element instanceof PsiWhiteSpace || element instanceof PsiComment) {
            return false;
        }
        
        if (element instanceof PsiIdentifier || 
            element instanceof PsiLiteralExpression ||
            element instanceof PsiKeyword ||
            element instanceof PsiJavaToken) {
            return true;
        }
        
        return element.getText().trim().length() > 0;
    }
    
    /**
     * Finds the path between two nodes.
     */
    private ASTPath findPath(PsiElement start, PsiElement end) {
        if (start == end) return null;
        
        // Find common ancestor
        PsiElement commonAncestor = PsiTreeUtil.findCommonParent(start, end);
        if (commonAncestor == null) return null;
        
        // Build path from start to common ancestor
        List<String> upPath = new ArrayList<>();
        PsiElement current = start;
        while (current != commonAncestor && upPath.size() < MAX_PATH_LENGTH / 2) {
            upPath.add(getNodeType(current));
            current = current.getParent();
            if (current == null) return null;
        }
        
        // Build path from end to common ancestor
        List<String> downPath = new ArrayList<>();
        current = end;
        while (current != commonAncestor && downPath.size() < MAX_PATH_LENGTH / 2) {
            downPath.add(getNodeType(current));
            current = current.getParent();
            if (current == null) return null;
        }
        
        // Reverse the down path to go from ancestor to end
        Collections.reverse(downPath);
        
        // Create the complete path
        ASTPath path = new ASTPath();
        path.startValue = getNodeValue(start);
        path.endValue = getNodeValue(end);
        path.pathNodes = new ArrayList<>();
        path.pathNodes.addAll(upPath);
        path.pathNodes.add(getNodeType(commonAncestor)); // Common ancestor
        path.pathNodes.addAll(downPath);
        
        return path;
    }
    
    /**
     * Gets a simplified type name for a PSI element.
     */
    private String getNodeType(PsiElement element) {
        if (element instanceof PsiMethodCallExpression) return "MethodCall";
        if (element instanceof PsiReferenceExpression) return "Reference";
        if (element instanceof PsiLiteralExpression) return "Literal";
        if (element instanceof PsiIdentifier) return "Identifier";
        if (element instanceof PsiKeyword) return element.getText();
        if (element instanceof PsiIfStatement) return "If";
        if (element instanceof PsiForStatement) return "For";
        if (element instanceof PsiWhileStatement) return "While";
        if (element instanceof PsiReturnStatement) return "Return";
        if (element instanceof PsiAssignmentExpression) return "Assignment";
        if (element instanceof PsiBinaryExpression) return "BinaryOp";
        if (element instanceof PsiUnaryExpression) return "UnaryOp";
        if (element instanceof PsiNewExpression) return "New";
        if (element instanceof PsiArrayAccessExpression) return "ArrayAccess";
        if (element instanceof PsiConditionalExpression) return "Conditional";
        if (element instanceof PsiSwitchStatement) return "Switch";
        if (element instanceof PsiTryStatement) return "Try";
        if (element instanceof PsiThrowStatement) return "Throw";
        if (element instanceof PsiClass) return "Class";
        if (element instanceof PsiMethod) return "Method";
        if (element instanceof PsiField) return "Field";
        if (element instanceof PsiParameter) return "Parameter";
        if (element instanceof PsiLocalVariable) return "LocalVar";
        if (element instanceof PsiCodeBlock) return "Block";
        if (element instanceof PsiExpressionStatement) return "ExprStmt";
        if (element instanceof PsiDeclarationStatement) return "DeclStmt";
        
        // Default to simple class name
        String className = element.getClass().getSimpleName();
        if (className.startsWith("Psi")) {
            className = className.substring(3);
        }
        return className;
    }
    
    /**
     * Gets the value of a node (for terminals).
     */
    private String getNodeValue(PsiElement element) {
        if (element instanceof PsiIdentifier) {
            return element.getText();
        }
        if (element instanceof PsiLiteralExpression) {
            PsiLiteralExpression literal = (PsiLiteralExpression) element;
            Object value = literal.getValue();
            if (value instanceof String) return "STR";
            if (value instanceof Number) return "NUM";
            if (value instanceof Boolean) return value.toString();
            if (value instanceof Character) return "CHAR";
            return "LITERAL";
        }
        if (element instanceof PsiKeyword) {
            return element.getText();
        }
        if (element instanceof PsiJavaToken) {
            return element.getText();
        }
        return element.getText();
    }
    
    /**
     * Represents an AST path between two nodes.
     */
    public static class ASTPath {
        private String startValue;
        private String endValue;
        private List<String> pathNodes;
        
        /**
         * Checks if this path is valid.
         */
        public boolean isValid() {
            return startValue != null && endValue != null && 
                   pathNodes != null && !pathNodes.isEmpty() &&
                   pathNodes.size() <= MAX_PATH_LENGTH;
        }
        
        /**
         * Converts this path to a feature string for embedding.
         */
        public String toFeature() {
            if (!isValid()) return "";
            
            StringBuilder sb = new StringBuilder();
            sb.append(startValue);
            sb.append(",");
            sb.append(String.join("^", pathNodes));
            sb.append(",");
            sb.append(endValue);
            return sb.toString();
        }
        
        @Override
        public String toString() {
            return toFeature();
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ASTPath astPath = (ASTPath) o;
            return Objects.equals(startValue, astPath.startValue) &&
                   Objects.equals(endValue, astPath.endValue) &&
                   Objects.equals(pathNodes, astPath.pathNodes);
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(startValue, endValue, pathNodes);
        }
    }
}
