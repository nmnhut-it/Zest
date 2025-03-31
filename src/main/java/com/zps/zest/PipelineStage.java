package com.zps.zest;

/**
 * Pipeline interface defining the execution contract for pipeline stages.
 */
public interface PipelineStage {
    void process(CodeContext context) throws PipelineExecutionException;
}
