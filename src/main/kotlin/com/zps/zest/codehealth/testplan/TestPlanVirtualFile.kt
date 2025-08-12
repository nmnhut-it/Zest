package com.zps.zest.codehealth.testplan

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import com.zps.zest.codehealth.testplan.models.TestPlanData
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Virtual file representing a test plan for editor display
 */
class TestPlanVirtualFile(
    private val methodFqn: String
) : VirtualFile() {
    
    private val fileName = "TestPlan_${extractMethodName(methodFqn)}.zestplan"
    private val path = "testplan://plans/$fileName"
    private var testPlanData: TestPlanData = TestPlanData(methodFqn = methodFqn)
    
    override fun getName(): String = fileName
    
    override fun getPath(): String = path
    
    override fun getFileSystem(): VirtualFileSystem = TestPlanVirtualFileSystem.getInstance()
    
    override fun isWritable(): Boolean = true // Test plans can be edited
    
    override fun isDirectory(): Boolean = false
    
    override fun isValid(): Boolean = true
    
    override fun getParent(): VirtualFile? = null
    
    override fun getChildren(): Array<VirtualFile>? = null
    
    override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): OutputStream {
        return ByteArrayOutputStream()
    }
    
    override fun contentsToByteArray(): ByteArray {
        return generateMarkdownContent().toByteArray(Charsets.UTF_8)
    }
    
    override fun getTimeStamp(): Long = System.currentTimeMillis()
    
    override fun getLength(): Long = contentsToByteArray().size.toLong()
    
    override fun refresh(asynchronous: Boolean, recursive: Boolean, postRunnable: Runnable?) {
        postRunnable?.run()
    }
    
    override fun getInputStream(): InputStream {
        return ByteArrayInputStream(contentsToByteArray())
    }
    
    /**
     * Get the test plan data
     */
    fun getTestPlanData(): TestPlanData = testPlanData
    
    /**
     * Update the test plan data
     */
    fun updateTestPlanData(data: TestPlanData) {
        testPlanData = data
    }
    
    /**
     * Get the method FQN
     */
    fun getMethodFqn(): String = methodFqn
    
    private fun generateMarkdownContent(): String {
        return buildString {
            appendLine("# Test Plan: ${formatMethodName(methodFqn)}")
            appendLine()
            appendLine("**Generated:** ${testPlanData.createdAt}")
            appendLine("**Method:** `$methodFqn`")
            appendLine("**Testability Score:** ${testPlanData.testabilityScore}/100")
            appendLine("**Complexity:** ${testPlanData.complexity}")
            appendLine()
            
            // Overview section
            appendLine("## Overview")
            appendLine("This test plan provides comprehensive testing strategy for the method `$methodFqn`.")
            appendLine()
            
            // Dependencies section
            if (testPlanData.dependencies.isNotEmpty()) {
                appendLine("## Dependencies")
                testPlanData.dependencies.forEach { dep ->
                    appendLine("- $dep")
                }
                appendLine()
            }
            
            // Mocking requirements section
            if (testPlanData.mockingRequirements.isNotEmpty()) {
                appendLine("## Mocking Requirements")
                testPlanData.mockingRequirements.forEach { mock ->
                    appendLine("- **${mock.className}** (${mock.mockType}): ${mock.reason}")
                }
                appendLine()
            }
            
            // Side effects section
            if (testPlanData.sideEffects.isNotEmpty()) {
                appendLine("## Side Effects to Consider")
                testPlanData.sideEffects.forEach { effect ->
                    appendLine("### ${effect.type}")
                    appendLine("**Description:** ${effect.description}")
                    appendLine("**Impact:** ${effect.impact}")
                    if (effect.mitigation != null) {
                        appendLine("**Mitigation:** ${effect.mitigation}")
                    }
                    appendLine()
                }
            }
            
            // Test cases section
            appendLine("## Test Cases")
            if (testPlanData.testCases.isNotEmpty()) {
                val groupedCases = testPlanData.testCases.groupBy { it.category }
                
                groupedCases.forEach { (category, cases) ->
                    appendLine("### ${formatCategoryName(category)}")
                    cases.forEach { testCase ->
                        appendLine("#### ${testCase.name}")
                        appendLine("**Description:** ${testCase.description}")
                        appendLine("**Setup:** ${testCase.setup}")
                        appendLine("**Input:** ${testCase.input}")
                        appendLine("**Expected:** ${testCase.expectedOutput}")
                        if (testCase.assertions.isNotEmpty()) {
                            appendLine("**Assertions:**")
                            testCase.assertions.forEach { assertion ->
                                appendLine("- $assertion")
                            }
                        }
                        appendLine()
                    }
                }
            } else {
                appendLine("*Test cases will be generated based on method analysis*")
                appendLine()
            }
            
            // Setup requirements section
            if (testPlanData.setupRequirements.isNotEmpty()) {
                appendLine("## Setup Requirements")
                testPlanData.setupRequirements.forEach { setup ->
                    appendLine("### ${setup.type}")
                    appendLine(setup.description)
                    if (setup.code != null) {
                        appendLine("```")
                        appendLine(setup.code)
                        appendLine("```")
                    }
                    appendLine()
                }
            }
            
            // Implementation template section
            appendLine("## Implementation Template")
            appendLine("```java")
            appendLine("// Test implementation will be generated here")
            appendLine("// Based on the selected test framework and mocking strategy")
            appendLine("```")
        }
    }
    
    private fun extractMethodName(fqn: String): String {
        return if (fqn.contains(":")) {
            // JS/TS file with line numbers
            val colonIndex = fqn.lastIndexOf(":")
            val filePath = fqn.substring(0, colonIndex)
            val fileName = filePath.substringAfterLast("/").substringAfterLast("\\")
            val lineInfo = fqn.substring(colonIndex)
            fileName.substringBeforeLast(".") + lineInfo
        } else {
            // Java method FQN
            fqn.substringAfterLast(".")
        }
    }
    
    private fun formatMethodName(fqn: String): String {
        return if (fqn.contains(":")) {
            // JS/TS file with line numbers
            val colonIndex = fqn.lastIndexOf(":")
            val filePath = fqn.substring(0, colonIndex)
            val fileName = filePath.substringAfterLast("/").substringAfterLast("\\")
            val lineInfo = fqn.substring(colonIndex)
            "$fileName$lineInfo"
        } else {
            // Java method FQN
            fqn
        }
    }
    
    private fun formatCategoryName(category: com.zps.zest.codehealth.testplan.models.TestCaseCategory): String {
        return when (category) {
            com.zps.zest.codehealth.testplan.models.TestCaseCategory.HAPPY_PATH -> "✅ Happy Path Tests"
            com.zps.zest.codehealth.testplan.models.TestCaseCategory.EDGE_CASE -> "⚠️ Edge Case Tests"
            com.zps.zest.codehealth.testplan.models.TestCaseCategory.ERROR_CONDITION -> "🚨 Error Condition Tests"
            com.zps.zest.codehealth.testplan.models.TestCaseCategory.BOUNDARY -> "🔍 Boundary Tests"
            com.zps.zest.codehealth.testplan.models.TestCaseCategory.NEGATIVE -> "❌ Negative Tests"
        }
    }
}