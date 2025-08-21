package com.zps.zest.codehealth.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.zps.zest.codehealth.BackgroundHealthReviewer
import com.zps.zest.codehealth.ProjectChangesTracker

/**
 * Test action to add the method at caret to the review queue
 */
class AddMethodAtCaretAction : AnAction() {
    
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        
        val offset = editor.caretModel.offset
        val element = psiFile.findElementAt(offset) ?: return
        
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)
        if (method == null) {
            showNotification(project, "🎯 Place cursor inside a method first!", NotificationType.WARNING)
            return
        }
        
        val containingClass = method.containingClass
        if (containingClass == null) {
            showNotification(project, "⚠️ Method needs a containing class", NotificationType.WARNING)
            return
        }
        
        val fqn = "${containingClass.qualifiedName}.${method.name}"
        
        // Add to tracker
        val tracker = ProjectChangesTracker.getInstance(project)
        tracker.trackMethodModification(fqn)
        
        // Add to background reviewer
        val reviewer = BackgroundHealthReviewer.getInstance(project)
        reviewer.queueMethodForReview(fqn, System.currentTimeMillis())
        
        showNotification(project, "✅ Queued for review: $fqn", NotificationType.INFORMATION)
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        
        e.presentation.isEnabledAndVisible = project != null && editor != null && psiFile != null
    }
    
    private fun showNotification(project: com.intellij.openapi.project.Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Zest Code Health")
            .createNotification(content, type)
            .notify(project)
    }
}
