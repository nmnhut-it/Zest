package com.zps.zest.testgen.ui.persistence

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.intellij.openapi.diagnostic.Logger
import com.zps.zest.testgen.model.*
import com.zps.zest.testgen.ui.model.*
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Serializes and deserializes test generation sessions to JSON format.
 * Supports compression for large analysis texts.
 */
class SessionSerializer {
    
    companion object {
        private val LOG = Logger.getInstance(SessionSerializer::class.java)
        private val gson: Gson = GsonBuilder()
            .setPrettyPrinting()
            .setDateFormat("yyyy-MM-dd'T'HH:mm:ss")
            .create()
        
        private const val SESSION_VERSION = "1.0"
    }
    
    /**
     * Data class representing a serializable session
     */
    data class SerializableSession(
        val version: String = SESSION_VERSION,
        val sessionId: String,
        val createdAt: Long,
        val lastModified: Long = System.currentTimeMillis(),
        val status: String,
        val targetFile: String,
        val targetFilePath: String? = null,  // Full path to the file
        val targetClass: String?,
        val targetMethods: List<String>,
        val testType: String,
        
        // Request data for reconstruction
        val requestData: SerializableRequestData? = null,
        
        // Context data
        val contextFiles: List<SerializableContextFile>,
        
        // Test plan data
        val testPlan: SerializableTestPlan?,
        val selectedScenarioIds: Set<String>,
        
        // Generated tests
        val generatedTests: List<SerializableGeneratedTest>,
        
        // Progress info
        val progressPercent: Int,
        val currentPhase: String,
        
        // Additional metadata
        val metadata: Map<String, Any> = emptyMap()
    )
    
    data class SerializableRequestData(
        val targetFilePath: String,
        val targetMethodNames: List<String>,
        val selectedCode: String?,
        val testType: String,
        val additionalContext: Map<String, String>
    )
    
    data class SerializableContextFile(
        val filePath: String,
        val fileName: String,
        val status: String,
        val summary: String,
        val analysisCompressed: String?, // Base64 encoded GZIP compressed analysis
        val classes: List<String>,
        val methods: List<String>,
        val timestamp: Long
    )
    
    data class SerializableTestPlan(
        val targetClass: String,
        val targetMethods: List<String>,
        val recommendedTestType: String,
        val scenarios: List<SerializableScenario>,
        val totalScenarios: Int
    )
    
    data class SerializableScenario(
        val id: String,
        val name: String,
        val description: String,
        val priority: String,
        val category: String,
        val generationStatus: String,
        val selected: Boolean
    )
    
    data class SerializableGeneratedTest(
        val className: String,
        val fullTestCodeCompressed: String, // Base64 encoded GZIP compressed code
        val timestamp: Long
    )
    
    /**
     * Serialize a test generation session to JSON from session data
     */
    fun serializeSessionData(
        sessionData: SessionRestorationData
    ): String {
        // Extract request data if available
        val requestData = sessionData.metadata["requestData"]?.let { data ->
            if (data is SerializableRequestData) {
                data
            } else if (data is Map<*, *>) {
                // Try to reconstruct from map
                SerializableRequestData(
                    targetFilePath = data["targetFilePath"]?.toString() ?: "",
                    targetMethodNames = (data["targetMethodNames"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList(),
                    selectedCode = data["selectedCode"]?.toString(),
                    testType = data["testType"]?.toString() ?: "AUTO_DETECT",
                    additionalContext = (data["additionalContext"] as? Map<*, *>)?.mapNotNull { (k, v) ->
                        k?.toString()?.let { key -> key to v.toString() }
                    }?.toMap() ?: emptyMap()
                )
            } else null
        }
        
        val serializableSession = SerializableSession(
            sessionId = sessionData.sessionId,
            createdAt = sessionData.createdAt,
            status = sessionData.status,
            targetFile = sessionData.metadata["targetFile"]?.toString() ?: "unknown",
            targetFilePath = sessionData.metadata["targetFilePath"]?.toString(),
            targetClass = sessionData.testPlanData?.targetClass ?: sessionData.metadata["targetClass"]?.toString(),
            targetMethods = sessionData.testPlanData?.targetMethods ?: emptyList(),
            testType = requestData?.testType ?: "UNIT_TESTS",
            requestData = requestData,
            
            contextFiles = sessionData.contextFiles.map { file ->
                SerializableContextFile(
                    filePath = file.filePath,
                    fileName = file.fileName,
                    status = file.status.name,
                    summary = file.summary,
                    analysisCompressed = file.fullAnalysis?.let { compressString(it) },
                    classes = file.classes,
                    methods = file.methods,
                    timestamp = file.timestamp
                )
            },
            
            testPlan = sessionData.testPlanData?.let { plan ->
                SerializableTestPlan(
                    targetClass = plan.targetClass,
                    targetMethods = plan.targetMethods,
                    recommendedTestType = plan.recommendedTestType,
                    scenarios = plan.scenarios.map { scenario ->
                        SerializableScenario(
                            id = scenario.id,
                            name = scenario.name,
                            description = scenario.description,
                            priority = scenario.priority.name,
                            category = scenario.category,
                            generationStatus = scenario.generationStatus.name,
                            selected = plan.selectedScenarios.contains(scenario.id)
                        )
                    },
                    totalScenarios = plan.totalScenarios
                )
            },
            
            selectedScenarioIds = sessionData.testPlanData?.selectedScenarios ?: emptySet(),
            
            generatedTests = sessionData.generatedTests.map { test ->
                SerializableGeneratedTest(
                    className = test.className,
                    fullTestCodeCompressed = compressString(test.fullTestCode),
                    timestamp = test.timestamp
                )
            },
            
            progressPercent = sessionData.progressPercent,
            currentPhase = sessionData.currentPhase,
            metadata = sessionData.metadata
        )
        
        return gson.toJson(serializableSession)
    }
    
    
    /**
     * Deserialize a session from JSON
     */
    fun deserializeSession(json: String): SessionRestorationData? {
        return try {
            val serializableSession = gson.fromJson(json, SerializableSession::class.java)
            
            // Check version compatibility
            if (serializableSession.version != SESSION_VERSION) {
                LOG.warn("Session version mismatch: ${serializableSession.version} != $SESSION_VERSION")
            }
            
            // Restore context files
            val contextFiles = serializableSession.contextFiles.map { file ->
                ContextDisplayData(
                    filePath = file.filePath,
                    fileName = file.fileName,
                    status = ContextDisplayData.AnalysisStatus.valueOf(file.status),
                    summary = file.summary,
                    fullAnalysis = file.analysisCompressed?.let { decompressString(it) },
                    classes = file.classes,
                    methods = file.methods,
                    dependencies = emptyList(),
                    timestamp = file.timestamp
                )
            }

            
            // Restore test plan
            val testPlanData = serializableSession.testPlan?.let { plan ->
                TestPlanDisplayData(
                    targetClass = plan.targetClass,
                    targetMethods = plan.targetMethods,
                    recommendedTestType = plan.recommendedTestType,
                    scenarios = plan.scenarios.map { scenario ->
                        ScenarioDisplayData(
                            id = scenario.id,
                            name = scenario.name,
                            description = scenario.description,
                            priority = ScenarioDisplayData.Priority.valueOf(scenario.priority),
                            category = scenario.category,
                            generationStatus = ScenarioDisplayData.GenerationStatus.valueOf(scenario.generationStatus)
                        )
                    },
                    summary = "Restored test plan with ${plan.totalScenarios} scenarios",
                    totalScenarios = plan.totalScenarios,
                    selectedScenarios = serializableSession.selectedScenarioIds
                )
            }
            
            // Restore generated tests
            val generatedTests = serializableSession.generatedTests.map { test ->
                GeneratedTestDisplayData(
                    className = test.className,
                    fullTestCode = decompressString(test.fullTestCodeCompressed),
                    timestamp = test.timestamp
                )
            }
            
            SessionRestorationData(
                sessionId = serializableSession.sessionId,
                createdAt = serializableSession.createdAt,
                status = serializableSession.status,
                contextFiles = contextFiles,
                testPlanData = testPlanData,
                generatedTests = generatedTests,
                progressPercent = serializableSession.progressPercent,
                currentPhase = serializableSession.currentPhase,
                metadata = serializableSession.metadata + mapOf(
                    "targetFile" to serializableSession.targetFile,
                    "targetFilePath" to (serializableSession.targetFilePath ?: ""),
                    "targetClass" to (serializableSession.targetClass ?: "unknown"),
                    "requestData" to (serializableSession.requestData ?: mapOf<String, Any>())
                )
            )
            
        } catch (e: JsonSyntaxException) {
            LOG.error("Failed to deserialize session", e)
            null
        } catch (e: Exception) {
            LOG.error("Error restoring session", e)
            null
        }
    }
    
    
    /**
     * Compress a string using GZIP and encode as Base64
     */
    private fun compressString(input: String): String {
        val bytes = input.toByteArray()
        val baos = java.io.ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzip ->
            gzip.write(bytes)
        }
        return java.util.Base64.getEncoder().encodeToString(baos.toByteArray())
    }
    
    /**
     * Decompress a Base64 encoded GZIP string
     */
    private fun decompressString(compressed: String): String {
        val bytes = java.util.Base64.getDecoder().decode(compressed)
        val bais = java.io.ByteArrayInputStream(bytes)
        return GZIPInputStream(bais).use { gzip ->
            String(gzip.readBytes())
        }
    }
    
    /**
     * Data class for restored session information
     */
    data class SessionRestorationData(
        val sessionId: String,
        val createdAt: Long,
        val status: String,
        val contextFiles: List<ContextDisplayData>,
        val testPlanData: TestPlanDisplayData?,
        val generatedTests: List<GeneratedTestDisplayData>,
        val progressPercent: Int,
        val currentPhase: String,
        val metadata: Map<String, Any>
    )
}