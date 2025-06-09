package com.zps.zest.validation;

import com.intellij.openapi.diagnostic.Logger;

public class CommitTemplateValidator {
    private static final Logger LOG = Logger.getInstance(CommitTemplateValidator.class);
    
    public static class ValidationResult {
        public final boolean isValid;
        public final String errorMessage;
        
        public ValidationResult(boolean isValid, String errorMessage) {
            this.isValid = isValid;
            this.errorMessage = errorMessage;
        }
    }
    
    public static ValidationResult validate(String template) {
        if (template == null || template.trim().isEmpty()) {
            return new ValidationResult(false, "Template cannot be empty");
        }
        
        if (!template.contains("{FILES_LIST}")) {
            return new ValidationResult(false, "Template must contain {FILES_LIST} placeholder");
        }
        
        if (!template.contains("{DIFFS}")) {
            return new ValidationResult(false, "Template must contain {DIFFS} placeholder");
        }
        
        // Check for balanced braces
        int openBraces = 0;
        for (char c : template.toCharArray()) {
            if (c == '{') openBraces++;
            else if (c == '}') openBraces--;
            if (openBraces < 0) {
                return new ValidationResult(false, "Template has unbalanced braces");
            }
        }
        
        if (openBraces != 0) {
            return new ValidationResult(false, "Template has unbalanced braces");
        }
        
        return new ValidationResult(true, null);
    }
}
