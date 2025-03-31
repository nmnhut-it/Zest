package com.zps.zest;

/**
 * Pipeline class that manages the execution of the stages.
 */
class TestGenerationPipeline {
    private final java.util.List<PipelineStage> stages = new java.util.ArrayList<>();

    public TestGenerationPipeline addStage(PipelineStage stage) {
        stages.add(stage);
        return this;
    }

    public void execute(CodeContext context) throws PipelineExecutionException {
        for (PipelineStage stage : stages) {
            try {
                stage.process(context);
            } catch (PipelineExecutionException e) {
                throw e;
            } catch (Exception e) {
                throw new PipelineExecutionException("Error in stage: " + stage.getClass().getSimpleName(), e);
            }
        }
    }

    public int getStageCount() {
        return this.stages.size();
    }

    public PipelineStage getStage(int i) {
        return this.stages.get(i); 
    }
}
