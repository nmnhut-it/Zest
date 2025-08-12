package com.zps.zest.codehealth.testplan.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.zps.zest.codehealth.testplan.tutorial.TestGenerationTutorialService

/**
 * Action to show the test generation tutorial
 */
class ShowTestGenerationTutorialAction : AnAction(
    "🎓 Test Generation Tutorial",
    "Learn how to generate tests from Code Health analysis",
    AllIcons.General.ContextHelp
) {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        
        TestGenerationTutorialService.getInstance(project)
            .showBulkTestGenerationTutorial()
    }
    
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}