package com.zps.zest.testgen.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.*
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Dialog for selecting which methods to generate tests for
 */
class MethodSelectionDialog(
    project: Project,
    private val psiFile: PsiFile,
    private val preselectedElement: PsiElement? = null
) : DialogWrapper(project) {
    
    private val methodCheckBoxes = mutableMapOf<PsiMethod, JBCheckBox>()
    private var selectAllCheckBox: JBCheckBox? = null
    
    init {
        title = "Select Methods to Test"
        init()
    }
    
    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.preferredSize = Dimension(600, 500)
        
        // Header panel with description
        val headerPanel = JPanel(BorderLayout())
        headerPanel.background = UIUtil.getPanelBackground()
        headerPanel.border = EmptyBorder(10, 15, 10, 15)
        
        val titleLabel = JBLabel("Select Methods for Test Generation")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 14f)
        headerPanel.add(titleLabel, BorderLayout.NORTH)
        
        val descLabel = JBLabel("Choose which methods you want to generate tests for:")
        descLabel.foreground = UIUtil.getContextHelpForeground()
        headerPanel.add(descLabel, BorderLayout.CENTER)
        
        mainPanel.add(headerPanel, BorderLayout.NORTH)
        
        // Methods list panel
        val methodsPanel = JPanel()
        methodsPanel.layout = BoxLayout(methodsPanel, BoxLayout.Y_AXIS)
        methodsPanel.background = UIUtil.getPanelBackground()
        methodsPanel.border = EmptyBorder(10, 15, 10, 15)
        
        // Add "Select All" checkbox
        selectAllCheckBox = JBCheckBox("Select All")
        selectAllCheckBox!!.addActionListener {
            val selectAll = selectAllCheckBox!!.isSelected
            methodCheckBoxes.values.forEach { it.isSelected = selectAll }
        }
        methodsPanel.add(selectAllCheckBox!!)
        methodsPanel.add(Box.createVerticalStrut(10))
        methodsPanel.add(JSeparator())
        methodsPanel.add(Box.createVerticalStrut(10))
        
        // Find all methods in the file
        val methods = findMethods(psiFile)
        
        if (methods.isEmpty()) {
            val noMethodsLabel = JBLabel("No testable methods found in this file")
            noMethodsLabel.foreground = Color.RED
            methodsPanel.add(noMethodsLabel)
        } else {
            // Group methods by class
            val methodsByClass = methods.groupBy { it.containingClass?.name ?: "Unknown" }
            
            methodsByClass.forEach { (className, classMethods) ->
                // Add class label
                val classLabel = JBLabel("Class: $className")
                classLabel.font = classLabel.font.deriveFont(Font.BOLD, 12f)
                classLabel.border = EmptyBorder(5, 0, 5, 0)
                methodsPanel.add(classLabel)
                
                // Add methods for this class
                classMethods.forEach { method ->
                    val methodPanel = createMethodPanel(method)
                    methodsPanel.add(methodPanel)
                    methodsPanel.add(Box.createVerticalStrut(5))
                }
                
                methodsPanel.add(Box.createVerticalStrut(10))
            }
        }
        
        val scrollPane = JBScrollPane(methodsPanel)
        scrollPane.border = JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1)
        mainPanel.add(scrollPane, BorderLayout.CENTER)
        
        // Footer with statistics
        val footerPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        footerPanel.background = UIUtil.getPanelBackground()
        footerPanel.border = EmptyBorder(10, 15, 10, 15)
        
        val statsLabel = JBLabel("${methods.size} methods found")
        statsLabel.foreground = UIUtil.getContextHelpForeground()
        footerPanel.add(statsLabel)
        
        mainPanel.add(footerPanel, BorderLayout.SOUTH)
        
        // Update select all state
        updateSelectAllState()
        
        return mainPanel
    }
    
    private fun createMethodPanel(method: PsiMethod): JComponent {
        val panel = JPanel(BorderLayout())
        panel.background = UIUtil.getPanelBackground()
        panel.border = EmptyBorder(5, 20, 5, 10)
        
        // Create checkbox with method signature
        val methodSignature = buildMethodSignature(method)
        val checkBox = JBCheckBox(methodSignature)
        
        // Preselect if this method contains the selected element
        if (preselectedElement != null && isMethodContainsElement(method, preselectedElement)) {
            checkBox.isSelected = true
        } else if (isTestableMethod(method)) {
            // Auto-select public methods by default
            checkBox.isSelected = method.modifierList.hasModifierProperty(PsiModifier.PUBLIC)
        }
        
        checkBox.addActionListener {
            updateSelectAllState()
        }
        
        methodCheckBoxes[method] = checkBox
        panel.add(checkBox, BorderLayout.WEST)
        
        // Add method info label
        val infoPanel = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0))
        infoPanel.background = UIUtil.getPanelBackground()
        
        // Add visibility badge
        val visibility = when {
            method.modifierList.hasModifierProperty(PsiModifier.PUBLIC) -> "public"
            method.modifierList.hasModifierProperty(PsiModifier.PROTECTED) -> "protected"
            method.modifierList.hasModifierProperty(PsiModifier.PRIVATE) -> "private"
            else -> "package"
        }
        
        val visibilityLabel = JBLabel(visibility)
        visibilityLabel.foreground = when (visibility) {
            "public" -> Color(76, 175, 80)
            "protected" -> Color(255, 152, 0)
            "private" -> Color(244, 67, 54)
            else -> UIUtil.getLabelForeground()
        }
        visibilityLabel.font = visibilityLabel.font.deriveFont(10f)
        infoPanel.add(visibilityLabel)
        
        // Add complexity indicator
        val complexity = estimateMethodComplexity(method)
        if (complexity > 0) {
            val complexityLabel = JBLabel("Complexity: $complexity")
            complexityLabel.foreground = when {
                complexity <= 5 -> Color(76, 175, 80)
                complexity <= 10 -> Color(255, 152, 0)
                else -> Color(244, 67, 54)
            }
            complexityLabel.font = complexityLabel.font.deriveFont(10f)
            infoPanel.add(complexityLabel)
        }
        
        panel.add(infoPanel, BorderLayout.CENTER)
        
        return panel
    }
    
    private fun buildMethodSignature(method: PsiMethod): String {
        val params = method.parameterList.parameters.joinToString(", ") { param ->
            "${param.type.presentableText} ${param.name}"
        }
        
        val returnType = method.returnType?.presentableText ?: "void"
        return "${method.name}($params): $returnType"
    }
    
    private fun findMethods(psiFile: PsiFile): List<PsiMethod> {
        val methods = mutableListOf<PsiMethod>()
        
        psiFile.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element is PsiMethod && isTestableMethod(element)) {
                    methods.add(element)
                }
                super.visitElement(element)
            }
        })
        
        return methods
    }
    
    private fun isTestableMethod(method: PsiMethod): Boolean {
        // Skip constructors, getters/setters, and already test methods
        return !method.isConstructor &&
               !method.name.startsWith("get") &&
               !method.name.startsWith("set") &&
               !method.name.startsWith("is") &&
               !method.name.startsWith("test") &&
               !method.modifierList.annotations.any { 
                   it.qualifiedName?.contains("Test") == true 
               }
    }
    
    private fun isMethodContainsElement(method: PsiMethod, element: PsiElement): Boolean {
        var current = element
        while (current != null && current !is PsiFile) {
            if (current == method) {
                return true
            }
            current = current.parent
        }
        return false
    }
    
    private fun estimateMethodComplexity(method: PsiMethod): Int {
        var complexity = 1
        
        method.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                when (element) {
                    is PsiIfStatement -> complexity++
                    is PsiForStatement -> complexity++
                    is PsiWhileStatement -> complexity++
                    is PsiSwitchStatement -> complexity++
                    is PsiTryStatement -> complexity++
                    is PsiConditionalExpression -> complexity++
                }
                super.visitElement(element)
            }
        })
        
        return complexity
    }
    
    private fun updateSelectAllState() {
        selectAllCheckBox?.let { selectAll ->
            val allSelected = methodCheckBoxes.values.all { it.isSelected }
            val noneSelected = methodCheckBoxes.values.none { it.isSelected }
            
            selectAll.isSelected = allSelected
            selectAll.isEnabled = methodCheckBoxes.isNotEmpty()
        }
    }
    
    fun getSelectedMethods(): List<PsiMethod> {
        return methodCheckBoxes.entries
            .filter { it.value.isSelected }
            .map { it.key }
    }
    
    override fun createActions(): Array<Action> {
        return arrayOf(
            okAction.apply { putValue(Action.NAME, "Generate Tests") },
            cancelAction
        )
    }
    
    override fun doOKAction() {
        if (getSelectedMethods().isEmpty()) {
            JOptionPane.showMessageDialog(
                contentPanel,
                "Please select at least one method to test",
                "No Methods Selected",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }
        super.doOKAction()
    }
}