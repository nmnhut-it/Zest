package com.zps.zest.codehealth

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage.CODE_HEALTH
import com.zps.zest.langchain4j.naive_service.NaiveLLMService

/**
 * Extension analyzer for JavaScript/TypeScript regions.
 * Keeps the same logic and API while improving readability and structure.
 */
class JsTsHealthAnalyzer(private val project: Project) {

    private val naiveLlmService: NaiveLLMService = project.service()
    private val contextHelper = JsTsContextHelper(project)
    private val gson = Gson()

    fun analyzeRegion(region: ModifiedRegion, onComplete: (CodeHealthAnalyzer.MethodHealthResult) -> Unit) {
        if (project.isDisposed) { onComplete(createEmptyResult(region)); return }
        println("$TAG Starting analysis of region: ${region.getIdentifier()}")
        try {
            performAnalysis(region, onComplete)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            handleError(e, region, onComplete)
        }
    }

    private fun performAnalysis(
        region: ModifiedRegion,
        onComplete: (CodeHealthAnalyzer.MethodHealthResult) -> Unit
    ) {
        val context = fetchRegionContext(region)
        if (context == null) {
            println("$TAG Failed to get context for region: ${region.getIdentifier()}")
            onComplete(createEmptyResult(region)); return
        }
        val params = buildParams(context, region)
        val model = params.getModel()
        println("$TAG Using model: $model for ${region.getIdentifier()}")
        val response = naiveLlmService.queryWithParams(params, CODE_HEALTH)
        val result = if (response != null)
            parseAnalysisResponse(region, context, response, model)
        else createEmptyResult(region, model)
        println("$TAG Analysis complete for ${region.getIdentifier()}: ${result.issues.size} issues found")
        onComplete(result)
    }

    private fun fetchRegionContext(region: ModifiedRegion): RegionAnalysisContext? =
        ReadAction.nonBlocking<RegionAnalysisContext?> { createRegionContext(region) }
            .inSmartMode(project)
            .executeSynchronously()

    private fun createRegionContext(region: ModifiedRegion): RegionAnalysisContext? {
        val vf = LocalFileSystem.getInstance().findFileByPath(region.filePath) ?: return null
        val psi = PsiManager.getInstance(project).findFile(vf) ?: return null
        val doc = PsiDocumentManager.getInstance(project).getDocument(psi) ?: return null
        val rc = contextHelper.extractRegionContext(doc, region.centerLine, CONTEXT_LINES)
        return RegionAnalysisContext(psi.name, region.filePath, rc, psi)
    }

    private fun buildParams(context: RegionAnalysisContext, region: ModifiedRegion): NaiveLLMService.LLMQueryParams {
        val prompt = contextHelper.buildRegionAnalysisPrompt(
            context.fileName,
            region,
            context.regionContext
        )
        return NaiveLLMService.LLMQueryParams(prompt)
            .useLiteCodeModel()
            .withMaxTokens(2048)
            .withTemperature(0.3)
    }

    private fun parseAnalysisResponse(
        region: ModifiedRegion,
        context: RegionAnalysisContext,
        llmResponse: String,
        actualModel: String
    ): CodeHealthAnalyzer.MethodHealthResult {
        return try {
            val json = extractJsonObject(llmResponse) ?: return createEmptyResult(region)
            val summary = json.get("summary")?.asString ?: "Analysis completed"
            val healthScore = json.get("healthScore")?.asInt ?: 85
            val issues = parseIssues(json.getAsJsonArray("issues"))
            buildResult(region, context, summary, healthScore, issues, actualModel)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            println("$TAG Error parsing response: ${e.message}")
            createEmptyResult(region, actualModel)
        }
    }

    private fun extractJsonObject(text: String): JsonObject? {
        val start = text.indexOf("{")
        val end = text.lastIndexOf("}")
        if (start == -1 || end == -1 || end < start) return null
        return runCatching { gson.fromJson(text.substring(start, end + 1), JsonObject::class.java) }.getOrNull()
    }

    private fun parseIssues(array: JsonArray?): List<CodeHealthAnalyzer.HealthIssue> {
        if (array == null) return emptyList()
        val list = mutableListOf<CodeHealthAnalyzer.HealthIssue>()
        array.forEach { el ->
            val o = el.asJsonObject
            list.add(
                CodeHealthAnalyzer.HealthIssue(
                    issueCategory = o.get("category")?.asString ?: "Unknown",
                    severity = o.get("severity")?.asInt ?: 1,
                    title = o.get("title")?.asString ?: "Unknown Issue",
                    description = o.get("description")?.asString ?: "",
                    impact = o.get("impact")?.asString ?: "",
                    suggestedFix = o.get("suggestedFix")?.asString ?: "",
                    confidence = o.get("confidence")?.asDouble ?: 0.8,
                    verified = true,
                    lineNumbers = extractLineNumbers(o),
                    codeSnippet = o.get("codeSnippet")?.asString
                )
            )
        }
        return list
    }

    private fun extractLineNumbers(obj: JsonObject): List<Int> {
        val lines = mutableListOf<Int>()
        obj.get("lineNumber")?.asInt?.let { lines.add(it) }
        obj.getAsJsonArray("lineNumbers")?.forEach { el ->
            runCatching { el.asInt }.onSuccess { lines.add(it) }
        }
        return lines
    }

    private fun buildResult(
        region: ModifiedRegion,
        context: RegionAnalysisContext,
        summary: String,
        healthScore: Int,
        issues: List<CodeHealthAnalyzer.HealthIssue>,
        actualModel: String
    ): CodeHealthAnalyzer.MethodHealthResult {
        val code = context.regionContext.markedText
        return CodeHealthAnalyzer.MethodHealthResult(
            fqn = region.getIdentifier(),
            issues = issues,
            impactedCallers = emptyList(),
            healthScore = healthScore,
            modificationCount = region.modificationCount,
            codeContext = code,
            summary = summary,
            actualModel = actualModel,
            annotatedCode = code,
            originalCode = code
        )
    }

    private fun createEmptyResult(
        region: ModifiedRegion,
        actualModel: String = DEFAULT_MODEL
    ): CodeHealthAnalyzer.MethodHealthResult {
        return CodeHealthAnalyzer.MethodHealthResult(
            fqn = region.getIdentifier(),
            issues = emptyList(),
            impactedCallers = emptyList(),
            healthScore = 85,
            modificationCount = region.modificationCount,
            codeContext = "",
            summary = "Analysis failed or no issues found",
            actualModel = actualModel,
            annotatedCode = "",
            originalCode = ""
        )
    }

    private fun handleError(
        e: Exception,
        region: ModifiedRegion,
        onComplete: (CodeHealthAnalyzer.MethodHealthResult) -> Unit
    ) {
        println("$TAG Error analyzing region ${region.getIdentifier()}: ${e.message}")
        e.printStackTrace()
        onComplete(createEmptyResult(region))
    }

    private data class RegionAnalysisContext(
        val fileName: String,
        val filePath: String,
        val regionContext: JsTsContextHelper.RegionContext,
        val psiFile: PsiFile
    )

    companion object {
        private const val CONTEXT_LINES = 20
        private const val DEFAULT_MODEL = "local-model-mini"
        private const val TAG = "[JsTsHealthAnalyzer]"
    }
}