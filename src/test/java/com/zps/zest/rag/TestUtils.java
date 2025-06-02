package com.zps.zest.rag;

import com.google.gson.JsonObject;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;

import java.util.Arrays;
import java.util.List;

/**
 * Utility methods for RAG system tests.
 */
public class TestUtils {
    
    /**
     * Creates a test code signature.
     */
    public static CodeSignature createTestSignature(String className, String type) {
        String id = className;
        String signature = "public " + type + " " + className;
        JsonObject metadata = new JsonObject();
        metadata.addProperty("type", type);
        
        return new CodeSignature(
            id,
            signature,
            metadata.toString(),
            "/src/test/" + className + ".java"
        );
    }
    
    /**
     * Creates a method signature.
     */
    public static CodeSignature createMethodSignature(String className, String methodName) {
        String id = className + "#" + methodName;
        String signature = "public void " + methodName + "()";
        JsonObject metadata = new JsonObject();
        metadata.addProperty("type", "method");
        metadata.addProperty("class", className);
        
        return new CodeSignature(
            id,
            signature,
            metadata.toString(),
            "/src/test/" + className + ".java"
        );
    }
    
    /**
     * Creates a field signature.
     */
    public static CodeSignature createFieldSignature(String className, String fieldName, String fieldType) {
        String id = className + "." + fieldName;
        String signature = "private " + fieldType + " " + fieldName;
        JsonObject metadata = new JsonObject();
        metadata.addProperty("type", "field");
        metadata.addProperty("class", className);
        metadata.addProperty("fieldType", fieldType);
        
        return new CodeSignature(
            id,
            signature,
            metadata.toString(),
            "/src/test/" + className + ".java"
        );
    }
    
    /**
     * Creates test project info.
     */
    public static ProjectInfo createTestProjectInfo(String buildSystem, String... dependencies) {
        ProjectInfo info = new ProjectInfo();
        info.setBuildSystem(buildSystem);
        info.setMainLanguage("Java");
        info.setTotalSourceFiles(10);
        
        Arrays.stream(dependencies).forEach(info::addDependency);
        
        return info;
    }
    
    /**
     * Simple mock progress indicator for testing.
     */
    public static class MockProgressIndicator implements ProgressIndicator {
        private volatile boolean canceled = false;
        private String text = "";
        private String text2 = "";
        private double fraction = 0;
        private boolean indeterminate = false;
        
        @Override
        public void start() {}
        
        @Override
        public void stop() {}
        
        @Override
        public boolean isRunning() { return !canceled; }
        
        @Override
        public void cancel() { canceled = true; }
        
        @Override
        public boolean isCanceled() { return canceled; }
        
        @Override
        public void setText(String text) { this.text = text; }
        
        @Override
        public String getText() { return text; }
        
        @Override
        public void setText2(String text) { this.text2 = text; }
        
        @Override
        public String getText2() { return text2; }
        
        @Override
        public double getFraction() { return fraction; }
        
        @Override
        public void setFraction(double fraction) { this.fraction = fraction; }
        
        @Override
        public void pushState() {}
        
        @Override
        public void popState() {}
        
        @Override
        public boolean isModal() { return false; }
        
        @Override
        public void setModalityProgress(ProgressIndicator modalityProgress) {}
        
        @Override
        public boolean isIndeterminate() { return indeterminate; }
        
        @Override
        public void setIndeterminate(boolean indeterminate) { 
            this.indeterminate = indeterminate; 
        }
        
        @Override
        public void checkCanceled() throws ProcessCanceledException {
            if (canceled) throw new ProcessCanceledException();
        }
        
        @Override
        public boolean isPopupWasShown() { return false; }
        
        @Override
        public boolean isShowing() { return true; }
        
        // Test helper methods
        public String getFullStatus() {
            return text + " - " + text2 + " (" + (int)(fraction * 100) + "%)";
        }
        
        public void reset() {
            canceled = false;
            text = "";
            text2 = "";
            fraction = 0;
            indeterminate = false;
        }
    }
}
