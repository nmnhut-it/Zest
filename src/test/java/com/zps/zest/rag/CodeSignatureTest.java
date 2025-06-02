package com.zps.zest.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CodeSignature using JUnit Jupiter features.
 */
@DisplayName("Code Signature Tests")
class CodeSignatureTest {
    
    @Test
    @DisplayName("Should create code signature with all fields")
    void testCodeSignatureCreation() {
        // Given
        String id = "com.test.MyClass#myMethod";
        String signature = "public void myMethod(String arg)";
        String metadata = "{\"type\":\"method\"}";
        String filePath = "/src/main/java/com/test/MyClass.java";
        
        // When
        CodeSignature codeSignature = new CodeSignature(id, signature, metadata, filePath);
        
        // Then
        assertAll("Code signature properties",
            () -> assertEquals(id, codeSignature.getId()),
            () -> assertEquals(signature, codeSignature.getSignature()),
            () -> assertEquals(metadata, codeSignature.getMetadata()),
            () -> assertEquals(filePath, codeSignature.getFilePath())
        );
    }
    
    @Test
    @DisplayName("Should return signature as toString")
    void testToString() {
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
    
    @ParameterizedTest(name = "ID: {0} should be valid: {1}")
    @CsvSource({
        "com.test.MyClass, true",
        "com.test.MyClass#method, true",
        "com.test.MyClass.field, true",
        "'', false",
        ", false",
        "'   ', false"
    })
    @DisplayName("Should validate signature IDs")
    void testIdValidation(String id, boolean shouldBeValid) {
        // This is an example of how you might add validation
        boolean isValid = id != null && !id.trim().isEmpty();
        assertEquals(shouldBeValid, isValid);
    }
    
    @Test
    @DisplayName("Should handle null values gracefully")
    void testNullHandling() {
        // When creating with nulls
        CodeSignature codeSignature = new CodeSignature(null, null, null, null);
        
        // Then should not throw and return nulls
        assertAll("Null handling",
            () -> assertNull(codeSignature.getId()),
            () -> assertNull(codeSignature.getSignature()),
            () -> assertNull(codeSignature.getMetadata()),
            () -> assertNull(codeSignature.getFilePath()),
            () -> assertEquals("null", codeSignature.toString())
        );
    }
}
