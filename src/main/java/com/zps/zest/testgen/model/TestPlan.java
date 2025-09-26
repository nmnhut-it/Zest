package com.zps.zest.testgen.model;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class TestPlan {
    private final List<String> targetMethods;
    private final String targetClass;
    private final List<TestScenario> testScenarios;
    private final List<String> dependencies;
    private final TestGenerationRequest.TestType recommendedTestType;
    private final String reasoning;
    private String testingNotes; // Natural language test configuration (editable)
    
    public TestPlan(@NotNull List<String> targetMethods,
                   @NotNull String targetClass,
                   @NotNull List<TestScenario> testScenarios,
                   @NotNull List<String> dependencies,
                   @NotNull TestGenerationRequest.TestType recommendedTestType,
                   @NotNull String reasoning) {
        this.targetMethods = new ArrayList<>(targetMethods);
        this.targetClass = targetClass;
        this.testScenarios = new ArrayList<>(testScenarios);
        this.dependencies = new ArrayList<>(dependencies);
        this.recommendedTestType = recommendedTestType;
        this.reasoning = reasoning;
        this.testingNotes = ""; // Default empty, will be set by CoordinatorAgent
    }
    
    @NotNull
    public List<String> getTargetMethods() {
        return new ArrayList<>(targetMethods);
    }
    
    @NotNull
    public String getTargetClass() {
        return targetClass;
    }
    
    @NotNull
    public List<TestScenario> getTestScenarios() {
        return new ArrayList<>(testScenarios);
    }
    
    @NotNull
    public List<String> getDependencies() {
        return new ArrayList<>(dependencies);
    }
    
    @NotNull
    public TestGenerationRequest.TestType getRecommendedTestType() {
        return recommendedTestType;
    }
    
    @NotNull
    public String getReasoning() {
        return reasoning;
    }

    @NotNull
    public String getTestingNotes() {
        return testingNotes != null ? testingNotes : "";
    }

    public void setTestingNotes(@NotNull String testingNotes) {
        this.testingNotes = testingNotes;
    }

    public void addScenario(@NotNull TestScenario scenario) {
        this.testScenarios.add(scenario);
    }

    public void removeScenario(@NotNull TestScenario scenario) {
        this.testScenarios.remove(scenario);
    }

    public void updateScenario(int index, @NotNull TestScenario newScenario) {
        if (index >= 0 && index < testScenarios.size()) {
            this.testScenarios.set(index, newScenario);
        }
    }

    public void setTestScenarios(@NotNull List<TestScenario> scenarios) {
        this.testScenarios.clear();
        this.testScenarios.addAll(scenarios);
    }

    public int getScenarioCount() {
        return testScenarios.size();
    }
    
    public boolean hasIntegrationScenarios() {
        return testScenarios.stream()
                .anyMatch(s -> s.getType() == TestScenario.Type.INTEGRATION);
    }
    
    public boolean hasUnitScenarios() {
        return testScenarios.stream()
                .anyMatch(s -> s.getType() == TestScenario.Type.UNIT);
    }
    
    @Override
    public String toString() {
        return "TestPlan{" +
               "targetMethods=" + targetMethods +
               ", targetClass='" + targetClass + '\'' +
               ", scenarioCount=" + testScenarios.size() +
               ", recommendedType=" + recommendedTestType +
               '}';
    }
    
    public static class TestScenario {
        private final String name;
        private final String description;
        private final Type type;
        private final List<String> inputs;
        private final String expectedOutcome;
        private final Priority priority;
        
        public enum Type {
            UNIT("Unit Test"),
            INTEGRATION("Integration Test"),
            EDGE_CASE("Edge Case Test"),
            ERROR_HANDLING("Error Handling Test");
            
            private final String displayName;
            
            Type(String displayName) {
                this.displayName = displayName;
            }
            
            public String getDisplayName() {
                return displayName;
            }
        }
        
        public enum Priority {
            HIGH(3, "High"),
            MEDIUM(2, "Medium"),
            LOW(1, "Low");
            
            private final int level;
            private final String displayName;
            
            Priority(int level, String displayName) {
                this.level = level;
                this.displayName = displayName;
            }
            
            public int getLevel() {
                return level;
            }
            
            public String getDisplayName() {
                return displayName;
            }
        }
        
        public TestScenario(@NotNull String name,
                          @NotNull String description,
                          @NotNull Type type,
                          @NotNull List<String> inputs,
                          @NotNull String expectedOutcome,
                          @NotNull Priority priority) {
            this.name = name;
            this.description = description;
            this.type = type;
            this.inputs = new ArrayList<>(inputs);
            this.expectedOutcome = expectedOutcome;
            this.priority = priority;
        }
        
        @NotNull
        public String getName() {
            return name;
        }
        
        @NotNull
        public String getDescription() {
            return description;
        }
        
        @NotNull
        public Type getType() {
            return type;
        }
        
        @NotNull
        public List<String> getInputs() {
            return new ArrayList<>(inputs);
        }
        
        @NotNull
        public String getExpectedOutcome() {
            return expectedOutcome;
        }
        
        @NotNull
        public Priority getPriority() {
            return priority;
        }
        
        @Override
        public String toString() {
            return "TestScenario{" +
                   "name='" + name + '\'' +
                   ", type=" + type +
                   ", priority=" + priority +
                   '}';
        }
    }
}