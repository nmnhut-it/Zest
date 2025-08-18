package com.zps.zest.testgen.ui.panels

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.zps.zest.testgen.ui.dialogs.ContextAnalysisDialog
import com.zps.zest.testgen.ui.model.ContextDisplayData
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Panel that displays context files in a tree structure with analysis links.
 * Provides a clean overview of analyzed files with drill-down capability.
 */
class ContextDisplayPanel(private val project: Project) : JPanel(BorderLayout()) {
    
    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode("Context"))
    private val tree = JTree(treeModel)
    private val fileDataMap = mutableMapOf<String, ContextDisplayData>()
    private val statusLabel = JBLabel("No files analyzed yet")
    
    init {
        setupUI()
    }
    
    private fun setupUI() {
        // Header panel
        val headerPanel = JPanel(BorderLayout())
        headerPanel.border = EmptyBorder(5, 10, 5, 10)
        headerPanel.background = UIUtil.getPanelBackground()
        
        val titleLabel = JBLabel("📁 Context Analysis")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 14f)
        headerPanel.add(titleLabel, BorderLayout.WEST)
        
        statusLabel.foreground = UIUtil.getContextHelpForeground()
        headerPanel.add(statusLabel, BorderLayout.EAST)
        
        add(headerPanel, BorderLayout.NORTH)
        
        // Tree setup
        tree.isRootVisible = true
        tree.cellRenderer = ContextTreeCellRenderer()
        tree.rowHeight = 24
        tree.border = EmptyBorder(5, 5, 5, 5)
        
        // Add mouse listener for double-click to view analysis
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val path = tree.getPathForLocation(e.x, e.y) ?: return
                    val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    val userObject = node.userObject as? FileNodeData ?: return
                    
                    // Open analysis dialog on double-click if analysis is available
                    if (userObject.data.hasAnalysis()) {
                        showAnalysisDialog(userObject.data)
                    }
                }
            }
            
            override fun mousePressed(e: MouseEvent) {
                handlePopup(e)
            }
            
            override fun mouseReleased(e: MouseEvent) {
                handlePopup(e)
            }
            
            private fun handlePopup(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    val path = tree.getPathForLocation(e.x, e.y) ?: return
                    val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    val userObject = node.userObject as? FileNodeData ?: return
                    
                    // Select the node
                    tree.selectionPath = path
                    
                    // Show context menu
                    val popupMenu = JPopupMenu()
                    
                    if (userObject.data.hasAnalysis()) {
                        val viewAnalysisItem = JMenuItem("View Analysis")
                        viewAnalysisItem.addActionListener {
                            showAnalysisDialog(userObject.data)
                        }
                        popupMenu.add(viewAnalysisItem)
                    }
                    
                    val copyPathItem = JMenuItem("Copy File Path")
                    copyPathItem.addActionListener {
                        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                        clipboard.setContents(StringSelection(userObject.data.filePath), null)
                    }
                    popupMenu.add(copyPathItem)
                    
                    if (popupMenu.componentCount > 0) {
                        popupMenu.show(tree, e.x, e.y)
                    }
                }
            }
        })
        
        val scrollPane = JBScrollPane(tree)
        scrollPane.border = BorderFactory.createLineBorder(UIUtil.getBoundsColor())
        add(scrollPane, BorderLayout.CENTER)
        
        // Bottom panel with summary
        val bottomPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        bottomPanel.border = EmptyBorder(5, 10, 5, 10)
        bottomPanel.background = UIUtil.getPanelBackground()
        
        val refreshButton = JButton("Refresh")
        refreshButton.addActionListener { refreshDisplay() }
        bottomPanel.add(refreshButton)
        
        add(bottomPanel, BorderLayout.SOUTH)
    }
    
    /**
     * Add a file to the context display
     */
    fun addFile(data: ContextDisplayData) {
        SwingUtilities.invokeLater {
            fileDataMap[data.filePath] = data
            updateTree()
            updateStatus()
        }
    }
    
    /**
     * Update file status
     */
    fun updateFile(data: ContextDisplayData) {
        SwingUtilities.invokeLater {
            fileDataMap[data.filePath] = data
            updateTree()
            updateStatus()
        }
    }
    
    /**
     * Clear all files
     */
    fun clear() {
        SwingUtilities.invokeLater {
            fileDataMap.clear()
            val root = treeModel.root as DefaultMutableTreeNode
            root.removeAllChildren()
            treeModel.reload()
            statusLabel.text = "No files analyzed yet"
        }
    }
    
    /**
     * Get all context files for saving
     */
    fun getContextFiles(): List<ContextDisplayData> {
        return fileDataMap.values.toList()
    }
    
    /**
     * Update the tree structure
     */
    private fun updateTree() {
        val root = treeModel.root as DefaultMutableTreeNode
        root.removeAllChildren()
        
        // Group files by directory
        val filesByDir = fileDataMap.values.groupBy { 
            it.filePath.substringBeforeLast('/', ".")
        }
        
        filesByDir.forEach { (dir, files) ->
            val dirNode = if (dir == ".") {
                root
            } else {
                val dirName = dir.substringAfterLast('/')
                DefaultMutableTreeNode(dirName).also { root.add(it) }
            }
            
            files.sortedBy { it.fileName }.forEach { file ->
                val fileNode = DefaultMutableTreeNode(FileNodeData(file))
                dirNode.add(fileNode)
            }
        }
        
        treeModel.reload()
        
        // Expand all nodes
        for (i in 0 until tree.rowCount) {
            tree.expandRow(i)
        }
    }
    
    /**
     * Update status label
     */
    private fun updateStatus() {
        val total = fileDataMap.size
        val analyzed = fileDataMap.values.count { 
            it.status == ContextDisplayData.AnalysisStatus.COMPLETED 
        }
        val analyzing = fileDataMap.values.count { 
            it.status == ContextDisplayData.AnalysisStatus.ANALYZING 
        }
        
        statusLabel.text = when {
            analyzing > 0 -> "$analyzed/$total analyzed, $analyzing in progress..."
            total > 0 -> "$analyzed/$total files analyzed"
            else -> "No files analyzed yet"
        }
    }
    
    /**
     * Show analysis dialog for a file
     */
    private fun showAnalysisDialog(data: ContextDisplayData) {
        val dialog = ContextAnalysisDialog(project, data)
        dialog.show()
    }
    
    /**
     * Refresh the display
     */
    private fun refreshDisplay() {
        updateTree()
        updateStatus()
    }
    
    /**
     * Data class for tree nodes
     */
    private data class FileNodeData(val data: ContextDisplayData)
    
    /**
     * Custom tree cell renderer
     */
    private inner class ContextTreeCellRenderer : DefaultTreeCellRenderer() {
        
        init {
            preferredSize = Dimension(400, 24)
        }
        
        override fun getTreeCellRendererComponent(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ): Component {
            val component = super.getTreeCellRendererComponent(
                tree, value, selected, expanded, leaf, row, hasFocus
            )
            
            if (value is DefaultMutableTreeNode) {
                when (val userObject = value.userObject) {
                    is FileNodeData -> {
                        val data = userObject.data
                        val panel = JPanel(BorderLayout())
                        panel.isOpaque = false
                        
                        // File info with status
                        val fileLabel = JBLabel("${data.getStatusIcon()} ${data.fileName}")
                        fileLabel.toolTipText = if (data.hasAnalysis()) {
                            "${data.filePath}\nDouble-click to view analysis"
                        } else {
                            data.filePath
                        }
                        panel.add(fileLabel, BorderLayout.WEST)
                        
                        // Analysis link or status
                        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0))
                        rightPanel.isOpaque = false
                        
                        if (data.hasAnalysis()) {
                            val linkLabel = JBLabel("<html><a href='#'>View Analysis</a> (double-click)</html>")
                            linkLabel.foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
                            linkLabel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                            linkLabel.toolTipText = "Double-click to view full analysis, or right-click for options"
                            rightPanel.add(linkLabel)
                        } else {
                            val statusLabel = JBLabel(data.getStatusText())
                            statusLabel.foreground = UIUtil.getContextHelpForeground()
                            rightPanel.add(statusLabel)
                        }
                        
                        // Summary info
                        if (!data.summary.isBlank() && data.status == ContextDisplayData.AnalysisStatus.COMPLETED) {
                            val summaryLabel = JBLabel(data.summary)
                            summaryLabel.foreground = UIUtil.getContextHelpForeground()
                            summaryLabel.font = summaryLabel.font.deriveFont(11f)
                            rightPanel.add(summaryLabel)
                        }
                        
                        panel.add(rightPanel, BorderLayout.EAST)
                        
                        return panel
                    }
                    is String -> {
                        // Directory node
                        text = "📂 $userObject"
                    }
                }
            }
            
            return component
        }
    }
}