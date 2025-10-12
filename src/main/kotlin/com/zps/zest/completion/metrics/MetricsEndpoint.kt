package com.zps.zest.completion.metrics

/**
 * Enum representing different metrics endpoints.
 * Single responsibility: URL construction only.
 */
enum class MetricsEndpoint(val path: String) {
    AUTOCOMPLETE("autocomplete"),
    CODE_HEALTH("code_health"),
    QUICK_ACTION("quick_action"),
    DUAL_EVALUATION("dual_evaluation"),
    CODE_QUALITY("code_quality"),
    UNIT_TEST("unit_test");

    /**
     * Build full URL for this endpoint
     */
    fun buildUrl(baseUrl: String, eventType: String): String {
        return "$baseUrl/$path/$eventType"
    }

    companion object {
        /**
         * Determine endpoint from usage string
         */
        fun fromUsage(enumUsage: String): MetricsEndpoint {
            return when (enumUsage) {
                "INLINE_COMPLETION_LOGGING" -> AUTOCOMPLETE
                "CODE_HEALTH_LOGGING" -> CODE_HEALTH
                "BLOCK_REWRITE_LOGGING", "QUICK_ACTION_LOGGING" -> QUICK_ACTION
                "DUAL_EVALUATION_LOGGING" -> DUAL_EVALUATION
                "CODE_QUALITY_LOGGING" -> CODE_QUALITY
                "UNIT_TEST_LOGGING" -> UNIT_TEST
                else -> AUTOCOMPLETE  // Default to autocomplete
            }
        }
    }
}