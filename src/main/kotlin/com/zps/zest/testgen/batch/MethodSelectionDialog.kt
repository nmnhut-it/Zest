package com.zps.zest.testgen.batch

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*

/**
 * Dialog for selecting specific methods from a file for batch test generation.
 * Displays all public methods with checkboxes allowing granular selection.
 */
class MethodSelectionDialog(
    private val project: Project,
    private val file: VirtualFile,
    private val excludeSimpleAccessors: Boolean,
    initialSelection: Set<String>
) : DialogWrapper(project) {

    private val methodCheckboxes = mutableMapOf<String, JBCheckBox>()
    private val methodInfos: List<PublicApiDetector.MethodInfo>
    private var selectedMethods: Set<String> = initialSelection

    init {
        title = "Select Methods - ${file.name}"

        // Get method details from file
        val psiFile = PsiManager.getInstance(project).findFile(file)
        methodInfos = if (psiFile != null) {
            PublicApiDetector.getMethodDetails(psiFile, excludeSimpleAccessors)
        } else {
            emptyList()
        }

        init()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())

        // Top panel with action buttons
        val topPanel = createActionButtonsPanel()
        mainPanel.add(topPanel, BorderLayout.NORTH)

        // Center panel with method list
        val methodsPanel = createMethodsPanel()
        val scrollPane = JBScrollPane(methodsPanel)
        scrollPane.preferredSize = java.awt.Dimension(600, 400)
        mainPanel.add(scrollPane, BorderLayout.CENTER)

        // Bottom panel with selection count
        val bottomPanel = createSelectionCountPanel()
        mainPanel.add(bottomPanel, BorderLayout.SOUTH)

        return mainPanel
    }

    private fun createActionButtonsPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.X_AXIS)

        val selectAllButton = JButton("Select All")
        selectAllButton.addActionListener {
            methodCheckboxes.values.forEach { it.isSelected = true }
            updateSelectionCount()
        }

        val deselectAllButton = JButton("Deselect All")
        deselectAllButton.addActionListener {
            methodCheckboxes.values.forEach { it.isSelected = false }
            updateSelectionCount()
        }

        val selectBusinessLogicButton = JButton("Select Business Logic Only")
        selectBusinessLogicButton.addActionListener {
            methodCheckboxes.forEach { (methodName, checkbox) ->
                val methodInfo = methodInfos.find { it.name == methodName }
                checkbox.isSelected = methodInfo != null && !methodInfo.isSimpleAccessor
            }
            updateSelectionCount()
        }

        panel.add(selectAllButton)
        panel.add(Box.createHorizontalStrut(5))
        panel.add(deselectAllButton)
        panel.add(Box.createHorizontalStrut(5))
        panel.add(selectBusinessLogicButton)
        panel.add(Box.createHorizontalGlue())

        return panel
    }

    private fun createMethodsPanel(): JPanel {
        val panel = JPanel()
        panel.layout = GridBagLayout()
        val gbc = GridBagConstraints()
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.anchor = GridBagConstraints.NORTHWEST
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        gbc.insets = Insets(5, 10, 5, 10)

        if (methodInfos.isEmpty()) {
            val noMethodsLabel = JBLabel("No public methods found in this file.")
            panel.add(noMethodsLabel, gbc)
            return panel
        }

        for (methodInfo in methodInfos) {
            // Checkbox with method signature
            val checkbox = JBCheckBox(methodInfo.signature)
            checkbox.isSelected = selectedMethods.contains(methodInfo.name)
            methodCheckboxes[methodInfo.name] = checkbox

            checkbox.addActionListener {
                updateSelectionCount()
            }

            gbc.gridy++
            panel.add(checkbox, gbc)

            // Method documentation/description (if available)
            if (methodInfo.documentation.isNotBlank()) {
                val docLabel = JBLabel("<html><i>${escapeHtml(methodInfo.documentation)}</i></html>")
                docLabel.foreground = java.awt.Color.GRAY
                gbc.gridy++
                gbc.insets = Insets(0, 30, 5, 10)
                panel.add(docLabel, gbc)
                gbc.insets = Insets(5, 10, 5, 10)
            }

            // Indicator for simple accessor
            if (methodInfo.isSimpleAccessor) {
                val accessorLabel = JBLabel("<html><font color='orange'>[Simple Accessor]</font></html>")
                gbc.gridy++
                gbc.insets = Insets(0, 30, 10, 10)
                panel.add(accessorLabel, gbc)
                gbc.insets = Insets(5, 10, 5, 10)
            }
        }

        // Add filler at the bottom to push everything to the top
        gbc.gridy++
        gbc.weighty = 1.0
        panel.add(Box.createVerticalGlue(), gbc)

        return panel
    }

    private var selectionCountLabel: JBLabel? = null

    private fun createSelectionCountPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.X_AXIS)

        selectionCountLabel = JBLabel()
        updateSelectionCount()

        panel.add(Box.createHorizontalStrut(10))
        panel.add(selectionCountLabel!!)
        panel.add(Box.createHorizontalGlue())

        return panel
    }

    private fun updateSelectionCount() {
        val selectedCount = methodCheckboxes.values.count { it.isSelected }
        val totalCount = methodCheckboxes.size
        selectionCountLabel?.text = "Selected: $selectedCount / $totalCount methods"
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
            .take(200) // Limit length
    }

    override fun doOKAction() {
        // Collect selected method names
        selectedMethods = methodCheckboxes
            .filter { it.value.isSelected }
            .keys
            .toSet()

        super.doOKAction()
    }

    /**
     * Get the set of selected method names after dialog is closed.
     */
    fun getSelectedMethodNames(): Set<String> {
        return selectedMethods
    }
}
