package com.zps.zest.inlinechat

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAware

/**
 * Test action to verify the diff view functionality
 */
class TestDiffViewAction : AnAction("Test Diff View"), DumbAware {
    companion object {
        private val LOG = Logger.getInstance(TestDiffViewAction::class.java)
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        
        // Enable console output for testing
        System.setProperty("idea.log.debug.categories", "#com.zps.zest")
        
        println("=== TestDiffViewAction Started ===")
        System.out.println("=== TestDiffViewAction Started (System.out) ===")
        LOG.info("=== TestDiffViewAction Started (Logger) ===")
        LOG.warn("Test warning: Starting diff view test")
        LOG.error("Test error log: This is not a real error, just testing logging")
        
        // Sample original code
        val originalCode = """
public class Calculator {
    public int add(int a, int b) {
        return a + b;
    }
    
    public int subtract(int a, int b) {
        return a - b;
    }
}
        """.trimIndent()
        
        // Sample modified code with AI suggestions
        val suggestedCode = """
public class Calculator {
    private static final String LOG_TAG = "Calculator";
    
    public int add(int a, int b) {
        System.out.println(LOG_TAG + ": Adding " + a + " + " + b);
        int result = a + b;
        System.out.println(LOG_TAG + ": Result = " + result);
        return result;
    }
    
    public int subtract(int a, int b) {
        System.out.println(LOG_TAG + ": Subtracting " + b + " from " + a);
        return a - b;
    }
    
    public int multiply(int a, int b) {
        System.out.println(LOG_TAG + ": Multiplying " + a + " * " + b);
        return a * b;
    }
}
        """.trimIndent()
        
        println("Original code length: ${originalCode.length}")
        println("Suggested code length: ${suggestedCode.length}")
        LOG.info("Original code length: ${originalCode.length}")
        LOG.info("Suggested code length: ${suggestedCode.length}")
        
        // Create and show the floating window
        val floatingWindow = FloatingCodeWindow(
            project,
            editor,
            suggestedCode,
            originalCode,
            onAccept = {
                println("=== ACCEPTED: User accepted the changes ===")
                LOG.info("=== ACCEPTED: User accepted the changes ===")
                System.out.println("Accept callback executed")
            },
            onReject = {
                println("=== REJECTED: User rejected the changes ===")
                LOG.info("=== REJECTED: User rejected the changes ===")
                System.out.println("Reject callback executed")
            }
        )
        
        println("Creating floating window...")
        LOG.info("Creating floating window...")
        
        floatingWindow.show()
        
        println("=== TestDiffViewAction Completed ===")
        LOG.info("=== TestDiffViewAction Completed ===")
        System.out.println("Test action execution finished")
    }
    
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && e.getData(CommonDataKeys.EDITOR) != null
    }
}
