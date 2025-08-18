package com.zps.zest.testgen.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds metadata about the test class being generated.
 * This separates class-level information from individual test methods.
 */
public class TestClassMetadata {
    private final String packageName;
    private final String className;
    private final String framework;
    private final List<String> classImports;
    private final List<String> classAnnotations;
    private final List<String> fieldDeclarations;
    private final String beforeAllCode;
    private final String afterAllCode;
    private final String beforeEachCode;
    private final String afterEachCode;
    
    private TestClassMetadata(Builder builder) {
        this.packageName = builder.packageName;
        this.className = builder.className;
        this.framework = builder.framework;
        this.classImports = new ArrayList<>(builder.classImports);
        this.classAnnotations = new ArrayList<>(builder.classAnnotations);
        this.fieldDeclarations = new ArrayList<>(builder.fieldDeclarations);
        this.beforeAllCode = builder.beforeAllCode;
        this.afterAllCode = builder.afterAllCode;
        this.beforeEachCode = builder.beforeEachCode;
        this.afterEachCode = builder.afterEachCode;
    }
    
    @NotNull
    public String getPackageName() {
        return packageName;
    }
    
    @NotNull
    public String getClassName() {
        return className;
    }
    
    @NotNull
    public String getFramework() {
        return framework;
    }
    
    @NotNull
    public List<String> getClassImports() {
        return new ArrayList<>(classImports);
    }
    
    @NotNull
    public List<String> getClassAnnotations() {
        return new ArrayList<>(classAnnotations);
    }
    
    @NotNull
    public List<String> getFieldDeclarations() {
        return new ArrayList<>(fieldDeclarations);
    }
    
    @Nullable
    public String getBeforeAllCode() {
        return beforeAllCode;
    }
    
    @Nullable
    public String getAfterAllCode() {
        return afterAllCode;
    }
    
    @Nullable
    public String getBeforeEachCode() {
        return beforeEachCode;
    }
    
    @Nullable
    public String getAfterEachCode() {
        return afterEachCode;
    }
    
    public boolean hasSetupCode() {
        return beforeAllCode != null || beforeEachCode != null;
    }
    
    public boolean hasTeardownCode() {
        return afterAllCode != null || afterEachCode != null;
    }
    
    @Override
    public String toString() {
        return "TestClassMetadata{" +
               "className='" + className + '\'' +
               ", packageName='" + packageName + '\'' +
               ", framework='" + framework + '\'' +
               ", imports=" + classImports.size() +
               ", fields=" + fieldDeclarations.size() +
               '}';
    }
    
    public static class Builder {
        private String packageName = "";
        private String className = "";
        private String framework = "JUnit5";
        private final List<String> classImports = new ArrayList<>();
        private final List<String> classAnnotations = new ArrayList<>();
        private final List<String> fieldDeclarations = new ArrayList<>();
        private String beforeAllCode;
        private String afterAllCode;
        private String beforeEachCode;
        private String afterEachCode;
        
        public Builder packageName(@NotNull String packageName) {
            this.packageName = packageName;
            return this;
        }
        
        public Builder className(@NotNull String className) {
            this.className = className;
            return this;
        }
        
        public Builder framework(@NotNull String framework) {
            this.framework = framework;
            return this;
        }
        
        public Builder addImport(@NotNull String importStatement) {
            if (!classImports.contains(importStatement)) {
                classImports.add(importStatement);
            }
            return this;
        }
        
        public Builder addClassAnnotation(@NotNull String annotation) {
            if (!classAnnotations.contains(annotation)) {
                classAnnotations.add(annotation);
            }
            return this;
        }
        
        public Builder addFieldDeclaration(@NotNull String fieldDeclaration) {
            fieldDeclarations.add(fieldDeclaration);
            return this;
        }
        
        public Builder beforeAllCode(@Nullable String code) {
            this.beforeAllCode = code;
            return this;
        }
        
        public Builder afterAllCode(@Nullable String code) {
            this.afterAllCode = code;
            return this;
        }
        
        public Builder beforeEachCode(@Nullable String code) {
            this.beforeEachCode = code;
            return this;
        }
        
        public Builder afterEachCode(@Nullable String code) {
            this.afterEachCode = code;
            return this;
        }
        
        public TestClassMetadata build() {
            if (className.isEmpty()) {
                throw new IllegalStateException("Class name is required");
            }
            return new TestClassMetadata(this);
        }
    }
}