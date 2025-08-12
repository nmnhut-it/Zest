package com.zps.zest.completion.metrics

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.project.Project
import com.zps.zest.ConfigurationManager
import java.security.MessageDigest

object MetricsUtils {
    private var cachedPluginVersion: String? = null
    
    fun createBaseMetadata(project: Project, model: String): BaseMetadataBuilder {
        return BaseMetadataBuilder(project, model)
    }
    
    fun getUserHash(project: Project): String {
        // Use a combination of username and machine name
        val userName = System.getProperty("user.name", "unknown")
        val machineName = try {
            java.net.InetAddress.getLocalHost().hostName
        } catch (e: Exception) {
            "unknown"
        }
        return hashString("$userName-$machineName")
    }
    
    fun getProjectHash(project: Project): String {
        return hashString(project.basePath ?: project.name)
    }
    
    fun getPluginVersion(): String {
        if (cachedPluginVersion == null) {
            // TODO: Read from plugin.xml or build configuration
            cachedPluginVersion = "1.9.891"  // Fallback version
        }
        return cachedPluginVersion!!
    }
    
    fun getIdeVersion(): String {
        return ApplicationInfo.getInstance().fullVersion
    }
    
    fun getAuthToken(project: Project): String {
        return ConfigurationManager.getInstance(project).authToken ?: "anonymous"
    }
    
    fun parseStrategy(strategyStr: String): CompletionStrategy {
        return try {
            CompletionStrategy.valueOf(strategyStr.uppercase().replace(" ", "_"))
        } catch (e: Exception) {
            CompletionStrategy.UNKNOWN
        }
    }
    
    fun parseAcceptType(acceptTypeStr: String): AcceptType {
        return try {
            AcceptType.valueOf(acceptTypeStr.uppercase())
        } catch (e: Exception) {
            AcceptType.FULL
        }
    }
    
    fun parseUserAction(actionStr: String): UserAction {
        return try {
            UserAction.valueOf(actionStr.uppercase())
        } catch (e: Exception) {
            UserAction.UNKNOWN
        }
    }
    
    fun parseRejectReason(reasonStr: String): RejectReason {
        return when (reasonStr) {
            "esc_pressed" -> RejectReason.ESC_PRESSED
            "user_typed" -> RejectReason.USER_TYPED
            "focus_lost" -> RejectReason.FOCUS_LOST
            "file_changed" -> RejectReason.FILE_CHANGED
            "cursor_moved" -> RejectReason.CURSOR_MOVED
            else -> RejectReason.USER_TYPED
        }
    }
    
    private fun hashString(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }
    
    // Builder for common fields
    class BaseMetadataBuilder(private val project: Project, private val model: String) {
        private val token = getAuthToken(project)
        private val projectId = getProjectHash(project)
        private val userId = getUserHash(project)
        private val ideVersion = getIdeVersion()
        private val pluginVersion = getPluginVersion()
        private val timestamp = System.currentTimeMillis()
        
        fun buildInlineRequest(fileType: String, strategy: CompletionStrategy): InlineRequestMetadata {
            return InlineRequestMetadata(
                token, model, projectId, userId, ideVersion, pluginVersion, timestamp,
                fileType, strategy
            )
        }
        
        fun buildInlineResponse(fileType: String, strategy: CompletionStrategy, responseTimeMs: Long): InlineResponseMetadata {
            return InlineResponseMetadata(
                token, model, projectId, userId, ideVersion, pluginVersion, timestamp,
                fileType, strategy, responseTimeMs
            )
        }
        
        fun buildInlineView(completionLength: Int, completionLineCount: Int, confidence: Float): InlineViewMetadata {
            return InlineViewMetadata(
                token, model, projectId, userId, ideVersion, pluginVersion, timestamp,
                completionLength, completionLineCount, confidence
            )
        }
        
        fun buildInlineAccept(
            acceptType: AcceptType,
            userAction: UserAction,
            strategy: CompletionStrategy,
            fileType: String,
            isPartial: Boolean,
            partialAcceptCount: Int,
            totalAcceptedLength: Int,
            viewToAcceptTimeMs: Long
        ): InlineAcceptMetadata {
            return InlineAcceptMetadata(
                token, model, projectId, userId, ideVersion, pluginVersion, timestamp,
                acceptType, userAction, strategy, fileType, isPartial, 
                partialAcceptCount, totalAcceptedLength, viewToAcceptTimeMs
            )
        }
        
        fun buildInlineReject(reason: RejectReason): InlineRejectMetadata {
            return InlineRejectMetadata(
                token, model, projectId, userId, ideVersion, pluginVersion, timestamp,
                reason
            )
        }
        
        fun buildInlineDismiss(reason: String, partialAcceptCount: Int, totalAcceptedLength: Int): InlineDismissMetadata {
            return InlineDismissMetadata(
                token, model, projectId, userId, ideVersion, pluginVersion, timestamp,
                reason, partialAcceptCount, totalAcceptedLength
            )
        }
        
        // Quick Action builders
        fun buildQuickActionRequest(
            methodName: String,
            language: String,
            fileType: String,
            hasCustomInstruction: Boolean
        ): QuickActionRequestMetadata {
            return QuickActionRequestMetadata(
                token, model, projectId, userId, ideVersion, pluginVersion, timestamp,
                methodName, language, fileType, hasCustomInstruction
            )
        }
        
        fun buildQuickActionResponse(
            methodName: String,
            language: String,
            responseTimeMs: Long,
            contentLength: Int
        ): QuickActionResponseMetadata {
            return QuickActionResponseMetadata(
                token, model, projectId, userId, ideVersion, pluginVersion, timestamp,
                methodName, language, responseTimeMs, contentLength
            )
        }
        
        fun buildQuickActionView(
            methodName: String,
            language: String,
            diffChanges: Int,
            confidence: Float
        ): QuickActionViewMetadata {
            return QuickActionViewMetadata(
                token, model, projectId, userId, ideVersion, pluginVersion, timestamp,
                methodName, language, diffChanges, confidence
            )
        }
        
        fun buildQuickActionAccept(
            methodName: String,
            language: String,
            viewToAcceptTimeMs: Long,
            contentLength: Int,
            userAction: UserAction
        ): QuickActionAcceptMetadata {
            return QuickActionAcceptMetadata(
                token, model, projectId, userId, ideVersion, pluginVersion, timestamp,
                methodName, language, viewToAcceptTimeMs, contentLength, userAction
            )
        }
        
        fun buildQuickActionReject(methodName: String, reason: RejectReason): QuickActionRejectMetadata {
            return QuickActionRejectMetadata(
                token, model, projectId, userId, ideVersion, pluginVersion, timestamp,
                methodName, reason
            )
        }
        
        fun buildQuickActionDismiss(methodName: String, reason: String, wasViewed: Boolean): QuickActionDismissMetadata {
            return QuickActionDismissMetadata(
                token, model, projectId, userId, ideVersion, pluginVersion, timestamp,
                methodName, reason, wasViewed
            )
        }
        
        // Code Health builder
        fun buildCodeHealth(eventType: String, analysisData: Map<String, Any>): CodeHealthMetadata {
            return CodeHealthMetadata(
                token, model, projectId, userId, ideVersion, pluginVersion, timestamp,
                eventType, analysisData
            )
        }
        
        // Custom event builder
        fun buildCustom(customTool: String, additionalData: Map<String, Any>): CustomEventMetadata {
            return CustomEventMetadata(
                token, model, projectId, userId, ideVersion, pluginVersion, timestamp,
                customTool, additionalData
            )
        }
    }
}