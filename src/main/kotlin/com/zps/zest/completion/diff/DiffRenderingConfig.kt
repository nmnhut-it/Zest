package com.zps.zest.completion.diff

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Configuration for diff rendering behavior
 */
@State(
    name = "ZestDiffRenderingConfig",
    storages = [Storage("zest-diff-rendering.xml")]
)
@Service
class DiffRenderingConfig : PersistentStateComponent<DiffRenderingConfig.State> {
    
    data class State(
        var diffAlgorithm: String = DiffAlgorithm.HISTOGRAM.name,
        var multiLineThreshold: Int = 1,
        var maxColumnWidthPercentage: Double = 0.45,
        var rightColumnFontSizeFactor: Float = 0.9f,
        var ghostTextAlphaLight: Float = 0.6f,
        var ghostTextAlphaDark: Float = 0.7f,
        var enableWordLevelDiff: Boolean = true,
        var enableSmartLineWrapping: Boolean = true,
        var wrapAtOperators: Boolean = true,
        var continuationIndentSize: Int = 2,
        var useSubtleColors: Boolean = true,
        var useSideBySideView: Boolean = true,
        var showDiffAtStart: Boolean = true,
        var autoScrollToDiff: Boolean = true,
        var showFloatingDiffButton: Boolean = false,
        var showAdditionHint: Boolean = true,
        var useGhostTextForAdditions: Boolean = true
    )
    
    enum class DiffAlgorithm {
        MYERS,
        HISTOGRAM
    }
    
    private var myState = State()
    
    override fun getState(): State = myState
    
    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }
    
    companion object {
        @JvmStatic
        fun getInstance(): DiffRenderingConfig = service()
    }
    
    // Convenience getters
    fun getDiffAlgorithm(): WordDiffUtil.DiffAlgorithm {
        return try {
            WordDiffUtil.DiffAlgorithm.valueOf(myState.diffAlgorithm)
        } catch (e: IllegalArgumentException) {
            WordDiffUtil.DiffAlgorithm.HISTOGRAM
        }
    }
    
    fun shouldRenderAsMultiLine(originalLineCount: Int, modifiedLineCount: Int): Boolean {
        return originalLineCount > myState.multiLineThreshold || 
               modifiedLineCount > myState.multiLineThreshold
    }
    
    fun getMaxColumnWidthPercentage(): Double = myState.maxColumnWidthPercentage
    
    fun getRightColumnFontSizeFactor(): Float = myState.rightColumnFontSizeFactor
    
    fun getGhostTextAlpha(isDarkTheme: Boolean): Float {
        return if (isDarkTheme) myState.ghostTextAlphaDark else myState.ghostTextAlphaLight
    }
    
    fun isWordLevelDiffEnabled(): Boolean = myState.enableWordLevelDiff
    
    fun isSmartLineWrappingEnabled(): Boolean = myState.enableSmartLineWrapping
    
    fun shouldWrapAtOperators(): Boolean = myState.wrapAtOperators
    
    fun getContinuationIndentSize(): Int = myState.continuationIndentSize
    
    fun useSubtleColors(): Boolean = myState.useSubtleColors
    
    fun useSideBySideView(): Boolean = myState.useSideBySideView
    
    fun showDiffAtStart(): Boolean = myState.showDiffAtStart
    
    fun autoScrollToDiff(): Boolean = myState.autoScrollToDiff
    
    fun showFloatingDiffButton(): Boolean = myState.showFloatingDiffButton
    
    fun showAdditionHint(): Boolean = myState.showAdditionHint
    
    fun useGhostTextForAdditions(): Boolean = myState.useGhostTextForAdditions
}
