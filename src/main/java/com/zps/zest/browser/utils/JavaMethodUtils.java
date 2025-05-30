package com.zps.zest.browser.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for handling Java methods using PSI and text-based parsing.
 */
public class JavaMethodUtils {
    private static final Logger LOG = Logger.getInstance(JavaMethodUtils.class);
    
    /**
     * Finds functions in Java files using PSI (Program Structure Interface).
     */
    public static void findFunctionsInJavaFile(Project project, VirtualFile file, String relativePath, 
                                              String targetName, JsonArray results, boolean caseSensitive) {
        try {
            // Check if we can use PSI
            PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
            if (psiFile instanceof PsiJavaFile) {
                PsiJavaFile javaFile = (PsiJavaFile) psiFile;
                JsonArray functions = new JsonArray();
                
                // Visit all classes in the file
                for (PsiClass psiClass : javaFile.getClasses()) {
                    findMethodsInClass(psiClass, targetName, functions, caseSensitive);
                }
                
                if (functions.size() > 0) {
                    JsonObject fileResult = new JsonObject();
                    fileResult.addProperty("file", relativePath);
                    fileResult.add("functions", functions);
                    results.add(fileResult);
                }
            } else {
                // Fall back to text-based parsing for Java files
                findFunctionsInJavaFileText(file, relativePath, targetName, results, caseSensitive);
            }
        } catch (Exception e) {
            LOG.warn("Error finding functions in Java file: " + relativePath, e);
            // Fall back to text-based parsing
            try {
                findFunctionsInJavaFileText(file, relativePath, targetName, results, caseSensitive);
            } catch (Exception ex) {
                LOG.error("Failed to parse Java file: " + relativePath, ex);
            }
        }
    }
    
    /**
     * Recursively finds methods in a PSI class and its inner classes.
     */
    private static void findMethodsInClass(PsiClass psiClass, String targetName, JsonArray functions, boolean caseSensitive) {
        // Add methods from this class
        for (PsiMethod method : psiClass.getMethods()) {
            String methodName = method.getName();
            
            if (targetName == null || StringMatchingUtils.matchesAcrossNamingConventions(methodName, targetName, caseSensitive)) {
                JsonObject func = new JsonObject();
                func.addProperty("name", methodName);
                func.addProperty("line", getLineNumber(method));
                func.addProperty("signature", getMethodSignature(method));
                func.addProperty("implementation", method.getText());
                func.addProperty("type", getMethodType(method));
                
                // Add class context
                func.addProperty("className", psiClass.getName());
                func.addProperty("classQualifiedName", psiClass.getQualifiedName());
                
                functions.add(func);
            }
        }
        
        // Process inner classes
        for (PsiClass innerClass : psiClass.getInnerClasses()) {
            findMethodsInClass(innerClass, targetName, functions, caseSensitive);
        }
    }
    
    /**
     * Gets the line number for a PSI element.
     */
    private static int getLineNumber(PsiElement element) {
        PsiFile file = element.getContainingFile();
        if (file != null) {
            String text = file.getText();
            int offset = element.getTextOffset();
            return CodeExtractionUtils.getLineNumber(text, offset);
        }
        return 1;
    }
    
    /**
     * Generates a method signature string from a PSI method.
     */
    private static String getMethodSignature(PsiMethod method) {
        StringBuilder sig = new StringBuilder();
        
        // Add modifiers
        PsiModifierList modifiers = method.getModifierList();
        if (modifiers.hasModifierProperty(PsiModifier.PUBLIC)) sig.append("public ");
        if (modifiers.hasModifierProperty(PsiModifier.PRIVATE)) sig.append("private ");
        if (modifiers.hasModifierProperty(PsiModifier.PROTECTED)) sig.append("protected ");
        if (modifiers.hasModifierProperty(PsiModifier.STATIC)) sig.append("static ");
        if (modifiers.hasModifierProperty(PsiModifier.FINAL)) sig.append("final ");
        if (modifiers.hasModifierProperty(PsiModifier.ABSTRACT)) sig.append("abstract ");
        if (modifiers.hasModifierProperty(PsiModifier.SYNCHRONIZED)) sig.append("synchronized ");
        
        // Add return type
        if (!method.isConstructor()) {
            PsiType returnType = method.getReturnType();
            if (returnType != null) {
                sig.append(returnType.getPresentableText()).append(" ");
            }
        }
        
        // Add method name
        sig.append(method.getName()).append("(");
        
        // Add parameters
        PsiParameter[] params = method.getParameterList().getParameters();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sig.append(", ");
            sig.append(params[i].getType().getPresentableText()).append(" ");
            sig.append(params[i].getName());
        }
        sig.append(")");
        
        // Add throws clause
        PsiClassType[] throwTypes = method.getThrowsList().getReferencedTypes();
        if (throwTypes.length > 0) {
            sig.append(" throws ");
            for (int i = 0; i < throwTypes.length; i++) {
                if (i > 0) sig.append(", ");
                sig.append(throwTypes[i].getPresentableText());
            }
        }
        
        return sig.toString();
    }
    
    /**
     * Determines the type of a Java method.
     */
    private static String getMethodType(PsiMethod method) {
        if (method.isConstructor()) return "constructor";
        if (method.hasModifierProperty(PsiModifier.STATIC)) return "static";
        if (method.hasModifierProperty(PsiModifier.ABSTRACT)) return "abstract";
        
        // Check for getter/setter
        String name = method.getName();
        if ((name.startsWith("get") || name.startsWith("is")) && 
            method.getParameterList().getParametersCount() == 0) {
            return "getter";
        }
        if (name.startsWith("set") && method.getParameterList().getParametersCount() == 1) {
            return "setter";
        }
        
        // Check for common lifecycle methods
        if (name.equals("onCreate") || name.equals("onStart") || name.equals("onResume") ||
            name.equals("onPause") || name.equals("onStop") || name.equals("onDestroy")) {
            return "lifecycle";
        }
        
        // Check for test methods
        for (PsiAnnotation annotation : method.getAnnotations()) {
            String qName = annotation.getQualifiedName();
            if (qName != null && (qName.contains("Test") || qName.contains("Before") || 
                qName.contains("After"))) {
                return "test";
            }
        }
        
        return "method";
    }
    
    /**
     * Fallback method to find functions in Java files using text parsing.
     */
    private static void findFunctionsInJavaFileText(VirtualFile file, String relativePath, String targetName,
                                                   JsonArray results, boolean caseSensitive) throws IOException {
        String content = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
        
        // Java method patterns
        List<Pattern> patterns = Arrays.asList(
            // Standard method declarations
            Pattern.compile("(?:public|private|protected)?\\s*(?:static)?\\s*(?:final)?\\s*(?:synchronized)?\\s*(?:<[^>]+>\\s*)?(?:[a-zA-Z_][a-zA-Z0-9_.<>\\[\\]]*\\s+)?([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\([^)]*\\)\\s*(?:throws\\s+[^{]+)?\\s*\\{"),
            
            // Constructor patterns
            Pattern.compile("(?:public|private|protected)\\s+([A-Z][a-zA-Z0-9_]*)\\s*\\([^)]*\\)\\s*(?:throws\\s+[^{]+)?\\s*\\{"),
            
            // Interface method declarations (no body)
            Pattern.compile("(?:public)?\\s*(?:default|static)?\\s*(?:<[^>]+>\\s*)?(?:[a-zA-Z_][a-zA-Z0-9_.<>\\[\\]]*\\s+)?([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\([^)]*\\)\\s*;"),
            
            // Abstract method declarations
            Pattern.compile("(?:public|protected)?\\s*abstract\\s+(?:<[^>]+>\\s*)?(?:[a-zA-Z_][a-zA-Z0-9_.<>\\[\\]]*\\s+)?([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\([^)]*\\)\\s*;")
        );
        
        JsonArray functions = new JsonArray();
        Set<String> foundFunctions = new HashSet<>();
        
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(content);
            while (matcher.find()) {
                String funcName = matcher.group(1);
                
                // Skip if already found
                String uniqueKey = funcName + "_" + matcher.start();
                if (foundFunctions.contains(uniqueKey)) continue;
                foundFunctions.add(uniqueKey);
                
                if (targetName == null || StringMatchingUtils.matchesAcrossNamingConventions(funcName, targetName, caseSensitive)) {
                    int position = matcher.start();
                    int lineNumber = CodeExtractionUtils.getLineNumber(content, position);
                    
                    JsonObject func = new JsonObject();
                    func.addProperty("name", funcName);
                    func.addProperty("line", lineNumber);
                    
                    // For Java, extract the full method including JavaDoc if present
                    String implementation = extractJavaMethodImplementation(content, position);
                    func.addProperty("implementation", implementation);
                    func.addProperty("signature", matcher.group(0).trim());
                    func.addProperty("type", CodeExtractionUtils.detectJavaMethodType(matcher.group(0)));
                    functions.add(func);
                }
            }
        }
        
        if (functions.size() > 0) {
            JsonObject fileResult = new JsonObject();
            fileResult.addProperty("file", relativePath);
            fileResult.add("functions", functions);
            results.add(fileResult);
        }
    }
    
    /**
     * Extracts a complete Java method implementation including JavaDoc.
     */
    private static String extractJavaMethodImplementation(String content, int methodStart) {
        // First, find any JavaDoc comment before the method
        int docStart = methodStart;
        
        // Go backwards to find JavaDoc
        int searchPos = methodStart - 1;
        while (searchPos > 0 && Character.isWhitespace(content.charAt(searchPos))) {
            searchPos--;
        }
        
        if (searchPos > 1 && content.charAt(searchPos) == '/' && content.charAt(searchPos - 1) == '*') {
            // Found end of JavaDoc, find the start
            int docSearchPos = searchPos - 2;
            while (docSearchPos > 1) {
                if (content.charAt(docSearchPos) == '/' && 
                    content.charAt(docSearchPos + 1) == '*' && 
                    content.charAt(docSearchPos + 2) == '*') {
                    docStart = docSearchPos;
                    break;
                }
                docSearchPos--;
            }
        }
        
        // Find the end of the method
        int bracePos = content.indexOf('{', methodStart);
        if (bracePos == -1) {
            // Interface or abstract method, ends with semicolon
            int semiPos = content.indexOf(';', methodStart);
            if (semiPos != -1) {
                return content.substring(docStart, semiPos + 1);
            }
            return content.substring(docStart, Math.min(docStart + 500, content.length()));
        }
        
        int methodEnd = CodeExtractionUtils.findMatchingBrace(content, bracePos);
        if (methodEnd == -1) {
            // Can't find matching brace, return what we have
            return content.substring(docStart, Math.min(bracePos + 1000, content.length()));
        }
        
        return content.substring(docStart, methodEnd + 1);
    }
}