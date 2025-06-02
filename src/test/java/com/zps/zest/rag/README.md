# RAG System Tests

This directory contains all tests for the RAG (Retrieval Augmented Generation) system using JUnit 4.

## Test Structure

### Unit Tests
- **CodeSignatureTest** - Tests the CodeSignature model class
- **RagAgentTest** - Tests the main RAG agent logic with mocked dependencies
- **SignatureExtractorTest** - Tests code signature extraction from PSI

### Integration Tests
- **RagSystemIntegrationTest** - Tests the full RAG flow from indexing to search

### Test Utilities
- **MockKnowledgeApiClient** - In-memory mock implementation of the API client
- **TestUtils** - Helper methods for creating test data
- **RagTestSuite** - JUnit 4 test suite for running all tests together

## Running Tests

### From IntelliJ IDEA
1. Right-click on any test class or method
2. Select "Run 'TestName'"
3. For coverage: "Run with Coverage"

### From Command Line
```bash
# Run all tests
./gradlew test

# Run only RAG tests
./gradlew test --tests "com.zps.zest.rag.*"

# Run with detailed output
./gradlew test --info

# Generate test report
./gradlew test jacocoTestReport
```

### Running the Test Suite
```bash
# Run the entire RAG test suite
./gradlew test --tests "com.zps.zest.rag.RagTestSuite"
```

## Test Patterns

### Mocking with Mockito
```java
public class MyTest extends TestCase {
    @Mock
    private KnowledgeApiClient mockApiClient;
    
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.openMocks(this);
    }
    
    public void testSomething() {
        when(mockApiClient.uploadFile(any(), any())).thenReturn("file-id");
        // test code
    }
}
```

### Testing with IntelliJ PSI
Tests that need PSI support extend `LightJavaCodeInsightFixtureTestCase`:
```java
public class SignatureExtractorTest extends LightJavaCodeInsightFixtureTestCase {
    public void testExtraction() {
        PsiFile file = myFixture.configureByText("Test.java", "class Test {}");
        // test code
    }
}
```

### Using Test Utilities
```java
// Create test data easily
CodeSignature sig = TestUtils.createTestSignature("MyClass", "class");
ProjectInfo info = TestUtils.createTestProjectInfo("Maven", "spring-core:5.0");
MockProgressIndicator indicator = new TestUtils.MockProgressIndicator();
```

## JUnit 4 Conventions

- **Test methods** - Must start with "test" prefix
- **setUp()** - Override for setup before each test
- **tearDown()** - Override for cleanup after each test
- **TestCase** - Extend for basic tests
- **Static imports** - Use `import static junit.framework.TestCase.*;` for assertions

## Best Practices

1. **Naming Convention**
   - Test methods: `testMethodName_Scenario_ExpectedBehavior()`
   - Test classes: End with "Test"

2. **Test Structure**
   - Given - Set up test data
   - When - Execute the method under test
   - Then - Verify the results

3. **Mocking**
   - Mock external dependencies
   - Use real implementations for simple objects
   - Verify interactions with mocks

4. **Assertions**
   - Use descriptive assertion messages
   - Common assertions: assertEquals, assertTrue, assertFalse, assertNull, assertNotNull
   - Use fail() for expected exceptions

## Coverage Goals

- Unit test coverage: 80%+
- Integration test coverage: Key flows
- Focus on business logic, not getters/setters

## Troubleshooting

### Test Not Found
- Ensure test class names end with "Test"
- Check that test methods start with "test" prefix
- Verify methods are public
- Ensure class extends TestCase or appropriate base class

### Mock Issues
- Ensure MockitoAnnotations.openMocks(this) is called in setUp()
- Check mock initialization
- Verify stubbing before method calls

### PSI Tests Failing
- Ensure proper test fixture setup
- Use ApplicationManager.getApplication() for app-level operations
- Use ReadAction.compute() for PSI reads
