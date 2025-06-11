package com.zps.zest.completion.actions

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixture4TestCase
import com.zps.zest.completion.ZestInlineCompletionService
import io.mockk.*
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZestInlineCompletionActionTest : LightPlatformCodeInsightFixture4TestCase() {

    @Test
    fun `test action priority ordering`() {
        val triggerAction = ZestTrigger()
        val acceptAction = ZestAccept()
        val dismissAction = ZestDismiss()
        val tabAcceptAction = ZestTabAccept()
        
        // Verify priority ordering
        assertTrue(triggerAction.priority > acceptAction.priority)
        assertTrue(acceptAction.priority > tabAcceptAction.priority)
        assertTrue(tabAcceptAction.priority > dismissAction.priority)
        
        assertEquals(15, triggerAction.priority)
        assertEquals(12, acceptAction.priority)
        assertEquals(11, tabAcceptAction.priority)
        assertEquals(-1, dismissAction.priority)
    }

    @Test
    fun `test action handler execution`() {
        val mockEditor = mockk<Editor>(relaxed = true)
        val mockCaret = mockk<Caret>(relaxed = true)
        val mockService = mockk<ZestInlineCompletionService>(relaxed = true)
        
        // Test accept action
        val acceptHandler = object : ZestInlineCompletionActionHandler {
            override fun doExecute(editor: Editor, caret: Caret?, service: ZestInlineCompletionService) {
                service.accept(editor, caret?.offset, ZestInlineCompletionService.AcceptType.FULL_COMPLETION)
            }
            
            override fun isEnabledForCaret(editor: Editor, caret: Caret, service: ZestInlineCompletionService): Boolean {
                return service.isInlineCompletionVisibleAt(editor, caret.offset)
            }
        }
        
        every { mockCaret.offset } returns 100
        every { mockService.isInlineCompletionVisibleAt(mockEditor, 100) } returns true
        
        // Test execution
        acceptHandler.doExecute(mockEditor, mockCaret, mockService)
        verify { mockService.accept(mockEditor, 100, ZestInlineCompletionService.AcceptType.FULL_COMPLETION) }
        
        // Test enablement
        val isEnabled = acceptHandler.isEnabledForCaret(mockEditor, mockCaret, mockService)
        assertTrue(isEnabled)
        verify { mockService.isInlineCompletionVisibleAt(mockEditor, 100) }
    }

    @Test
    fun `test tab accept smart behavior`() {
        val mockEditor = mockk<Editor>(relaxed = true)
        val mockCaret = mockk<Caret>(relaxed = true)
        val mockService = mockk<ZestInlineCompletionService>(relaxed = true)
        
        every { mockCaret.offset } returns 50
        every { mockService.isInlineCompletionVisibleAt(mockEditor, 50) } returns true
        
        val tabAcceptHandler = object : ZestInlineCompletionActionHandler {
            override fun doExecute(editor: Editor, caret: Caret?, service: ZestInlineCompletionService) {
                service.accept(editor, caret?.offset, ZestInlineCompletionService.AcceptType.FULL_COMPLETION)
            }
            
            override fun isEnabledForCaret(editor: Editor, caret: Caret, service: ZestInlineCompletionService): Boolean {
                return service.isInlineCompletionVisibleAt(editor, caret.offset) && 
                       !service.isInlineCompletionStartWithIndentation()
            }
        }
        
        // Test when completion doesn't start with indentation
        every { mockService.isInlineCompletionStartWithIndentation() } returns false
        assertTrue(tabAcceptHandler.isEnabledForCaret(mockEditor, mockCaret, mockService))
        
        // Test when completion starts with indentation
        every { mockService.isInlineCompletionStartWithIndentation() } returns true
        assertFalse(tabAcceptHandler.isEnabledForCaret(mockEditor, mockCaret, mockService))
    }

    @Test
    fun `test dismiss action always enabled when completion visible`() {
        val mockEditor = mockk<Editor>(relaxed = true)
        val mockCaret = mockk<Caret>(relaxed = true)
        val mockService = mockk<ZestInlineCompletionService>(relaxed = true)
        
        every { mockCaret.offset } returns 75
        
        val dismissHandler = object : ZestInlineCompletionActionHandler {
            override fun doExecute(editor: Editor, caret: Caret?, service: ZestInlineCompletionService) {
                service.dismiss()
            }
            
            override fun isEnabledForCaret(editor: Editor, caret: Caret, service: ZestInlineCompletionService): Boolean {
                return service.isInlineCompletionVisibleAt(editor, caret.offset)
            }
        }
        
        // Test when completion is visible
        every { mockService.isInlineCompletionVisibleAt(mockEditor, 75) } returns true
        assertTrue(dismissHandler.isEnabledForCaret(mockEditor, mockCaret, mockService))
        
        // Test execution
        dismissHandler.doExecute(mockEditor, mockCaret, mockService)
        verify { mockService.dismiss() }
    }

    @Test
    fun `test cycle actions`() {
        val mockEditor = mockk<Editor>(relaxed = true)
        val mockCaret = mockk<Caret>(relaxed = true)
        val mockService = mockk<ZestInlineCompletionService>(relaxed = true)
        
        every { mockCaret.offset } returns 200
        every { mockService.isInlineCompletionVisibleAt(mockEditor, 200) } returns true
        
        val cycleNextHandler = object : ZestInlineCompletionActionHandler {
            override fun doExecute(editor: Editor, caret: Caret?, service: ZestInlineCompletionService) {
                service.cycle(editor, caret?.offset, ZestInlineCompletionService.CycleDirection.NEXT)
            }
            
            override fun isEnabledForCaret(editor: Editor, caret: Caret, service: ZestInlineCompletionService): Boolean {
                return service.isInlineCompletionVisibleAt(editor, caret.offset)
            }
        }
        
        // Test cycle next
        cycleNextHandler.doExecute(mockEditor, mockCaret, mockService)
        verify { mockService.cycle(mockEditor, 200, ZestInlineCompletionService.CycleDirection.NEXT) }
        
        // Test enablement
        assertTrue(cycleNextHandler.isEnabledForCaret(mockEditor, mockCaret, mockService))
    }

    @Test
    fun `test partial acceptance actions`() {
        val mockEditor = mockk<Editor>(relaxed = true)
        val mockCaret = mockk<Caret>(relaxed = true)
        val mockService = mockk<ZestInlineCompletionService>(relaxed = true)
        
        every { mockCaret.offset } returns 150
        every { mockService.isInlineCompletionVisibleAt(mockEditor, 150) } returns true
        
        // Test next word acceptance
        val nextWordHandler = object : ZestInlineCompletionActionHandler {
            override fun doExecute(editor: Editor, caret: Caret?, service: ZestInlineCompletionService) {
                service.accept(editor, caret?.offset, ZestInlineCompletionService.AcceptType.NEXT_WORD)
            }
            
            override fun isEnabledForCaret(editor: Editor, caret: Caret, service: ZestInlineCompletionService): Boolean {
                return service.isInlineCompletionVisibleAt(editor, caret.offset)
            }
        }
        
        nextWordHandler.doExecute(mockEditor, mockCaret, mockService)
        verify { mockService.accept(mockEditor, 150, ZestInlineCompletionService.AcceptType.NEXT_WORD) }
        
        // Test next line acceptance
        val nextLineHandler = object : ZestInlineCompletionActionHandler {
            override fun doExecute(editor: Editor, caret: Caret?, service: ZestInlineCompletionService) {
                service.accept(editor, caret?.offset, ZestInlineCompletionService.AcceptType.NEXT_LINE)
            }
            
            override fun isEnabledForCaret(editor: Editor, caret: Caret, service: ZestInlineCompletionService): Boolean {
                return service.isInlineCompletionVisibleAt(editor, caret.offset)
            }
        }
        
        nextLineHandler.doExecute(mockEditor, mockCaret, mockService)
        verify { mockService.accept(mockEditor, 150, ZestInlineCompletionService.AcceptType.NEXT_LINE) }
    }
}
