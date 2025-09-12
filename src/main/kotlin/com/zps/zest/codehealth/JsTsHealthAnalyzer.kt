package com.zps.zest.codehealth

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.zps.zest.langchain4j.naive_service.NaiveLLMService
import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * Extension for CodeHealthAnalyzer to support JavaScript and TypeScript files
 */
class JsTsHealthAnalyzer(private val project: Project) {
    
    private val naiveLlmService: NaiveLLMService = project.service()
    private val contextHelper = JsTsContextHelper(project)
    private val gson = Gson()
    
    /**
     * Analyze a JS/TS region
     */
    fun analyzeRegion(
        region: ModifiedRegion,
        onComplete: (CodeHealthAnalyzer.MethodHealthResult) -> Unit
    ) {
        if (project.isDisposed) {
            onComplete(createEmptyResult(region))
            return
        }
        
        println("[JsTsHealthAnalyzer] Starting analysis of region: ${region.getIdentifier()}")
        
        try {
            // Get the file content and context
            val context = ReadAction.nonBlocking<RegionAnalysisContext?> {
                getRegionContext(region)
            }
            .inSmartMode(project)
            .executeSynchronously()
            
            if (context == null) {
                println("[JsTsHealthAnalyzer] Failed to get context for region: ${region.getIdentifier()}")
                onComplete(createEmptyResult(region))
                return
            }
            
            // Build prompt
            val prompt = contextHelper.buildRegionAnalysisPrompt(
                context.fileName,
                region,
                context.regionContext
            )
            
            // Call LLM for analysis
            val params = NaiveLLMService.LLMQueryParams(prompt)
                .useLiteCodeModel()
                .withMaxTokens(2048)
                .withTemperature(0.3)
            
            // Track the actual model being used
            val actualModel = params.getModel()
            println("[JsTsHealthAnalyzer] Using model: $actualModel for ${region.getIdentifier()}")
            
            val response = naiveLlmService.queryWithParams(
                params, 
                com.zps.zest.browser.utils.ChatboxUtilities.EnumUsage.CODE_HEALTH
            )
            
            if (response != null) {
                val result = parseAnalysisResponse(region, context, response, actualModel)
                println("[JsTsHealthAnalyzer] Analysis complete for ${region.getIdentifier()}: ${result.issues.size} issues found")
                onComplete(result)
            } else {
                println("[JsTsHealthAnalyzer] LLM returned null for region: ${region.getIdentifier()}")
                onComplete(createEmptyResult(region, actualModel))
            }
            
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e // Must rethrow
        } catch (e: Exception) {
            println("[JsTsHealthAnalyzer] Error analyzing region ${region.getIdentifier()}: ${e.message}")
            e.printStackTrace()
            onComplete(createEmptyResult(region))
        }
    }
    
    /**
     * Get context for a region
     */
    private fun getRegionContext(region: ModifiedRegion): RegionAnalysisContext? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(region.filePath)
            ?: return null
            
        val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(virtualFile)
            ?: return null
            
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: return null
            
        val regionContext = contextHelper.extractRegionContext(
            document,
            region.centerLine,
            20 // Context lines
        )
        
        return RegionAnalysisContext(
            fileName = psiFile.name,
            filePath = region.filePath,
            regionContext = regionContext,
            psiFile = psiFile
        )
    }
    
    /**
     * Parse LLM response for region analysis
     */
    private fun parseAnalysisResponse(
        region: ModifiedRegion,
        context: RegionAnalysisContext,
        llmResponse: String,
        actualModel: String
    ): CodeHealthAnalyzer.MethodHealthResult {
        return try {
            // Extract JSON content
            val jsonStart = llmResponse.indexOf("{")
            val jsonEnd = llmResponse.lastIndexOf("}")
            if (jsonStart == -1 || jsonEnd == -1) {
                return createEmptyResult(region)
            }
            
            val jsonContent = llmResponse.substring(jsonStart, jsonEnd + 1)
            val jsonObject = gson.fromJson(jsonContent, JsonObject::class.java)
            
            // Parse summary and health score
            val summary = jsonObject.get("summary")?.asString ?: "Analysis completed"
            val healthScore = jsonObject.get("healthScore")?.asInt ?: 85
            
            // Parse issues
            val issues = mutableListOf<CodeHealthAnalyzer.HealthIssue>()
            val issuesArray = jsonObject.getAsJsonArray("issues")
            
            issuesArray?.forEach { element ->
                val issueObject = element.asJsonObject
                
                issues.add(CodeHealthAnalyzer.HealthIssue(
                    issueCategory = issueObject.get("category")?.asString ?: "Unknown",
                    severity = issueObject.get("severity")?.asInt ?: 1,
                    title = issueObject.get("title")?.asString ?: "Unknown Issue",
                    description = issueObject.get("description")?.asString ?: "",
                    impact = issueObject.get("impact")?.asString ?: "",
                    suggestedFix = issueObject.get("suggestedFix")?.asString ?: "",
                    confidence = issueObject.get("confidence")?.asDouble ?: 0.8,
                    verified = true, // Pre-verified for JS/TS
                    lineNumbers = extractLineNumbers(issueObject),
                    codeSnippet = issueObject.get("codeSnippet")?.asString
                ))
            }
            
            CodeHealthAnalyzer.MethodHealthResult(
                fqn = region.getIdentifier(),
                issues = issues,
                impactedCallers = emptyList(), // JS/TS doesn't track callers
                healthScore = healthScore,
                modificationCount = region.modificationCount,
                codeContext = context.regionContext.markedText,
                summary = summary,
                actualModel = actualModel,
                annotatedCode = context.regionContext.markedText, // Include the region code
                originalCode = context.regionContext.markedText
            )
            
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e // Must rethrow
        } catch (e: Exception) {
            println("[JsTsHealthAnalyzer] Error parsing response: ${e.message}")
            createEmptyResult(region, actualModel)
        }
    }
    
    /**
     * Extract line numbers from issue object
     */
    private fun extractLineNumbers(issueObject: JsonObject): List<Int> {
        val lineNumbers = mutableListOf<Int>()
        
        // Try to get line number
        issueObject.get("lineNumber")?.asInt?.let { lineNumbers.add(it) }
        
        // Try to get line numbers array
        issueObject.getAsJsonArray("lineNumbers")?.forEach { element ->
            try {
                lineNumbers.add(element.asInt)
            } catch (_: Exception) {
                // Ignore non-integer elements
            }
        }
        
        return lineNumbers
    }
    
    /**
     * Create empty result for failed analysis
     */
    private fun createEmptyResult(region: ModifiedRegion, actualModel: String = "local-model-mini"): CodeHealthAnalyzer.MethodHealthResult {
        return CodeHealthAnalyzer.MethodHealthResult(
            fqn = region.getIdentifier(),
            issues = emptyList(),
            impactedCallers = emptyList(),
            healthScore = 85,
            modificationCount = region.modificationCount,
            codeContext = "",
            summary = "Analysis failed or no issues found",
            actualModel = actualModel,
            annotatedCode = "", // Will be populated by region context when available
            originalCode = ""
        )
    }
    
    /**
     * Context for region analysis
     */
    private data class RegionAnalysisContext(
        val fileName: String,
        val filePath: String,
        val regionContext: JsTsContextHelper.RegionContext,
        val psiFile: PsiFile
    )
}
