package com.zps.zest.testgen.ui.model

import com.zps.zest.testgen.model.TestPlan

/**
 * Data model for displaying test plan information in the UI.
 * Provides structured data for both overview and detailed views.
 */
data class TestPlanDisplayData(
    val targetClass: String,
    val targetMethods: List<String>,
    val recommendedTestType: String,
    val scenarios: List<ScenarioDisplayData>,
    val summary: String,
    val totalScenarios: Int,
    val selectedScenarios: Set<String> = emptySet()  // IDs of selected scenarios
) {
    /**
     * Get count of selected scenarios
     */
    fun getSelectedCount(): Int = selectedScenarios.size
    
    /**
     * Check if all scenarios are selected
     */
    fun areAllSelected(): Boolean = selectedScenarios.size == totalScenarios
    
    /**
     * Check if a specific scenario is selected
     */
    fun isScenarioSelected(scenarioId: String): Boolean = 
        scenarioId in selectedScenarios
    
    companion object {
        /**
         * Create display data from a TestPlan model
         */
        fun fromTestPlan(testPlan: TestPlan): TestPlanDisplayData {
            return TestPlanDisplayData(
                targetClass = testPlan.targetClass,
                targetMethods = testPlan.targetMethods,
                recommendedTestType = testPlan.recommendedTestType.description,
                scenarios = testPlan.testScenarios.map { scenario ->
                    ScenarioDisplayData.fromScenario(scenario)
                },
                summary = "Test plan with ${testPlan.scenarioCount} scenarios for ${testPlan.targetClass}",
                totalScenarios = testPlan.scenarioCount
            )
        }
    }
}

/**
 * Data model for individual test scenario display
 */
data class ScenarioDisplayData(
    val id: String,
    val name: String,
    val description: String,
    val priority: Priority,
    val category: String,
    val setupSteps: List<String> = emptyList(),
    val executionSteps: List<String> = emptyList(),
    val assertions: List<String> = emptyList(),
    val expectedComplexity: String = "Medium",
    val generationStatus: GenerationStatus = GenerationStatus.PENDING
) {
    enum class Priority(val displayName: String, val color: String) {
        HIGH("High", "#FF0000"),
        MEDIUM("Medium", "#FFA500"),
        LOW("Low", "#00FF00")
    }
    
    enum class GenerationStatus {
        PENDING,      // Not yet generated
        GENERATING,   // Currently being generated
        COMPLETED,    // Successfully generated
        FAILED,       // Generation failed
        SKIPPED       // User chose not to generate
    }
    
    /**
     * Get priority icon for display
     */
    fun getPriorityIcon(): String = when (priority) {
        Priority.HIGH -> "🔴"
        Priority.MEDIUM -> "🟡"
        Priority.LOW -> "🟢"
    }
    
    /**
     * Get status icon for display
     */
    fun getStatusIcon(): String = when (generationStatus) {
        GenerationStatus.PENDING -> "⏳"
        GenerationStatus.GENERATING -> "⚙️"
        GenerationStatus.COMPLETED -> "✅"
        GenerationStatus.FAILED -> "❌"
        GenerationStatus.SKIPPED -> "⏭️"
    }
    
    /**
     * Get a brief summary for list display
     */
    fun getSummary(): String = 
        "$name [${priority.displayName}] - ${getStatusIcon()}"
    
    companion object {
        /**
         * Create display data from a TestPlan.TestScenario
         */
        fun fromScenario(scenario: TestPlan.TestScenario): ScenarioDisplayData {
            return ScenarioDisplayData(
                id = "scenario_${scenario.name.hashCode()}",  // Generate ID from name
                name = scenario.name,
                description = scenario.description,
                priority = when (scenario.priority) {
                    TestPlan.TestScenario.Priority.HIGH -> Priority.HIGH
                    TestPlan.TestScenario.Priority.MEDIUM -> Priority.MEDIUM
                    TestPlan.TestScenario.Priority.LOW -> Priority.LOW
                },
                category = scenario.type.displayName  // Use type as category
            )
        }
    }
}