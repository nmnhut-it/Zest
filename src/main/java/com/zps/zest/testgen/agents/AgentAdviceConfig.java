package com.zps.zest.testgen.agents;

import org.jetbrains.annotations.NotNull;
import java.util.Map;
import java.util.HashMap;

/**
 * Configuration class for agent advice and prompts.
 * Provides flexible, configurable advice instead of hardcoded strings.
 */
public class AgentAdviceConfig {
    
    // Test Writer Agent Advice
    public static class TestWriterAdvice {
        public static final String GENERATION_APPROACH = 
            "Focus on the actual implementation provided. " +
            "Generate tests that verify real behavior, not assumptions. " +
            "Avoid mocking as much as possible - only mock when there are no other solution." +
            "Do not re-introduce code under test or repeat existing code provided by user which may cause us to test that newly rewritten code rather than the actual code" +
            "Use appropriate test patterns from the codebase.";
            
        public static final String STRUCTURE_GUIDANCE = 
            "Follow existing test structure in the codebase. " +
            "Use Given-When-Then or Arrange-Act-Assert patterns. " +
            "Keep tests focused and readable.";
            
        public static final String ASSERTION_GUIDANCE = 
            "Use assertions that match the framework in use. " +
            "Test both expected outcomes and edge cases. " +
            "If the method include figure calculation, verify those figures." +
            "Verify behavior, not implementation details.";
            
        public static Map<String, String> getTestGenerationAdvice(@NotNull String framework) {
            Map<String, String> advice = new HashMap<>();
            
            advice.put("approach", GENERATION_APPROACH);
            advice.put("structure", STRUCTURE_GUIDANCE);
            advice.put("assertions", ASSERTION_GUIDANCE);
            
            // Framework-specific advice
            if (framework.contains("JUnit 5")) {
                advice.put("imports", "Use JUnit 5 assertions and annotations");
                advice.put("setup", "Use @BeforeEach for setup, @AfterEach for cleanup");
            } else if (framework.contains("JUnit 4")) {
                advice.put("imports", "Use JUnit 4 assertions and annotations");
                advice.put("setup", "Use @Before for setup, @After for cleanup");
            } else if (framework.contains("TestNG")) {
                advice.put("imports", "Use TestNG assertions and annotations");
                advice.put("setup", "Use @BeforeMethod for setup, @AfterMethod for cleanup");
            }
            
            return advice;
        }
    }
    
    // Context Agent Advice
    public static class ContextGatheringAdvice {
        public static final String GATHERING_STRATEGY = 
            "Be strategic in gathering context. " +
            "First list out what symbols in the code you need to understand, where they come from, where their implementation might reside, and then what to read." +
            "Prioritize: target implementation, test examples, dependencies. " +
            "Avoid excessive tool calls - quality over quantity.";
            
        public static final String RELEVANCE_GUIDANCE = 
            "Focus on files directly related to the test target. " +
            "Include test patterns and conventions from the codebase. " +
            "Gather enough context to understand dependencies and interactions. " +
            "Example 1: method A use a function from class B, then we should also read class B " +
            "Example 2: We need to use function X, then we need to read their implementation. Do not assume. " +
            "Example 3: Our code under test refer to an external JSON file in the project. We read the JSON file to understand its structure.";

        public static Map<String, String> getContextAdvice() {
            Map<String, String> advice = new HashMap<>();
            advice.put("strategy", GATHERING_STRATEGY);
            advice.put("relevance", RELEVANCE_GUIDANCE);
            advice.put("tool_limit", "Use 3-7 tool calls maximum");
            advice.put("priority", "Target file > Test examples > Dependencies > Related code");
            return advice;
        }
    }
    
    // Coordinator Agent Advice  
    public static class CoordinatorAdvice {
        public static final String PLANNING_APPROACH = 
            "Create comprehensive test plans based on actual code behavior. " +
            "Consider different test types: unit, integration, edge cases. " +
            "Plan tests that provide good coverage without redundancy.";
            
        public static final String SCENARIO_GUIDANCE = 
            "Each scenario should test a specific behavior or condition. " +
            "Include both happy path and error cases. " +
            "Consider boundary conditions and edge cases.";
            
        public static Map<String, String> getPlanningAdvice(@NotNull String testType) {
            Map<String, String> advice = new HashMap<>();
            advice.put("approach", PLANNING_APPROACH);
            advice.put("scenarios", SCENARIO_GUIDANCE);
            
            if ("UNIT_TESTS".equals(testType)) {
                advice.put("focus", "Test individual methods in isolation");
                advice.put("mocking", "Mock external dependencies");
            } else if ("INTEGRATION_TESTS".equals(testType)) {
                advice.put("focus", "Test component interactions");
                advice.put("mocking", "Use real implementations where possible");
            }
            
            return advice;
        }
    }
    
    // General advice for all agents
    public static class GeneralAdvice {
        public static final String CODE_ANALYSIS = 
            "Analyze the actual implementation thoroughly. " +
            "Understand the code's purpose and behavior. " +
            "Do not make assumptions about functionality.";
            
        public static final String QUALITY_FOCUS = 
            "Prioritize test quality over quantity. " +
            "Generate maintainable, readable tests. " +
            "Follow the codebase's conventions and patterns.";
            
        public static final String OUTPUT_FORMAT = 
            "Provide clear, structured output. " +
            "Use consistent formatting. " +
            "Follow programming best practices. Strictl follow D-R-Y (Dont repeat yourself) " +
            "Include necessary context in responses.";
    }
    
    /**
     * Get dynamic prompt based on context and requirements
     */
    @NotNull
    public static String buildDynamicPrompt(@NotNull String baseTask, 
                                           @NotNull Map<String, String> advice,
                                           @NotNull Map<String, Object> context) {
        StringBuilder prompt = new StringBuilder();
        
        // Start with the base task
        prompt.append("Task: ").append(baseTask).append("\n\n");
        
        // Add relevant advice
        prompt.append("Guidance:\n");
        advice.forEach((key, value) -> {
            prompt.append("- ").append(value).append("\n");
        });
        prompt.append("\n");
        
        // Add context if available
        if (!context.isEmpty()) {
            prompt.append("Context:\n");
            context.forEach((key, value) -> {
                if (value != null) {
                    prompt.append(key).append(": ").append(value.toString()).append("\n");
                }
            });
            prompt.append("\n");
        }
        
        prompt.append("Provide a response following the guidance above.\n");
        
        return prompt.toString();
    }
}