package com.zps.zest.util

import com.intellij.ui.JBColor

/**
 * Utility for detecting IDE theme (light/dark mode).
 * Use this instead of deprecated UIUtil.isUnderDarcula() for better compatibility with all themes.
 */
object ThemeUtils {

    /**
     * Check if the current IDE theme is dark.
     * Works with all dark themes (Darcula, High Contrast, New UI dark, etc.)
     *
     * @return true if dark theme is active, false for light theme
     */
    @JvmStatic
    fun isDarkTheme(): Boolean = !JBColor.isBright()
}
