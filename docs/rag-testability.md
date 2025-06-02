# RAG System Testability Guide

## Design Principles

The RAG system is designed with testability as a core principle:

1. **Dependency Injection** - All major components accept dependencies through constructors
2. **Interface-Based Design** - Key components have interfaces for easy mocking
3. **Separation of Concerns** - Business logic is separated from IntelliJ platform APIs
4. **Pure Functions** - Many methods are pure functions that are easy to test

## Key Interfaces

### KnowledgeApiClient
Abstracts all API operations:
```java
public interface KnowledgeApiClient {
    String createKnowledgeBase(String name, String description) throws IOException;
    String uploadFile(String fileName, String content) throws IOException;
    void addFileToKnowledge(String knowledgeId, String fileId) throws IOException;
    List<String> queryKnowledge(String knowledgeId, String query) throws IOException;
}
```

### CodeAnalyzer
Abstracts code analysis operations:
```java
public interface CodeAnalyzer {
    List<CodeSignature> extractSignatures(PsiFile psiFile);
    ProjectInfo extractProjectInfo();
    List<VirtualFile> findAllSourceFiles();
}
```

## Testing Strategies

### Unit Testing
Test individual components in isolation:

```java
@Test
public void testSignatureExtraction() {
    // Given
    PsiFile file = createTestFile("class Test { void method() {} }");
    SignatureExtractor extractor = new SignatureExtractor();
    
    // When
    List<CodeSignature> signatures = extractor.extractFromFile(file);
    
    // Then
    assertEquals(2, signatures.size()); // Class + method
}
```

### Integration Testing
Test component interactions:

```java
@Test
public void testIndexingFlow() {
    // Given
    MockKnowledgeApiClient mockApi = new MockKnowledgeApiClient();
    RagAgent agent = new RagAgent(project, config, analyzer, mockApi);
    
    // When
    agent.performIndexing(indicator, false);
    
    // Then
    assertEquals(1, mockApi.getKnowledgeBaseCount());
    assertTrue(mockApi.getFileCount() > 0);
}
```

### Mock Implementations

The system provides `MockKnowledgeApiClient` for testing:

```java
MockKnowledgeApiClient mockApi = new MockKnowledgeApiClient();
mockApi.setShouldFailOnCreate(true); // Simulate failures

RagAgent agent = RagComponentFactory.createRagAgent(
    project, config, analyzer, mockApi
);
```

## Testing Best Practices

1. **Use Factory Methods**
   ```java
   // Production
   RagAgent agent = RagComponentFactory.createRagAgent(project);
   
   // Test
   RagAgent agent = RagComponentFactory.createRagAgent(
       project, mockConfig, mockAnalyzer, mockApi
   );
   ```

2. **Test Error Scenarios**
   ```java
   @Test
   public void testHandlesApiFailure() {
       mockApi.setShouldFailOnUpload(true);
       // Verify graceful handling
   }
   ```

3. **Use IntelliJ Test Framework**
   ```java
   public class SignatureExtractorTest extends LightJavaCodeInsightFixtureTestCase {
       // Provides PSI infrastructure for testing
   }
   ```

4. **Test Threading**
   ```java
   ApplicationManager.getApplication().invokeAndWait(() -> {
       // Test code that requires EDT
   });
   ```

## Test Structure

```
src/test/java/com/zps/zest/rag/
├── RagAgentTest.java              # Unit tests for RagAgent
├── SignatureExtractorTest.java    # Tests for signature extraction
├── MockKnowledgeApiClient.java    # Mock implementation
└── RagSystemIntegrationTest.java  # Full system integration tests
```

## Running Tests

1. **From IntelliJ IDEA**
   - Right-click on test class/method
   - Select "Run 'TestName'"

2. **From Command Line**
   ```bash
   ./gradlew test
   ```

3. **With Coverage**
   - Right-click → "Run with Coverage"
   - View coverage report

## Continuous Integration

Add to your CI pipeline:

```yaml
test:
  script:
    - ./gradlew test
    - ./gradlew jacocoTestReport
  artifacts:
    reports:
      junit: build/test-results/test/TEST-*.xml
      coverage: build/reports/jacoco/test/html/
```

## Benefits

1. **Confidence** - Changes can be made without fear of breaking functionality
2. **Documentation** - Tests serve as living documentation
3. **Design Feedback** - Difficulty testing often indicates design issues
4. **Regression Prevention** - Automated tests catch regressions early
5. **Refactoring Safety** - Tests enable safe refactoring

## Future Improvements

1. **Property-Based Testing** - Use generators for comprehensive testing
2. **Performance Tests** - Ensure indexing performance at scale
3. **Mutation Testing** - Verify test quality
4. **Contract Tests** - Ensure API compatibility
