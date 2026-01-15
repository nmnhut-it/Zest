package com.zps.zest.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Configurable rules for test generation.
 * These rules define conventions, constraints, and preferences.
 */
public class TestGenRules {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Naming conventions
    private String testClassSuffix = "Test";
    private String testMethodPattern = "test{Method}_{Condition}_{Expected}";
    private boolean useDisplayNames = true;

    // Testing strategy
    private boolean allowMocking = false;
    private boolean preferTestcontainers = true;
    private boolean preferAssertJ = true;
    private String defaultFramework = "JUnit5";

    // Code style
    private boolean useNestedClasses = true;
    private boolean useParameterizedTests = true;
    private int maxTestMethodLines = 30;
    private int maxSetupLines = 20;

    // Coverage targets
    private int minScenariosPerMethod = 3;
    private int maxScenariosPerMethod = 10;
    private boolean requireHappyPath = true;
    private boolean requireErrorHandling = true;
    private boolean requireBoundaryTests = true;

    // Integration test rules
    private boolean shareContainers = true;
    private boolean cleanupAfterEach = true;
    private int integrationTestTimeoutSeconds = 60;

    // Custom rules (user-defined)
    private List<String> customRules = new ArrayList<>();
    private Map<String, String> frameworkOverrides = new HashMap<>();

    /**
     * Create default rules.
     */
    public static TestGenRules defaults() {
        return new TestGenRules();
    }

    /**
     * Create strict rules (more constraints).
     */
    public static TestGenRules strict() {
        TestGenRules rules = new TestGenRules();
        rules.allowMocking = false;
        rules.requireHappyPath = true;
        rules.requireErrorHandling = true;
        rules.requireBoundaryTests = true;
        rules.minScenariosPerMethod = 5;
        rules.maxTestMethodLines = 25;
        return rules;
    }

    /**
     * Create minimal rules (fewer constraints).
     */
    public static TestGenRules minimal() {
        TestGenRules rules = new TestGenRules();
        rules.useNestedClasses = false;
        rules.useParameterizedTests = false;
        rules.minScenariosPerMethod = 1;
        rules.requireBoundaryTests = false;
        return rules;
    }

    /**
     * Parse rules from JSON string.
     */
    public static TestGenRules fromJson(String json) {
        return GSON.fromJson(json, TestGenRules.class);
    }

    /**
     * Convert rules to JSON string.
     */
    public String toJson() {
        return GSON.toJson(this);
    }

    /**
     * Format rules as prompt instructions for LLM.
     */
    @NotNull
    public String toPromptInstructions() {
        StringBuilder sb = new StringBuilder();
        sb.append("## Testing Rules\n\n");

        // Naming conventions
        sb.append("### Naming Conventions\n");
        sb.append("- Test class suffix: ").append(testClassSuffix).append("\n");
        sb.append("- Test method pattern: ").append(testMethodPattern).append("\n");
        if (useDisplayNames) {
            sb.append("- Use @DisplayName for readable test descriptions\n");
        }
        sb.append("\n");

        // Testing strategy
        sb.append("### Testing Strategy\n");
        if (!allowMocking) {
            sb.append("- **NO MOCKING ALLOWED** - Do not use Mockito or similar\n");
        }
        if (preferTestcontainers) {
            sb.append("- Use Testcontainers for integration tests\n");
        }
        if (preferAssertJ) {
            sb.append("- Prefer AssertJ assertions over JUnit assertions\n");
        }
        sb.append("- Default framework: ").append(defaultFramework).append("\n");
        sb.append("\n");

        // Code style
        sb.append("### Code Style\n");
        if (useNestedClasses) {
            sb.append("- Use @Nested classes to group related tests\n");
        }
        if (useParameterizedTests) {
            sb.append("- Use @ParameterizedTest for data-driven scenarios\n");
        }
        sb.append("- Max test method lines: ").append(maxTestMethodLines).append("\n");
        sb.append("- Max setup lines: ").append(maxSetupLines).append("\n");
        sb.append("\n");

        // Coverage requirements
        sb.append("### Coverage Requirements\n");
        sb.append("- Min scenarios per method: ").append(minScenariosPerMethod).append("\n");
        sb.append("- Max scenarios per method: ").append(maxScenariosPerMethod).append("\n");
        if (requireHappyPath) {
            sb.append("- Happy path test: REQUIRED\n");
        }
        if (requireErrorHandling) {
            sb.append("- Error handling test: REQUIRED\n");
        }
        if (requireBoundaryTests) {
            sb.append("- Boundary value tests: REQUIRED\n");
        }
        sb.append("\n");

        // Integration test rules
        sb.append("### Integration Test Rules\n");
        if (shareContainers) {
            sb.append("- Share containers across tests (@Container static)\n");
        }
        if (cleanupAfterEach) {
            sb.append("- Clean up test data in @AfterEach\n");
        }
        sb.append("- Timeout: ").append(integrationTestTimeoutSeconds).append(" seconds\n");
        sb.append("\n");

        // Custom rules
        if (!customRules.isEmpty()) {
            sb.append("### Custom Rules\n");
            for (String rule : customRules) {
                sb.append("- ").append(rule).append("\n");
            }
            sb.append("\n");
        }

        // Framework overrides
        if (!frameworkOverrides.isEmpty()) {
            sb.append("### Framework-Specific Overrides\n");
            for (Map.Entry<String, String> entry : frameworkOverrides.entrySet()) {
                sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        }

        return sb.toString();
    }

    // Builder pattern for fluent configuration

    public TestGenRules withTestClassSuffix(String suffix) {
        this.testClassSuffix = suffix;
        return this;
    }

    public TestGenRules withTestMethodPattern(String pattern) {
        this.testMethodPattern = pattern;
        return this;
    }

    public TestGenRules withDisplayNames(boolean use) {
        this.useDisplayNames = use;
        return this;
    }

    public TestGenRules withMocking(boolean allow) {
        this.allowMocking = allow;
        return this;
    }

    public TestGenRules withTestcontainers(boolean prefer) {
        this.preferTestcontainers = prefer;
        return this;
    }

    public TestGenRules withAssertJ(boolean prefer) {
        this.preferAssertJ = prefer;
        return this;
    }

    public TestGenRules withFramework(String framework) {
        this.defaultFramework = framework;
        return this;
    }

    public TestGenRules withNestedClasses(boolean use) {
        this.useNestedClasses = use;
        return this;
    }

    public TestGenRules withParameterizedTests(boolean use) {
        this.useParameterizedTests = use;
        return this;
    }

    public TestGenRules withMaxTestMethodLines(int max) {
        this.maxTestMethodLines = max;
        return this;
    }

    public TestGenRules withMinScenarios(int min) {
        this.minScenariosPerMethod = min;
        return this;
    }

    public TestGenRules withMaxScenarios(int max) {
        this.maxScenariosPerMethod = max;
        return this;
    }

    public TestGenRules requireHappyPath(boolean require) {
        this.requireHappyPath = require;
        return this;
    }

    public TestGenRules requireErrorHandling(boolean require) {
        this.requireErrorHandling = require;
        return this;
    }

    public TestGenRules requireBoundaryTests(boolean require) {
        this.requireBoundaryTests = require;
        return this;
    }

    public TestGenRules addCustomRule(String rule) {
        this.customRules.add(rule);
        return this;
    }

    public TestGenRules withFrameworkOverride(String framework, String override) {
        this.frameworkOverrides.put(framework, override);
        return this;
    }

    // Getters

    public String getTestClassSuffix() {
        return testClassSuffix;
    }

    public String getTestMethodPattern() {
        return testMethodPattern;
    }

    public boolean isUseDisplayNames() {
        return useDisplayNames;
    }

    public boolean isAllowMocking() {
        return allowMocking;
    }

    public boolean isPreferTestcontainers() {
        return preferTestcontainers;
    }

    public boolean isPreferAssertJ() {
        return preferAssertJ;
    }

    public String getDefaultFramework() {
        return defaultFramework;
    }

    public boolean isUseNestedClasses() {
        return useNestedClasses;
    }

    public boolean isUseParameterizedTests() {
        return useParameterizedTests;
    }

    public int getMaxTestMethodLines() {
        return maxTestMethodLines;
    }

    public int getMaxSetupLines() {
        return maxSetupLines;
    }

    public int getMinScenariosPerMethod() {
        return minScenariosPerMethod;
    }

    public int getMaxScenariosPerMethod() {
        return maxScenariosPerMethod;
    }

    public boolean isRequireHappyPath() {
        return requireHappyPath;
    }

    public boolean isRequireErrorHandling() {
        return requireErrorHandling;
    }

    public boolean isRequireBoundaryTests() {
        return requireBoundaryTests;
    }

    public boolean isShareContainers() {
        return shareContainers;
    }

    public boolean isCleanupAfterEach() {
        return cleanupAfterEach;
    }

    public int getIntegrationTestTimeoutSeconds() {
        return integrationTestTimeoutSeconds;
    }

    public List<String> getCustomRules() {
        return Collections.unmodifiableList(customRules);
    }

    public Map<String, String> getFrameworkOverrides() {
        return Collections.unmodifiableMap(frameworkOverrides);
    }
}
