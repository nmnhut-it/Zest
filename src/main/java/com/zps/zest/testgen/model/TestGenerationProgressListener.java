package com.zps.zest.testgen.model;

import org.jetbrains.annotations.NotNull;

@FunctionalInterface
public interface TestGenerationProgressListener {
    void onProgress(@NotNull TestGenerationProgress progress);
}