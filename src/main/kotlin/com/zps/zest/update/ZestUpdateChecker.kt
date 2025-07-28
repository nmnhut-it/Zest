package com.zps.zest.update

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.io.HttpRequests
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Service responsible for checking plugin updates periodically
 */
@Service
class ZestUpdateChecker : Disposable {
    companion object {
        private val logger = Logger.getInstance(ZestUpdateChecker::class.java)
        const val UPDATE_URL = "https://zest-internal.zingplay.com/static/release/updatePlugins.xml"
        private const val PLUGIN_ID = "com.zps.Zest"
        private const val CHECK_INTERVAL_HOURS = 1L
        private const val NOTIFICATION_GROUP = "Zest Updates"
        
        fun getInstance(): ZestUpdateChecker = ApplicationManager.getApplication().getService(ZestUpdateChecker::class.java)
    }
    
    private val executor = AppExecutorUtil.getAppScheduledExecutorService()
    private var scheduledFuture: ScheduledFuture<*>? = null
    private var lastNotifiedVersion: String? = null
    
    data class UpdateInfo(
        val version: String,
        val downloadUrl: String,
        val description: String,
        val changeNotes: String,
        val vendor: String
    )
    
    init {
        startUpdateChecking()
    }
    
    fun startUpdateChecking() {
        // Cancel any existing scheduled check
        scheduledFuture?.cancel(false)
        
        // Schedule periodic checks
        scheduledFuture = executor.scheduleWithFixedDelay(
            { checkForUpdates() },
            0, // Initial delay - check immediately
            CHECK_INTERVAL_HOURS,
            TimeUnit.HOURS
        )
        
        logger.info("Started Zest update checker with ${CHECK_INTERVAL_HOURS} hour interval")
    }
    
    fun checkForUpdates() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                logger.info("Checking for Zest updates...")
                
                val updateInfo = fetchUpdateInfo()
                if (updateInfo != null && isUpdateAvailable(updateInfo)) {
                    if (updateInfo.version != lastNotifiedVersion) {
                        showUpdateNotification(updateInfo)
                        lastNotifiedVersion = updateInfo.version
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to check for updates", e)
            }
        }
    }
    
    fun fetchUpdateInfo(): UpdateInfo? {
        return try {
            HttpRequests.request(UPDATE_URL)
                .connect { request ->
                    val content = request.readString()
                    parseUpdateXml(content)
                }
        } catch (e: Exception) {
            logger.warn("Failed to fetch update info: ${e.message}")
            null
        }
    }
    
    private fun parseUpdateXml(xmlContent: String): UpdateInfo? {
        try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc: Document = builder.parse(xmlContent.byteInputStream())
            
            doc.documentElement.normalize()
            
            val pluginNodes = doc.getElementsByTagName("plugin")
            for (i in 0 until pluginNodes.length) {
                val pluginElement = pluginNodes.item(i) as Element
                
                if (pluginElement.getAttribute("id") == PLUGIN_ID) {
                    return UpdateInfo(
                        version = pluginElement.getAttribute("version"),
                        downloadUrl = pluginElement.getAttribute("url"),
                        description = extractCDataContent(pluginElement, "description"),
                        changeNotes = extractCDataContent(pluginElement, "change-notes"),
                        vendor = pluginElement.getElementsByTagName("vendor").item(0)?.textContent ?: ""
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to parse update XML", e)
        }
        
        return null
    }
    
    private fun extractCDataContent(element: Element, tagName: String): String {
        val nodes = element.getElementsByTagName(tagName)
        return if (nodes.length > 0) {
            nodes.item(0).textContent.trim()
        } else {
            ""
        }
    }
    
    fun isUpdateAvailable(updateInfo: UpdateInfo): Boolean {
        val currentPlugin = findCurrentPlugin() ?: return false
        val currentVersion = currentPlugin.version
        
        return try {
            compareVersions(updateInfo.version, currentVersion) > 0
        } catch (e: Exception) {
            logger.warn("Failed to compare versions: ${updateInfo.version} vs $currentVersion")
            false
        }
    }
    
    fun findCurrentPlugin(): IdeaPluginDescriptor? {
        return PluginManagerCore.getPlugin(PluginManagerCore.getPluginByClassName(this::class.java.name))
    }
    
    private fun compareVersions(version1: String, version2: String): Int {
        val parts1 = version1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = version2.split(".").map { it.toIntOrNull() ?: 0 }
        
        val maxLength = maxOf(parts1.size, parts2.size)
        
        for (i in 0 until maxLength) {
            val v1 = parts1.getOrNull(i) ?: 0
            val v2 = parts2.getOrNull(i) ?: 0
            
            if (v1 != v2) {
                return v1.compareTo(v2)
            }
        }
        
        return 0
    }
    
    private fun showUpdateNotification(updateInfo: UpdateInfo) {
        val currentVersion = findCurrentPlugin()?.version ?: "Unknown"
        
        val notification = Notification(
            NOTIFICATION_GROUP,
            "Zest Update Available",
            "<html><b>A new version of Zest is available!</b><br/>" +
                "Current version: $currentVersion<br/>" +
                "New version: ${updateInfo.version}<br/><br/>" +
                "${parseHtml(updateInfo.changeNotes)}</html>",
            NotificationType.INFORMATION
        )
        
        notification.addAction(object : NotificationAction("Download Update") {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent, notification: Notification) {
                downloadUpdate(updateInfo, e.project)
                notification.expire()
            }
        })
        
        notification.addAction(object : NotificationAction("Remind Me Later") {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent, notification: Notification) {
                notification.expire()
            }
        })
        
        notification.addAction(object : NotificationAction("Skip This Version") {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent, notification: Notification) {
                // Keep the lastNotifiedVersion to prevent showing again
                notification.expire()
            }
        })
        
        Notifications.Bus.notify(notification)
    }
    
    private fun parseHtml(html: String): String {
        return html
            .replace("<![CDATA[", "")
            .replace("]]>", "")
            .replace("<h3>", "<b>")
            .replace("</h3>", "</b>")
            .trim()
    }
    
    fun downloadUpdate(updateInfo: UpdateInfo, project: Project?) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Downloading Zest Update",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Downloading Zest ${updateInfo.version}..."
                    indicator.isIndeterminate = false
                    
                    val tempFile = Files.createTempFile("Zest-${updateInfo.version}", ".zip")
                    
                    HttpRequests.request(updateInfo.downloadUrl)
                        .connect { request ->
                            val connection = request.connection
                            val contentLength = connection.contentLength
                            
                            connection.inputStream.use { input ->
                                Files.newOutputStream(tempFile).use { output ->
                                    val buffer = ByteArray(8192)
                                    var bytesRead: Int
                                    var totalBytesRead = 0L
                                    
                                    while (input.read(buffer).also { bytesRead = it } != -1) {
                                        output.write(buffer, 0, bytesRead)
                                        totalBytesRead += bytesRead
                                        
                                        if (contentLength > 0) {
                                            indicator.fraction = totalBytesRead.toDouble() / contentLength
                                            indicator.text = "Downloading... ${formatBytes(totalBytesRead)} / ${formatBytes(contentLength.toLong())}"
                                        }
                                    }
                                }
                            }
                        }
                    
                    indicator.text = "Download complete!"
                    
                    // Show save dialog
                    ApplicationManager.getApplication().invokeLater {
                        showSaveDialog(tempFile.toFile(), updateInfo)
                    }
                    
                } catch (e: Exception) {
                    logger.error("Failed to download update", e)
                    ApplicationManager.getApplication().invokeLater {
                        showErrorNotification("Failed to download update: ${e.message}")
                    }
                }
            }
        })
    }
    
    private fun showSaveDialog(tempFile: File, updateInfo: UpdateInfo) {
        val fileChooser = JFileChooser().apply {
            dialogTitle = "Save Zest Plugin Update"
            selectedFile = File("Zest-${updateInfo.version}.zip")
            fileFilter = FileNameExtensionFilter("ZIP files", "zip")
        }
        
        if (fileChooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            try {
                val targetFile = fileChooser.selectedFile
                Files.copy(tempFile.toPath(), targetFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                
                showSuccessNotification(
                    "Update Downloaded",
                    "Zest ${updateInfo.version} has been saved to:<br/>${targetFile.absolutePath}<br/><br/>" +
                    "To install: Settings → Plugins → ⚙️ → Install Plugin from Disk"
                )
            } catch (e: Exception) {
                logger.error("Failed to save update file", e)
                showErrorNotification("Failed to save file: ${e.message}")
            } finally {
                Files.deleteIfExists(tempFile.toPath())
            }
        } else {
            Files.deleteIfExists(tempFile.toPath())
        }
    }
    
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }
    
    private fun showErrorNotification(message: String) {
        Notification(
            NOTIFICATION_GROUP,
            "Update Error",
            message,
            NotificationType.ERROR
        ).notify(null)
    }
    
    private fun showSuccessNotification(title: String, message: String) {
        Notification(
            NOTIFICATION_GROUP,
            title,
            message,
            NotificationType.INFORMATION
        ).notify(null)
    }
    
    override fun dispose() {
        scheduledFuture?.cancel(true)
    }
}