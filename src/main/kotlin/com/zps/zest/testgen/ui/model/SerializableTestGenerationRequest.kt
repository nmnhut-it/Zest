package com.zps.zest.testgen.ui.model

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.zps.zest.testgen.model.TestGenerationRequest

/**
 * Serializable version of TestGenerationRequest that can be persisted.
 * Contains only data that can be serialized to JSON.
 */
data class SerializableTestGenerationRequest(
    val targetFilePath: String,
    val targetMethodNames: List<String>,
    val selectedCode: String?,
    val testType: String,
    val additionalContext: Map<String, String>
) {
    companion object {
        /**
         * Create from a TestGenerationRequest (for saving)
         */
        fun fromRequest(request: TestGenerationRequest): SerializableTestGenerationRequest {
            return SerializableTestGenerationRequest(
                targetFilePath = request.targetFile.virtualFile?.path ?: "",
                targetMethodNames = request.targetMethods.mapNotNull { it.name },
                selectedCode = request.selectedCode,
                testType = request.testType.name,
                additionalContext = request.additionalContext
            )
        }
        
        /**
         * Reconstruct a TestGenerationRequest from serializable data (for loading)
         */
        fun toRequest(
            project: Project,
            data: SerializableTestGenerationRequest
        ): TestGenerationRequest? {
            // Find the file by path
            val virtualFile = VirtualFileManager.getInstance()
                .findFileByUrl("file://${data.targetFilePath}") ?: return null
            
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? PsiFile 
                ?: return null
            
            // Find methods by name in the file
            val psiMethods = if (data.targetMethodNames.isNotEmpty()) {
                PsiTreeUtil.findChildrenOfType(psiFile, PsiMethod::class.java)
                    .filter { method -> method.name in data.targetMethodNames }
                    .toList()
            } else {
                emptyList()
            }
            
            // Reconstruct the test type enum
            val testType = try {
                TestGenerationRequest.TestType.valueOf(data.testType)
            } catch (e: IllegalArgumentException) {
                TestGenerationRequest.TestType.AUTO_DETECT
            }
            
            return TestGenerationRequest(
                psiFile,
                psiMethods,
                data.selectedCode,
                testType,
                data.additionalContext
            )
        }
    }
}