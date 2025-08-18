package com.zps.zest.completion.actions

import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataContext

/**
 * Action promoter that gives Zest completion actions priority over IntelliJ's default actions.
 * This ensures that our Tab action is executed instead of IntelliJ's default tab behavior.
 */
class ZestActionPromoter : ActionPromoter {
    
    companion object {
        // List of our action IDs that should have priority
        private val PROMOTED_ACTION_IDS = setOf(
            "Zest.InlineCompletion.TabAccept",
            "Zest.InlineCompletion.Dismiss",
            "Zest.InlineCompletion.Trigger"
            // Commented out - actions not implemented yet:
            // "Zest.InlineCompletion.ImmediateTrigger",
            // "Zest.InlineCompletion.Accept",
            // "Zest.InlineCompletion.CycleNext",
            // "Zest.InlineCompletion.CyclePrevious"
        )
    }
    
    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> {
        if (actions.size <= 1) {
            return actions
        }
        
        // Separate our actions from others
        val zestActions = mutableListOf<AnAction>()
        val otherActions = mutableListOf<AnAction>()
        
        for (action in actions) {
            // Check if this is one of our promoted actions
            val actionId = action.javaClass.simpleName
            if (isZestCompletionAction(action)) {
                zestActions.add(action)
            } else {
                otherActions.add(action)
            }
        }
        
        // Put our actions first to give them priority
        return if (zestActions.isNotEmpty()) {
            zestActions + otherActions
        } else {
            actions
        }
    }
    
    /**
     * Check if an action is one of our Zest completion actions
     */
    private fun isZestCompletionAction(action: AnAction): Boolean {
        // Check by class name
        val className = action.javaClass.name
        if (className.startsWith("com.zps.zest.completion.actions.")) {
            return true
        }
        
        // Check by action ID if available
        val actionManager = com.intellij.openapi.actionSystem.ActionManager.getInstance()
        val actionId = actionManager.getId(action)
        if (actionId != null && PROMOTED_ACTION_IDS.contains(actionId)) {
            return true
        }
        
        return false
    }
}