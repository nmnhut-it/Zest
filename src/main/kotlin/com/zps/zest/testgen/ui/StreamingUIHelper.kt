package com.zps.zest.testgen.ui

import com.intellij.openapi.diagnostic.Logger
import com.zps.zest.testgen.model.*
import com.zps.zest.testgen.ui.model.*
import javax.swing.SwingUtilities
import java.util.regex.Pattern

/**
 * Event listener interface for streaming updates
 */
interface StreamingEventListener {
    fun onFileAnalyzed(data: ContextDisplayData)
    fun onTestPlanUpdated(data: TestPlanDisplayData)
    fun onTestGenerated(data: GeneratedTestDisplayData)
    fun onMergedTestClassUpdated(mergedClass: MergedTestClass) {}  // Default empty implementation
    fun onMergerAgentCreated(mergerAgent: com.zps.zest.testgen.agents.AITestMergerAgent) {}  // Default empty implementation
    fun onMergingStarted() {}  // Default empty implementation - called when merging phase begins
    fun onMergingCompleted(success: Boolean) {}  // Default empty implementation - called when merging finishes
    fun onStatusChanged(status: String)
    fun onProgressChanged(percent: Int, message: String)
    fun onGenerationCompleted(session: TestGenerationSession) {}  // Default empty implementation
    fun onPhaseStarted(phase: com.zps.zest.testgen.statemachine.TestGenerationState) {}  // Default empty implementation
}

/**
 * Helper class to parse streaming output from agents and update UI components.
 * Works with the new LangChain4j-based streaming agents.
 */
class StreamingUIHelper(
    private val eventListener: StreamingEventListener
) {
    
    companion object {
        private val LOG = Logger.getInstance(StreamingUIHelper::class.java)
        
        // Patterns for parsing streaming output
        private val AGENT_HEADER_PATTERN = Pattern.compile("={60}\\s*🤖\\s*(\\w+)\\s+Starting")
        private val AGENT_FOOTER_PATTERN = Pattern.compile("={60}\\s*✅\\s*(\\w+)\\s+Complete")
        private val TOOL_CALL_PATTERN = Pattern.compile("🔧\\s*Tool Call:\\s*(.+)")
        private val STATUS_PATTERN = Pattern.compile("(🔍|🎯|✍️|✅|❌)\\s*(.+)")
        
        // Patterns for context analysis
        private val FILE_READING_PATTERN = Pattern.compile("Reading file:\\s*(.+)")
        private val FILE_ANALYZING_PATTERN = Pattern.compile("(?:📄\\s*)?Analyzing file:\\s*(.+)")
        private val ANALYSIS_COMPLETE_PATTERN = Pattern.compile("Analysis complete for:\\s*(.+)\\n([\\s\\S]+?)(?=\\n{2}|$)")
        
        // Patterns for test plan updates
        private val TARGET_CLASS_PATTERN = Pattern.compile("Target class set to:\\s*(.+)")
        private val TARGET_METHOD_PATTERN = Pattern.compile("(?:Target method set to|Added target method):\\s*(.+)")
        private val SCENARIO_ADDED_PATTERN = Pattern.compile("Added scenario #(\\d+):\\s*(.+)")
        private val SCENARIOS_BATCH_START = Pattern.compile("📋 SCENARIOS_BATCH_START")
        private val SCENARIOS_BATCH_END = Pattern.compile("📋 SCENARIOS_BATCH_END")
        private val SCENARIO_LINE = Pattern.compile("SCENARIO\\|([^|]+)\\|([^|]+)\\|([^|]+)\\|([^|]+)")
        private val TEST_PLAN_SUMMARY_PATTERN = Pattern.compile("Test Plan Summary:\\s*\\n([\\s\\S]+?)(?=\\n{2}|$)")
        
        // Patterns for test generation
        private val TEST_CLASS_PATTERN = Pattern.compile("Test class name set to:\\s*(.+)")
        private val PACKAGE_PATTERN = Pattern.compile("Package set to:\\s*(.+)")
        private val TEST_METHOD_PATTERN = Pattern.compile("Test method added:\\s*(.+)\\s*\\(#(\\d+)\\)")
        private val TEST_GENERATED_PATTERN = Pattern.compile("✅ Test generated for:\\s*(.+)\\n```(?:java)?\\n([\\s\\S]+?)\\n```")
    }
    
    private var currentAgent: String? = null
    private var currentPhase: Phase = Phase.IDLE
    // Removed progressPercent - using only phase tracking now
    
    // Temporary storage for building test plan
    private var targetClass: String? = null
    private val targetMethods = mutableListOf<String>()
    private val scenarios = mutableListOf<TestPlan.TestScenario>()
    
    // Temporary storage for test generation
    private var testClassName: String? = null
    private var packageName: String? = null
    private var testMethodCount: Int = 0
    
    enum class Phase {
        IDLE,
        CONTEXT_GATHERING,
        TEST_PLANNING,
        USER_SELECTION,
        TEST_GENERATION,
        VALIDATION,
        COMPLETE
    }
    
    /**
     * Process streaming text from agents.
     * Most parsing logic is now handled by direct object passing from agents.
     * This method only tracks agent transitions and progress.
     */
    fun processStreamingText(text: String) {
        SwingUtilities.invokeLater {
            try {
                // Check for agent transitions
                checkAgentTransition(text)
                
                // Check for tool calls
                checkToolCall(text)
                
                // Check for status updates
                checkStatusUpdate(text)
                
                // Track phase changes only (no progress calculation)
                
                // Update status
                updateStatus()
                
            } catch (e: Exception) {
                LOG.warn("Error processing streaming text", e)
            }
        }
    }
    
    
    /**
     * Check for agent transitions in the text.
     */
    private fun checkAgentTransition(text: String) {
        // Check for agent start
        val startMatcher = AGENT_HEADER_PATTERN.matcher(text)
        if (startMatcher.find()) {
            currentAgent = startMatcher.group(1)
            when (currentAgent) {
                "ContextAgent" -> {
                    currentPhase = Phase.CONTEXT_GATHERING
                    eventListener.onStatusChanged("🔍 Gathering context...")
                }
                "CoordinatorAgent" -> {
                    currentPhase = Phase.TEST_PLANNING
                    eventListener.onStatusChanged("🎯 Planning test scenarios...")
                }
                "TestWriterAgent" -> {
                    currentPhase = Phase.TEST_GENERATION
                    eventListener.onStatusChanged("✍️ Generating test code...")
                }
                "ValidatorAgent" -> {
                    currentPhase = Phase.VALIDATION
                    eventListener.onStatusChanged("✅ Validating tests...")
                }
            }
        }
        
        // Check for agent completion
        val endMatcher = AGENT_FOOTER_PATTERN.matcher(text)
        if (endMatcher.find()) {
            when (currentAgent) {
                "ContextAgent" -> currentPhase = Phase.CONTEXT_GATHERING
                "CoordinatorAgent" -> {
                    currentPhase = Phase.TEST_PLANNING
                    // Test plan will be sent directly by the agent
                }
                "TestWriterAgent" -> currentPhase = Phase.TEST_GENERATION
                "ValidatorAgent" -> {
                    currentPhase = Phase.COMPLETE
                    eventListener.onStatusChanged("✨ Test generation complete!")
                }
            }
        }
    }
    
    /**
     * Check for tool calls.
     */
    private fun checkToolCall(text: String) {
        val matcher = TOOL_CALL_PATTERN.matcher(text)
        if (matcher.find()) {
            val toolName = matcher.group(1)
            LOG.debug("Tool called: $toolName")
        }
    }
    
    /**
     * Check for status updates.
     */
    private fun checkStatusUpdate(text: String) {
        val matcher = STATUS_PATTERN.matcher(text)
        if (matcher.find()) {
            val icon = matcher.group(1)
            val message = matcher.group(2)
            eventListener.onStatusChanged("$icon $message")
        }
    }
    
    
    /**
     * Update status based on current phase (no progress calculation).
     */
    private fun updateStatus() {
        val message = when (currentPhase) {
            Phase.CONTEXT_GATHERING -> "Gathering context..."
            Phase.TEST_PLANNING -> "Planning scenarios..."
            Phase.USER_SELECTION -> "Waiting for user selection..."
            Phase.TEST_GENERATION -> "Generating tests..."
            Phase.VALIDATION -> "Validating..."
            Phase.COMPLETE -> "Complete!"
            Phase.IDLE -> "Idle"
        }
        eventListener.onProgressChanged(0, message) // Always 0 progress since we don't track it
    }
    
    /**
     * Reset the helper for a new session.
     */
    fun reset() {
        currentAgent = null
        currentPhase = Phase.IDLE
        // Reset phase only - no progress tracking
        targetClass = null
        targetMethods.clear()
        scenarios.clear()
        testClassName = null
        packageName = null
        testMethodCount = 0
    }
    
    /**
     * Get the current phase as a string
     */
    fun getCurrentPhase(): String {
        return currentPhase.name
    }
}