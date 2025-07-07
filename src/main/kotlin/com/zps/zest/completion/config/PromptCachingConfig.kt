package com.zps.zest.completion.config

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Configuration for prompt caching feature
 */
@State(
    name = "ZestPromptCachingConfig",
    storages = [Storage("zest-prompt-caching.xml")]
)
@Service(Service.Level.PROJECT)
class PromptCachingConfig : PersistentStateComponent<PromptCachingConfig.State> {
    
    data class State(
        var enableStructuredPrompts: Boolean = true,
        var enableForSimpleStrategy: Boolean = true,
        var enableForLeanStrategy: Boolean = true,
        var logPromptComparison: Boolean = false,
        var cacheSystemPrompts: Boolean = true,
        var systemPromptCacheDurationHours: Int = 24
    )
    
    private var myState = State()
    
    override fun getState(): State = myState
    
    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }
    
    // Convenience accessors
    var enableStructuredPrompts: Boolean
        get() = state.enableStructuredPrompts
        set(value) {
            state.enableStructuredPrompts = value
        }
    
    var enableForSimpleStrategy: Boolean
        get() = state.enableForSimpleStrategy
        set(value) {
            state.enableForSimpleStrategy = value
        }
    
    var enableForLeanStrategy: Boolean
        get() = state.enableForLeanStrategy
        set(value) {
            state.enableForLeanStrategy = value
        }
    
    var logPromptComparison: Boolean
        get() = state.logPromptComparison
        set(value) {
            state.logPromptComparison = value
        }
    
    companion object {
        @JvmStatic
        fun getInstance(project: Project): PromptCachingConfig {
            return project.getService(PromptCachingConfig::class.java)
        }
    }
}
