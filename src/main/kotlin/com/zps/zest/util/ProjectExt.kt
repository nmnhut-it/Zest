package com.zps.zest.util

import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

/**
 * Safely get a sync publisher for a topic, handling disposed projects
 */
fun <L : Any> Project.safeSyncPublisher(topic: Topic<L>): L? {
    return if (isDisposed) {
        null
    } else {
        messageBus.let { bus ->
            if (bus.isDisposed) {
                null
            } else {
                bus.syncPublisher(topic)
            }
        }
    }
}
