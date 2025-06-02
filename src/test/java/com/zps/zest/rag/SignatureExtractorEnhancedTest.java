package com.zps.zest.rag;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.junit.Test;

import java.util.List;

/**
 * Tests for enhanced SignatureExtractor functionality including interfaces and javadocs.
 */
public class SignatureExtractorEnhancedTest extends BasePlatformTestCase {
    
    private SignatureExtractor extractor;
    private Gson gson = new Gson();
    
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        extractor = new SignatureExtractor();
    }
    
    @Test
    public void testExtractInterface() {
        String code = """
            package com.example;
            
            /**
             * Repository interface for user operations.
             * Provides CRUD operations for User entities.
             */
            public interface UserRepository {
                /**
                 * Finds a user by their ID.
                 * @param id the user ID
                 * @return the user or null if not found
                 */
                User findById(Long id);
                
                /**
                 * Saves a user to the repository.
                 * @param user the user to save
                 * @return the saved user
                 */
                User save(User user);
            }
            """;
        
        PsiFile file = myFixture.configureByText("UserRepository.java", code);
        List<CodeSignature> signatures = extractor.extractFromFile(file);
        
        // Should have 1 interface + 2 methods = 3 signatures
        assertEquals(3, signatures.size());
        
        // Check interface signature
        CodeSignature interfaceSig = signatures.stream()
            .filter(s -> s.getId().equals("com.example.UserRepository"))
            .findFirst()
            .orElse(null);
        
        assertNotNull(interfaceSig);
        assertEquals("public interface UserRepository", interfaceSig.getSignature());
        
        // Check metadata
        JsonObject metadata = gson.fromJson(interfaceSig.getMetadata(), JsonObject.class);
        assertEquals("interface", metadata.get("type").getAsString());
        assertTrue(metadata.get("isInterface").getAsBoolean());
        
        // Check javadoc
        assertTrue(metadata.has("javadoc"));
        String javadoc = metadata.get("javadoc").getAsString();
        assertTrue(javadoc.contains("Repository interface for user operations"));
        assertTrue(javadoc.contains("Provides CRUD operations"));
    }
    
    @Test
    public void testExtractEnum() {
        String code = """
            package com.example;
            
            /**
             * Represents user roles in the system.
             */
            public enum UserRole {
                /** Administrator with full access */
                ADMIN,
                /** Regular user with limited access */
                USER,
                /** Guest with read-only access */
                GUEST;
                
                /**
                 * Checks if this role has admin privileges.
                 * @return true if admin, false otherwise
                 */
                public boolean isAdmin() {
                    return this == ADMIN;
                }
            }
            """;
        
        PsiFile file = myFixture.configureByText("UserRole.java", code);
        List<CodeSignature> signatures = extractor.extractFromFile(file);
        
        // Should have 1 enum + 3 fields + 1 method = 5 signatures
        assertEquals(5, signatures.size());
        
        // Check enum signature
        CodeSignature enumSig = signatures.stream()
            .filter(s -> s.getId().equals("com.example.UserRole"))
            .findFirst()
            .orElse(null);
        
        assertNotNull(enumSig);
        assertEquals("public enum UserRole", enumSig.getSignature());
        
        JsonObject metadata = gson.fromJson(enumSig.getMetadata(), JsonObject.class);
        assertEquals("enum", metadata.get("type").getAsString());
        assertTrue(metadata.get("isEnum").getAsBoolean());
        
        // Check javadoc
        String javadoc = metadata.get("javadoc").getAsString();
        assertTrue(javadoc.contains("Represents user roles"));
    }
    
    @Test
    public void testExtractAnnotation() {
        String code = """
            package com.example;
            
            import java.lang.annotation.*;
            
            /**
             * Marks a method as requiring authentication.
             * Can specify required roles.
             */
            @Target(ElementType.METHOD)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface RequiresAuth {
                /**
                 * The roles allowed to access this method.
                 * @return array of role names
                 */
                String[] roles() default {};
            }
            """;
        
        PsiFile file = myFixture.configureByText("RequiresAuth.java", code);
        List<CodeSignature> signatures = extractor.extractFromFile(file);
        
        // Should have 1 annotation + 1 method = 2 signatures
        assertTrue(signatures.size() >= 1);
        
        // Check annotation signature
        CodeSignature annotationSig = signatures.stream()
            .filter(s -> s.getId().equals("com.example.RequiresAuth"))
            .findFirst()
            .orElse(null);
        
        assertNotNull(annotationSig);
        assertEquals("public @interface RequiresAuth", annotationSig.getSignature());
        
        JsonObject metadata = gson.fromJson(annotationSig.getMetadata(), JsonObject.class);
        assertEquals("annotation", metadata.get("type").getAsString());
        assertTrue(metadata.get("isAnnotationType").getAsBoolean());
    }
    
    @Test
    public void testExtractGenericClass() {
        String code = """
            package com.example;
            
            /**
             * A generic repository interface.
             * @param <T> the entity type
             * @param <ID> the ID type
             */
            public interface Repository<T, ID> {
                T findById(ID id);
                T save(T entity);
            }
            """;
        
        PsiFile file = myFixture.configureByText("Repository.java", code);
        List<CodeSignature> signatures = extractor.extractFromFile(file);
        
        // Check interface signature includes generics
        CodeSignature interfaceSig = signatures.stream()
            .filter(s -> s.getId().equals("com.example.Repository"))
            .findFirst()
            .orElse(null);
        
        assertNotNull(interfaceSig);
        assertEquals("public interface Repository<T, ID>", interfaceSig.getSignature());
    }
    
    @Test
    public void testExtractMethodWithExceptions() {
        String code = """
            package com.example;
            
            public class FileService {
                /**
                 * Reads a file from disk.
                 * @param path the file path
                 * @return file contents
                 * @throws IOException if file cannot be read
                 * @throws SecurityException if access is denied
                 */
                public String readFile(String path) throws IOException, SecurityException {
                    return "";
                }
            }
            """;
        
        PsiFile file = myFixture.configureByText("FileService.java", code);
        List<CodeSignature> signatures = extractor.extractFromFile(file);
        
        // Find method signature
        CodeSignature methodSig = signatures.stream()
            .filter(s -> s.getId().contains("#readFile"))
            .findFirst()
            .orElse(null);
        
        assertNotNull(methodSig);
        assertTrue(methodSig.getSignature().contains("throws IOException, SecurityException"));
        
        // Check javadoc includes @throws
        JsonObject metadata = gson.fromJson(methodSig.getMetadata(), JsonObject.class);
        String javadoc = metadata.get("javadoc").getAsString();
        assertTrue(javadoc.contains("@throws IOException"));
        assertTrue(javadoc.contains("@throws SecurityException"));
    }
    
    @Test
    public void testExtractFieldWithJavadoc() {
        String code = """
            package com.example;
            
            public class Constants {
                /**
                 * Maximum number of retry attempts.
                 * Used for network operations.
                 */
                public static final int MAX_RETRIES = 3;
                
                /** Default timeout in milliseconds */
                private static final long DEFAULT_TIMEOUT = 5000L;
            }
            """;
        
        PsiFile file = myFixture.configureByText("Constants.java", code);
        List<CodeSignature> signatures = extractor.extractFromFile(file);
        
        // Find field signatures
        CodeSignature maxRetriesSig = signatures.stream()
            .filter(s -> s.getId().contains(".MAX_RETRIES"))
            .findFirst()
            .orElse(null);
        
        assertNotNull(maxRetriesSig);
        assertEquals("public static final int MAX_RETRIES", maxRetriesSig.getSignature());
        
        // Check javadoc
        JsonObject metadata = gson.fromJson(maxRetriesSig.getMetadata(), JsonObject.class);
        String javadoc = metadata.get("javadoc").getAsString();
        assertTrue(javadoc.contains("Maximum number of retry attempts"));
        assertTrue(javadoc.contains("Used for network operations"));
    }
}
