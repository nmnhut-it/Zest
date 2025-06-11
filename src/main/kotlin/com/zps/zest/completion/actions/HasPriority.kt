package com.zps.zest.completion.actions

/**
 * Interface for actions that have priority in the action promotion system.
 * Higher priority actions will be promoted over lower priority ones.
 */
interface HasPriority {
    val priority: Int
}
