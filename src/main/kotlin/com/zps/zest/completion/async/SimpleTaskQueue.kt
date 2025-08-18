package com.zps.zest.completion.async

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Simple task queue that processes tasks one at a time with delays to avoid CPU spikes
 */
class SimpleTaskQueue(
    private val delayMs: Long = 10
) {
    private val queue = LinkedBlockingQueue<Runnable>()
    private val executor: ScheduledExecutorService = ScheduledThreadPoolExecutor(1)
    private var isRunning = false
    
    fun submit(task: Runnable) {
        queue.offer(task)
        if (!isRunning) {
            start()
        }
    }
    
    private fun start() {
        isRunning = true
        executor.schedule(this::processNext, 0, TimeUnit.MILLISECONDS)
    }
    
    private fun processNext() {
        val task = queue.poll()
        if (task != null) {
            try {
                task.run()
            } catch (e: Exception) {
                // Log error but continue processing
                e.printStackTrace()
            }
            // Schedule next task with delay
            executor.schedule(this::processNext, delayMs, TimeUnit.MILLISECONDS)
        } else {
            isRunning = false


        }
    }
    
    fun shutdown() {
        queue.clear()
        executor.shutdown()
    }
}
