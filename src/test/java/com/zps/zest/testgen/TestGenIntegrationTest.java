package com.zps.zest.testgen;

import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.zps.zest.testgen.model.TestGenerationRequest;
import com.zps.zest.testgen.statemachine.TestGenerationState;
import com.zps.zest.testgen.statemachine.TestGenerationStateMachine;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Integration test for TestGen feature using a mock HTTP server
 * to simulate OpenAI API responses.
 */
public class TestGenIntegrationTest extends LightJavaCodeInsightFixtureTestCase {

    private MockWebServer mockServer;
    private String mockServerUrl;
    private AtomicInteger requestCount;
    private StateMachineTestGenerationService service;

    @Override
    protected String getTestDataPath() {
        return "src/test/testData";
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        // Initialize request counter
        requestCount = new AtomicInteger(0);

        // Start mock web server
        mockServer = new MockWebServer();
        mockServer.setDispatcher(new SmartResponseDispatcher());
        mockServer.start();
        mockServerUrl = mockServer.url("/v1").toString();

        // Configure environment to use mock server
        System.setProperty("OPENAI_BASE_URL", mockServerUrl);
        System.setProperty("OPENAI_API_KEY", "test-api-key");
        System.setProperty("OPENAI_MODEL", "gpt-4o-mini");

        // Get the service

        service = myFixture.getProject().getService(StateMachineTestGenerationService.class); // StateMachineTestGenerationService.getInstance(myFixture.getProject());
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            // Shutdown mock server
            if (mockServer != null) {
                mockServer.shutdown();
            }

            // Clear system properties
            System.clearProperty("OPENAI_BASE_URL");
            System.clearProperty("OPENAI_API_KEY");
            System.clearProperty("OPENAI_MODEL");
        } finally {
            super.tearDown();
        }
    }

    /**
     * Test the full testgen flow with mocked HTTP responses
     */
    public void testFullTestGenFlow() throws Exception {
        // Create a test Java file
        PsiJavaFile javaFile = createTestJavaFile();
        assertNotNull("Test file should be created", javaFile);

        // Get the method to test
        PsiMethod testMethod = findMethod(javaFile, "getUser");
        assertNotNull("getUser method should exist", testMethod);

        // Create test generation request
        TestGenerationRequest request = createTestRequest(javaFile, testMethod);

        // Start test generation
        String sessionId = service.startTestGeneration(request, null, null)
            .thenApply(TestGenerationStateMachine::getSessionId)
            .get(30, TimeUnit.SECONDS);

        assertNotNull("Session ID should be created", sessionId);

        // Wait for completion (with timeout)
        TestGenerationStateMachine stateMachine = service.getStateMachine(sessionId);
        assertNotNull("State machine should exist", stateMachine);

        // Poll for completion
        for (int i = 0; i < 60; i++) {
            TestGenerationState currentState = stateMachine.getCurrentState();
            if (currentState == TestGenerationState.COMPLETED ||
                currentState == TestGenerationState.FAILED ||
                currentState == TestGenerationState.CANCELLED) {
                break;
            }
            Thread.sleep(500);
        }

        // Verify final state
        TestGenerationState finalState = stateMachine.getCurrentState();
        assertTrue("Should reach terminal state, but got: " + finalState,
                   finalState == TestGenerationState.COMPLETED ||
                   finalState == TestGenerationState.FAILED);

        // Verify requests were made
        assertTrue("Should have made HTTP requests to mock server",
                   requestCount.get() > 0);
    }

    /**
     * Test cancellation during context gathering
     */
    public void testCancellationDuringContextGathering() throws Exception {
        // Create test file and request
        PsiJavaFile javaFile = createTestJavaFile();
        PsiMethod testMethod = findMethod(javaFile, "getUser");
        TestGenerationRequest request = createTestRequest(javaFile, testMethod);

        // Start test generation
        String sessionId = service.startTestGeneration(request, null, null)
            .thenApply(TestGenerationStateMachine::getSessionId)
            .get(5, TimeUnit.SECONDS);

        // Wait a bit for context gathering to start
        Thread.sleep(1000);

        // Cancel the generation
        int requestsBefore = requestCount.get();
        service.cancelGeneration(sessionId, "Test cancellation");

        // Wait a bit to see if more requests are made
        Thread.sleep(1000);
        int requestsAfter = requestCount.get();

        // Verify state is cancelled
        TestGenerationStateMachine stateMachine = service.getStateMachine(sessionId);
        assertEquals("State should be CANCELLED",
                     TestGenerationState.CANCELLED,
                     stateMachine.getCurrentState());

        // Verify no new requests after cancellation
        assertEquals("Should not make new requests after cancellation",
                     requestsBefore, requestsAfter);
    }

    /**
     * Test that the mock server is working correctly
     */
    public void testMockServerResponds() throws Exception {
        // Make a simple HTTP request to the mock server
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
        okhttp3.Request request = new okhttp3.Request.Builder()
            .url(mockServerUrl + "/chat/completions")
            .post(okhttp3.RequestBody.create(
                "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"test\"}]}",
                okhttp3.MediaType.parse("application/json")
            ))
            .build();

        okhttp3.Response response = client.newCall(request).execute();

        // Verify response
        assertTrue("Mock server should respond with success", response.isSuccessful());
        assertNotNull("Response body should not be null", response.body());
        response.close();

        // Verify request was recorded
        RecordedRequest recordedRequest = mockServer.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull("Mock server should record the request", recordedRequest);
        assertEquals("Request should be to chat completions endpoint",
                     "/v1/chat/completions", recordedRequest.getPath());
    }

    // Helper Methods

    private PsiJavaFile createTestJavaFile() {
        PsiFile file = myFixture.addFileToProject(
            "UserService.java",
            "package com.example;\n\n" +
            "public class UserService {\n" +
            "    public User getUser(int id) {\n" +
            "        if (id <= 0) {\n" +
            "            throw new IllegalArgumentException(\"Invalid ID\");\n" +
            "        }\n" +
            "        return new User(id, \"John Doe\");\n" +
            "    }\n" +
            "\n" +
            "    public void deleteUser(int id) {\n" +
            "        // Delete logic\n" +
            "    }\n" +
            "}\n" +
            "\n" +
            "class User {\n" +
            "    private int id;\n" +
            "    private String name;\n" +
            "    User(int id, String name) { this.id = id; this.name = name; }\n" +
            "}\n"
        );
        return (PsiJavaFile) file;
    }

    private PsiMethod findMethod(PsiJavaFile file, String methodName) {
        return Arrays.stream(file.getClasses())
            .flatMap(cls -> Arrays.stream(cls.getMethods()))
            .filter(method -> methodName.equals(method.getName()))
            .findFirst()
            .orElse(null);
    }

    private TestGenerationRequest createTestRequest(PsiJavaFile file, PsiMethod method) {
        List<PsiMethod> methods = Arrays.asList(method);
        return new TestGenerationRequest(
            file,
            methods,
            null, // selectedCode
            TestGenerationRequest.TestType.UNIT_TESTS,
            null  // additionalContext
        );
    }

    // Mock Response Dispatcher

    private class SmartResponseDispatcher extends Dispatcher {
        @NotNull
        @Override
        public MockResponse dispatch(@NotNull RecordedRequest request) {
            requestCount.incrementAndGet();

            try {
                String body = request.getBody().readUtf8();

                // Route based on request content
                if (body.contains("gather context") || body.contains("Context")) {
                    return contextGatheringResponse();
                } else if (body.contains("test plan") || body.contains("TestPlanningAssistant")) {
                    return testPlanningResponse();
                } else if (body.contains("generate test") || body.contains("@Test")) {
                    return testGenerationResponse();
                } else {
                    return defaultResponse();
                }
            } catch (Exception e) {
                return new MockResponse()
                    .setResponseCode(500)
                    .setBody("{\"error\": \"" + e.getMessage() + "\"}");
            }
        }
    }

    // Mock Response Builders

    private MockResponse contextGatheringResponse() {
        String responseBody = "{\n" +
            "  \"id\": \"chatcmpl-test\",\n" +
            "  \"object\": \"chat.completion\",\n" +
            "  \"created\": 1234567890,\n" +
            "  \"model\": \"gpt-4o-mini\",\n" +
            "  \"choices\": [{\n" +
            "    \"index\": 0,\n" +
            "    \"message\": {\n" +
            "      \"role\": \"assistant\",\n" +
            "      \"content\": null,\n" +
            "      \"tool_calls\": [{\n" +
            "        \"id\": \"call_1\",\n" +
            "        \"type\": \"function\",\n" +
            "        \"function\": {\n" +
            "          \"name\": \"takeNote\",\n" +
            "          \"arguments\": \"{\\\"note\\\":\\\"Analyzing UserService class with getUser method\\\"}\"\n" +
            "        }\n" +
            "      }, {\n" +
            "        \"id\": \"call_2\",\n" +
            "        \"type\": \"function\",\n" +
            "        \"function\": {\n" +
            "          \"name\": \"markContextCollectionDone\",\n" +
            "          \"arguments\": \"{}\"\n" +
            "        }\n" +
            "      }]\n" +
            "    },\n" +
            "    \"finish_reason\": \"tool_calls\"\n" +
            "  }]\n" +
            "}";

        return new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(responseBody);
    }

    private MockResponse testPlanningResponse() {
        String responseBody = "{\n" +
            "  \"id\": \"chatcmpl-test\",\n" +
            "  \"object\": \"chat.completion\",\n" +
            "  \"created\": 1234567890,\n" +
            "  \"model\": \"gpt-4o-mini\",\n" +
            "  \"choices\": [{\n" +
            "    \"index\": 0,\n" +
            "    \"message\": {\n" +
            "      \"role\": \"assistant\",\n" +
            "      \"content\": null,\n" +
            "      \"tool_calls\": [{\n" +
            "        \"id\": \"call_1\",\n" +
            "        \"type\": \"function\",\n" +
            "        \"function\": {\n" +
            "          \"name\": \"setTargetClass\",\n" +
            "          \"arguments\": \"{\\\"className\\\":\\\"com.example.UserService\\\"}\"\n" +
            "        }\n" +
            "      }, {\n" +
            "        \"id\": \"call_2\",\n" +
            "        \"type\": \"function\",\n" +
            "        \"function\": {\n" +
            "          \"name\": \"addTestScenarios\",\n" +
            "          \"arguments\": \"[{\\\"name\\\":\\\"testGetUser_ValidId_ReturnsUser\\\",\\\"description\\\":\\\"Test getUser with valid ID\\\",\\\"type\\\":\\\"UNIT\\\",\\\"inputs\\\":[\\\"id=5\\\"],\\\"expectedOutcome\\\":\\\"Returns User object\\\",\\\"priority\\\":\\\"HIGH\\\"}]\"\n" +
            "        }\n" +
            "      }]\n" +
            "    },\n" +
            "    \"finish_reason\": \"tool_calls\"\n" +
            "  }]\n" +
            "}";

        return new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(responseBody);
    }

    private MockResponse testGenerationResponse() {
        String testCode = "```java\\n" +
            "import org.junit.jupiter.api.Test;\\n" +
            "import static org.junit.jupiter.api.Assertions.*;\\n\\n" +
            "class UserServiceTest {\\n" +
            "    @Test\\n" +
            "    void testGetUser_ValidId_ReturnsUser() {\\n" +
            "        UserService service = new UserService();\\n" +
            "        User user = service.getUser(5);\\n" +
            "        assertNotNull(user);\\n" +
            "        assertEquals(5, user.getId());\\n" +
            "    }\\n" +
            "}\\n" +
            "```";

        String responseBody = "{\n" +
            "  \"id\": \"chatcmpl-test\",\n" +
            "  \"object\": \"chat.completion\",\n" +
            "  \"created\": 1234567890,\n" +
            "  \"model\": \"gpt-4o-mini\",\n" +
            "  \"choices\": [{\n" +
            "    \"index\": 0,\n" +
            "    \"message\": {\n" +
            "      \"role\": \"assistant\",\n" +
            "      \"content\": \"" + testCode + "\"\n" +
            "    },\n" +
            "    \"finish_reason\": \"stop\"\n" +
            "  }]\n" +
            "}";

        return new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(responseBody);
    }

    private MockResponse defaultResponse() {
        String responseBody = "{\n" +
            "  \"id\": \"chatcmpl-test\",\n" +
            "  \"object\": \"chat.completion\",\n" +
            "  \"created\": 1234567890,\n" +
            "  \"model\": \"gpt-4o-mini\",\n" +
            "  \"choices\": [{\n" +
            "    \"index\": 0,\n" +
            "    \"message\": {\n" +
            "      \"role\": \"assistant\",\n" +
            "      \"content\": \"Acknowledged.\"\n" +
            "    },\n" +
            "    \"finish_reason\": \"stop\"\n" +
            "  }]\n" +
            "}";

        return new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(responseBody);
    }
}
