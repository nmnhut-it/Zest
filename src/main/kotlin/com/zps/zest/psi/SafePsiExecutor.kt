package com.zps.zest.psi

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import kotlinx.coroutines.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Safe executor for PSI operations that prevents UI blocking and handles proper threading.
 * 
 * Features:
 * - Automatic ReadAction/WriteAction wrapping
 * - EDT safety - never blocks the Event Dispatch Thread
 * - Timeout protection to prevent infinite operations
 * - Proper exception handling and PSI error recovery
 * - Cancellation support for long-running operations
 * - Progress indicator integration
 */
@Service(Service.Level.PROJECT)
class SafePsiExecutor(private val project: Project) : Disposable {
    
    companion object {
        private val logger = Logger.getInstance(SafePsiExecutor::class.java)
        
        // Default timeouts for different operation types
        const val DEFAULT_READ_TIMEOUT_MS = 5000L  // 5 seconds
        const val DEFAULT_WRITE_TIMEOUT_MS = 10000L // 10 seconds
        const val CRITICAL_TIMEOUT_MS = 1000L       // 1 second for critical path operations
        
        fun getInstance(project: Project): SafePsiExecutor {
            return project.getService(SafePsiExecutor::class.java)
        }
    }
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val operationCounter = AtomicLong(0)
    
    /**
     * Execute a PSI read operation safely without blocking the EDT
     */
    fun <T> executeRead(
        operation: () -> T,
        timeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
        onError: ((Exception) -> T)? = null
    ): CompletableFuture<T> {
        val operationId = operationCounter.incrementAndGet()
        logger.debug("Starting PSI read operation $operationId")
        
        return CompletableFuture.supplyAsync({
            try {
                val result = ReadAction.compute<T, RuntimeException> {
                    operation()
                }
                
                logger.debug("PSI read operation $operationId completed successfully")
                result
            } catch (e: Exception) {
                logger.warn("PSI read operation $operationId failed", e)
                onError?.invoke(e) ?: throw e
            }
        })
        .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .handle { result, throwable ->
            when (throwable) {
                null -> result
                is java.util.concurrent.TimeoutException -> {
                    logger.warn("PSI read operation $operationId timed out after ${timeoutMs}ms")
                    onError?.invoke(Exception("PSI operation timed out")) 
                        ?: throw Exception("PSI read operation timed out after ${timeoutMs}ms")
                }
                else -> {
                    logger.error("PSI read operation $operationId failed with error", throwable)
                    onError?.invoke(Exception("PSI read operation failed", throwable))
                        ?: throw throwable
                }
            }
        }
    }
    
    /**
     * Execute a PSI write operation safely without blocking the EDT
     */
    fun <T> executeWrite(
        operation: () -> T,
        timeoutMs: Long = DEFAULT_WRITE_TIMEOUT_MS,
        onError: ((Exception) -> T)? = null
    ): CompletableFuture<T> {
        val operationId = operationCounter.incrementAndGet()
        logger.debug("Starting PSI write operation $operationId")
        
        return CompletableFuture.supplyAsync({
            try {
                val result = WriteAction.compute<T, Exception> {
                    operation()
                }
                
                logger.debug("PSI write operation $operationId completed successfully")
                result
            } catch (e: Exception) {
                logger.warn("PSI write operation $operationId failed", e)
                onError?.invoke(e) ?: throw e
            }
        })
        .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .handle { result, throwable ->
            when (throwable) {
                null -> result
                is java.util.concurrent.TimeoutException -> {
                    logger.warn("PSI write operation $operationId timed out after ${timeoutMs}ms")
                    onError?.invoke(Exception("PSI write operation timed out"))
                        ?: throw Exception("PSI write operation timed out after ${timeoutMs}ms")
                }
                else -> {
                    logger.error("PSI write operation $operationId failed with error", throwable)
                    onError?.invoke(Exception("PSI write operation failed", throwable))
                        ?: throw throwable
                }
            }
        }
    }
    
    /**
     * Execute a PSI operation with progress indicator
     */
    fun <T> executeWithProgress(
        title: String,
        operation: (ProgressIndicator) -> T,
        timeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
        onError: ((Exception) -> T)? = null
    ): CompletableFuture<T> {
        val operationId = operationCounter.incrementAndGet()
        logger.debug("Starting PSI operation with progress $operationId: $title")
        
        val future = CompletableFuture<T>()
        
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = title
                    indicator.isIndeterminate = true
                    
                    val result = ReadAction.compute<T, RuntimeException> {
                        if (indicator.isCanceled) {
                            throw ProcessCanceledException()
                        }
                        operation(indicator)
                    }
                    
                    if (!indicator.isCanceled) {
                        future.complete(result)
                        logger.debug("PSI operation with progress $operationId completed successfully")
                    } else {
                        future.cancel(true)
                        logger.debug("PSI operation with progress $operationId was cancelled")
                    }
                } catch (e: Exception) {
                    logger.warn("PSI operation with progress $operationId failed", e)
                    val errorResult = onError?.invoke(e)
                    if (errorResult != null) {
                        future.complete(errorResult)
                    } else {
                        future.completeExceptionally(e)
                    }
                }
            }
            
            override fun onCancel() {
                future.cancel(true)
                logger.debug("PSI operation with progress $operationId was cancelled by user")
            }
        })
        
        return future.orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .handle { result, throwable ->
                when (throwable) {
                    null -> result
                    is java.util.concurrent.TimeoutException -> {
                        logger.warn("PSI operation with progress $operationId timed out after ${timeoutMs}ms")
                        onError?.invoke(Exception("PSI operation timed out"))
                            ?: throw Exception("PSI operation with progress timed out after ${timeoutMs}ms")
                    }
                    else -> {
                        logger.error("PSI operation with progress $operationId failed with error", throwable)
                        onError?.invoke(Exception("PSI operation with progress failed", throwable))
                            ?: throw throwable
                    }
                }
            }
    }
    
    /**
     * Execute a critical PSI read operation with very short timeout (for completion, etc.)
     */
    fun <T> executeCriticalRead(
        operation: () -> T,
        fallback: () -> T
    ): CompletableFuture<T> {
        return executeRead(
            operation = operation,
            timeoutMs = CRITICAL_TIMEOUT_MS,
            onError = { fallback() }
        )
    }
    
    /**
     * Execute multiple PSI read operations in parallel
     */
    fun <T> executeParallelReads(
        operations: List<() -> T>,
        timeoutMs: Long = DEFAULT_READ_TIMEOUT_MS
    ): CompletableFuture<List<T>> {
        val futures = operations.map { operation ->
            executeRead(operation, timeoutMs)
        }
        
        return CompletableFuture.allOf(*futures.toTypedArray())
            .thenApply { futures.map { it.join() } }
    }
    
    /**
     * Check if we're currently on the EDT (for debugging/safety checks)
     */
    fun isOnEDT(): Boolean = ApplicationManager.getApplication().isDispatchThread
    
    /**
     * Execute operation immediately if safe, otherwise queue it
     */
    fun <T> executeImmediate(
        operation: () -> T,
        fallback: () -> T
    ): T {
        return if (ApplicationManager.getApplication().isReadAccessAllowed) {
            try {
                operation()
            } catch (e: Exception) {
                logger.warn("Immediate PSI operation failed, using fallback", e)
                fallback()
            }
        } else {
            logger.debug("PSI read access not allowed, using fallback")
            fallback()
        }
    }
    
    /**
     * Get current operation statistics
     */
    fun getStats(): Map<String, Long> {
        return mapOf(
            "totalOperations" to operationCounter.get(),
            "activeCoroutines" to scope.coroutineContext.job.children.count().toLong()
        )
    }
    
    override fun dispose() {
        logger.info("Disposing SafePsiExecutor")
        scope.cancel()
    }
}

/**
 * Exception for cancelled PSI operations
 */
class ProcessCanceledException : RuntimeException("Process was cancelled")