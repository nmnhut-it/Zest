package com.zps.zest.langchain4j;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.openapi.application.ReadAction;
import com.zps.zest.langchain4j.ast.ASTPathExtractor;
import com.zps.zest.rag.CodeSignature;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates rich code embeddings that include AST structure, semantic information,
 * and contextual data for improved search accuracy.
 */
public class CodeEmbeddingGenerator {
    private static final Logger LOG = Logger.getInstance(CodeEmbeddingGenerator.class);
    
    private final ASTPathExtractor astPathExtractor;
    private final CodeAwareTokenizer tokenizer;
    private final int maxAstPaths = 50;
    
    public CodeEmbeddingGenerator() {
        this.astPathExtractor = new ASTPathExtractor();
        this.tokenizer = new CodeAwareTokenizer();
    }
    
    /**
     * Generates a rich embedding content for a code signature.
     * Combines multiple representations for better search accuracy.
     */
    public EmbeddingContent generateEmbedding(CodeSignature signature, PsiElement psiElement) {
        EmbeddingContent content = new EmbeddingContent();
        
        try {
            // 1. Basic signature information
            content.setSignatureId(signature.getId());
            content.setSignatureText(signature.getSignature());
            
            // 2. Extract metadata
            JsonObject metadata = JsonParser.parseString(signature.getMetadata()).getAsJsonObject();
            String type = metadata.has("type") ? metadata.get("type").getAsString() : "unknown";
            content.setElementType(type);
            
            // 3. Tokenized representation
            List<String> tokens = tokenizer.tokenize(signature.getId(), false);
            content.setTokens(tokens);
            
            // 4. AST paths (if applicable) - need read action
            if (psiElement != null) {
                List<ASTPathExtractor.ASTPath> paths = ReadAction.compute(() -> {
                    if (psiElement instanceof PsiMethod) {
                        return astPathExtractor.extractPaths((PsiMethod) psiElement);
                    } else if (psiElement instanceof PsiClass) {
                        return astPathExtractor.extractPaths((PsiClass) psiElement);
                    }
                    return new ArrayList<>();
                });
                content.setAstPaths(limitPaths(paths));
            }
            
            // 5. Contextual information
            if (psiElement != null) {
                content.setContext(extractContext(psiElement));
            }
            
            // 6. Documentation
            if (metadata.has("javadoc") && !metadata.get("javadoc").isJsonNull()) {
                content.setDocumentation(metadata.get("javadoc").getAsString());
            }
            
            // 7. Code metrics
            if (psiElement != null) {
                content.setMetrics(calculateMetrics(psiElement));
            }
            
            // 8. Build combined text representation
            content.setCombinedText(buildCombinedText(content));
            
        } catch (Exception e) {
            LOG.error("Failed to generate embedding for: " + signature.getId(), e);
        }
        
        return content;
    }
    
    /**
     * Extracts contextual information from the PSI element.
     * Must be called from within a read action.
     */
    private ContextInfo extractContext(PsiElement element) {
        return ReadAction.compute(() -> {
            ContextInfo context = new ContextInfo();
            
            // Package and imports
            PsiFile file = element.getContainingFile();
            if (file instanceof PsiJavaFile) {
                PsiJavaFile javaFile = (PsiJavaFile) file;
                context.setPackageName(javaFile.getPackageName());
                
                // Get imports
                PsiImportList importList = javaFile.getImportList();
                if (importList != null) {
                    List<String> imports = Arrays.stream(importList.getImportStatements())
                        .map(PsiImportStatement::getQualifiedName)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                    context.setImports(imports);
                }
            }
            
            // Containing class info
            PsiClass containingClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);
            if (containingClass != null) {
                context.setContainingClass(containingClass.getQualifiedName());
                
                // Superclass and interfaces
                PsiClass superClass = containingClass.getSuperClass();
                if (superClass != null && !"java.lang.Object".equals(superClass.getQualifiedName())) {
                    context.setSuperClass(superClass.getQualifiedName());
                }
                
                List<String> interfaces = Arrays.stream(containingClass.getInterfaces())
                    .map(PsiClass::getQualifiedName)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
                context.setInterfaces(interfaces);
            }
            
            // Method context
            if (element instanceof PsiMethod) {
                PsiMethod method = (PsiMethod) element;
                
                // Called methods - collect without resolving
                Set<String> calledMethods = new HashSet<>();
                method.accept(new JavaRecursiveElementVisitor() {
                    @Override
                    public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                        // Just get the method name without resolving
                        String methodName = expression.getMethodExpression().getReferenceName();
                        if (methodName != null) {
                            calledMethods.add(methodName);
                        }
                        super.visitMethodCallExpression(expression);
                    }
                });
                context.setCalledMethods(new ArrayList<>(calledMethods));
                
                // Parameter types
                List<String> paramTypes = Arrays.stream(method.getParameterList().getParameters())
                    .map(param -> param.getType().getPresentableText())
                    .collect(Collectors.toList());
                context.setParameterTypes(paramTypes);
                
                // Return type
                PsiType returnType = method.getReturnType();
                if (returnType != null) {
                    context.setReturnType(returnType.getPresentableText());
                }
            }
            
            return context;
        });
    }
    
    /**
     * Calculates code metrics for the element.
     * Must be called from within a read action.
     */
    private CodeMetrics calculateMetrics(PsiElement element) {
        return ReadAction.compute(() -> {
            CodeMetrics metrics = new CodeMetrics();
            
            if (element instanceof PsiMethod) {
                PsiMethod method = (PsiMethod) element;
                
                // Lines of code
                String text = method.getText();
                metrics.setLinesOfCode((int) text.lines().count());
                
                // Cyclomatic complexity (simplified)
                metrics.setCyclomaticComplexity(calculateCyclomaticComplexity(method));
                
                // Number of parameters
                metrics.setParameterCount(method.getParameterList().getParametersCount());
                
                // Depth of nesting
                metrics.setMaxNestingDepth(calculateMaxNestingDepth(method));
                
                // Number of method calls
                int[] callCount = {0};
                method.accept(new JavaRecursiveElementVisitor() {
                    @Override
                    public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                        callCount[0]++;
                        super.visitMethodCallExpression(expression);
                    }
                });
                metrics.setMethodCallCount(callCount[0]);
                
            } else if (element instanceof PsiClass) {
                PsiClass clazz = (PsiClass) element;
                
                metrics.setMethodCount(clazz.getMethods().length);
                metrics.setFieldCount(clazz.getFields().length);
                
                // Lines of code
                String text = clazz.getText();
                metrics.setLinesOfCode((int) text.lines().count());
            }
            
            return metrics;
        });
    }
    
    /**
     * Calculates cyclomatic complexity of a method.
     */
    private int calculateCyclomaticComplexity(PsiMethod method) {
        final int[] complexity = {1}; // Base complexity
        
        method.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitIfStatement(PsiIfStatement statement) {
                complexity[0]++;
                super.visitIfStatement(statement);
            }
            
            @Override
            public void visitForStatement(PsiForStatement statement) {
                complexity[0]++;
                super.visitForStatement(statement);
            }
            
            @Override
            public void visitWhileStatement(PsiWhileStatement statement) {
                complexity[0]++;
                super.visitWhileStatement(statement);
            }
            
            @Override
            public void visitDoWhileStatement(PsiDoWhileStatement statement) {
                complexity[0]++;
                super.visitDoWhileStatement(statement);
            }
            
            @Override
            public void visitSwitchStatement(PsiSwitchStatement statement) {
                complexity[0] += statement.getBody().getStatements().length;
                super.visitSwitchStatement(statement);
            }
            
            @Override
            public void visitConditionalExpression(PsiConditionalExpression expression) {
                complexity[0]++;
                super.visitConditionalExpression(expression);
            }
            
            @Override
            public void visitCatchSection(PsiCatchSection section) {
                complexity[0]++;
                super.visitCatchSection(section);
            }
        });
        
        return complexity[0];
    }
    
    /**
     * Calculates maximum nesting depth.
     */
    private int calculateMaxNestingDepth(PsiMethod method) {
        int[] maxDepth = {0};
        
        method.accept(new JavaRecursiveElementVisitor() {
            private int currentDepth = 0;
            
            @Override
            public void visitCodeBlock(PsiCodeBlock block) {
                currentDepth++;
                maxDepth[0] = Math.max(maxDepth[0], currentDepth);
                super.visitCodeBlock(block);
                currentDepth--;
            }
        });
        
        return maxDepth[0];
    }
    
    /**
     * Limits the number of AST paths to avoid excessive data.
     */
    private List<ASTPathExtractor.ASTPath> limitPaths(List<ASTPathExtractor.ASTPath> paths) {
        if (paths.size() <= maxAstPaths) {
            return paths;
        }
        
        // Select diverse paths
        List<ASTPathExtractor.ASTPath> selected = new ArrayList<>();
        int step = paths.size() / maxAstPaths;
        
        for (int i = 0; i < paths.size() && selected.size() < maxAstPaths; i += step) {
            selected.add(paths.get(i));
        }
        
        return selected;
    }
    
    /**
     * Builds a combined text representation for embedding.
     */
    private String buildCombinedText(EmbeddingContent content) {
        StringBuilder sb = new StringBuilder();
        
        // Signature
        sb.append(content.getSignatureText()).append("\n\n");
        
        // Type and ID
        sb.append("Type: ").append(content.getElementType()).append("\n");
        sb.append("ID: ").append(content.getSignatureId()).append("\n\n");
        
        // Documentation
        if (content.getDocumentation() != null && !content.getDocumentation().isEmpty()) {
            sb.append("Documentation:\n").append(content.getDocumentation()).append("\n\n");
        }
        
        // Context
        ContextInfo ctx = content.getContext();
        if (ctx != null) {
            if (ctx.getPackageName() != null) {
                sb.append("Package: ").append(ctx.getPackageName()).append("\n");
            }
            if (ctx.getContainingClass() != null) {
                sb.append("Class: ").append(ctx.getContainingClass()).append("\n");
            }
            if (ctx.getSuperClass() != null) {
                sb.append("Extends: ").append(ctx.getSuperClass()).append("\n");
            }
            if (!ctx.getInterfaces().isEmpty()) {
                sb.append("Implements: ").append(String.join(", ", ctx.getInterfaces())).append("\n");
            }
            if (ctx.getReturnType() != null) {
                sb.append("Returns: ").append(ctx.getReturnType()).append("\n");
            }
            if (!ctx.getParameterTypes().isEmpty()) {
                sb.append("Parameters: ").append(String.join(", ", ctx.getParameterTypes())).append("\n");
            }
            if (!ctx.getCalledMethods().isEmpty()) {
                sb.append("Calls: ").append(String.join(", ", ctx.getCalledMethods())).append("\n");
            }
            sb.append("\n");
        }
        
        // Tokens
        if (!content.getTokens().isEmpty()) {
            sb.append("Tokens: ").append(String.join(" ", content.getTokens())).append("\n\n");
        }
        
        // AST Paths (sample)
        if (!content.getAstPaths().isEmpty()) {
            sb.append("AST Patterns:\n");
            // Include a few representative paths
            content.getAstPaths().stream()
                .limit(10)
                .forEach(path -> sb.append("- ").append(path.toFeature()).append("\n"));
            sb.append("\n");
        }
        
        // Metrics
        CodeMetrics metrics = content.getMetrics();
        if (metrics != null) {
            sb.append("Metrics: ");
            sb.append("LOC=").append(metrics.getLinesOfCode()).append(" ");
            if (metrics.getCyclomaticComplexity() > 0) {
                sb.append("CC=").append(metrics.getCyclomaticComplexity()).append(" ");
            }
            if (metrics.getParameterCount() > 0) {
                sb.append("Params=").append(metrics.getParameterCount()).append(" ");
            }
            sb.append("\n");
        }
        
        return sb.toString();
    }
    
    /**
     * Container for embedding content.
     */
    public static class EmbeddingContent {
        private String signatureId;
        private String signatureText;
        private String elementType;
        private List<String> tokens = new ArrayList<>();
        private List<ASTPathExtractor.ASTPath> astPaths = new ArrayList<>();
        private ContextInfo context;
        private String documentation;
        private CodeMetrics metrics;
        private String combinedText;
        
        // Getters and setters
        public String getSignatureId() { return signatureId; }
        public void setSignatureId(String signatureId) { this.signatureId = signatureId; }
        
        public String getSignatureText() { return signatureText; }
        public void setSignatureText(String signatureText) { this.signatureText = signatureText; }
        
        public String getElementType() { return elementType; }
        public void setElementType(String elementType) { this.elementType = elementType; }
        
        public List<String> getTokens() { return tokens; }
        public void setTokens(List<String> tokens) { this.tokens = tokens; }
        
        public List<ASTPathExtractor.ASTPath> getAstPaths() { return astPaths; }
        public void setAstPaths(List<ASTPathExtractor.ASTPath> astPaths) { this.astPaths = astPaths; }
        
        public ContextInfo getContext() { return context; }
        public void setContext(ContextInfo context) { this.context = context; }
        
        public String getDocumentation() { return documentation; }
        public void setDocumentation(String documentation) { this.documentation = documentation; }
        
        public CodeMetrics getMetrics() { return metrics; }
        public void setMetrics(CodeMetrics metrics) { this.metrics = metrics; }
        
        public String getCombinedText() { return combinedText; }
        public void setCombinedText(String combinedText) { this.combinedText = combinedText; }
    }
    
    /**
     * Context information for code elements.
     */
    public static class ContextInfo {
        private String packageName;
        private String containingClass;
        private String superClass;
        private List<String> interfaces = new ArrayList<>();
        private List<String> imports = new ArrayList<>();
        private List<String> calledMethods = new ArrayList<>();
        private List<String> parameterTypes = new ArrayList<>();
        private String returnType;
        
        // Getters and setters
        public String getPackageName() { return packageName; }
        public void setPackageName(String packageName) { this.packageName = packageName; }
        
        public String getContainingClass() { return containingClass; }
        public void setContainingClass(String containingClass) { this.containingClass = containingClass; }
        
        public String getSuperClass() { return superClass; }
        public void setSuperClass(String superClass) { this.superClass = superClass; }
        
        public List<String> getInterfaces() { return interfaces; }
        public void setInterfaces(List<String> interfaces) { this.interfaces = interfaces; }
        
        public List<String> getImports() { return imports; }
        public void setImports(List<String> imports) { this.imports = imports; }
        
        public List<String> getCalledMethods() { return calledMethods; }
        public void setCalledMethods(List<String> calledMethods) { this.calledMethods = calledMethods; }
        
        public List<String> getParameterTypes() { return parameterTypes; }
        public void setParameterTypes(List<String> parameterTypes) { this.parameterTypes = parameterTypes; }
        
        public String getReturnType() { return returnType; }
        public void setReturnType(String returnType) { this.returnType = returnType; }
    }
    
    /**
     * Code metrics for quality and complexity assessment.
     */
    public static class CodeMetrics {
        private int linesOfCode;
        private int cyclomaticComplexity;
        private int parameterCount;
        private int maxNestingDepth;
        private int methodCallCount;
        private int methodCount;
        private int fieldCount;
        
        // Getters and setters
        public int getLinesOfCode() { return linesOfCode; }
        public void setLinesOfCode(int linesOfCode) { this.linesOfCode = linesOfCode; }
        
        public int getCyclomaticComplexity() { return cyclomaticComplexity; }
        public void setCyclomaticComplexity(int cyclomaticComplexity) { this.cyclomaticComplexity = cyclomaticComplexity; }
        
        public int getParameterCount() { return parameterCount; }
        public void setParameterCount(int parameterCount) { this.parameterCount = parameterCount; }
        
        public int getMaxNestingDepth() { return maxNestingDepth; }
        public void setMaxNestingDepth(int maxNestingDepth) { this.maxNestingDepth = maxNestingDepth; }
        
        public int getMethodCallCount() { return methodCallCount; }
        public void setMethodCallCount(int methodCallCount) { this.methodCallCount = methodCallCount; }
        
        public int getMethodCount() { return methodCount; }
        public void setMethodCount(int methodCount) { this.methodCount = methodCount; }
        
        public int getFieldCount() { return fieldCount; }
        public void setFieldCount(int fieldCount) { this.fieldCount = fieldCount; }
    }
}
