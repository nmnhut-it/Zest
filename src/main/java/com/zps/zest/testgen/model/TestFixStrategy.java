package com.zps.zest.testgen.model;

/**
 * Strategy for fixing validation errors in generated test code.
 * Used for A/B testing different approaches.
 */
public enum TestFixStrategy {
    /**
     * Full rewrite only - AI analyzes all errors and does ONE complete rewrite.
     * Simpler, faster, no line number drift issues.
     * Best for: Simple to moderate complexity tests.
     */
    FULL_REWRITE_ONLY,

    /**
     * Two-phase strategy - AI does bulk fix (Phase 1) then incremental fixes (Phase 2).
     * Phase 1: Complete rewrite to fix major patterns.
     * Phase 2: Surgical fixes for remaining errors.
     * Best for: Complex tests with many errors.
     */
    TWO_PHASE
}
