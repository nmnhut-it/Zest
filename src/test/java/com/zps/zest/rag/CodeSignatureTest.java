package com.zps.zest.rag;

import junit.framework.TestCase;

/**
 * Unit tests for CodeSignature using JUnit 4.
 */
public class CodeSignatureTest extends TestCase {
    
    public void testCodeSignatureCreation() {
        // Given
        String id = "com.test.MyClass#myMethod";
        String signature = "public void myMethod(String arg)";
        String metadata = "{\"type\":\"method\"}";
        String filePath = "/src/main/java/com/test/MyClass.java";
        
        // When
        CodeSignature codeSignature = new CodeSignature(id, signature, metadata, filePath);
        
        // Then
        assertEquals(id, codeSignature.getId());
        assertEquals(signature, codeSignature.getSignature());
        assertEquals(metadata, codeSignature.getMetadata());
        assertEquals(filePath, codeSignature.getFilePath());
    }
    
    public void testToString() {
        // Given
        CodeSignature codeSignature = new CodeSignature(
            "com.test.MyClass",
            "public class MyClass",
            "{}",
            "/path/to/file.java"
        );
        
        // When
        String result = codeSignature.toString();
        
        // Then
        assertEquals("public class MyClass", result);
    }
    
    public void testIdValidation() {
        // Test valid IDs
        assertTrue(isValidId("com.test.MyClass"));
        assertTrue(isValidId("com.test.MyClass#method"));
        assertTrue(isValidId("com.test.MyClass.field"));
        
        // Test invalid IDs
        assertFalse(isValidId(""));
        assertFalse(isValidId(null));
        assertFalse(isValidId("   "));
    }
    
    public void testNullHandling() {
        // When creating with nulls
        CodeSignature codeSignature = new CodeSignature(null, null, null, null);
        
        // Then should not throw and return nulls
        assertNull(codeSignature.getId());
        assertNull(codeSignature.getSignature());
        assertNull(codeSignature.getMetadata());
        assertNull(codeSignature.getFilePath());
        assertEquals("null", codeSignature.toString());
    }
    
    // Helper method for ID validation
    private boolean isValidId(String id) {
        return id != null && !id.trim().isEmpty();
    }
}
