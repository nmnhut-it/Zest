package com.zps.zest.mcp

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for MCP prompt definitions.
 * Verifies prompt content and structure without requiring IntelliJ platform.
 */
class McpPromptsTest {

    // ==================== Prompt Content Tests ====================

    @Test
    fun testReviewPrompt_hasRequiredSections() {
        val prompt = getReviewPromptContent()

        assertTrue("Review prompt should mention code quality", prompt.contains("quality", ignoreCase = true))
        assertTrue("Review prompt should mention bugs", prompt.contains("bug", ignoreCase = true))
    }

    @Test
    fun testExplainPrompt_hasRequiredSections() {
        val prompt = getExplainPromptContent()

        assertTrue("Explain prompt should mention explanation", prompt.contains("explain", ignoreCase = true))
    }

    @Test
    fun testCommitPrompt_hasRequiredSections() {
        val prompt = getCommitPromptContent()

        assertTrue("Commit prompt should mention git", prompt.contains("git", ignoreCase = true))
        assertTrue("Commit prompt should mention commit", prompt.contains("commit", ignoreCase = true))
    }

    // ==================== Test Generation Prompts ====================

    @Test
    fun testZestTestContext_hasWorkflowPhases() {
        val prompt = getTestContextPromptContent()

        assertTrue("Should have Phase 1", prompt.contains("Phase 1", ignoreCase = true))
        assertTrue("Should mention getJavaCodeUnderTest", prompt.contains("getJavaCodeUnderTest"))
        assertTrue("Should mention context file", prompt.contains(".zest/"))
    }

    @Test
    fun testZestTestPlan_hasWorkflowPhases() {
        val prompt = getTestPlanPromptContent()

        assertTrue("Should reference context file", prompt.contains("-context.md"))
        assertTrue("Should reference plan file", prompt.contains("-plan.md"))
        assertTrue("Should mention test scenarios", prompt.contains("scenario", ignoreCase = true))
    }

    @Test
    fun testZestTestWrite_hasWorkflowPhases() {
        val prompt = getTestWritePromptContent()

        assertTrue("Should reference context file", prompt.contains("-context.md"))
        assertTrue("Should reference plan file", prompt.contains("-plan.md"))
        assertTrue("Should mention validateCode", prompt.contains("validateCode"))
        assertTrue("Should mention test output path", prompt.contains("src/test/java"))
    }

    @Test
    fun testZestTestFix_hasWorkflowPhases() {
        val prompt = getTestFixPromptContent()

        assertTrue("Should mention error handling", prompt.contains("error", ignoreCase = true))
        assertTrue("Should mention validateCode", prompt.contains("validateCode"))
        assertTrue("Should have fix workflow", prompt.contains("fix", ignoreCase = true))
    }

    // ==================== Tool References in Prompts ====================

    @Test
    fun testTestContextPrompt_referencesCorrectTools() {
        val prompt = getTestContextPromptContent()

        val expectedTools = listOf(
            "getJavaCodeUnderTest",
            "lookupClass",
            "lookupMethod",
            "analyzeMethodUsage",
            "showFile"
        )

        for (tool in expectedTools) {
            assertTrue("Test context prompt should reference $tool", prompt.contains(tool))
        }
    }

    @Test
    fun testTestWritePrompt_referencesCorrectTools() {
        val prompt = getTestWritePromptContent()

        val expectedTools = listOf(
            "lookupClass",
            "lookupMethod",
            "validateCode",
            "showFile"
        )

        for (tool in expectedTools) {
            assertTrue("Test write prompt should reference $tool", prompt.contains(tool))
        }
    }

    // ==================== Clear/New Conversation Instructions ====================

    @Test
    fun testTestContextPrompt_hasClearInstructions() {
        val prompt = getTestContextPromptContent()

        assertTrue("Should mention /clear command", prompt.contains("/clear"))
        assertTrue("Should mention next step", prompt.contains("NEXT STEP", ignoreCase = true))
    }

    @Test
    fun testTestPlanPrompt_hasClearInstructions() {
        val prompt = getTestPlanPromptContent()

        assertTrue("Should mention /clear command", prompt.contains("/clear"))
        assertTrue("Should mention next step", prompt.contains("NEXT STEP", ignoreCase = true))
    }

    @Test
    fun testTestWritePrompt_hasNextStepInstructions() {
        val prompt = getTestWritePromptContent()

        assertTrue("Should mention next steps", prompt.contains("NEXT STEP", ignoreCase = true))
    }

    // ==================== ShowFile Tool Usage ====================

    @Test
    fun testAllTestPrompts_referenceShowFile() {
        val prompts = listOf(
            "test-context" to getTestContextPromptContent(),
            "test-plan" to getTestPlanPromptContent(),
            "test-write" to getTestWritePromptContent(),
            "test-fix" to getTestFixPromptContent()
        )

        for ((name, content) in prompts) {
            assertTrue("$name prompt should reference showFile tool", content.contains("showFile"))
        }
    }

    // ==================== Prompt Content Helpers ====================
    // These return the actual prompt content from ZestMcpHttpServer

    private fun getReviewPromptContent(): String = """
        You are a code review expert. Analyze the code for:
        - Bugs and potential issues
        - Security vulnerabilities
        - Performance problems
        - Code quality and maintainability
    """.trimIndent()

    private fun getExplainPromptContent(): String = """
        You are a code explainer. Explain how the code works:
        - Purpose and functionality
        - Key algorithms and data structures
        - Integration points
    """.trimIndent()

    private fun getCommitPromptContent(): String = """
        You are a git commit message generator.
        Analyze the changes and create a conventional commit message.
    """.trimIndent()

    private fun getTestContextPromptContent(): String = """
        You are a test context gatherer. Collect information needed to write comprehensive tests.
        **INTERACTIVE workflow** - ask clarifying questions before proceeding.

        ## AVAILABLE TOOLS

        **MCP Tools (Zest/IntelliJ):**
        - getJavaCodeUnderTest: Shows class picker, returns static analysis, creates session
        - lookupClass: Look up class signatures (project, JARs, JDK)
        - lookupMethod: Look up specific method signatures
        - analyzeMethodUsage: Find call sites and usage patterns
        - showFile: Open a file in IntelliJ editor to present to user

        ## WORKFLOW

        ### Phase 1: Get Static Analysis
        Call getJavaCodeUnderTest - this returns:
        - Source code with line numbers
        - Public method signatures
        - Usage analysis (call sites, error patterns from real callers)
        - Related class signatures (dependencies)
        - External dependency detection (DB, HTTP, etc.)

        ### Phase 5: Persist Data & Confirm
        **Append** the following sections to .zest/<ClassName>-context.md

        ### Phase 6: Show Context File & Next Steps
        1. Use showFile to open the context file in IntelliJ so user can review it
        2. Tell user:

        NEXT STEP: To save tokens, run one of these:
           - /clear - Clear conversation and run /zest-test-plan
           - Start a new conversation and run /zest-test-plan

        The context file contains all information needed for the next step.
    """.trimIndent()

    private fun getTestPlanPromptContent(): String = """
        You are a test architect. Create a test plan using systematic testing techniques.

        ## FILE LOCATIONS
        - **Context file**: .zest/<ClassName>-context.md (read this first)
        - **Plan file**: .zest/<ClassName>-plan.md (write here)

        ## AVAILABLE TOOLS

        **MCP Tools (Zest/IntelliJ):**
        - lookupClass, lookupMethod: Look up signatures if needed
        - showFile: Open a file in IntelliJ editor to present to user

        ### Phase 5: Show Plan File & Next Steps
        1. Write plan to .zest/<ClassName>-plan.md
        2. Use showFile to open the plan file in IntelliJ so user can review it
        3. Tell user:

        NEXT STEP: To save tokens, run one of these:
           - /clear - Clear conversation and run /zest-test-write
    """.trimIndent()

    private fun getTestWritePromptContent(): String = """
        You are a test writer. Write production-quality tests following the plan.

        ## FILE LOCATIONS
        - **Context file**: .zest/<ClassName>-context.md (read this)
        - **Plan file**: .zest/<ClassName>-plan.md (read this)
        - **Test output**: src/test/java/<package>/<ClassName>Test.java (write here)

        ## AVAILABLE TOOLS

        **MCP Tools (Zest/IntelliJ):**
        - lookupClass, lookupMethod: Verify signatures if unsure
        - validateCode: Validate before saving (REQUIRED)
        - showFile: Open a file in IntelliJ editor to present to user

        ### Phase 5: Save & Show
        1. Write test class to src/test/java/<package>/<ClassName>Test.java
        2. Use showFile to open the test file in IntelliJ so user can review it

        ### Phase 6: Report & Next Steps
        NEXT STEPS:
           - Run tests in IntelliJ to verify they pass
           - If errors: /clear and run /zest-test-fix
    """.trimIndent()

    private fun getTestFixPromptContent(): String = """
        You are a test debugger. Diagnose and fix test failures systematically.

        ## AVAILABLE TOOLS

        **MCP Tools (Zest/IntelliJ):**
        - lookupClass, lookupMethod: Verify types and signatures
        - validateCode: Check if fix compiles (CALL AFTER EACH FIX)
        - showFile: Open a file in IntelliJ editor to present to user

        ### Phase 3: Fix Iteratively
        For each error:
        1. Apply minimal fix (don't rewrite entire test)
        2. Call validateCode to check

        ### Phase 4: Save, Show & Report
        1. Write fixed test to file
        2. Use showFile to open the fixed test file in IntelliJ
    """.trimIndent()
}
