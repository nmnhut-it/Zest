package com.zps.zest.inlinechat

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.project.DumbAwareAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Action to open inline chat editor
 */
class InlineChatAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getRequiredData(CommonDataKeys.EDITOR)
        val project = e.project ?: return
        InlineChatIntentionAction().invoke(project, editor, null)
    }
}

/**
 * Action to accept inline chat edit
 */
class InlineChatAcceptAction : DumbAwareAction() {
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val inlineChatService = project.serviceOrNull<InlineChatService>() ?: return
        val location = inlineChatService.location ?: return
        scope.launch {
            resolveInlineChatEdit(project, ChatEditResolveParams(location = location, action = "accept"))
        }
    }
}

/**
 * Action to discard inline chat edit
 */
class InlineChatDiscardAction : DumbAwareAction() {
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val inlineChatService = project.serviceOrNull<InlineChatService>() ?: return
        val location = inlineChatService.location ?: return
        scope.launch {
            resolveInlineChatEdit(project, ChatEditResolveParams(location = location, action = "discard"))
        }
    }
}

/**
 * Action to cancel inline chat edit
 */
class InlineChatCancelAction : DumbAwareAction() {
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val inlineChatService = project.serviceOrNull<InlineChatService>() ?: return
        val location = inlineChatService.location ?: return
        scope.launch {
            resolveInlineChatEdit(project, ChatEditResolveParams(location = location, action = "cancel"))
        }
    }
}