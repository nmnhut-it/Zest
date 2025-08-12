package com.zps.zest.codehealth.testplan.generation

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.zps.zest.codehealth.testplan.models.*
import com.zps.zest.codehealth.testplan.storage.TestPlanStorage
import kotlinx.coroutines.delay
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Service for generating test files from test plans
 */
@Service(Service.Level.PROJECT)
class TestGenerationService(private val project: Project) {
    
    private val storage = TestPlanStorage.getInstance(project)
    
    companion object {
        fun getInstance(project: Project): TestGenerationService {
            return project.getService(TestGenerationService::class.java)
        }
    }
    
    /**
     * Generates all pending test files from stored test plans
     */
    suspend fun generateAllTests(
        framework: TestFramework = TestFramework.JUNIT5,
        mockingFramework: MockingFramework = MockingFramework.MOCKITO,
        targetDirectory: String = "src/test/java",
        progressCallback: (TestGenerationProgress) -> Unit = {}
    ) {
        val pendingPlans = storage.getAllPendingTestPlans()
        val total = pendingPlans.size
        
        if (total == 0) {
            progressCallback(TestGenerationProgress(0, 0, "", "No pending test plans found"))
            return
        }
        
        pendingPlans.forEachIndexed { index, plan ->
            progressCallback(TestGenerationProgress(
                current = index + 1,
                total = total,
                currentPlan = plan.methodFqn,
                status = "Generating test for ${formatMethodName(plan.methodFqn)}..."
            ))
            
            val filePath = generateTestFile(plan, framework, mockingFramework, targetDirectory)
            storage.markTestGenerated(plan.id, filePath)
            
            delay(100) // Don't overwhelm the system
        }
        
        progressCallback(TestGenerationProgress(
            current = total,
            total = total,
            currentPlan = "",
            status = "Test generation completed successfully!"
        ))
    }
    
    /**
     * Generate a single test file
     */
    suspend fun generateTestFile(
        plan: TestPlanData,
        framework: TestFramework,
        mockingFramework: MockingFramework,
        targetDirectory: String
    ): String {
        val testCode = TestCodeGenerator.generateTestClass(plan, framework, mockingFramework)
        val fileName = generateTestFileName(plan.methodFqn)
        val filePath = "$targetDirectory/$fileName"
        
        // Create directory if it doesn't exist
        val targetDir = File(project.basePath, targetDirectory)
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        
        // Write test file
        val testFile = File(project.basePath, filePath)
        testFile.writeText(testCode)
        
        // Refresh VFS and format the file
        ApplicationManager.getApplication().invokeLater {
            LocalFileSystem.getInstance().refreshAndFindFileByPath(testFile.absolutePath)?.let { vf ->
                formatTestFile(vf)
            }
        }
        
        return filePath
    }
    
    private fun formatTestFile(virtualFile: VirtualFile) {
        ApplicationManager.getApplication().runWriteAction {
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
            if (psiFile != null) {
                CodeStyleManager.getInstance(project).reformat(psiFile)
            }
        }
    }
    
    private fun generateTestFileName(methodFqn: String): String {
        return if (methodFqn.contains(":")) {
            // JS/TS file - extract file name
            val colonIndex = methodFqn.lastIndexOf(":")
            val filePath = methodFqn.substring(0, colonIndex)
            val fileName = filePath.substringAfterLast("/").substringAfterLast("\\")
            "${fileName.substringBeforeLast(".")}Test.java"
        } else {
            // Java method - extract class name
            val className = methodFqn.substringBeforeLast(".")
            "${className.substringAfterLast(".")}Test.java"
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
}