package com.zps.zest.codehealth.testplan.storage

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.zps.zest.codehealth.testplan.models.TestPlanData
import java.time.LocalDateTime

/**
 * Service for persisting and retrieving test plans
 */
@Service(Service.Level.PROJECT)
@State(name = "TestPlanStorage", storages = [Storage("testPlans.xml")])
class TestPlanStorage : PersistentStateComponent<TestPlanStorage.State> {
    
    data class State(
        var testPlans: MutableMap<String, TestPlanData> = mutableMapOf(),
        var generatedTestFiles: MutableMap<String, String> = mutableMapOf() // planId -> generated file path
    )
    
    data class TestPlanStatistics(
        val totalPlans: Int,
        val pendingPlans: Int,
        val generatedPlans: Int,
        val averageTestabilityScore: Double,
        val highScorePlans: Int = 0,
        val lowScorePlans: Int = 0
    )
    
    private var state = State()
    
    companion object {
        fun getInstance(project: Project): TestPlanStorage {
            return project.getService(TestPlanStorage::class.java)
        }
    }
    
    override fun getState(): State = state
    
    override fun loadState(state: State) {
        this.state = state
    }
    
    /**
     * Store a test plan
     */
    fun storeTestPlan(testPlan: TestPlanData) {
        state.testPlans[testPlan.id] = testPlan.copy(updatedAt = LocalDateTime.now())
    }
    
    /**
     * Get a test plan by ID
     */
    fun getTestPlan(id: String): TestPlanData? {
        return state.testPlans[id]
    }
    
    /**
     * Get test plan by method FQN
     */
    fun getTestPlanByMethod(methodFqn: String): TestPlanData? {
        return state.testPlans.values.find { it.methodFqn == methodFqn }
    }
    
    /**
     * Get all test plans
     */
    fun getAllTestPlans(): List<TestPlanData> {
        return state.testPlans.values.toList().sortedByDescending { it.updatedAt }
    }
    
    /**
     * Get all pending test plans (not yet generated)
     */
    fun getAllPendingTestPlans(): List<TestPlanData> {
        return state.testPlans.values.filter { !it.isGenerated }.sortedByDescending { it.updatedAt }
    }
    
    /**
     * Get all generated test plans
     */
    fun getAllGeneratedTestPlans(): List<TestPlanData> {
        return state.testPlans.values.filter { it.isGenerated }.sortedByDescending { it.updatedAt }
    }
    
    /**
     * Mark a test plan as generated
     */
    fun markTestGenerated(planId: String, filePath: String? = null) {
        state.testPlans[planId]?.let { plan ->
            val updatedPlan = plan.copy(isGenerated = true, updatedAt = LocalDateTime.now())
            state.testPlans[planId] = updatedPlan
            
            if (filePath != null) {
                state.generatedTestFiles[planId] = filePath
            }
        }
    }
    
    /**
     * Get generated test file path for a plan
     */
    fun getGeneratedTestFilePath(planId: String): String? {
        return state.generatedTestFiles[planId]
    }
    
    /**
     * Delete a test plan
     */
    fun deleteTestPlan(id: String) {
        state.testPlans.remove(id)
        state.generatedTestFiles.remove(id)
    }
    
    /**
     * Delete test plan by method FQN
     */
    fun deleteTestPlanByMethod(methodFqn: String) {
        val plan = getTestPlanByMethod(methodFqn)
        if (plan != null) {
            deleteTestPlan(plan.id)
        }
    }
    
    /**
     * Clear all test plans
     */
    fun clearAllTestPlans() {
        state.testPlans.clear()
        state.generatedTestFiles.clear()
    }
    
    
    /**
     * Get statistics
     */
    fun getStatistics(): TestPlanStatistics {
        val plans = getAllTestPlans()
        return TestPlanStatistics(
            totalPlans = plans.size,
            pendingPlans = plans.count { !it.isGenerated },
            generatedPlans = plans.count { it.isGenerated },
            averageTestabilityScore = if (plans.isNotEmpty()) plans.map { it.testabilityScore }.average() else 0.0,
            highScorePlans = plans.count { it.testabilityScore >= 80 },
            lowScorePlans = plans.count { it.testabilityScore < 60 }
        )
    }
}