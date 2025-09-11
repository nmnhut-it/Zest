package com.zps.zest.codehealth.actions

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiFile
import com.zps.zest.codehealth.BackgroundHealthReviewer
import com.zps.zest.codehealth.CodeHealthAnalyzer
import com.zps.zest.codehealth.CodeHealthReportStorage
import com.zps.zest.codehealth.ProjectChangesTracker
import com.zps.zest.codehealth.ui.editor.CodeHealthOverviewVirtualFile
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import java.util.concurrent.TimeUnit

/**
 * Action to immediately review the current file for code health issues
 */
class ReviewCurrentFileAction : AnAction() {
    companion object {
        private val logger = Logger.getInstance(ReviewCurrentFileAction::class.java)
        private val SUPPORTED_EXTENSIONS = setOf("java", "kt", "js", "ts", "jsx", "tsx")
    }
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        
        // Check if it's a code file
        if (!isCodeFile(file)) {
            Messages.showWarningDialog(
                project,
                "Please select a code file (Java, Kotlin, JS, TS)",
                "Not a Code File"
            )
            return
        }
        
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Reviewing ${file.name}...",
            false
        ) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val tracker = ProjectChangesTracker.getInstance(project)
                    val reviewer = BackgroundHealthReviewer.getInstance(project)
                    
                    // 1. Clear existing tracking to focus on this file only
                    indicator.text = "Preparing review..."
                    tracker.clearAllTracking()
                    
                    // 2. Track all methods in current file
                    indicator.text = "Analyzing ${file.name}..."
                    val methodFqns = extractMethodsFromFile(project, file)
                    
                    if (methodFqns.isEmpty()) {
                        ApplicationManager.getApplication().invokeLater {
                            Messages.showInfoMessage(
                                project,
                                "No methods found in ${file.name}",
                                "No Methods to Review"
                            )
                        }
                        return
                    }
                    
                    // Track each method for immediate review
                    methodFqns.forEach { fqn ->
                        tracker.trackMethodForImmediateReview(fqn)
                    }
                    
                    // 3. Trigger immediate review
                    indicator.text = "Running code health analysis..."
                    val future = reviewer.triggerImmediateReview(methodFqns) { msg ->
                        indicator.text = msg
                    }
                    
                    val results = future.get(3000, TimeUnit.SECONDS)
                    
                    // 4. Store results and open Code Health Editor
                    ApplicationManager.getApplication().invokeLater {
                        // Store results for the editor to display
                        val storage = CodeHealthReportStorage.getInstance(project)
                        storage.saveImmediateReviewResults(file.name, results)
                        
                        // Open Code Health Editor
                        openCodeHealthEditor(project)
                        
                        // Show notification
                        showReviewNotification(project, file, results)
                        
                        // Clear all tracking after review completes
                        tracker.clearAllTracking()
                    }
                    
                } catch (e: Exception) {
                    logger.error("Review failed", e)
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "Review failed: ${e.message}",
                            "Review Error"
                        )
                        
                        // Clear tracking even on failure
                        ProjectChangesTracker.getInstance(project).clearAllTracking()
                    }
                }
            }
        })
    }
    
    override fun getActionUpdateThread(): com.intellij.openapi.actionSystem.ActionUpdateThread {
        return com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
    }
    
    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = file != null && isCodeFile(file)
    }
    
    private fun isCodeFile(file: VirtualFile): Boolean {
        return file.extension?.toLowerCase() in SUPPORTED_EXTENSIONS
    }
    
    private fun extractMethodsFromFile(project: Project, file: VirtualFile): List<String> {
        return ReadAction.compute<List<String>, RuntimeException> {
            val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@compute emptyList()
            
            when (file.extension?.toLowerCase()) {
                "java" -> extractJavaMethods(psiFile as? PsiJavaFile)
                "kt" -> extractKotlinMethods(psiFile as? KtFile)
                "js", "ts", "jsx", "tsx" -> extractJsTsMethods(psiFile, file)
                else -> emptyList()
            }
        }
    }
    
    private fun extractJavaMethods(psiFile: PsiJavaFile?): List<String> {
        if (psiFile == null) return emptyList()
        
        val methods = mutableListOf<String>()
        psiFile.classes.forEach { psiClass ->
            psiClass.methods.forEach { method ->
                val fqn = "${psiClass.qualifiedName}.${method.name}"
                methods.add(fqn)
            }
        }
        return methods
    }
    
    private fun extractKotlinMethods(ktFile: KtFile?): List<String> {
        if (ktFile == null) return emptyList()
        
        val methods = mutableListOf<String>()
        val packageName = ktFile.packageFqName.asString()
        
        ktFile.declarations.forEach { declaration ->
            when (declaration) {
                is org.jetbrains.kotlin.psi.KtClass -> {
                    val className = declaration.fqName?.asString() ?: return@forEach
                    declaration.declarations.filterIsInstance<KtNamedFunction>().forEach { function ->
                        methods.add("$className.${function.name}")
                    }
                }
                is KtNamedFunction -> {
                    // Top-level function
                    val functionName = declaration.name ?: return@forEach
                    methods.add("$packageName.$functionName")
                }
            }
        }
        return methods
    }
    
    private fun extractJsTsMethods(psiFile: PsiFile, file: VirtualFile): List<String> {
        // For JS/TS, we'll use file path with line numbers as identifiers
        val methods = mutableListOf<String>()
        val filePath = file.path
        
        // Simple approach: track the whole file as regions
        // The analyzer will handle the actual method extraction
        methods.add("$filePath:1")
        
        return methods
    }
    
    private fun openCodeHealthEditor(project: Project) {
        // Open the Code Health Overview Editor
        val healthFile = CodeHealthOverviewVirtualFile()
        val editorManager = FileEditorManager.getInstance(project)
        editorManager.openFile(healthFile, true)
    }
    
    private fun showReviewNotification(
        project: Project,
        file: VirtualFile,
        results: List<CodeHealthAnalyzer.MethodHealthResult>
    ) {
        val totalIssues = results.sumOf { it.issues.size }
        val avgScore = if (results.isNotEmpty()) 
            results.map { it.healthScore }.average().toInt() 
        else 100
        
        val notificationType = when {
            avgScore >= 80 -> NotificationType.INFORMATION
            avgScore >= 60 -> NotificationType.WARNING
            else -> NotificationType.ERROR
        }
        
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("Code Health")
            .createNotification(
                "Review Complete: ${file.name}",
                """
                Health Score: $avgScore/100
                Methods Analyzed: ${results.size}
                Issues Found: $totalIssues
                Results shown in Code Health Editor
                """.trimIndent(),
                notificationType
            )
        
        notification.notify(project)
    }
}