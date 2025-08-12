package com.zps.zest.codehealth.testplan.models

import java.time.LocalDateTime

/**
 * Data class representing a test plan for a method
 */
data class TestPlanData(
    val id: String = generateId(),
    val methodFqn: String,
    val testabilityScore: Int = 0,
    val complexity: Int = 0,
    val dependencies: List<String> = emptyList(),
    val mockingRequirements: List<MockingRequirement> = emptyList(),
    val sideEffects: List<SideEffect> = emptyList(),
    val testCases: List<TestCase> = emptyList(),
    val setupRequirements: List<SetupRequirement> = emptyList(),
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now(),
    val isGenerated: Boolean = false
) {
    companion object {
        private fun generateId(): String = System.currentTimeMillis().toString() + "_" + (Math.random() * 1000).toInt()
    }
}

/**
 * Represents a mocking requirement for testing
 */
data class MockingRequirement(
    val className: String,
    val reason: String,
    val mockType: MockType = MockType.MOCK
)

enum class MockType {
    MOCK,      // Standard mock
    SPY,       // Partial mock
    STUB       // Simple stub
}

/**
 * Represents a side effect that affects testability
 */
data class SideEffect(
    val type: SideEffectType,
    val description: String,
    val impact: String,
    val mitigation: String? = null
)

enum class SideEffectType {
    FILE_IO,
    DATABASE,
    NETWORK,
    THREADING,
    STATIC_CALL,
    SYSTEM_PROPERTY,
    RANDOM,
    TIME_DEPENDENT
}

/**
 * Represents a test case
 */
data class TestCase(
    val name: String,
    val description: String,
    val category: TestCaseCategory,
    val setup: String,
    val input: String,
    val expectedOutput: String,
    val assertions: List<String> = emptyList(),
    val priority: TestPriority = TestPriority.MEDIUM
)

enum class TestCaseCategory {
    HAPPY_PATH,
    EDGE_CASE,
    ERROR_CONDITION,
    BOUNDARY,
    NEGATIVE
}

enum class TestPriority {
    HIGH,
    MEDIUM,
    LOW
}

/**
 * Represents setup requirements for testing
 */
data class SetupRequirement(
    val type: SetupType,
    val description: String,
    val code: String? = null
)

enum class SetupType {
    DATABASE,
    FILES,
    ENVIRONMENT,
    DEPENDENCIES,
    CONFIGURATION
}

/**
 * Result of testability analysis
 */
data class TestabilityResult(
    val methodFqn: String,
    val score: Int,
    val complexity: Int,
    val dependencies: List<String>,
    val mockingRequirements: List<MockingRequirement>,
    val sideEffects: List<SideEffect>,
    val recommendations: List<String>,
    val progress: Float = 1.0f
)

/**
 * Progress information for test generation
 */
data class TestGenerationProgress(
    val current: Int,
    val total: Int,
    val currentPlan: String,
    val status: String
)

/**
 * Test framework options
 */
enum class TestFramework {
    JUNIT4,
    JUNIT5,
    TESTNG
}

/**
 * Mocking framework options
 */
enum class MockingFramework {
    MOCKITO,
    POWERMOCK,
    EASYMOCK,
    JMOCKIT
}