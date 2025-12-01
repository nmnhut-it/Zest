package com.zps.zest.testgen.ui.dialogs

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.zps.zest.testgen.model.UserQuestion
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.*

/**
 * Dialog for displaying user questions from the LLM during test planning.
 * Similar to Claude Code's AskUserQuestion functionality.
 */
class UserQuestionDialog(
    project: Project?,
    private val question: UserQuestion
) : DialogWrapper(project) {

    private val optionButtonGroup = ButtonGroup()
    private val selectedOptions = mutableSetOf<String>()
    private val checkBoxes = mutableMapOf<String, JCheckBox>()
    private val radioButtons = mutableMapOf<String, JRadioButton>()
    private val freeTextArea: JTextArea = JTextArea(5, 40)

    init {
        title = question.header
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(10, 10))
        panel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

        // Question text
        val questionLabel = JLabel("<html><body style='width: 400px'>${question.questionText}</body></html>")
        questionLabel.font = questionLabel.font.deriveFont(14f)
        panel.add(questionLabel, BorderLayout.NORTH)

        // Options panel
        when (question.type) {
            UserQuestion.QuestionType.SINGLE_CHOICE -> {
                panel.add(createSingleChoicePanel(), BorderLayout.CENTER)
            }
            UserQuestion.QuestionType.MULTI_CHOICE -> {
                panel.add(createMultiChoicePanel(), BorderLayout.CENTER)
            }
            UserQuestion.QuestionType.FREE_TEXT -> {
                panel.add(createFreeTextPanel(), BorderLayout.CENTER)
            }
        }

        panel.preferredSize = Dimension(500, 300)
        return panel
    }

    private fun createSingleChoicePanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createTitledBorder("Choose one option:")

        for (option in question.options) {
            val radioButton = JRadioButton(option.label)
            radioButton.toolTipText = option.description
            optionButtonGroup.add(radioButton)
            radioButtons[option.label] = radioButton

            val optionPanel = JPanel(BorderLayout(5, 5))
            optionPanel.add(radioButton, BorderLayout.NORTH)

            val descLabel = JLabel("<html><body style='width: 380px; font-size: 11px; color: gray;'>${option.description}</body></html>")
            optionPanel.add(descLabel, BorderLayout.CENTER)

            panel.add(optionPanel)
            panel.add(Box.createVerticalStrut(10))
        }

        // Add "Other" option for free text
        val otherRadio = JRadioButton("Other (specify)")
        val otherTextField = JTextField(30)
        otherTextField.isEnabled = false

        otherRadio.addActionListener {
            otherTextField.isEnabled = otherRadio.isSelected
        }

        optionButtonGroup.add(otherRadio)

        val otherPanel = JPanel(BorderLayout(5, 5))
        otherPanel.add(otherRadio, BorderLayout.WEST)
        otherPanel.add(otherTextField, BorderLayout.CENTER)

        panel.add(otherPanel)

        return panel
    }

    private fun createMultiChoicePanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createTitledBorder("Choose one or more options:")

        for (option in question.options) {
            val checkBox = JCheckBox(option.label)
            checkBox.toolTipText = option.description
            checkBoxes[option.label] = checkBox

            checkBox.addActionListener {
                if (checkBox.isSelected) {
                    selectedOptions.add(option.label)
                } else {
                    selectedOptions.remove(option.label)
                }
            }

            val optionPanel = JPanel(BorderLayout(5, 5))
            optionPanel.add(checkBox, BorderLayout.NORTH)

            val descLabel = JLabel("<html><body style='width: 380px; font-size: 11px; color: gray;'>${option.description}</body></html>")
            optionPanel.add(descLabel, BorderLayout.CENTER)

            panel.add(optionPanel)
            panel.add(Box.createVerticalStrut(10))
        }

        // Add "Other" option for free text
        val otherCheckBox = JCheckBox("Other (specify)")
        val otherTextField = JTextField(30)
        otherTextField.isEnabled = false

        otherCheckBox.addActionListener {
            otherTextField.isEnabled = otherCheckBox.isSelected
        }

        val otherPanel = JPanel(BorderLayout(5, 5))
        otherPanel.add(otherCheckBox, BorderLayout.WEST)
        otherPanel.add(otherTextField, BorderLayout.CENTER)

        panel.add(otherPanel)

        return panel
    }

    private fun createFreeTextPanel(): JPanel {
        val panel = JPanel(BorderLayout(10, 10))
        panel.border = BorderFactory.createTitledBorder("Your answer:")

        freeTextArea.lineWrap = true
        freeTextArea.wrapStyleWord = true
        val scrollPane = JScrollPane(freeTextArea)
        scrollPane.preferredSize = Dimension(450, 150)

        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    override fun doOKAction() {
        // Collect answers and set on the question object
        when (question.type) {
            UserQuestion.QuestionType.SINGLE_CHOICE -> {
                val selectedButton = radioButtons.entries.find { it.value.isSelected }
                if (selectedButton != null) {
                    question.setSelectedOptions(listOf(selectedButton.key))
                } else {
                    // Check "Other" option
                    val otherRadio = optionButtonGroup.elements.toList().lastOrNull() as? JRadioButton
                    if (otherRadio?.isSelected == true) {
                        val otherTextField = (otherRadio.parent as? JPanel)?.components
                            ?.filterIsInstance<JTextField>()?.firstOrNull()
                        if (otherTextField != null && otherTextField.text.isNotBlank()) {
                            question.setSelectedOptions(listOf(otherTextField.text.trim()))
                        } else {
                            JOptionPane.showMessageDialog(
                                contentPane,
                                "Please specify your answer in the 'Other' field",
                                "Input Required",
                                JOptionPane.WARNING_MESSAGE
                            )
                            return
                        }
                    } else {
                        JOptionPane.showMessageDialog(
                            contentPane,
                            "Please select an option",
                            "Selection Required",
                            JOptionPane.WARNING_MESSAGE
                        )
                        return
                    }
                }
            }
            UserQuestion.QuestionType.MULTI_CHOICE -> {
                if (selectedOptions.isEmpty()) {
                    JOptionPane.showMessageDialog(
                        contentPane,
                        "Please select at least one option",
                        "Selection Required",
                        JOptionPane.WARNING_MESSAGE
                    )
                    return
                }
                question.setSelectedOptions(selectedOptions.toList())
            }
            UserQuestion.QuestionType.FREE_TEXT -> {
                val answer = freeTextArea.text.trim()
                if (answer.isBlank()) {
                    JOptionPane.showMessageDialog(
                        contentPane,
                        "Please provide an answer",
                        "Input Required",
                        JOptionPane.WARNING_MESSAGE
                    )
                    return
                }
                question.setFreeTextAnswer(answer)
            }
        }

        super.doOKAction()
    }

    companion object {
        /**
         * Show the dialog and wait for user response.
         * Returns the answered question, or null if user cancelled.
         */
        fun showAndGetAnswer(project: Project?, question: UserQuestion): UserQuestion? {
            val dialog = UserQuestionDialog(project, question)
            return if (dialog.showAndGet()) {
                question
            } else {
                null
            }
        }
    }
}
