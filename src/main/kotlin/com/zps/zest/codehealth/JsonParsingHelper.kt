package com.zps.zest.codehealth

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Helper class for resilient JSON parsing from LLM responses
 */
class JsonParsingHelper {
    private val gson = Gson()

    /**
     * Extract JSON object from response with multiple fallback strategies
     */
    fun extractJson(response: String): JsonObject? {
        // Strategy 1: Standard extraction between { and }
        val standard = tryStandardExtraction(response)
        if (standard != null) return standard

        // Strategy 2: Find JSON in code blocks
        val codeBlock = tryCodeBlockExtraction(response)
        if (codeBlock != null) return codeBlock

        // Strategy 3: Extract partial JSON
        return tryPartialExtraction(response)
    }

    private fun tryStandardExtraction(response: String): JsonObject? {
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        if (start == -1 || end == -1 || end < start) return null

        return try {
            gson.fromJson(response.substring(start, end + 1), JsonObject::class.java)
        } catch (e: Exception) {
            null
        }
    }

    private fun tryCodeBlockExtraction(response: String): JsonObject? {
        val patterns = listOf("```json", "```", "~~~json", "~~~")
        for (pattern in patterns) {
            val startIdx = response.indexOf(pattern)
            if (startIdx == -1) continue

            val jsonStart = response.indexOf('{', startIdx)
            val jsonEnd = response.lastIndexOf('}')
            if (jsonStart != -1 && jsonEnd > jsonStart) {
                return tryParse(response.substring(jsonStart, jsonEnd + 1))
            }
        }
        return null
    }

    private fun tryPartialExtraction(response: String): JsonObject? {
        // Try to find diagnostic-like structures
        val diagnosticPattern = """"diagnostics"\s*:\s*\[""".toRegex()
        val match = diagnosticPattern.find(response) ?: return null

        val startIdx = response.lastIndexOf('{', match.range.first)
        if (startIdx == -1) return null

        // Try to find matching closing brace
        var depth = 0
        var endIdx = -1
        for (i in startIdx until response.length) {
            when (response[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        endIdx = i
                        break
                    }
                }
            }
        }

        if (endIdx != -1) {
            return tryParse(response.substring(startIdx, endIdx + 1))
        }
        return null
    }

    private fun tryParse(json: String): JsonObject? {
        return try {
            gson.fromJson(json, JsonObject::class.java)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extract individual diagnostics even from broken JSON
     */
    fun extractDiagnostics(response: String): List<JsonObject> {
        val diagnostics = mutableListOf<JsonObject>()

        // Try to find diagnostic objects
        val pattern = """\{[^{}]*"category"[^{}]*"severity"[^{}]*\}""".toRegex()
        pattern.findAll(response).forEach { match ->
            tryParse(match.value)?.let { diagnostics.add(it) }
        }

        return diagnostics
    }

    /**
     * Create fallback diagnostic from raw response
     */
    fun createFallbackDiagnostic(response: String, fqn: String): JsonObject {
        val diagnostic = JsonObject()
        diagnostic.addProperty("category", "Other")
        diagnostic.addProperty("severity", 3)
        diagnostic.addProperty("title", "Analysis partially failed")
        diagnostic.addProperty("message", "LLM response was malformed. Manual review recommended.")
        diagnostic.addProperty("impact", "Code quality issues may be present but not detected")
        diagnostic.addProperty("suggestedFix", "Review method manually: $fqn")
        diagnostic.addProperty("confidence", 0.5)

        val range = JsonObject()
        range.addProperty("relativeTo", "method")
        val start = JsonObject()
        start.addProperty("line", 1)
        start.addProperty("col", 1)
        val end = JsonObject()
        end.addProperty("line", 1)
        end.addProperty("col", 1)
        range.add("start", start)
        range.add("end", end)
        diagnostic.add("range", range)

        return diagnostic
    }
}