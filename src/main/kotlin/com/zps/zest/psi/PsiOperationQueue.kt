package com.zps.zest.psi

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

/**
 * Priority-based queue for PSI operations with intelligent scheduling and deduplication.
 * 
 * Features:
 * - Priority lanes: URGENT (completion), HIGH (analysis), NORMAL (background)
 * - Operation deduplication to avoid redundant PSI work
 * - Load balancing across available threads
 * - Backpressure handling to prevent queue overflow
 * - Smart batching of related operations
 */
@Service(Service.Level.PROJECT)
class PsiOperationQueue(private val project: Project) : Disposable {
    
    companion object {
        private val logger = Logger.getInstance(PsiOperationQueue::class.java)
        
        // Queue limits to prevent memory issues
        const val MAX_QUEUE_SIZE = 1000
        const val MAX_URGENT_QUEUE_SIZE = 100
        const val BATCH_SIZE = 5
        const val BATCH_TIMEOUT_MS = 100L
        
        fun getInstance(project: Project): PsiOperationQueue {
            return project.getService(PsiOperationQueue::class.java)
        }
    }
    
    /**
     * Priority levels for PSI operations
     */
    enum class Priority(val level: Int) {
        URGENT(3),    // Completion, critical UI operations
        HIGH(2),      // Code analysis, inspections  
        NORMAL(1),    // Background processing, caching
        LOW(0)        // Cleanup, maintenance
    }
    
    /**
     * PSI operation wrapper with metadata
     */
    data class PsiOperation<T>(
        val id: String,
        val priority: Priority,
        val operation: () -> T,
        val future: CompletableFuture<T>,
        val createdAt: Long = System.currentTimeMillis(),
        val timeoutMs: Long = 5000L,
        val canBatch: Boolean = false,
        val batchKey: String? = null
    )
    
    // Priority queues for different operation types
    private val urgentQueue = ArrayBlockingQueue<PsiOperation<*>>(MAX_URGENT_QUEUE_SIZE)
    private val highQueue = LinkedBlockingQueue<PsiOperation<*>>()
    private val normalQueue = LinkedBlockingQueue<PsiOperation<*>>()
    private val lowQueue = LinkedBlockingQueue<PsiOperation<*>>()
    
    // Operation deduplication
    private val activeOperations = ConcurrentHashMap<String, CompletableFuture<*>>()
    private val batchGroups = ConcurrentHashMap<String, MutableList<PsiOperation<*>>>()
    
    // Thread management
    private val poolSize = maxOf(2, Runtime.getRuntime().availableProcessors() / 2)
    private val threadPool = Executors.newFixedThreadPool(
        poolSize,
        { runnable ->
            Thread(runnable, "PSI-Operation-Worker").apply {
                isDaemon = true
            }
        }
    )
    
    // Statistics
    private val operationCounter = AtomicLong(0)
    private val completedOperations = AtomicLong(0)
    private val failedOperations = AtomicLong(0)
    private val deduplicatedOperations = AtomicLong(0)
    private val batchedOperations = AtomicLong(0)
    
    // Worker management
    private val activeWorkers = AtomicInteger(0)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    init {
        // Start worker threads
        startWorkers()
        
        // Start batch processor
        startBatchProcessor()
        
        // Start cleanup task
        startCleanupTask()
        
        logger.info("PsiOperationQueue initialized for project: ${project.name}")
    }
    
    /**
     * Submit a PSI operation to the queue
     */
    fun <T> submit(
        operationId: String,
        priority: Priority = Priority.NORMAL,
        timeoutMs: Long = 5000L,
        canBatch: Boolean = false,
        batchKey: String? = null,
        operation: () -> T
    ): CompletableFuture<T> {
        
        // Check for duplicate operations
        val existingFuture = activeOperations[operationId]
        if (existingFuture != null && !existingFuture.isDone) {
            logger.debug("Deduplicating operation: $operationId")
            deduplicatedOperations.incrementAndGet()
            @Suppress("UNCHECKED_CAST")
            return existingFuture as CompletableFuture<T>
        }
        
        // Check queue size limits
        if (getTotalQueueSize() >= MAX_QUEUE_SIZE) {
            logger.warn("Queue is full, rejecting operation: $operationId")
            return CompletableFuture.failedFuture(
                Exception("PSI operation queue is full")
            )
        }
        
        val future = CompletableFuture<T>()
        val psiOperation = PsiOperation(
            id = operationId,
            priority = priority,
            operation = operation,
            future = future,
            timeoutMs = timeoutMs,
            canBatch = canBatch,
            batchKey = batchKey
        )
        
        // Track active operation
        activeOperations[operationId] = future
        future.whenComplete { _, _ ->
            activeOperations.remove(operationId)
        }
        
        // Add to appropriate queue
        val queued = when (priority) {
            Priority.URGENT -> urgentQueue.offer(psiOperation)
            Priority.HIGH -> {
                highQueue.offer(psiOperation)
                true
            }
            Priority.NORMAL -> {
                normalQueue.offer(psiOperation)
                true
            }
            Priority.LOW -> {
                lowQueue.offer(psiOperation)
                true
            }
        }
        
        if (!queued) {
            logger.warn("Failed to queue operation: $operationId")
            activeOperations.remove(operationId)
            return CompletableFuture.failedFuture(
                Exception("Failed to queue PSI operation")
            )
        }
        
        operationCounter.incrementAndGet()
        logger.debug("Queued PSI operation: $operationId (priority: ${priority.name})")
        
        // Handle batching
        if (canBatch && batchKey != null) {
            addToBatch(batchKey, psiOperation)
        }
        
        return future
    }
    
    /**
     * Submit with automatic ID generation
     */
    fun <T> submit(
        priority: Priority = Priority.NORMAL,
        timeoutMs: Long = 5000L,
        operation: () -> T
    ): CompletableFuture<T> {
        val operationId = "auto_${operationCounter.incrementAndGet()}_${System.currentTimeMillis()}"
        return submit(operationId, priority, timeoutMs, operation = operation)
    }
    
    /**
     * Submit a batch of related operations
     */
    fun <T> submitBatch(
        batchKey: String,
        operations: List<Pair<String, () -> T>>,
        priority: Priority = Priority.NORMAL
    ): CompletableFuture<List<T>> {
        val futures = operations.map { (id, operation) ->
            submit(
                operationId = "${batchKey}_$id",
                priority = priority,
                canBatch = true,
                batchKey = batchKey,
                operation = operation
            )
        }
        
        return CompletableFuture.allOf(*futures.toTypedArray())
            .thenApply { futures.map { it.join() } }
    }
    
    private fun startWorkers() {
        repeat(poolSize) { workerId ->
            scope.launch {
                workerLoop(workerId)
            }
        }
    }
    
    private suspend fun workerLoop(workerId: Int) {
        logger.debug("Starting PSI worker $workerId")
        activeWorkers.incrementAndGet()
        
        try {
            while (scope.isActive) {
                try {
                    val operation = getNextOperation()
                    if (operation != null) {
                        executeOperation(operation)
                    } else {
                        // No operations available, wait a bit
                        delay(10)
                    }
                } catch (e: Exception) {
                    logger.warn("Error in PSI worker $workerId", e)
                }
            }
        } finally {
            activeWorkers.decrementAndGet()
            logger.debug("PSI worker $workerId stopped")
        }
    }
    
    private fun getNextOperation(): PsiOperation<*>? {
        // Check urgent queue first
        urgentQueue.poll()?.let { return it }
        
        // Then high priority
        highQueue.poll()?.let { return it }
        
        // Then normal
        normalQueue.poll()?.let { return it }
        
        // Finally low priority
        return lowQueue.poll()
    }
    
    private fun <T> executeOperation(operation: PsiOperation<T>) {
        val startTime = System.currentTimeMillis()
        
        try {
            // Check if operation has timed out while in queue
            if (startTime - operation.createdAt > operation.timeoutMs) {
                operation.future.completeExceptionally(
                    Exception("Operation timed out in queue")
                )
                failedOperations.incrementAndGet()
                return
            }
            
            logger.debug("Executing PSI operation: ${operation.id}")
            
            val result = operation.operation()
            operation.future.complete(result)
            completedOperations.incrementAndGet()
            
            val executionTime = System.currentTimeMillis() - startTime
            logger.debug("PSI operation ${operation.id} completed in ${executionTime}ms")
            
        } catch (e: Exception) {
            logger.warn("PSI operation ${operation.id} failed", e)
            operation.future.completeExceptionally(e)
            failedOperations.incrementAndGet()
        }
    }
    
    private fun addToBatch(batchKey: String, operation: PsiOperation<*>) {
        batchGroups.computeIfAbsent(batchKey) { mutableListOf() }.add(operation)
    }
    
    private fun startBatchProcessor() {
        scope.launch {
            while (scope.isActive) {
                try {
                    processBatches()
                    delay(BATCH_TIMEOUT_MS)
                } catch (e: Exception) {
                    logger.warn("Error in batch processor", e)
                }
            }
        }
    }
    
    private fun processBatches() {
        val batchesToProcess = batchGroups.toMap()
        batchGroups.clear()
        
        for ((batchKey, operations) in batchesToProcess) {
            if (operations.size >= BATCH_SIZE || 
                operations.any { System.currentTimeMillis() - it.createdAt > BATCH_TIMEOUT_MS }) {
                
                logger.debug("Processing batch: $batchKey with ${operations.size} operations")
                batchedOperations.addAndGet(operations.size.toLong())
                
                // Execute batch operations together (could be optimized further)
                operations.forEach { executeOperation(it) }
            } else {
                // Put back in batch groups for next iteration
                batchGroups[batchKey] = operations.toMutableList()
            }
        }
    }
    
    private fun startCleanupTask() {
        scope.launch {
            while (scope.isActive) {
                try {
                    cleanupExpiredOperations()
                    delay(30000) // Cleanup every 30 seconds
                } catch (e: Exception) {
                    logger.warn("Error in cleanup task", e)
                }
            }
        }
    }
    
    private fun cleanupExpiredOperations() {
        val now = System.currentTimeMillis()
        val expiredOperations = activeOperations.entries.filter { (_, future) ->
            future.isDone || future.isCancelled
        }
        
        expiredOperations.forEach { (operationId, _) ->
            activeOperations.remove(operationId)
        }
        
        if (expiredOperations.isNotEmpty()) {
            logger.debug("Cleaned up ${expiredOperations.size} expired operations")
        }
    }
    
    /**
     * Get current queue statistics
     */
    fun getStats(): Map<String, Any> {
        return mapOf(
            "totalOperations" to operationCounter.get(),
            "completedOperations" to completedOperations.get(),
            "failedOperations" to failedOperations.get(),
            "deduplicatedOperations" to deduplicatedOperations.get(),
            "batchedOperations" to batchedOperations.get(),
            "urgentQueueSize" to urgentQueue.size,
            "highQueueSize" to highQueue.size,
            "normalQueueSize" to normalQueue.size,
            "lowQueueSize" to lowQueue.size,
            "totalQueueSize" to getTotalQueueSize(),
            "activeWorkers" to activeWorkers.get(),
            "activeBatches" to batchGroups.size,
            "activeOperationsCount" to activeOperations.size
        )
    }
    
    private fun getTotalQueueSize(): Int {
        return urgentQueue.size + highQueue.size + normalQueue.size + lowQueue.size
    }
    
    /**
     * Cancel all pending operations
     */
    fun cancelAll() {
        logger.info("Cancelling all pending PSI operations")
        
        listOf(urgentQueue, highQueue, normalQueue, lowQueue).forEach { queue ->
            queue.clear()
        }
        
        activeOperations.values.forEach { future ->
            future.cancel(true)
        }
        activeOperations.clear()
        batchGroups.clear()
    }
    
    override fun dispose() {
        logger.info("Disposing PsiOperationQueue")
        cancelAll()
        scope.cancel()
        threadPool.shutdown()
        
        try {
            if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                threadPool.shutdownNow()
            }
        } catch (e: InterruptedException) {
            threadPool.shutdownNow()
        }
    }
}