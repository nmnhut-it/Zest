package com.zps.zest.completion.actions

import com.intellij.openapi.actionSystem.ActionPromoter
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.diagnostic.Logger

/**
 * High-priority action promoter for Zest inline completion actions.
 * Ensures that Zest completion actions have priority over IntelliJ's built-in actions
 * when multiple actions are available for the same keyboard shortcut.
 * This prevents IntelliJ's completion popup from interfering with our inline completions.
 */
class ZestActionPromoter : ActionPromoter {
    private val logger = Logger.getInstance(ZestActionPromoter::class.java)

    override fun promote(actions: List<AnAction>, context: DataContext): List<AnAction> {
        // Check if we have any Zest completion actions
        val zestActions = actions.filter { it is ZestInlineCompletionAction || it is HasPriority }
        val otherActions = actions.filter { it !is ZestInlineCompletionAction && it !is HasPriority }
        
        if (zestActions.isNotEmpty()) {
            System.out.println("Promoting ${zestActions.size} Zest actions over ${otherActions.size} other actions")
            
            // Sort Zest actions by priority
            val sortedZestActions = zestActions.sortedByDescending { action ->
                when {
                    action is HasPriority -> {
                        System.out.println("Zest action ${action.javaClass.simpleName} has priority ${action.priority}")
                        action.priority + 1000
                    }
                    action is ZestInlineCompletionAction -> {
                        System.out.println("Zest action ${action.javaClass.simpleName} has priority ${action.priority}")
                        action.priority + 10000 // Boost Zest actions significantly
                    }
                    else -> 0
                }
            }
            
            // Return Zest actions first, then other actions
            return sortedZestActions + otherActions
        }
        
        // No Zest actions, use normal priority sorting
        return actions.sortedByDescending { action ->
            when {
                action is HasPriority -> action.priority
                else -> 0
            }
        }
    }
}
