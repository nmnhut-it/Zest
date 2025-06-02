package com.zps.zest.rag;

import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

import java.util.List;

/**
 * Unit tests for SignatureExtractor using JUnit 4.
 * Note: This extends IntelliJ's test base class for PSI support.
 */
public class SignatureExtractorTest extends LightJavaCodeInsightFixtureTestCase {
    
    private SignatureExtractor extractor;
    
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        extractor = new SignatureExtractor();
    }
    
    public void testExtractJavaClassSignature() {
        // Given
        PsiFile file = myFixture.configureByText("TestClass.java", 
            "package com.test;\n" +
            "public abstract class TestClass extends BaseClass implements Serializable {\n" +
            "}"
        );
        
        // When
        List<CodeSignature> signatures = extractor.extractFromFile(file);
        
        // Then
        assertEquals(1, signatures.size());
        CodeSignature sig = signatures.get(0);
        assertEquals("com.test.TestClass", sig.getId());
        assertEquals("public abstract class TestClass extends BaseClass implements Serializable", 
                     sig.getSignature());
        assertTrue(sig.getMetadata().contains("\"isAbstract\":true"));
    }
    
    public void testExtractJavaMethodSignatures() {
        // Given
        PsiFile file = myFixture.configureByText("Service.java",
            "package com.test;\n" +
            "public class Service {\n" +
            "    public String process(String input, int count) {\n" +
            "        return input;\n" +
            "    }\n" +
            "    private static void helper() {}\n" +
            "}"
        );
        
        // When
        List<CodeSignature> signatures = extractor.extractFromFile(file);
        
        // Then
        assertEquals(3, signatures.size()); // 1 class + 2 methods
        
        // Find method signatures
        CodeSignature processMethod = signatures.stream()
            .filter(s -> s.getId().endsWith("#process"))
            .findFirst().orElse(null);
        
        assertNotNull(processMethod);
        assertEquals("com.test.Service#process", processMethod.getId());
        assertEquals("public String process(String input, int count)", processMethod.getSignature());
        
        CodeSignature helperMethod = signatures.stream()
            .filter(s -> s.getId().endsWith("#helper"))
            .findFirst().orElse(null);
        
        assertNotNull(helperMethod);
        assertEquals("private static void helper()", helperMethod.getSignature());
        assertTrue(helperMethod.getMetadata().contains("\"isStatic\":true"));
    }
    
    public void testExtractJavaFieldSignatures() {
        // Given
        PsiFile file = myFixture.configureByText("Model.java",
            "package com.test;\n" +
            "public class Model {\n" +
            "    private final String name;\n" +
            "    public static int count = 0;\n" +
            "}"
        );
        
        // When
        List<CodeSignature> signatures = extractor.extractFromFile(file);
        
        // Then
        assertEquals(3, signatures.size()); // 1 class + 2 fields
        
        CodeSignature nameField = signatures.stream()
            .filter(s -> s.getId().endsWith(".name"))
            .findFirst().orElse(null);
        
        assertNotNull(nameField);
        assertEquals("private final String name", nameField.getSignature());
        assertTrue(nameField.getMetadata().contains("\"isFinal\":true"));
    }
    
    public void testExtractKotlinClassSignature() {
        // Given
        PsiFile file = myFixture.configureByText("DataClass.kt",
            "package com.test\n" +
            "data class DataClass(val id: String, var count: Int)"
        );
        
        // When
        List<CodeSignature> signatures = extractor.extractFromFile(file);
        
        // Then
        assertTrue(signatures.size() >= 1); // At least the class
        CodeSignature classSig = signatures.stream()
            .filter(s -> s.getMetadata().contains("\"type\":\"class\""))
            .findFirst().orElse(null);
        
        assertNotNull(classSig);
        assertTrue(classSig.getSignature().contains("data class DataClass"));
        assertTrue(classSig.getMetadata().contains("\"isData\":true"));
    }
    
    public void testExtractKotlinFunctionSignature() {
        // Given
        PsiFile file = myFixture.configureByText("Utils.kt",
            "package com.test\n" +
            "suspend fun fetchData(url: String): String {\n" +
            "    return \"\"\n" +
            "}"
        );
        
        // When
        List<CodeSignature> signatures = extractor.extractFromFile(file);
        
        // Then
        assertEquals(1, signatures.size());
        CodeSignature funcSig = signatures.get(0);
        assertTrue(funcSig.getSignature().contains("suspend fun fetchData"));
        assertTrue(funcSig.getMetadata().contains("\"isSuspend\":true"));
    }
    
    public void testIgnoresConstructors() {
        // Given
        PsiFile file = myFixture.configureByText("Builder.java",
            "public class Builder {\n" +
            "    public Builder() {}\n" +
            "    public void build() {}\n" +
            "}"
        );
        
        // When
        List<CodeSignature> signatures = extractor.extractFromFile(file);
        
        // Then
        assertEquals(2, signatures.size()); // Class + build method (no constructor)
        assertTrue(signatures.stream().noneMatch(s -> s.getId().contains("Builder#Builder")));
    }
    
    public void testHandlesInnerClasses() {
        // Given
        PsiFile file = myFixture.configureByText("Outer.java",
            "public class Outer {\n" +
            "    public static class Inner {\n" +
            "        public void innerMethod() {}\n" +
            "    }\n" +
            "}"
        );
        
        // When
        List<CodeSignature> signatures = extractor.extractFromFile(file);
        
        // Then
        assertTrue(signatures.size() >= 2); // Outer + Inner classes
        assertTrue(signatures.stream().anyMatch(s -> s.getId().contains("Outer")));
        assertTrue(signatures.stream().anyMatch(s -> s.getId().contains("Inner")));
    }
    
    public void testHandlesAnonymousClasses() {
        // Given
        PsiFile file = myFixture.configureByText("Container.java",
            "public class Container {\n" +
            "    Runnable r = new Runnable() {\n" +
            "        public void run() {}\n" +
            "    };\n" +
            "}"
        );
        
        // When
        List<CodeSignature> signatures = extractor.extractFromFile(file);
        
        // Then
        // Should extract Container class and field, but not anonymous class
        assertTrue(signatures.stream().anyMatch(s -> s.getId().equals("Container")));
        assertTrue(signatures.stream().anyMatch(s -> s.getId().endsWith(".r")));
    }
    
    public void testHandlesGenericTypes() {
        // Given
        PsiFile file = myFixture.configureByText("GenericService.java",
            "public class GenericService<T> {\n" +
            "    public List<T> process(Map<String, T> input) {\n" +
            "        return null;\n" +
            "    }\n" +
            "}"
        );
        
        // When
        List<CodeSignature> signatures = extractor.extractFromFile(file);
        
        // Then
        CodeSignature methodSig = signatures.stream()
            .filter(s -> s.getId().endsWith("#process"))
            .findFirst().orElse(null);
        
        assertNotNull(methodSig);
        assertTrue(methodSig.getSignature().contains("List<T>"));
        assertTrue(methodSig.getSignature().contains("Map<String, T>"));
    }
}
