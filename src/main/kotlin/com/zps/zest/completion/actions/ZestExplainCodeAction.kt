package com.zps.zest.completion.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiFile
import com.zps.zest.explanation.agents.CodeExplanationAgent
import com.zps.zest.explanation.ui.CodeExplanationDialog
import com.zps.zest.langchain4j.ZestLangChain4jService
import com.zps.zest.langchain4j.naive_service.NaiveLLMService
import java.util.concurrent.CompletableFuture

/**
 * Action for explaining selected code or entire file and showing its interactions with other code.
 * Uses a language-agnostic approach with LLM keyword extraction and grep search tools.
 */
class ZestExplainCodeAction : AnAction("Explain Code", "Explain the selected code and its interactions", null) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        
        try {
            // Get the code to explain (selection or entire file)
            val codeToExplain = getCodeToExplain(editor, psiFile)
            if (codeToExplain.isBlank()) {
                Messages.showWarningDialog(
                    project,
                    "No code selected and file appears to be empty.",
                    "Code Explanation"
                )
                return
            }

            // Determine the programming language
            val language = determineLanguage(psiFile)
            val filePath = psiFile.virtualFile.path

            // Show progress dialog
            val progressDialog = CodeExplanationDialog.showProgressDialog(project, filePath)

            // Start the explanation process asynchronously
            explainCodeAsync(project, filePath, codeToExplain, language) { result ->
                ApplicationManager.getApplication().invokeLater {
                    progressDialog.close(0)
                    
                    // Show the detailed explanation dialog
                    val explanationDialog = CodeExplanationDialog(project, result)
                    explanationDialog.show()
                }
            }

        } catch (ex: Exception) {
            Messages.showErrorDialog(
                project,
                "Failed to start code explanation: ${ex.message}",
                "Code Explanation Error"
            )
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        
        e.presentation.isEnabledAndVisible = project != null && editor != null && psiFile != null
    }

    /**
     * Get the code to explain - either selected text or entire file content
     */
    private fun getCodeToExplain(editor: Editor, psiFile: PsiFile): String {
        val selectionModel = editor.selectionModel
        
        return if (selectionModel.hasSelection()) {
            // Use selected text
            selectionModel.selectedText ?: ""
        } else {
            // Use entire file content
            val document = editor.document
            FileDocumentManager.getInstance().saveDocument(document)
            document.text
        }
    }

    /**
     * Determine the programming language from the file extension
     */
    private fun determineLanguage(psiFile: PsiFile): String {
        val fileName = psiFile.name.lowercase()
        
        return when {
            fileName.endsWith(".java") -> "Java"
            fileName.endsWith(".kt") -> "Kotlin"
            fileName.endsWith(".js") -> "JavaScript"
            fileName.endsWith(".ts") -> "TypeScript"
            fileName.endsWith(".py") -> "Python"
            fileName.endsWith(".cpp") || fileName.endsWith(".cc") -> "C++"
            fileName.endsWith(".c") -> "C"
            fileName.endsWith(".h") || fileName.endsWith(".hpp") -> "C/C++ Header"
            fileName.endsWith(".cs") -> "C#"
            fileName.endsWith(".go") -> "Go"
            fileName.endsWith(".rs") -> "Rust"
            fileName.endsWith(".php") -> "PHP"
            fileName.endsWith(".rb") -> "Ruby"
            fileName.endsWith(".scala") -> "Scala"
            fileName.endsWith(".clj") || fileName.endsWith(".cljs") -> "Clojure"
            fileName.endsWith(".xml") -> "XML"
            fileName.endsWith(".json") -> "JSON"
            fileName.endsWith(".yaml") || fileName.endsWith(".yml") -> "YAML"
            fileName.endsWith(".sql") -> "SQL"
            fileName.endsWith(".sh") -> "Shell Script"
            fileName.endsWith(".gradle") -> "Gradle"
            else -> "Unknown"
        }
    }

    /**
     * Explain code asynchronously using the CodeExplanationAgent
     */
    private fun explainCodeAsync(
        project: Project,
        filePath: String, 
        codeContent: String,
        language: String,
        onComplete: (CodeExplanationAgent.CodeExplanationResult) -> Unit
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val langChainService = project.getService(ZestLangChain4jService::class.java)
                val naiveLlmService = project.getService(NaiveLLMService::class.java)
                
                if (langChainService == null || naiveLlmService == null) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "LangChain4j or LLM service not available. Please check your configuration.",
                            "Service Unavailable"
                        )
                    }
                    return@executeOnPooledThread
                }

                // Create the explanation agent
                val explanationAgent = CodeExplanationAgent(project, langChainService, naiveLlmService)

                // Progress callback for UI updates
                val progressCallback: (String) -> Unit = { status ->
                    // Could update progress dialog here if needed
                }

                // Start the explanation process
                val resultFuture: CompletableFuture<CodeExplanationAgent.CodeExplanationResult> = 
                    explanationAgent.explainCode(filePath, codeContent, language, progressCallback)

                // Handle the result
                resultFuture.whenComplete { result, throwable ->
                    if (throwable != null) {
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showErrorDialog(
                                project,
                                "Code explanation failed: ${throwable.message}",
                                "Explanation Error"
                            )
                        }
                    } else {
                        onComplete(result)
                    }
                }

            } catch (ex: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "Failed to initialize code explanation: ${ex.message}",
                        "Initialization Error"
                    )
                }
            }
        }
    }
}