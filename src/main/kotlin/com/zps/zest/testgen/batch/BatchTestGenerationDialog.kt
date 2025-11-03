package com.zps.zest.testgen.batch

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.ui.components.*
import com.intellij.ui.table.JBTable
import com.zps.zest.testgen.model.TestGenerationConfig
import com.zps.zest.testgen.model.TestGenerationRequest
import java.awt.BorderLayout
import java.awt.Dimension
import java.io.File
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.table.DefaultTableModel

/**
 * Dialog for batch test generation on multiple files.
 * Auto-selects public API methods and runs test generation sequentially.
 */
class BatchTestGenerationDialog(private val project: Project) : DialogWrapper(project) {

    private val selectedFiles = mutableListOf<PsiFile>()
    private val fileCheckboxes = mutableMapOf<PsiFile, JCheckBox>()

    private lateinit var tabbedPane: JBTabbedPane
    private lateinit var fileTableModel: DefaultTableModel
    private lateinit var fileTable: JBTable
    private lateinit var selectAllCheckBox: JCheckBox

    private lateinit var excludeAccessorsCheckBox: JCheckBox
    private lateinit var continueOnFailureCheckBox: JCheckBox
    private lateinit var testTypeComboBox: JComboBox<TestGenerationRequest.TestType>

    private lateinit var resultsTableModel: DefaultTableModel
    private lateinit var resultsTable: JBTable
    private lateinit var resultsTextArea: JBTextArea

    private var batchResult: BatchTestGenerationResult? = null

    init {
        title = "Batch Test Generation - Evaluation Mode"
        init()
    }

    override fun createCenterPanel(): JComponent {
        tabbedPane = JBTabbedPane()

        // Tab 1: File Selection
        tabbedPane.addTab("1. Files", createFileSelectionPanel())

        // Tab 2: Configuration
        tabbedPane.addTab("2. Config", createConfigurationPanel())

        // Tab 3: Results (initially disabled)
        tabbedPane.addTab("3. Results", createResultsPanel())
        tabbedPane.setEnabledAt(2, false)

        val panel = JPanel(BorderLayout())
        panel.add(tabbedPane, BorderLayout.CENTER)
        panel.preferredSize = Dimension(900, 600)

        return panel
    }

    private fun createFileSelectionPanel(): JPanel {
        val panel = JPanel(BorderLayout(10, 10))

        // Top: Buttons
        val buttonPanel = JPanel()
        val addDirButton = JButton("Add Directory...")
        val addFilesButton = JButton("Add Files...")
        val removeButton = JButton("Remove Selected")

        addDirButton.addActionListener { addDirectory() }
        addFilesButton.addActionListener { addFiles() }
        removeButton.addActionListener { removeSelected() }

        buttonPanel.add(addDirButton)
        buttonPanel.add(addFilesButton)
        buttonPanel.add(removeButton)

        // Middle: File table
        fileTableModel = object : DefaultTableModel(
            arrayOf("File", "Public Methods", "Path"),
            0
        ) {
            override fun isCellEditable(row: Int, column: Int) = column == 0
        }

        fileTable = JBTable(fileTableModel)
        fileTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)

        // Column 0 is checkbox (handled by renderer)
        fileTable.getColumnModel().getColumn(0).preferredWidth = 300
        fileTable.getColumnModel().getColumn(1).preferredWidth = 100
        fileTable.getColumnModel().getColumn(2).preferredWidth = 400

        // Bottom: Select all + summary
        val bottomPanel = JPanel(BorderLayout())
        selectAllCheckBox = JCheckBox("Select All")
        selectAllCheckBox.addActionListener {
            val selectAll = selectAllCheckBox.isSelected
            fileCheckboxes.values.forEach { it.isSelected = selectAll }
            updateFileTable()
        }

        val summaryLabel = JLabel()
        bottomPanel.add(selectAllCheckBox, BorderLayout.WEST)
        bottomPanel.add(summaryLabel, BorderLayout.EAST)

        panel.add(buttonPanel, BorderLayout.NORTH)
        panel.add(JBScrollPane(fileTable), BorderLayout.CENTER)
        panel.add(bottomPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun createConfigurationPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createEmptyBorder(20, 20, 20, 20)

        // Test Type
        val testTypePanel = JPanel(BorderLayout())
        testTypePanel.add(JLabel("Test Type: "), BorderLayout.WEST)
        testTypeComboBox = JComboBox(TestGenerationRequest.TestType.values())
        testTypeComboBox.selectedItem = TestGenerationRequest.TestType.UNIT_TESTS
        testTypePanel.add(testTypeComboBox, BorderLayout.CENTER)

        // Options
        excludeAccessorsCheckBox = JCheckBox("Exclude simple getters/setters", true)
        continueOnFailureCheckBox = JCheckBox("Continue on failure (don't stop batch)", true)

        val autoSelectLabel = JLabel("<html><b>Auto-select all test scenarios</b> (no manual selection)</html>")

        panel.add(testTypePanel)
        panel.add(Box.createVerticalStrut(15))
        panel.add(excludeAccessorsCheckBox)
        panel.add(continueOnFailureCheckBox)
        panel.add(Box.createVerticalStrut(15))
        panel.add(autoSelectLabel)
        panel.add(Box.createVerticalGlue())

        return panel
    }

    private fun createResultsPanel(): JPanel {
        val panel = JPanel(BorderLayout(10, 10))

        // Results table
        resultsTableModel = object : DefaultTableModel(
            arrayOf("File", "Methods", "Tests", "Status", "Time(s)", "Error"),
            0
        ) {
            override fun isCellEditable(row: Int, column: Int) = false
        }

        resultsTable = JBTable(resultsTableModel)

        // Summary text area
        resultsTextArea = JBTextArea()
        resultsTextArea.isEditable = false
        resultsTextArea.rows = 5

        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT,
            JBScrollPane(resultsTable),
            JBScrollPane(resultsTextArea))
        splitPane.resizeWeight = 0.7

        // Export button
        val exportButton = JButton("Export Results to CSV...")
        exportButton.addActionListener { exportResults() }

        val buttonPanel = JPanel()
        buttonPanel.add(exportButton)

        panel.add(splitPane, BorderLayout.CENTER)
        panel.add(buttonPanel, BorderLayout.SOUTH)

        return panel
    }

    override fun createActions(): Array<Action> {
        val startAction = object : DialogWrapperAction("Start Batch Generation") {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                startBatchGeneration()
            }
        }

        return arrayOf(startAction, cancelAction)
    }

    private fun addDirectory() {
        val descriptor = FileChooserDescriptor(false, true, false, false, false, false)
        descriptor.title = "Select Directory"
        descriptor.description = "All .java files in this directory will be added"

        val selectedDirs = FileChooser.chooseFiles(descriptor, project, null)
        if (selectedDirs.isEmpty()) return

        val psiManager = PsiManager.getInstance(project)
        for (dir in selectedDirs) {
            collectJavaFiles(dir).forEach { vf ->
                psiManager.findFile(vf)?.let { psiFile ->
                    if (PublicApiDetector.hasPublicApi(psiFile, true)) {
                        addFileToList(psiFile)
                    }
                }
            }
        }

        updateFileTable()
    }

    private fun addFiles() {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, true)
        descriptor.title = "Select Java Files"
        descriptor.withFileFilter { it.extension == "java" }

        val selectedFiles = FileChooser.chooseFiles(descriptor, project, null)
        if (selectedFiles.isEmpty()) return

        val psiManager = PsiManager.getInstance(project)
        for (vf in selectedFiles) {
            psiManager.findFile(vf)?.let { psiFile ->
                addFileToList(psiFile)
            }
        }

        updateFileTable()
    }

    private fun collectJavaFiles(dir: VirtualFile): List<VirtualFile> {
        val javaFiles = mutableListOf<VirtualFile>()
        dir.children.forEach { child ->
            if (child.isDirectory) {
                javaFiles.addAll(collectJavaFiles(child))
            } else if (child.extension == "java") {
                javaFiles.add(child)
            }
        }
        return javaFiles
    }

    private fun addFileToList(psiFile: PsiFile) {
        if (!selectedFiles.contains(psiFile)) {
            selectedFiles.add(psiFile)
            val checkbox = JCheckBox()
            checkbox.isSelected = true
            fileCheckboxes[psiFile] = checkbox
        }
    }

    private fun removeSelected() {
        val selectedRows = fileTable.selectedRows.sortedDescending()
        for (row in selectedRows) {
            if (row < selectedFiles.size) {
                val file = selectedFiles[row]
                selectedFiles.removeAt(row)
                fileCheckboxes.remove(file)
            }
        }
        updateFileTable()
    }

    private fun updateFileTable() {
        fileTableModel.rowCount = 0
        for (file in selectedFiles) {
            val methodCount = PublicApiDetector.countPublicMethods(file, excludeAccessorsCheckBox?.isSelected ?: true)
            fileTableModel.addRow(arrayOf(
                file.name,
                methodCount,
                file.virtualFile.path
            ))
        }
    }

    private fun startBatchGeneration() {
        val filesToProcess = selectedFiles.toList()
        if (filesToProcess.isEmpty()) {
            JOptionPane.showMessageDialog(
                contentPanel,
                "Please select at least one file",
                "No Files",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        // Switch to results tab
        tabbedPane.selectedIndex = 2
        tabbedPane.setEnabledAt(2, true)

        // Clear previous results
        resultsTableModel.rowCount = 0
        resultsTextArea.text = "Starting batch generation...\n"

        // Create options
        val options = BatchTestGenerationExecutor.BatchExecutionOptions(
            testTypeComboBox.selectedItem as TestGenerationRequest.TestType,
            excludeAccessorsCheckBox.isSelected,
            continueOnFailureCheckBox.isSelected,
            true // autoSelectAllScenarios
        )

        // Run in background with progress
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Batch Test Generation",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                val executor = BatchTestGenerationExecutor(project)
                val config = TestGenerationConfig() // Use default config

                val future = executor.executeBatch(
                    filesToProcess,
                    config,
                    options,
                    indicator
                ) { progress ->
                    SwingUtilities.invokeLater {
                        updateProgressUI(progress)
                    }
                }

                batchResult = future.get()
            }

            override fun onSuccess() {
                SwingUtilities.invokeLater {
                    displayResults(batchResult)
                }
            }

            override fun onThrowable(error: Throwable) {
                SwingUtilities.invokeLater {
                    resultsTextArea.text += "\n\n❌ Batch execution failed: ${error.message}\n"
                }
            }
        })
    }

    private fun updateProgressUI(progress: BatchTestGenerationExecutor.FileProgressUpdate) {
        resultsTextArea.append("${progress.fileName}: ${progress.status}\n")
    }

    private fun displayResults(result: BatchTestGenerationResult?) {
        if (result == null) return

        resultsTableModel.rowCount = 0

        for (fileResult in result.fileResults) {
            resultsTableModel.addRow(arrayOf(
                fileResult.fileName,
                fileResult.methodCount,
                fileResult.testCount,
                if (fileResult.isSuccess) "✓" else "✗",
                String.format("%.1f", fileResult.durationMs / 1000.0),
                fileResult.errorMessage ?: ""
            ))
        }

        resultsTextArea.text = "\n" + result.summary + "\n"
    }

    private fun exportResults() {
        val result = batchResult ?: return

        val fileChooser = javax.swing.JFileChooser()
        fileChooser.selectedFile = File("batch-test-results.csv")
        fileChooser.fileFilter = FileNameExtensionFilter("CSV files", "csv")

        if (fileChooser.showSaveDialog(contentPanel) == javax.swing.JFileChooser.APPROVE_OPTION) {
            try {
                val csvContent = result.toCSV()
                fileChooser.selectedFile.writeText(csvContent)

                JOptionPane.showMessageDialog(
                    contentPanel,
                    "Results exported to:\n${fileChooser.selectedFile.absolutePath}",
                    "Success",
                    JOptionPane.INFORMATION_MESSAGE
                )
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    contentPanel,
                    "Failed to export: ${e.message}",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        }
    }
}
