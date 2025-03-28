package com.zps.zest;

/**
 * Custom exception for pipeline execution errors.
 */
public class PipelineExecutionException extends Exception {
    public PipelineExecutionException(String message) {
        super(message);
    }

    public PipelineExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
