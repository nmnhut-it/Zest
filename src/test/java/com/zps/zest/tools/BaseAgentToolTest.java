package com.zps.zest.tools;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BaseAgentToolTest {

    private static final String TEST_NAME = "test_tool";
    private static final String TEST_DESCRIPTION = "Test tool description";

    // A test implementation of BaseAgentTool for testing
    private static class TestTool extends BaseAgentTool {
        private boolean executeWasCalled = false;
        private JsonObject lastParams = null;
        private String returnValue = "Success";
        private boolean shouldThrowException = false;

        public TestTool() {
            super(TEST_NAME, TEST_DESCRIPTION);
        }

        @Override
        protected String doExecute(JsonObject params) throws Exception {
            executeWasCalled = true;
            lastParams = params;
            if (shouldThrowException) {
                throw new Exception("Test exception");
            }
            return returnValue;
        }

        public boolean wasExecuteCalled() {
            return executeWasCalled;
        }

        public JsonObject getLastParams() {
            return lastParams;
        }

        public void setReturnValue(String returnValue) {
            this.returnValue = returnValue;
        }

        public void setShouldThrowException(boolean shouldThrowException) {
            this.shouldThrowException = shouldThrowException;
        }

        @Override
        public JsonObject getExampleParams() {
            JsonObject params = new JsonObject();
            params.addProperty("testParam", "testValue");
            return params;
        }
    }

    private TestTool testTool;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        testTool = new TestTool();
    }

    @Test
    void testGetName() {
        assertEquals(TEST_NAME, testTool.getName());
    }

    @Test
    void testGetDescription() {
        assertEquals(TEST_DESCRIPTION, testTool.getDescription());
    }

    @Test
    void testExecuteCallsDoExecute() throws Exception {
        JsonObject testParams = new JsonObject();
        testParams.addProperty("key", "value");

        String result = testTool.execute(testParams);

        assertTrue(testTool.wasExecuteCalled());
        assertEquals(testParams, testTool.getLastParams());
        assertEquals("Success", result);
    }

    @Test
    void testExecuteHandlesException() {
        testTool.setShouldThrowException(true);
        JsonObject testParams = new JsonObject();

        Exception exception = assertThrows(Exception.class, () -> {
            testTool.execute(testParams);
        });
        
        assertTrue(testTool.wasExecuteCalled());
        assertEquals("Test exception", exception.getMessage());
    }

    @Test
    void testGetStringParam() {
        JsonObject params = new JsonObject();
        params.addProperty("existingParam", "paramValue");

        // Test with existing parameter
        assertEquals("paramValue", testTool.getStringParam(params, "existingParam", "default"));

        // Test with missing parameter
        assertEquals("default", testTool.getStringParam(params, "missingParam", "default"));

        // Test with null params
        assertEquals("default", testTool.getStringParam(null, "anyParam", "default"));
    }

    @Test
    void testGetBooleanParam() {
        JsonObject params = new JsonObject();
        params.addProperty("trueParam", true);
        params.addProperty("falseParam", false);

        // Test with existing parameters
        assertTrue(testTool.getBooleanParam(params, "trueParam", false));
        assertFalse(testTool.getBooleanParam(params, "falseParam", true));

        // Test with missing parameter
        assertTrue(testTool.getBooleanParam(params, "missingParam", true));
        assertFalse(testTool.getBooleanParam(params, "missingParam", false));

        // Test with null params
        assertTrue(testTool.getBooleanParam(null, "anyParam", true));
        assertFalse(testTool.getBooleanParam(null, "anyParam", false));
    }

    @Test
    void testGetExampleParams() {
        JsonObject exampleParams = testTool.getExampleParams();
        assertNotNull(exampleParams);
        assertEquals("testValue", exampleParams.get("testParam").getAsString());
    }
}