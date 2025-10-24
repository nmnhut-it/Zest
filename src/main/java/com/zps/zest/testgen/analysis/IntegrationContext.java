package com.zps.zest.testgen.analysis;

import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;

/**
 * Captures integration patterns discovered from how code is used.
 * Helps determine if tests should be UNIT or INTEGRATION tests.
 */
public class IntegrationContext {
    private final Set<String> transactionalCallers;
    private final Set<String> asyncCallers;
    private final Set<String> eventListenerCallers;
    private final Set<String> scheduledJobCallers;
    private boolean usedInLoop;
    private boolean usedInBatchOperation;
    private int estimatedCallFrequency; // Calls per typical request/job

    public IntegrationContext() {
        this.transactionalCallers = new HashSet<>();
        this.asyncCallers = new HashSet<>();
        this.eventListenerCallers = new HashSet<>();
        this.scheduledJobCallers = new HashSet<>();
        this.usedInLoop = false;
        this.usedInBatchOperation = false;
        this.estimatedCallFrequency = 1;
    }

    public void addTransactionalCaller(@NotNull String caller) {
        transactionalCallers.add(caller);
    }

    public void addAsyncCaller(@NotNull String caller) {
        asyncCallers.add(caller);
    }

    public void addEventListenerCaller(@NotNull String caller) {
        eventListenerCallers.add(caller);
    }

    public void addScheduledJobCaller(@NotNull String caller) {
        scheduledJobCallers.add(caller);
    }

    public void setUsedInLoop(boolean usedInLoop) {
        this.usedInLoop = usedInLoop;
    }

    public void setUsedInBatchOperation(boolean usedInBatchOperation) {
        this.usedInBatchOperation = usedInBatchOperation;
    }

    public void setEstimatedCallFrequency(int frequency) {
        this.estimatedCallFrequency = Math.max(1, frequency);
    }

    public boolean hasTransactionalContext() {
        return !transactionalCallers.isEmpty();
    }

    public boolean hasAsyncContext() {
        return !asyncCallers.isEmpty();
    }

    public boolean hasEventContext() {
        return !eventListenerCallers.isEmpty();
    }

    public boolean hasScheduledJobContext() {
        return !scheduledJobCallers.isEmpty();
    }

    public boolean isUsedInLoop() {
        return usedInLoop;
    }

    public boolean isUsedInBatchOperation() {
        return usedInBatchOperation;
    }

    public boolean hasIntegrationPatterns() {
        return hasTransactionalContext() || hasAsyncContext() ||
               hasEventContext() || hasScheduledJobContext() ||
               usedInLoop || usedInBatchOperation;
    }

    public boolean needsPerformanceTesting() {
        return usedInLoop || usedInBatchOperation || estimatedCallFrequency > 100;
    }

    public boolean needsConcurrencyTesting() {
        return hasAsyncContext() || usedInBatchOperation;
    }

    @NotNull
    public String formatForLLM() {
        if (!hasIntegrationPatterns()) {
            return "No integration patterns detected";
        }

        StringBuilder formatted = new StringBuilder();

        if (hasTransactionalContext()) {
            formatted.append("- Called within @Transactional methods: ")
                    .append(String.join(", ", transactionalCallers))
                    .append("\n  → Test transaction rollback on failures\n");
        }

        if (hasAsyncContext()) {
            formatted.append("- Called asynchronously by: ")
                    .append(String.join(", ", asyncCallers))
                    .append("\n  → Test async behavior and error propagation\n");
        }

        if (hasEventContext()) {
            formatted.append("- Called in event handlers: ")
                    .append(String.join(", ", eventListenerCallers))
                    .append("\n  → Test event-driven behavior\n");
        }

        if (hasScheduledJobContext()) {
            formatted.append("- Called in scheduled jobs: ")
                    .append(String.join(", ", scheduledJobCallers))
                    .append("\n  → Test batch processing behavior\n");
        }

        if (usedInLoop) {
            formatted.append("- Called in loops (potential performance concern)\n")
                    .append("  → Test with realistic data volumes\n");
        }

        if (usedInBatchOperation) {
            formatted.append("- Used in batch operations\n")
                    .append("  → Test batch processing and error handling\n");
        }

        if (needsPerformanceTesting()) {
            formatted.append("- High call frequency (").append(estimatedCallFrequency)
                    .append(" calls/operation)\n")
                    .append("  → Consider performance testing\n");
        }

        return formatted.toString();
    }

    @Override
    public String toString() {
        return "IntegrationContext{" +
               "transactional=" + !transactionalCallers.isEmpty() +
               ", async=" + !asyncCallers.isEmpty() +
               ", events=" + !eventListenerCallers.isEmpty() +
               ", loops=" + usedInLoop +
               ", batch=" + usedInBatchOperation +
               '}';
    }
}
