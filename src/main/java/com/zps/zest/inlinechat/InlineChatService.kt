package com.zps.zest.inlinechat

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import org.eclipse.lsp4j.Location

/**
 * Service for managing inline chat state
 */
@Service(Service.Level.PROJECT)
class InlineChatService(private val project: Project) : Disposable {

    var inlineChatInputVisible = false
    var inlineChatDiffActionState = mutableMapOf<String, Boolean>()
    var location: Location? = null

    val hasDiffAction: Boolean
        get() = inlineChatDiffActionState.any { it.value }

    override fun dispose() {
        inlineChatInputVisible = false
        inlineChatDiffActionState.clear()
        location = null
    }
}