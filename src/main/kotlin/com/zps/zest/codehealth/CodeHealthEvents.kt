package com.zps.zest.codehealth

import com.intellij.util.messages.Topic

/**
 * Events for Code Health analysis
 */
interface CodeHealthListener {
    companion object {
        @JvmField
        val TOPIC = Topic.create("Code Health Events", CodeHealthListener::class.java)
    }
    
    /**
     * Called when analysis starts
     */
    fun analysisStarted(status: String = "")
    
    /**
     * Called to report analysis progress
     */
    fun analysisProgress(current: Int, total: Int)
    
    /**
     * Called when analysis completes
     */
    fun analysisCompleted(results: List<CodeHealthAnalyzer.MethodHealthResult>)
    
    /**
     * Called when analysis fails
     */
    fun analysisError(message: String)
}
