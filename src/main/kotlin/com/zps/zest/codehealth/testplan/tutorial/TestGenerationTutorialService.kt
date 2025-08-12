package com.zps.zest.codehealth.testplan.tutorial

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.Color
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.Timer
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.border.EmptyBorder
import com.intellij.util.ui.UIUtil
import javax.swing.SwingConstants

/**
 * Service for providing interactive tutorials for test generation workflow
 */
@Service(Service.Level.PROJECT)
class TestGenerationTutorialService(private val project: Project) {
    
    companion object {
        fun getInstance(project: Project): TestGenerationTutorialService {
            return project.getService(TestGenerationTutorialService::class.java)
        }
    }
    
    /**
     * Show the bulk test generation tutorial
     */
    fun showBulkTestGenerationTutorial() {
        val steps = createTutorialSteps()
        showTutorialDialog(steps)
    }
    
    private fun createTutorialSteps(): List<TutorialStep> {
        return listOf(
            TutorialStep(
                title = "🎓 Welcome to Bulk Test Generation!",
                description = "Generate comprehensive test files with a few clicks.\n\nThis tutorial will guide you through the complete workflow.",
                action = "Let's get started!"
            ),
            TutorialStep(
                title = "Step 1: 🔍 Analyze Your Code",
                description = "First, run Code Health Review to identify methods that need tests:\n\n• Open Code Guardian tool window\n• Click 'Run Analysis' or use existing reports\n• Review code health issues and recommendations",
                action = "Find methods with quality issues or complex logic"
            ),
            TutorialStep(
                title = "Step 2: 🧪 Generate Test Plans", 
                description = "For methods that need testing:\n\n• Click '🧪 Generate Test Plan' button in Code Guardian\n• Test plan opens in full-screen editor\n• Review testability analysis and generated test cases\n• Edit the test plan as needed",
                action = "Create detailed test plans for your methods"
            ),
            TutorialStep(
                title = "Step 3: ✏️ Review and Refine Plans",
                description = "Test plans open in spacious editor tabs:\n\n• Left panel shows testability analysis\n• Right panel shows generated test cases\n• Edit test cases, add edge cases, modify setup\n• Plans are automatically saved",
                action = "Fine-tune your test strategies"
            ),
            TutorialStep(
                title = "Step 4: ⚡ Bulk Generate Tests",
                description = "When ready to create test files:\n\n• Go to Tools → Zest Test Generation → Generate All Tests\n• Choose test framework (JUnit 4/5, TestNG)\n• Choose mocking framework (Mockito, EasyMock, etc.)\n• Select target directory (e.g., src/test/java)",
                action = "Convert all test plans to actual test files"
            ),
            TutorialStep(
                title = "Step 5: 🎯 Run Your Tests",
                description = "Generated test files are ready to use:\n\n• Files are auto-formatted and organized\n• Import statements are included\n• Basic test structure is provided\n• Add specific assertions and test data\n• Run tests with standard IntelliJ test runner",
                action = "Execute your comprehensive test suite"
            ),
            TutorialStep(
                title = "🎉 Complete!",
                description = "You've mastered the test generation workflow!\n\n✅ Analyze code quality\n✅ Generate intelligent test plans\n✅ Review in full-screen editors\n✅ Bulk generate test files\n✅ Run comprehensive tests\n\nYour code is now well-tested and maintainable!",
                action = "Start generating tests for your projects"
            )
        )
    }
    
    private fun showTutorialDialog(steps: List<TutorialStep>) {
        var currentStep = 0
        val dialog = createTutorialDialog(steps[currentStep])
        
        fun showNextStep() {
            currentStep++
            if (currentStep < steps.size) {
                dialog.dispose()
                val nextDialog = createTutorialDialog(steps[currentStep])
                
                // Auto-advance for demo purposes (remove in production)
                Timer(3000) {
                    if (currentStep < steps.size - 1) {
                        showNextStep()
                    } else {
                        nextDialog.dispose()
                        showCompletionMessage()
                    }
                }.apply { 
                    isRepeats = false
                    start() 
                }
                
                nextDialog.isVisible = true
            } else {
                dialog.dispose()
                showCompletionMessage()
            }
        }
        
        // Auto-advance first step
        Timer(3000) {
            showNextStep()
        }.apply { 
            isRepeats = false
            start() 
        }
        
        dialog.isVisible = true
    }
    
    private fun createTutorialDialog(step: TutorialStep): JDialog {
        val dialog = JDialog()
        dialog.title = "Test Generation Tutorial"
        dialog.isModal = false
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
        
        val panel = JPanel(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(20, 20, 20, 20)
        panel.preferredSize = Dimension(500, 300)
        
        // Title
        val titleLabel = JLabel(step.title)
        titleLabel.font = titleLabel.font.deriveFont(18f)
        titleLabel.horizontalAlignment = SwingConstants.CENTER
        panel.add(titleLabel, BorderLayout.NORTH)
        
        // Description
        val descArea = JLabel("<html><div style='width: 450px; text-align: center;'>${step.description.replace("\n", "<br>")}</div></html>")
        descArea.font = descArea.font.deriveFont(14f)
        descArea.horizontalAlignment = SwingConstants.CENTER
        panel.add(descArea, BorderLayout.CENTER)
        
        // Action
        val actionLabel = JLabel("→ ${step.action}")
        actionLabel.font = actionLabel.font.deriveFont(16f)
        actionLabel.foreground = Color(0, 120, 215)
        actionLabel.horizontalAlignment = SwingConstants.CENTER
        actionLabel.border = EmptyBorder(10, 0, 0, 0)
        panel.add(actionLabel, BorderLayout.SOUTH)
        
        dialog.contentPane = panel
        dialog.pack()
        dialog.setLocationRelativeTo(null)
        
        return dialog
    }
    
    private fun showCompletionMessage() {
        Messages.showInfoMessage(
            project,
            "Tutorial completed! You're now ready to use the test generation workflow.\n\n" +
            "💡 Tip: Start by running Code Health Review to find methods that need testing.",
            "Tutorial Complete"
        )
    }
}

/**
 * Represents a step in the tutorial
 */
data class TutorialStep(
    val title: String,
    val description: String,
    val action: String
)