package com.zps.zest.update

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

class ShowUpdateInfoAction : AnAction("Check for Updates...", "Show Zest plugin update information and download options", null) {
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val updateChecker = ZestUpdateChecker.getInstance()
        
        // Show dialog
        UpdateInfoDialog(project, updateChecker).show()
    }
    
    private class UpdateInfoDialog(
        private val project: com.intellij.openapi.project.Project?,
        private val updateChecker: ZestUpdateChecker
    ) : DialogWrapper(project) {
        
        init {
            title = "Zest Plugin Updates"
            init()
        }
        
        override fun createCenterPanel(): JComponent {
            val panel = JPanel(BorderLayout())
            
            // Status panel
            val statusPanel = JPanel()
            statusPanel.layout = BoxLayout(statusPanel, BoxLayout.Y_AXIS)
            statusPanel.border = JBUI.Borders.empty(10)
            
            // Current version info
            val currentPlugin = updateChecker.findCurrentPlugin()
            val currentVersion = currentPlugin?.version ?: "Unknown"
            statusPanel.add(JBLabel("<html><b>Current Version:</b> $currentVersion</html>"))
            statusPanel.add(Box.createVerticalStrut(10))
            
            // Update URL
            statusPanel.add(JBLabel("<html><b>Update Server:</b></html>"))
            val urlField = JBTextArea(ZestUpdateChecker.UPDATE_URL)
            urlField.isEditable = false
            urlField.lineWrap = true
            urlField.wrapStyleWord = true
            urlField.border = JBUI.Borders.empty(5)
            statusPanel.add(urlField)
            statusPanel.add(Box.createVerticalStrut(10))
            
            // Loading indicator
            val loadingLabel = JBLabel("Checking for updates...")
            statusPanel.add(loadingLabel)
            
            panel.add(statusPanel, BorderLayout.NORTH)
            
            // Update info panel (will be populated after check)
            val updatePanel = JPanel(BorderLayout())
            updatePanel.border = JBUI.Borders.empty(10)
            
            // Check for updates in background
            SwingUtilities.invokeLater {
                try {
                    val updateInfo = updateChecker.fetchUpdateInfo()
                    SwingUtilities.invokeLater {
                        loadingLabel.isVisible = false
                        
                        if (updateInfo != null) {
                            val isNewer = updateChecker.isUpdateAvailable(updateInfo)
                            
                            val infoPanel = JPanel()
                            infoPanel.layout = BoxLayout(infoPanel, BoxLayout.Y_AXIS)
                            
                            // Version info
                            infoPanel.add(JBLabel("<html><b>Latest Version:</b> ${updateInfo.version} ${if (isNewer) "(Update Available!)" else "(Up to date)"}</html>"))
                            infoPanel.add(Box.createVerticalStrut(10))
                            
                            // Vendor
                            if (updateInfo.vendor.isNotEmpty()) {
                                infoPanel.add(JBLabel("<html><b>Vendor:</b> ${updateInfo.vendor}</html>"))
                                infoPanel.add(Box.createVerticalStrut(10))
                            }
                            
                            // Download URL
                            infoPanel.add(JBLabel("<html><b>Download URL:</b></html>"))
                            val downloadUrlField = JBTextArea(updateInfo.downloadUrl)
                            downloadUrlField.isEditable = false
                            downloadUrlField.lineWrap = true
                            downloadUrlField.wrapStyleWord = true
                            downloadUrlField.border = JBUI.Borders.empty(5)
                            infoPanel.add(downloadUrlField)
                            infoPanel.add(Box.createVerticalStrut(10))
                            
                            // Description
                            if (updateInfo.description.isNotEmpty()) {
                                infoPanel.add(JBLabel("<html><b>Description:</b></html>"))
                                infoPanel.add(Box.createVerticalStrut(5))
                                val descArea = JBTextArea(parseHtmlToPlain(updateInfo.description))
                                descArea.isEditable = false
                                descArea.lineWrap = true
                                descArea.wrapStyleWord = true
                                descArea.border = JBUI.Borders.empty(5)
                                infoPanel.add(JBScrollPane(descArea).apply {
                                    preferredSize = Dimension(500, 100)
                                })
                                infoPanel.add(Box.createVerticalStrut(10))
                            }
                            
                            // Change notes
                            if (updateInfo.changeNotes.isNotEmpty()) {
                                infoPanel.add(JBLabel("<html><b>Change Notes:</b></html>"))
                                infoPanel.add(Box.createVerticalStrut(5))
                                val notesArea = JBTextArea(parseHtmlToPlain(updateInfo.changeNotes))
                                notesArea.isEditable = false
                                notesArea.lineWrap = true
                                notesArea.wrapStyleWord = true
                                notesArea.border = JBUI.Borders.empty(5)
                                infoPanel.add(JBScrollPane(notesArea).apply {
                                    preferredSize = Dimension(500, 150)
                                })
                            }
                            
                            // Download button
                            if (isNewer) {
                                infoPanel.add(Box.createVerticalStrut(15))
                                val downloadButton = JButton("Download Update")
                                downloadButton.addActionListener {
                                    updateChecker.downloadUpdate(updateInfo, project)
                                    close(OK_EXIT_CODE)
                                }
                                val buttonPanel = JPanel()
                                buttonPanel.add(downloadButton)
                                infoPanel.add(buttonPanel)
                            }
                            
                            updatePanel.add(infoPanel, BorderLayout.CENTER)
                        } else {
                            updatePanel.add(JBLabel("<html><i>Unable to fetch update information. Please check your network connection.</i></html>"), BorderLayout.CENTER)
                        }
                        
                        updatePanel.revalidate()
                        updatePanel.repaint()
                    }
                } catch (e: Exception) {
                    SwingUtilities.invokeLater {
                        loadingLabel.text = "Error checking for updates: ${e.message}"
                    }
                }
            }
            
            panel.add(updatePanel, BorderLayout.CENTER)
            panel.preferredSize = Dimension(600, 500)
            
            return panel
        }
        
        private fun parseHtmlToPlain(html: String): String {
            return html
                .replace("<![CDATA[", "")
                .replace("]]>", "")
                .replace("<br>", "\n")
                .replace("<br/>", "\n")
                .replace("<p>", "\n")
                .replace("</p>", "\n")
                .replace(Regex("<[^>]+>"), "")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .trim()
        }
        
        override fun createActions(): Array<Action> {
            return arrayOf(okAction)
        }
    }
}