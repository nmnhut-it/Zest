package com.zps.zest.langchain4j;

import com.intellij.find.findUsages.*;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.usageView.UsageInfo;
import com.intellij.usages.Usage;
import com.intellij.usages.UsageInfo2UsageAdapter;
import com.intellij.util.Query;
import com.zps.zest.rag.CodeSignature;
import com.zps.zest.rag.SignatureExtractor;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Finds structurally related code using IntelliJ's Find Usages and PSI APIs.
 * Discovers method calls, class usages, override relationships, and field accesses.
 */
public class RelatedCodeFinder {
    private static final Logger LOG = Logger.getInstance(RelatedCodeFinder.class);
    
    private final Project project;
    private final SignatureExtractor signatureExtractor;
    
    public RelatedCodeFinder(Project project) {
        this.project = project;
        this.signatureExtractor = new SignatureExtractor();
    }
    
    /**
     * Finds all code related to a given signature ID.
     * 
     * @param signatureId The signature ID (e.g., "com.example.MyClass#myMethod")
     * @param maxResults Maximum number of related items to return
     * @return List of related code items with their relationships
     */
    public List<RelatedCodeItem> findRelatedCode(String signatureId, int maxResults) {
        return ReadAction.compute(() -> {
            try {
                PsiElement element = findPsiElement(signatureId);
                if (element == null) {
                    LOG.warn("Could not find PSI element for signature: " + signatureId);
                    return Collections.emptyList();
                }
                
                List<RelatedCodeItem> relatedItems = new ArrayList<>();
                
                // Find different types of relationships based on element type
                if (element instanceof PsiMethod) {
                    relatedItems.addAll(findMethodRelations((PsiMethod) element));
                } else if (element instanceof PsiClass) {
                    relatedItems.addAll(findClassRelations((PsiClass) element));
                } else if (element instanceof PsiField) {
                    relatedItems.addAll(findFieldRelations((PsiField) element));
                }
                
                // Sort by relevance and limit results
                return relatedItems.stream()
                    .sorted((a, b) -> Integer.compare(b.getRelevanceScore(), a.getRelevanceScore()))
                    .limit(maxResults)
                    .collect(Collectors.toList());
                    
            } catch (Exception e) {
                LOG.error("Error finding related code for: " + signatureId, e);
                return Collections.emptyList();
            }
        });
    }
    
    /**
     * Finds usages of multiple signatures and returns their relationships.
     * Useful for finding connections between search results.
     */
    public Map<String, List<RelatedCodeItem>> findRelatedCodeBatch(List<String> signatureIds, int maxPerSignature) {
        return ReadAction.compute(() -> {
            Map<String, List<RelatedCodeItem>> results = new HashMap<>();
            
            for (String signatureId : signatureIds) {
                results.put(signatureId, findRelatedCode(signatureId, maxPerSignature));
            }
            
            return results;
        });
    }
    
    private PsiElement findPsiElement(String signatureId) {
        if (signatureId.contains("#")) {
            // Method signature
            String[] parts = signatureId.split("#", 2);
            if (parts.length != 2) return null;
            
            String className = parts[0];
            String methodName = parts[1];
            
            PsiClass psiClass = JavaPsiFacade.getInstance(project)
                .findClass(className, GlobalSearchScope.projectScope(project));
            
            if (psiClass != null) {
                // Find the method - could be overloaded, so we take the first match
                for (PsiMethod method : psiClass.getMethods()) {
                    if (methodName.equals(method.getName())) {
                        return method;
                    }
                }
            }
        } else if (signatureId.contains(".") && Character.isLowerCase(signatureId.charAt(signatureId.lastIndexOf('.') + 1))) {
            // Field signature (heuristic: last part starts with lowercase)
            int lastDot = signatureId.lastIndexOf('.');
            String className = signatureId.substring(0, lastDot);
            String fieldName = signatureId.substring(lastDot + 1);
            
            PsiClass psiClass = JavaPsiFacade.getInstance(project)
                .findClass(className, GlobalSearchScope.projectScope(project));
            
            if (psiClass != null) {
                PsiField field = psiClass.findFieldByName(fieldName, true);
                if (field != null) return field;
            }
        } else {
            // Class signature
            PsiClass psiClass = JavaPsiFacade.getInstance(project)
                .findClass(signatureId, GlobalSearchScope.projectScope(project));
            return psiClass;
        }
        
        return null;
    }
    
    private List<RelatedCodeItem> findMethodRelations(PsiMethod method) {
        List<RelatedCodeItem> relations = new ArrayList<>();
        
        // 1. Find method calls (who calls this method)
        Query<PsiReference> references = MethodReferencesSearch.search(method, GlobalSearchScope.projectScope(project), true);
        for (PsiReference ref : references) {
            PsiElement caller = ref.getElement();
            PsiMethod callingMethod = PsiTreeUtil.getParentOfType(caller, PsiMethod.class);
            if (callingMethod != null) {
                relations.add(createRelatedItem(
                    callingMethod,
                    RelationType.CALLS,
                    "Calls " + method.getName(),
                    caller.getTextOffset()
                ));
            }
        }
        
        // 2. Find overrides (if this method overrides something)
        PsiMethod[] superMethods = method.findSuperMethods();
        for (PsiMethod superMethod : superMethods) {
            relations.add(createRelatedItem(
                superMethod,
                RelationType.OVERRIDES,
                method.getName() + " overrides this method",
                -1
            ));
        }
        
        // 3. Find implementations (if this is abstract/interface method)
        if (method.hasModifierProperty(PsiModifier.ABSTRACT) || method.getContainingClass().isInterface()) {
            Query<PsiMethod> overriders = OverridingMethodsSearch.search(method);
            for (PsiMethod overrider : overriders) {
                relations.add(createRelatedItem(
                    overrider,
                    RelationType.IMPLEMENTS,
                    "Implements " + method.getName(),
                    -1
                ));
            }
        }
        
        // 4. Find methods called by this method
        method.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(PsiMethodCallExpression expression) {
                PsiMethod called = expression.resolveMethod();
                if (called != null && !called.equals(method)) {
                    relations.add(createRelatedItem(
                        called,
                        RelationType.CALLED_BY,
                        "Called by " + method.getName(),
                        expression.getTextOffset()
                    ));
                }
                super.visitMethodCallExpression(expression);
            }
        });
        
        return relations;
    }
    
    private List<RelatedCodeItem> findClassRelations(PsiClass psiClass) {
        List<RelatedCodeItem> relations = new ArrayList<>();
        
        // 1. Find class usages (instantiations, type references)
        Query<PsiReference> references = ReferencesSearch.search(psiClass, GlobalSearchScope.projectScope(project));
        for (PsiReference ref : references) {
            PsiElement element = ref.getElement();
            PsiElement parent = element.getParent();
            
            // Check for instantiation
            if (parent instanceof PsiNewExpression) {
                PsiMethod constructor = PsiTreeUtil.getParentOfType(parent, PsiMethod.class);
                if (constructor != null) {
                    relations.add(createRelatedItem(
                        constructor,
                        RelationType.INSTANTIATES,
                        "Creates instance of " + psiClass.getName(),
                        parent.getTextOffset()
                    ));
                }
            }
            // Check for type usage
            else if (parent instanceof PsiTypeElement) {
                PsiElement context = PsiTreeUtil.getParentOfType(parent, PsiMethod.class, PsiField.class);
                if (context != null) {
                    relations.add(createRelatedItem(
                        context,
                        RelationType.USES_TYPE,
                        "Uses type " + psiClass.getName(),
                        element.getTextOffset()
                    ));
                }
            }
        }
        
        // 2. Find superclass/interfaces
        PsiClass superClass = psiClass.getSuperClass();
        if (superClass != null && !superClass.getQualifiedName().equals("java.lang.Object")) {
            relations.add(createRelatedItem(
                superClass,
                RelationType.EXTENDS,
                psiClass.getName() + " extends this class",
                -1
            ));
        }
        
        PsiClass[] interfaces = psiClass.getInterfaces();
        for (PsiClass iface : interfaces) {
            relations.add(createRelatedItem(
                iface,
                RelationType.IMPLEMENTS,
                psiClass.getName() + " implements this interface",
                -1
            ));
        }
        
        // 3. Find subclasses
        Query<PsiClass> subclasses = ClassInheritorsSearch.search(psiClass, GlobalSearchScope.projectScope(project), true);
        for (PsiClass subclass : subclasses) {
            RelationType type = subclass.isInterface() ? RelationType.EXTENDED_BY : RelationType.SUBCLASSED_BY;
            relations.add(createRelatedItem(
                subclass,
                type,
                "Extends/implements " + psiClass.getName(),
                -1
            ));
        }
        
        return relations;
    }
    
    private List<RelatedCodeItem> findFieldRelations(PsiField field) {
        List<RelatedCodeItem> relations = new ArrayList<>();
        
        // Find field accesses
        Query<PsiReference> references = ReferencesSearch.search(field, GlobalSearchScope.projectScope(project));
        for (PsiReference ref : references) {
            PsiElement accessor = ref.getElement();
            PsiMethod method = PsiTreeUtil.getParentOfType(accessor, PsiMethod.class);
            
            if (method != null) {
                // Determine if it's a read or write
                boolean isWrite = isFieldWrite(accessor);
                RelationType type = isWrite ? RelationType.WRITES_FIELD : RelationType.READS_FIELD;
                String description = (isWrite ? "Writes to " : "Reads ") + field.getName();
                
                relations.add(createRelatedItem(
                    method,
                    type,
                    description,
                    accessor.getTextOffset()
                ));
            }
        }
        
        return relations;
    }
    
    private boolean isFieldWrite(PsiElement fieldAccess) {
        PsiElement parent = fieldAccess.getParent();
        if (parent instanceof PsiAssignmentExpression) {
            PsiAssignmentExpression assignment = (PsiAssignmentExpression) parent;
            return assignment.getLExpression() == fieldAccess;
        }
        return false;
    }
    
    private RelatedCodeItem createRelatedItem(PsiElement element, RelationType type, String description, int usageOffset) {
        try {
            // Extract signature for the related element
            CodeSignature signature = null;
            PsiFile containingFile = element.getContainingFile();
            
            if (containingFile != null) {
                List<CodeSignature> signatures = signatureExtractor.extractFromFile(containingFile);
                
                // Find matching signature
                String elementId = getElementId(element);
                for (CodeSignature sig : signatures) {
                    if (sig.getId().equals(elementId)) {
                        signature = sig;
                        break;
                    }
                }
            }
            
            // Create the related item
            RelatedCodeItem item = new RelatedCodeItem(
                getElementId(element),
                type,
                description,
                element.getText(),
                containingFile != null ? containingFile.getVirtualFile().getPath() : null,
                usageOffset
            );
            
            if (signature != null) {
                item.setSignature(signature);
            }
            
            return item;
            
        } catch (Exception e) {
            LOG.error("Error creating related item for element: " + element, e);
            return new RelatedCodeItem(
                "unknown",
                type,
                description,
                element.getText(),
                null,
                usageOffset
            );
        }
    }
    
    private String getElementId(PsiElement element) {
        if (element instanceof PsiMethod) {
            PsiMethod method = (PsiMethod) element;
            PsiClass containingClass = method.getContainingClass();
            if (containingClass != null) {
                return containingClass.getQualifiedName() + "#" + method.getName();
            }
        } else if (element instanceof PsiClass) {
            return ((PsiClass) element).getQualifiedName();
        } else if (element instanceof PsiField) {
            PsiField field = (PsiField) element;
            PsiClass containingClass = field.getContainingClass();
            if (containingClass != null) {
                return containingClass.getQualifiedName() + "." + field.getName();
            }
        }
        return element.toString();
    }
    
    /**
     * Represents a code element related to a signature.
     */
    public static class RelatedCodeItem {
        private final String elementId;
        private final RelationType relationType;
        private final String description;
        private final String codeSnippet;
        private final String filePath;
        private final int usageOffset;
        private CodeSignature signature;
        
        public RelatedCodeItem(String elementId, RelationType relationType, String description,
                               String codeSnippet, String filePath, int usageOffset) {
            this.elementId = elementId;
            this.relationType = relationType;
            this.description = description;
            this.codeSnippet = truncateCode(codeSnippet);
            this.filePath = filePath;
            this.usageOffset = usageOffset;
        }
        
        private static String truncateCode(String code) {
            if (code == null) return "";
            // Keep first 200 characters for snippet
            if (code.length() > 200) {
                return code.substring(0, 200) + "...";
            }
            return code;
        }
        
        public int getRelevanceScore() {
            // Assign relevance scores based on relationship type
            switch (relationType) {
                case CALLS:
                case CALLED_BY:
                    return 10;
                case OVERRIDES:
                case IMPLEMENTS:
                    return 9;
                case EXTENDS:
                case EXTENDED_BY:
                case SUBCLASSED_BY:
                    return 8;
                case INSTANTIATES:
                    return 7;
                case READS_FIELD:
                case WRITES_FIELD:
                    return 6;
                case USES_TYPE:
                    return 5;
                default:
                    return 1;
            }
        }
        
        // Getters
        public String getElementId() { return elementId; }
        public RelationType getRelationType() { return relationType; }
        public String getDescription() { return description; }
        public String getCodeSnippet() { return codeSnippet; }
        public String getFilePath() { return filePath; }
        public int getUsageOffset() { return usageOffset; }
        public CodeSignature getSignature() { return signature; }
        public void setSignature(CodeSignature signature) { this.signature = signature; }
    }
    
    /**
     * Types of relationships between code elements.
     */
    public enum RelationType {
        CALLS("calls"),
        CALLED_BY("called by"),
        OVERRIDES("overrides"),
        IMPLEMENTS("implements"),
        EXTENDS("extends"),
        EXTENDED_BY("extended by"),
        SUBCLASSED_BY("subclassed by"),
        INSTANTIATES("instantiates"),
        USES_TYPE("uses type"),
        READS_FIELD("reads field"),
        WRITES_FIELD("writes field");
        
        private final String displayName;
        
        RelationType(String displayName) {
            this.displayName = displayName;
        }
        
        public String getDisplayName() {
            return displayName;
        }
    }
}
