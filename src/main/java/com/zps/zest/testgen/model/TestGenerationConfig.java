package com.zps.zest.testgen.model;

import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;

/**
 * Configuration for test generation behavior.
 * Stores user preferences for number of tests, types, priorities, and coverage targets.
 */
public class TestGenerationConfig {

    public enum TestTypeFilter {
        UNIT("Unit Tests", "Pure business logic without external dependencies"),
        INTEGRATION("Integration Tests", "Code with external dependencies (DB, APIs, files, etc.)"),
        EDGE_CASE("Edge Case Tests", "Boundary conditions and unusual inputs"),
        ERROR_HANDLING("Error Handling Tests", "Exception scenarios and error paths");

        private final String displayName;
        private final String description;

        TestTypeFilter(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getDescription() {
            return description;
        }
    }

    public enum PriorityFilter {
        HIGH("High Priority", "Main functionality, security-critical, happy paths"),
        MEDIUM("Medium Priority", "Common error scenarios, typical edge cases"),
        LOW("Low Priority", "Rare scenarios, completeness testing");

        private final String displayName;
        private final String description;

        PriorityFilter(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getDescription() {
            return description;
        }
    }

    public enum CoverageTarget {
        HAPPY_PATH("Happy Path", "Focus on main successful execution flows"),
        EDGE_CASES("Edge Cases", "Focus on boundary conditions and unusual inputs"),
        ERROR_HANDLING("Error Handling", "Focus on exception scenarios and error paths"),
        STATE_TRANSITIONS("State Transitions", "Focus on state changes and side effects"),
        SECURITY("Security", "Focus on security-critical paths and validation"),
        PERFORMANCE("Performance", "Include performance-sensitive scenarios"),
        CONCURRENCY("Concurrency", "Include multi-threading and race condition scenarios");

        private final String displayName;
        private final String description;

        CoverageTarget(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getDescription() {
            return description;
        }
    }

    private int testsPerMethod;
    private Set<TestTypeFilter> testTypeFilters;
    private Set<PriorityFilter> priorityFilters;
    private Set<CoverageTarget> coverageTargets;
    private boolean autoAdjustTestBudget;

    public TestGenerationConfig() {
        this.testsPerMethod = 5;
        this.testTypeFilters = getDefaultTestTypes();
        this.priorityFilters = getDefaultPriorities();
        this.coverageTargets = getDefaultCoverageTargets();
        this.autoAdjustTestBudget = true;
    }

    public TestGenerationConfig(int testsPerMethod,
                                Set<TestTypeFilter> testTypeFilters,
                                Set<PriorityFilter> priorityFilters,
                                Set<CoverageTarget> coverageTargets) {
        this.testsPerMethod = Math.max(1, testsPerMethod);
        this.testTypeFilters = new HashSet<>(testTypeFilters);
        this.priorityFilters = new HashSet<>(priorityFilters);
        this.coverageTargets = new HashSet<>(coverageTargets);
        this.autoAdjustTestBudget = true;
    }

    public TestGenerationConfig(int testsPerMethod,
                                Set<TestTypeFilter> testTypeFilters,
                                Set<PriorityFilter> priorityFilters,
                                Set<CoverageTarget> coverageTargets,
                                boolean autoAdjustTestBudget) {
        this.testsPerMethod = Math.max(1, testsPerMethod);
        this.testTypeFilters = new HashSet<>(testTypeFilters);
        this.priorityFilters = new HashSet<>(priorityFilters);
        this.coverageTargets = new HashSet<>(coverageTargets);
        this.autoAdjustTestBudget = autoAdjustTestBudget;
    }

    @NotNull
    public static Set<TestTypeFilter> getDefaultTestTypes() {
        Set<TestTypeFilter> defaults = new HashSet<>();
        defaults.add(TestTypeFilter.UNIT);
        defaults.add(TestTypeFilter.INTEGRATION);
        defaults.add(TestTypeFilter.EDGE_CASE);
        defaults.add(TestTypeFilter.ERROR_HANDLING);
        return defaults;
    }

    @NotNull
    public static Set<PriorityFilter> getDefaultPriorities() {
        Set<PriorityFilter> defaults = new HashSet<>();
        defaults.add(PriorityFilter.HIGH);
        defaults.add(PriorityFilter.MEDIUM);
        return defaults;
    }

    @NotNull
    public static Set<CoverageTarget> getDefaultCoverageTargets() {
        Set<CoverageTarget> defaults = new HashSet<>();
        defaults.add(CoverageTarget.HAPPY_PATH);
        defaults.add(CoverageTarget.EDGE_CASES);
        defaults.add(CoverageTarget.ERROR_HANDLING);
        return defaults;
    }

    public int getTestsPerMethod() {
        return testsPerMethod;
    }

    public void setTestsPerMethod(int testsPerMethod) {
        this.testsPerMethod = Math.max(1, testsPerMethod);
    }

    @NotNull
    public Set<TestTypeFilter> getTestTypeFilters() {
        return new HashSet<>(testTypeFilters);
    }

    public void setTestTypeFilters(@NotNull Set<TestTypeFilter> testTypeFilters) {
        this.testTypeFilters = new HashSet<>(testTypeFilters);
    }

    @NotNull
    public Set<PriorityFilter> getPriorityFilters() {
        return new HashSet<>(priorityFilters);
    }

    public void setPriorityFilters(@NotNull Set<PriorityFilter> priorityFilters) {
        this.priorityFilters = new HashSet<>(priorityFilters);
    }

    @NotNull
    public Set<CoverageTarget> getCoverageTargets() {
        return new HashSet<>(coverageTargets);
    }

    public void setCoverageTargets(@NotNull Set<CoverageTarget> coverageTargets) {
        this.coverageTargets = new HashSet<>(coverageTargets);
    }

    public boolean isAutoAdjustTestBudget() {
        return autoAdjustTestBudget;
    }

    public void setAutoAdjustTestBudget(boolean autoAdjustTestBudget) {
        this.autoAdjustTestBudget = autoAdjustTestBudget;
    }

    @NotNull
    public TestGenerationConfig copy() {
        return new TestGenerationConfig(
            testsPerMethod,
            new HashSet<>(testTypeFilters),
            new HashSet<>(priorityFilters),
            new HashSet<>(coverageTargets),
            autoAdjustTestBudget
        );
    }

    public String toPromptDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append("Test Generation Configuration:\n");
        if (autoAdjustTestBudget) {
            sb.append("- Test Count: Intelligent auto-adjustment enabled (base: ")
              .append(testsPerMethod).append(" scenarios, adjusted by complexity)\n");
        } else {
            sb.append("- Test Count: Fixed at ").append(testsPerMethod).append(" scenarios per method\n");
        }

        if (!testTypeFilters.isEmpty()) {
            sb.append("- Test Types: ");
            testTypeFilters.forEach(type -> sb.append(type.getDisplayName()).append(", "));
            if (sb.length() >= 2) {
                sb.setLength(sb.length() - 2);
            }
            sb.append("\n");
        }

        if (!priorityFilters.isEmpty()) {
            sb.append("- Priorities: ");
            priorityFilters.forEach(priority -> sb.append(priority.getDisplayName()).append(", "));
            if (sb.length() >= 2) {
                sb.setLength(sb.length() - 2);
            }
            sb.append("\n");
        }

        if (!coverageTargets.isEmpty()) {
            sb.append("- Coverage Focus: ");
            coverageTargets.forEach(target -> sb.append(target.getDisplayName()).append(", "));
            if (sb.length() >= 2) {
                sb.setLength(sb.length() - 2);
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    @Override
    public String toString() {
        return "TestGenerationConfig{" +
               "testsPerMethod=" + testsPerMethod +
               ", testTypes=" + testTypeFilters.size() +
               ", priorities=" + priorityFilters.size() +
               ", coverageTargets=" + coverageTargets.size() +
               '}';
    }
}
