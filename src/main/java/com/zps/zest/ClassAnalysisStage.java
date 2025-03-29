package com.zps.zest;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set; /**
 * Stage for analyzing the class and gathering context information.
 */
public class ClassAnalysisStage implements PipelineStage {
    @Override
    public void process(TestGenerationContext context) throws PipelineExecutionException {
        PsiFile psiFile = context.getPsiFile();
        PsiClass targetClass = context.getTargetClass();
        StringBuilder importBuilder = new StringBuilder();

        ReadAction.run(()->{
            // Collect imports from the current file
            if (psiFile instanceof PsiJavaFile) {
                for (PsiImportStatement importStatement : ((PsiJavaFile) psiFile).getImportList().getImportStatements()) {
                    importBuilder.append(getTextOfPsiElement(importStatement)).append("\n");
                }
            }


        context.setImports(importBuilder.toString());

        // Collect class-level information
        String classContext = ApplicationManager.getApplication().runReadAction(
                (Computable<String>) () -> collectClassContext(context.getProject(), targetClass));
        context.setClassContext(classContext);

        // Detect JUnit version
        boolean isJUnit5 = ApplicationManager.getApplication().runReadAction((Computable<Boolean>) () ->
                JavaPsiFacade.getInstance(context.getProject()).findClass(
                        "org.junit.jupiter.api.Test", GlobalSearchScope.allScope(context.getProject())) != null);

        context.setJunitVersion(isJUnit5 ? "JUnit 5" : "JUnit 4");

        // Detect Mockito presence
        boolean hasMockito = ApplicationManager.getApplication().runReadAction((Computable<Boolean>) () ->
                JavaPsiFacade.getInstance(context.getProject()).findClass(
                        "org.mockito.Mockito", GlobalSearchScope.allScope(context.getProject())) != null);

        context.setMockitoPresent(hasMockito);
        });
    }

    private String collectClassContext(Project project, PsiClass targetClass) {
        StringBuilder contextInfo = new StringBuilder();

        // Class structure info
        contextInfo.append("Class structure:\n```java\n");
        contextInfo.append(getTextOfPsiElement(targetClass));
        contextInfo.append("\n```\n\n");

        // Add class-level javadoc if available
        PsiDocComment classJavadoc = targetClass.getDocComment();
        if (classJavadoc != null) {
            contextInfo.append("Class JavaDoc:\n```java\n");
            contextInfo.append(getTextOfPsiElement(classJavadoc));
            contextInfo.append("\n```\n\n");
        }

        // Collect and include code from related classes
        contextInfo.append("Related Classes:\n\n");
        contextInfo.append("Only use methods and constructors explicitly shown in the source code. Do not invent or assume the existence of methods that aren't documented in the provided class information.\n\n");
        Set<PsiClass> relatedClasses = new HashSet<>();

        // Add implemented interfaces
        PsiClassType[] interfaces = targetClass.getImplementsListTypes();
        for (PsiClassType interfaceType : interfaces) {
            PsiClass interfaceClass = interfaceType.resolve();
            if (interfaceClass != null) {
                relatedClasses.add(interfaceClass);
            }
        }

        // Add superclass
        PsiClass superClass = targetClass.getSuperClass();
        if (superClass != null && !superClass.getQualifiedName().startsWith("java.")) {
            relatedClasses.add(superClass);
        }

        // Add field types
        PsiField[] fields = targetClass.getFields();
        for (PsiField field : fields) {
            PsiType fieldType = field.getType();
            if (fieldType instanceof PsiClassType) {
                PsiClass fieldClass = ((PsiClassType) fieldType).resolve();
                if (fieldClass != null && !fieldClass.getQualifiedName().startsWith("java.")) {
                    relatedClasses.add(fieldClass);
                }
            }
        }

        // Find classes used in method bodies
        targetClass.accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitReferenceExpression(PsiReferenceExpression expression) {
                super.visitReferenceExpression(expression);
                PsiElement resolved = expression.resolve();
                if (resolved instanceof PsiClass) {
                    PsiClass resolvedClass = (PsiClass) resolved;
                    if (!resolvedClass.getQualifiedName().startsWith("java.") &&
                            !resolvedClass.getQualifiedName().startsWith("javax.")) {
                        relatedClasses.add(resolvedClass);
                    }
                }
            }

            @Override
            public void visitNewExpression(PsiNewExpression expression) {
                super.visitNewExpression(expression);
                PsiJavaCodeReferenceElement classRef = expression.getClassReference();
                if (classRef != null) {
                    PsiElement resolved = classRef.resolve();
                    if (resolved instanceof PsiClass) {
                        PsiClass resolvedClass = (PsiClass) resolved;
                        if (!resolvedClass.getQualifiedName().startsWith("java.") &&
                                !resolvedClass.getQualifiedName().startsWith("javax.")) {
                            relatedClasses.add(resolvedClass);
                        }
                    }
                }
            }

            @Override
            public void visitMethod(@NotNull PsiMethod method) {
                super.visitMethod(method);

                PsiParameter[] parameters = method.getParameterList().getParameters();
                for (PsiParameter parameter : parameters) {
                    PsiType paramType = parameter.getType();
                    if (paramType instanceof PsiClassType) {
                        PsiClass paramClass = ((PsiClassType) paramType).resolve();
                        if (paramClass != null &&
                                !paramClass.getQualifiedName().startsWith("java.") &&
                                !paramClass.getQualifiedName().startsWith("javax.")) {
                            relatedClasses.add(paramClass);
                        }
                    }
                }
            }

        });

        // Add code snippets for each related class
        for (PsiClass cls : relatedClasses) {
            if (cls.getQualifiedName() == null) continue;

            contextInfo.append(cls.isInterface() ?"Interface: ": "Class: ").append(cls.getQualifiedName()).append("\n");

            // Include class structure (simplified for related classes)
            contextInfo.append("```java\n");
            // Just show class declaration and method signatures for brevity
            contextInfo.append("public ");
            if (cls.isInterface()) contextInfo.append("interface ");
            else contextInfo.append("class ");
            contextInfo.append(cls.getName());

            // Add extends
            PsiClass superClassType = cls.getSuperClass();
            if (superClassType != null && !Objects.equals(superClassType.getName(), "Object")) {
                contextInfo.append(" extends ").append(superClassType.getName());
            }

            // Add implements
            PsiClassType[] implementsTypes = cls.getImplementsListTypes();
            if (implementsTypes.length > 0) {
                contextInfo.append(" implements ");
                for (int i = 0; i < implementsTypes.length; i++) {
                    contextInfo.append(implementsTypes[i].getClassName());
                    if (i < implementsTypes.length - 1) {
                        contextInfo.append(", ");
                    }
                }
            }
            contextInfo.append(" {\n");
// Add constructors
            for (PsiMethod constructor : cls.getConstructors()) {
                if (constructor.hasModifierProperty(PsiModifier.PUBLIC) ||
                        constructor.hasModifierProperty(PsiModifier.PROTECTED)) {
                    contextInfo.append("    ");
                    if (constructor.hasModifierProperty(PsiModifier.PUBLIC)) contextInfo.append("public ");
                    if (constructor.hasModifierProperty(PsiModifier.PROTECTED)) contextInfo.append("protected ");

                    // Constructor name is the class name
                    contextInfo.append(cls.getName()).append("(");

                    PsiParameter[] parameters = constructor.getParameterList().getParameters();
                    for (int i = 0; i < parameters.length; i++) {
                        PsiParameter param = parameters[i];
                        contextInfo.append(param.getType().getPresentableText())
                                .append(" ")
                                .append(param.getName());
                        if (i < parameters.length - 1) {
                            contextInfo.append(", ");
                        }
                    }
                    contextInfo.append(");\n");
                }
            }

            // Add method signatures
            for (PsiMethod method : cls.getMethods()) {
                if (!method.isConstructor() && method.hasModifierProperty(PsiModifier.PUBLIC)) {
                    contextInfo.append("    ");
                    if (method.hasModifierProperty(PsiModifier.PUBLIC)) contextInfo.append("public ");
                    if (method.hasModifierProperty(PsiModifier.PROTECTED)) contextInfo.append("protected ");
                    if (method.hasModifierProperty(PsiModifier.STATIC)) contextInfo.append("static ");
                    if (method.hasModifierProperty(PsiModifier.FINAL)) contextInfo.append("final ");

                    PsiType returnType = method.getReturnType();
                    if (returnType != null) {
                        contextInfo.append(returnType.getPresentableText()).append(" ");
                    }

                    contextInfo.append(method.getName()).append("(");

                    PsiParameter[] parameters = method.getParameterList().getParameters();
                    for (int i = 0; i < parameters.length; i++) {
                        PsiParameter param = parameters[i];
                        contextInfo.append(param.getType().getPresentableText())
                                .append(" ")
                                .append(param.getName());
                        if (i < parameters.length - 1) {
                            contextInfo.append(", ");
                        }
                    }
                    contextInfo.append(");\n");
                }
            }
            contextInfo.append("}\n");
            contextInfo.append("```\n\n");
        }

        return contextInfo.toString();
    }

    private static String getTextOfPsiElement(PsiElement element) {
        return ApplicationManager.getApplication().runReadAction(
                (ThrowableComputable<String, RuntimeException>) () -> element.getText());
    }
}
