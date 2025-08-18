package com.zps.zest.testgen.ui.model

import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Utility class to build display data from raw agent output.
 * Handles parsing and formatting logic, caches formatted strings for performance.
 */
class DisplayDataBuilder {
    
    companion object {
        private val LOG = Logger.getInstance(DisplayDataBuilder::class.java)
        
        // Cache for formatted strings to avoid re-processing
        private val formattedCache = ConcurrentHashMap<String, String>()
        
        /**
         * Parse streaming text to extract file analysis information
         */
        fun parseFileAnalysis(streamingText: String): ContextDisplayData? {
            try {
                // Look for patterns like "📄 Analyzing file: /path/to/file.java"
                val filePattern = """(?:📄\s*)?Analyzing file:\s*(.+)""".toRegex()
                val fileMatch = filePattern.find(streamingText)
                
                if (fileMatch != null) {
                    val filePath = fileMatch.groupValues[1].trim()
                    val fileName = filePath.substringAfterLast('/')
                    
                    return ContextDisplayData(
                        filePath = filePath,
                        fileName = fileName,
                        status = ContextDisplayData.AnalysisStatus.ANALYZING,
                        summary = "Analyzing $fileName...",
                        fullAnalysis = null
                    )
                }
                
                // Look for completed analysis pattern
                val completedPattern = """Analysis complete for:\s*(.+)\n([\s\S]+)""".toRegex()
                val completedMatch = completedPattern.find(streamingText)
                
                if (completedMatch != null) {
                    val filePath = completedMatch.groupValues[1].trim()
                    val analysis = completedMatch.groupValues[2].trim()
                    val fileName = filePath.substringAfterLast('/')
                    
                    // Extract key information from analysis
                    val (classes, methods, dependencies) = extractAnalysisDetails(analysis)
                    
                    return ContextDisplayData(
                        filePath = filePath,
                        fileName = fileName,
                        status = ContextDisplayData.AnalysisStatus.COMPLETED,
                        summary = "Found ${classes.size} classes, ${methods.size} methods",
                        fullAnalysis = analysis,
                        classes = classes,
                        methods = methods,
                        dependencies = dependencies
                    )
                }
                
                // Look for file reading pattern
                val readingPattern = """Reading file:\s*(.+)""".toRegex()
                val readingMatch = readingPattern.find(streamingText)
                
                if (readingMatch != null) {
                    val filePath = readingMatch.groupValues[1].trim()
                    val fileName = filePath.substringAfterLast('/')
                    
                    return ContextDisplayData(
                        filePath = filePath,
                        fileName = fileName,
                        status = ContextDisplayData.AnalysisStatus.PENDING,
                        summary = "Reading $fileName",
                        fullAnalysis = null
                    )
                }
                
            } catch (e: Exception) {
                LOG.error("Error parsing file analysis", e)
            }
            
            return null
        }
        
        /**
         * Parse streaming text to extract test plan updates
         */
        fun parseTestPlanUpdate(streamingText: String): TestPlanDisplayData? {
            try {
                // Look for test plan pattern
                val planPattern = """Test Plan Summary:\s*\n([\s\S]+?)(?:\n\n|$)""".toRegex()
                val planMatch = planPattern.find(streamingText)
                
                if (planMatch != null) {
                    val planContent = planMatch.groupValues[1]
                    
                    // Extract target class
                    val classPattern = """Target Class:\s*(.+)""".toRegex()
                    val classMatch = classPattern.find(planContent)
                    val targetClass = classMatch?.groupValues?.get(1)?.trim() ?: "Unknown"
                    
                    // Extract methods
                    val methodsPattern = """Target Methods?:\s*(.+)""".toRegex()
                    val methodsMatch = methodsPattern.find(planContent)
                    val methods = methodsMatch?.groupValues?.get(1)?.trim()
                        ?.split(",")
                        ?.map { it.trim() }
                        ?: emptyList()
                    
                    // Extract scenarios
                    val scenarios = parseScenarios(planContent)
                    
                    return TestPlanDisplayData(
                        targetClass = targetClass,
                        targetMethods = methods,
                        recommendedTestType = "Unit Tests",
                        scenarios = scenarios,
                        summary = "Test plan with ${scenarios.size} scenarios",
                        totalScenarios = scenarios.size
                    )
                }
            } catch (e: Exception) {
                LOG.error("Error parsing test plan", e)
            }
            
            return null
        }
        
        /**
         * Parse streaming text to extract generated test information
         */
        fun parseGeneratedTest(streamingText: String): GeneratedTestDisplayData? {
            try {
                // Look for generated test pattern
                val testPattern = """Generated test:\s*(.+)\n```(?:java)?\n([\s\S]+?)\n```""".toRegex()
                val testMatch = testPattern.find(streamingText)
                
                if (testMatch != null) {
                    val testName = testMatch.groupValues[1].trim()
                    val testCode = testMatch.groupValues[2].trim()
                    
                    return GeneratedTestDisplayData(
                        testName = testName,
                        scenarioId = generateScenarioId(testName),
                        scenarioName = testName,
                        testCode = testCode
                    )
                }
                
                // Alternative pattern for test generation
                val altPattern = """✅ Test generated for:\s*(.+)\n([\s\S]+?)(?:\n\n|$)""".toRegex()
                val altMatch = altPattern.find(streamingText)
                
                if (altMatch != null) {
                    val scenarioName = altMatch.groupValues[1].trim()
                    val testCode = altMatch.groupValues[2].trim()
                    
                    return GeneratedTestDisplayData(
                        testName = "test${scenarioName.replace(" ", "")}",
                        scenarioId = generateScenarioId(scenarioName),
                        scenarioName = scenarioName,
                        testCode = testCode
                    )
                }
            } catch (e: Exception) {
                LOG.error("Error parsing generated test", e)
            }
            
            return null
        }
        
        /**
         * Extract classes, methods, and dependencies from analysis text
         */
        private fun extractAnalysisDetails(analysis: String): Triple<List<String>, List<String>, List<String>> {
            val classes = mutableListOf<String>()
            val methods = mutableListOf<String>()
            val dependencies = mutableListOf<String>()
            
            // Extract classes
            val classPattern = """class\s+(\w+)""".toRegex()
            classPattern.findAll(analysis).forEach { 
                classes.add(it.groupValues[1])
            }
            
            // Extract methods
            val methodPattern = """(?:public|private|protected)?\s*(?:static)?\s*\w+\s+(\w+)\s*\(""".toRegex()
            methodPattern.findAll(analysis).forEach {
                methods.add(it.groupValues[1])
            }
            
            // Extract imports/dependencies
            val importPattern = """import\s+(.+);""".toRegex()
            importPattern.findAll(analysis).forEach {
                dependencies.add(it.groupValues[1])
            }
            
            return Triple(classes, methods, dependencies)
        }
        
        /**
         * Parse scenarios from test plan content
         */
        private fun parseScenarios(planContent: String): List<ScenarioDisplayData> {
            val scenarios = mutableListOf<ScenarioDisplayData>()
            
            // Look for scenario patterns like "1. Scenario Name [Priority]"
            val scenarioPattern = """(\d+)\.\s*(.+?)\s*\[(\w+)\]""".toRegex()
            scenarioPattern.findAll(planContent).forEach { match ->
                val index = match.groupValues[1]
                val name = match.groupValues[2].trim()
                val priorityStr = match.groupValues[3].trim()
                
                val priority = when (priorityStr.uppercase()) {
                    "HIGH" -> ScenarioDisplayData.Priority.HIGH
                    "LOW" -> ScenarioDisplayData.Priority.LOW
                    else -> ScenarioDisplayData.Priority.MEDIUM
                }
                
                scenarios.add(
                    ScenarioDisplayData(
                        id = "scenario_$index",
                        name = name,
                        description = "Test scenario for $name",
                        priority = priority,
                        category = "Unit Test"
                    )
                )
            }
            
            return scenarios
        }
        
        /**
         * Generate a unique scenario ID from name
         */
        private fun generateScenarioId(name: String): String {
            return "scenario_${name.hashCode().toString().replace("-", "n")}"
        }
        
        /**
         * Format text for display with caching
         */
        fun formatForDisplay(text: String, maxLength: Int = 100): String {
            val cacheKey = "${text.hashCode()}_$maxLength"
            
            return formattedCache.getOrPut(cacheKey) {
                if (text.length <= maxLength) {
                    text
                } else {
                    text.take(maxLength - 3) + "..."
                }
            }
        }
        
        /**
         * Clear the formatting cache
         */
        fun clearCache() {
            formattedCache.clear()
        }
    }
}