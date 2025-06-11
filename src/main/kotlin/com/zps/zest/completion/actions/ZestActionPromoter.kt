package com.zps.zest.completion.actions

import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.diagnostic.Logger

/**
 * Action promoter for Zest inline completion actions.
 * Ensures that Zest completion actions have priority over other actions when multiple actions
 * are available for the same keyboard shortcut.
 */
class ZestActionPromoter : ActionPromoter {
    private val logger = Logger.getInstance(ZestActionPromoter::class.java)

    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> {
        logger.debug("Promoting actions: ${actions.map { it.javaClass.simpleName }}")
        
        return actions.sortedByDescending { action ->
            when {
                action is HasPriority -> {
                    logger.debug("Action ${action.javaClass.simpleName} has priority ${action.priority}")
                    action.priority
                }
                action is ZestInlineCompletionAction -> {
                    logger.debug("Action ${action.javaClass.simpleName} is Zest completion action with priority ${action.priority}")
                    action.priority
                }
                else -> {
                    logger.debug("Action ${action.javaClass.simpleName} has default priority 0")
                    0
                }
            }
        }
    }
}
