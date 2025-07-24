package com.zps.zest.completion.diff

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Disposer
import java.awt.Component
import java.awt.Graphics
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Base class for creating interactive inline panels in the editor
 * Handles mouse event redirection to make embedded components interactive
 */
abstract class InteractiveInlinePanel : EditorCustomElementRenderer, Disposable {
    
    protected var currentInlay: Inlay<*>? = null
    private var mouseListener: EditorMouseListener? = null
    private var mouseMotionListener: EditorMouseMotionListener? = null
    
    abstract fun getComponent(): JComponent
    abstract fun getPreferredWidth(editor: Editor): Int
    abstract fun getPreferredHeight(editor: Editor): Int
    
    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        currentInlay = inlay
        return getPreferredWidth(inlay.editor)
    }
    
    override fun calcHeightInPixels(inlay: Inlay<*>): Int {
        return getPreferredHeight(inlay.editor)
    }
    
    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: TextAttributes
    ) {
        val component = getComponent()
        
        // Update component bounds
        component.setBounds(0, 0, targetRegion.width, targetRegion.height)
        component.doLayout()
        component.validate()
        
        // Paint the component
        val g2 = g.create(targetRegion.x, targetRegion.y, targetRegion.width, targetRegion.height)
        try {
            component.paint(g2)
        } finally {
            g2.dispose()
        }
        
        // Set up mouse event handling if not already done
        if (mouseListener == null) {
            setupMouseHandling(inlay)
        }
    }
    
    private fun setupMouseHandling(inlay: Inlay<*>) {
        val editor = inlay.editor
        val component = getComponent()
        
        // Mouse click listener
        mouseListener = object : EditorMouseListener {
            override fun mouseClicked(e: EditorMouseEvent) {
                handleMouseEvent(e, inlay, component) { mouseEvent ->
                    component.dispatchEvent(mouseEvent)
                }
            }
            
            override fun mousePressed(e: EditorMouseEvent) {
                handleMouseEvent(e, inlay, component) { mouseEvent ->
                    component.dispatchEvent(mouseEvent)
                }
            }
            
            override fun mouseReleased(e: EditorMouseEvent) {
                handleMouseEvent(e, inlay, component) { mouseEvent ->
                    component.dispatchEvent(mouseEvent)
                }
            }
        }
        
        // Mouse motion listener
        mouseMotionListener = object : EditorMouseMotionListener {
            override fun mouseMoved(e: EditorMouseEvent) {
                handleMouseEvent(e, inlay, component) { mouseEvent ->
                    component.dispatchEvent(mouseEvent)
                }
            }
            
            override fun mouseDragged(e: EditorMouseEvent) {
                handleMouseEvent(e, inlay, component) { mouseEvent ->
                    component.dispatchEvent(mouseEvent)
                }
            }
        }
        
        editor.addEditorMouseListener(mouseListener!!)
        editor.addEditorMouseMotionListener(mouseMotionListener!!)
    }
    
    private fun handleMouseEvent(
        e: EditorMouseEvent,
        inlay: Inlay<*>,
        component: JComponent,
        handler: (MouseEvent) -> Unit
    ) {
        val bounds = getInlayBounds(inlay) ?: return
        val mousePoint = e.mouseEvent.point
        
        if (bounds.contains(mousePoint)) {
            // Convert to component coordinates
            val componentPoint = Point(
                mousePoint.x - bounds.x,
                mousePoint.y - bounds.y
            )
            
            // Find target component
            val target = SwingUtilities.getDeepestComponentAt(component, componentPoint.x, componentPoint.y)
                ?: component
            
            // Convert to target component coordinates
            val targetPoint = SwingUtilities.convertPoint(component, componentPoint, target)
            
            // Create new mouse event for the target component
            val mouseEvent = MouseEvent(
                target,
                e.mouseEvent.id,
                e.mouseEvent.`when`,
                e.mouseEvent.modifiersEx,
                targetPoint.x,
                targetPoint.y,
                e.mouseEvent.clickCount,
                e.mouseEvent.isPopupTrigger,
                e.mouseEvent.button
            )
            
            handler(mouseEvent)
            e.consume()
        }
    }
    
    private fun getInlayBounds(inlay: Inlay<*>): Rectangle? {
        if (!inlay.isValid) return null
        
        val editor = inlay.editor
        val offset = inlay.offset
        val visualPosition = editor.offsetToVisualPosition(offset)
        val point = editor.visualPositionToXY(visualPosition)
        
        return Rectangle(
            point.x,
            point.y,
            calcWidthInPixels(inlay),
            calcHeightInPixels(inlay)
        )
    }
    
    override fun dispose() {
        currentInlay?.editor?.let { editor ->
            mouseListener?.let { editor.removeEditorMouseListener(it) }
            mouseMotionListener?.let { editor.removeEditorMouseMotionListener(it) }
        }
        mouseListener = null
        mouseMotionListener = null
        currentInlay = null
    }
}

/**
 * Helper to create interactive inline panels
 */
object InteractiveInlinePanelHelper {
    
    fun createInlay(
        editor: Editor,
        offset: Int,
        component: JComponent,
        width: Int,
        height: Int,
        priority: Int = 100
    ): Inlay<*>? {
        
        val renderer = object : InteractiveInlinePanel() {
            override fun getComponent(): JComponent = component
            override fun getPreferredWidth(editor: Editor): Int = width
            override fun getPreferredHeight(editor: Editor): Int = height
        }
        
        val inlay = editor.inlayModel.addBlockElement(
            offset,
            true,
            false,
            priority,
            renderer
        )
        
        // Register for disposal
        inlay?.let {
            Disposer.register(it, renderer)
        }
        
        return inlay
    }
}