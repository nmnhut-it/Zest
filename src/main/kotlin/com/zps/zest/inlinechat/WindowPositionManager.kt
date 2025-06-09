package com.zps.zest.inlinechat

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.wm.WindowManager
import java.awt.*
import javax.swing.SwingUtilities

/**
 * Manages the positioning of the floating code window relative to the editor
 */
class WindowPositionManager(private val editor: Editor) {
    
    companion object {
        private const val PREFERRED_MARGIN = 20  // Original margin
        private const val MIN_MARGIN = 10      // Original minimum margin
    }
    
    /**
     * Calculate the optimal position for the floating window
     */
    fun calculateOptimalPosition(windowSize: Dimension): Point {
        // Get editor component bounds on screen
        val editorComponent = editor.component
        val editorLocationOnScreen = editorComponent.locationOnScreen
        val editorBounds = Rectangle(
            editorLocationOnScreen.x,
            editorLocationOnScreen.y,
            editorComponent.width,
            editorComponent.height
        )
        
        // Get screen bounds
        val screenBounds = getScreenBounds(editorLocationOnScreen)
        
        // Get selection bounds if available
        val selectionBounds = getSelectionBounds()
        
        // Try positions in order of preference
        val positions = listOf(
            // 1. Right of editor
            PositionCandidate.rightOf(editorBounds, windowSize, screenBounds),
            // 2. Left of editor
            PositionCandidate.leftOf(editorBounds, windowSize, screenBounds),
            // 3. Below selection/cursor
            PositionCandidate.below(selectionBounds ?: editorBounds, windowSize, screenBounds),
            // 4. Above selection/cursor
            PositionCandidate.above(selectionBounds ?: editorBounds, windowSize, screenBounds),
            // 5. Floating in available space
            PositionCandidate.floating(editorBounds, windowSize, screenBounds)
        )
        
        // Find the first position that fits well
        val optimalPosition = positions.firstOrNull { it.fitsWell() }
            ?: positions.first() // Fallback to first option
        
        return optimalPosition.position
    }
    
    /**
     * Get the bounds of the current selection or cursor position
     */
    private fun getSelectionBounds(): Rectangle? {
        val selectionModel = editor.selectionModel
        
        if (!selectionModel.hasSelection()) {
            // Get cursor position bounds
            val caretModel = editor.caretModel
            val offset = caretModel.offset
            val point = editor.offsetToXY(offset)
            val editorLocation = editor.component.locationOnScreen
            
            return Rectangle(
                editorLocation.x + point.x,
                editorLocation.y + point.y,
                100, // Approximate width
                editor.lineHeight
            )
        }
        
        // Get selection bounds
        val startOffset = selectionModel.selectionStart
        val endOffset = selectionModel.selectionEnd
        
        val startPoint = editor.offsetToXY(startOffset)
        val endPoint = editor.offsetToXY(endOffset)
        val editorLocation = editor.component.locationOnScreen
        
        val x = editorLocation.x + minOf(startPoint.x, endPoint.x)
        val y = editorLocation.y + startPoint.y
        val width = if (startPoint.y == endPoint.y) {
            maxOf(startPoint.x, endPoint.x) - minOf(startPoint.x, endPoint.x)
        } else {
            editor.component.width - minOf(startPoint.x, endPoint.x)
        }
        val height = endPoint.y - startPoint.y + editor.lineHeight
        
        return Rectangle(x, y, width, height)
    }
    
    /**
     * Get the screen bounds for the display containing the editor
     */
    private fun getScreenBounds(pointOnScreen: Point): Rectangle {
        val gc = getGraphicsConfiguration(pointOnScreen)
        val screenBounds = gc.bounds
        val screenInsets = Toolkit.getDefaultToolkit().getScreenInsets(gc)
        
        return Rectangle(
            screenBounds.x + screenInsets.left,
            screenBounds.y + screenInsets.top,
            screenBounds.width - screenInsets.left - screenInsets.right,
            screenBounds.height - screenInsets.top - screenInsets.bottom
        )
    }
    
    /**
     * Get the graphics configuration for a point
     */
    private fun getGraphicsConfiguration(point: Point): GraphicsConfiguration {
        val devices = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        
        for (device in devices) {
            val gc = device.defaultConfiguration
            val bounds = gc.bounds
            if (bounds.contains(point)) {
                return gc
            }
        }
        
        // Fallback to default
        return GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice
            .defaultConfiguration
    }
    
    /**
     * Represents a candidate position for the window
     */
    private data class PositionCandidate(
        val position: Point,
        val bounds: Rectangle,
        val screenBounds: Rectangle
    ) {
        fun fitsWell(): Boolean {
            // Check if the window fits within screen bounds with margins
            return bounds.x >= screenBounds.x + MIN_MARGIN &&
                   bounds.y >= screenBounds.y + MIN_MARGIN &&
                   bounds.x + bounds.width <= screenBounds.x + screenBounds.width - MIN_MARGIN &&
                   bounds.y + bounds.height <= screenBounds.y + screenBounds.height - MIN_MARGIN
        }
        
        companion object {
            fun rightOf(targetBounds: Rectangle, windowSize: Dimension, screenBounds: Rectangle): PositionCandidate {
                val x = targetBounds.x + targetBounds.width + PREFERRED_MARGIN
                val y = targetBounds.y + (targetBounds.height - windowSize.height) / 2
                val position = Point(x, y)
                val bounds = Rectangle(position, windowSize)
                return PositionCandidate(position, bounds, screenBounds)
            }
            
            fun leftOf(targetBounds: Rectangle, windowSize: Dimension, screenBounds: Rectangle): PositionCandidate {
                val x = targetBounds.x - windowSize.width - PREFERRED_MARGIN
                val y = targetBounds.y + (targetBounds.height - windowSize.height) / 2
                val position = Point(x, y)
                val bounds = Rectangle(position, windowSize)
                return PositionCandidate(position, bounds, screenBounds)
            }
            
            fun below(targetBounds: Rectangle, windowSize: Dimension, screenBounds: Rectangle): PositionCandidate {
                val x = targetBounds.x + (targetBounds.width - windowSize.width) / 2
                val y = targetBounds.y + targetBounds.height + PREFERRED_MARGIN
                val position = Point(x, y)
                val bounds = Rectangle(position, windowSize)
                return PositionCandidate(position, bounds, screenBounds)
            }
            
            fun above(targetBounds: Rectangle, windowSize: Dimension, screenBounds: Rectangle): PositionCandidate {
                val x = targetBounds.x + (targetBounds.width - windowSize.width) / 2
                val y = targetBounds.y - windowSize.height - PREFERRED_MARGIN
                val position = Point(x, y)
                val bounds = Rectangle(position, windowSize)
                return PositionCandidate(position, bounds, screenBounds)
            }
            
            fun floating(editorBounds: Rectangle, windowSize: Dimension, screenBounds: Rectangle): PositionCandidate {
                // Try to find the best floating position
                // Prefer bottom-right corner of the screen
                val x = screenBounds.x + screenBounds.width - windowSize.width - PREFERRED_MARGIN
                val y = screenBounds.y + screenBounds.height - windowSize.height - PREFERRED_MARGIN
                
                // Adjust if overlapping with editor
                val position = Point(x, y)
                val bounds = Rectangle(position, windowSize)
                
                if (bounds.intersects(editorBounds)) {
                    // Move to top-right if bottom-right overlaps
                    position.y = screenBounds.y + PREFERRED_MARGIN
                }
                
                return PositionCandidate(position, bounds, screenBounds)
            }
        }
    }
}