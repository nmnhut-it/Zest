package com.zps.zest.testgen;

import com.intellij.openapi.project.Project;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.zps.zest.langchain4j.ZestLangChain4jService;
import com.zps.zest.langchain4j.naive_service.NaiveLLMService;
import com.zps.zest.testgen.agents.StreamingBaseAgent;
import com.zps.zest.testgen.statemachine.AbstractStateHandler;
import com.zps.zest.testgen.statemachine.StateHandler;
import com.zps.zest.testgen.statemachine.TestGenerationState;
import com.zps.zest.testgen.statemachine.TestGenerationStateMachine;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CancellationException;

import static org.mockito.Mockito.*;

/**
 * Test suite to verify cancellation feature works correctly
 * across the testgen feature stack using IntelliJ Platform test framework.
 */
public class CancellationTest extends LightJavaCodeInsightFixtureTestCase {

    @Override
    protected String getTestDataPath() {
        return "src/test/testData";
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // myFixture.getProject() is now available with proper IntelliJ Application context
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * Test that StreamingBaseAgent supports cancellation flag
     */
    public void testStreamingBaseAgentCancellation() {
        // Create mock services
        NaiveLLMService mockLlmService = mock(NaiveLLMService.class);
        ZestLangChain4jService mockLangChainService = mock(ZestLangChain4jService.class);
        when(mockLlmService.getProject()).thenReturn(myFixture.getProject());

        // Create a minimal test agent
        TestAgent agent = new TestAgent(myFixture.getProject(), mockLangChainService, mockLlmService);

        // Initially not cancelled
        assertFalse("Agent should not be cancelled initially", agent.isCancelled());

        // Cancel the agent
        agent.cancel();

        // Verify cancellation
        assertTrue("Agent should be cancelled", agent.isCancelled());

        // Verify checkCancellation throws
        try {
            agent.testCheckCancellation();
            fail("Should have thrown CancellationException");
        } catch (CancellationException e) {
            // Expected
            assertTrue("Should throw CancellationException", true);
        }

        // Reset agent
        agent.reset();
        assertFalse("Agent should not be cancelled after reset", agent.isCancelled());
    }

    /**
     * Test that AbstractStateHandler supports cancellation
     */
    public void testAbstractStateHandlerCancellation() {
        // Create a minimal test handler
        TestStateHandler handler = new TestStateHandler(TestGenerationState.GATHERING_CONTEXT);

        // Initially not cancelled
        assertFalse("Handler should not be cancelled initially", handler.isCancelled());

        // Cancel the handler
        handler.cancel();

        // Verify cancellation
        assertTrue("Handler should be cancelled", handler.isCancelled());
    }

    /**
     * Test that StateMachine cancellation propagates to handlers
     */
    public void testStateMachineCancellationPropagation() {
        // Create state machine with real project from fixture
        TestGenerationStateMachine stateMachine = new TestGenerationStateMachine(
            myFixture.getProject(),
            "test-session"
        );

        // Create and register a spy handler to verify cancel() is called
        TestStateHandler handler = spy(new TestStateHandler(TestGenerationState.GATHERING_CONTEXT));
        stateMachine.registerStateHandler(TestGenerationState.GATHERING_CONTEXT, handler);

        // Transition to the handler's state
        stateMachine.transitionTo(TestGenerationState.INITIALIZING, "Test");
        stateMachine.transitionTo(TestGenerationState.GATHERING_CONTEXT, "Test");

        // Cancel the state machine
        stateMachine.cancel("Test cancellation");

        // Verify handler's cancel() was called
        verify(handler, times(1)).cancel();

        // Verify state transitioned to CANCELLED
        assertEquals("State should be CANCELLED",
                     TestGenerationState.CANCELLED,
                     stateMachine.getCurrentState());
    }

    /**
     * Minimal test agent for testing cancellation
     */
    private static class TestAgent extends StreamingBaseAgent {
        public TestAgent(@NotNull Project project,
                        @NotNull ZestLangChain4jService langChainService,
                        @NotNull NaiveLLMService naiveLlmService) {
            super(project, langChainService, naiveLlmService, "TestAgent");
        }

        public void testCheckCancellation() {
            checkCancellation();
        }
    }

    /**
     * Minimal test state handler for testing cancellation
     */
    private static class TestStateHandler extends AbstractStateHandler {
        public TestStateHandler(@NotNull TestGenerationState handledState) {
            super(handledState);
        }

        @NotNull
        @Override
        protected StateHandler.StateResult executeState(@NotNull TestGenerationStateMachine stateMachine) {
            return StateHandler.StateResult.success(null, "Test completed", TestGenerationState.COMPLETED);
        }
    }
}
