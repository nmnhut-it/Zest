package com.zps.zest.update

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.ide.BrowserUtil
import com.zps.zest.settings.ZestGlobalSettings
import java.io.File
import java.net.URL

/**
 * Detects plugin version changes and shows update notification
 */
class VersionUpdateNotifier : ProjectActivity {

    companion object {
        private const val CURRENT_VERSION = "1.9.899"
        private const val NOTIFICATION_GROUP = "Zest Updates"

        fun showTestGenerationOverview(project: Project) {
            // Try to open the HTML file directly in browser
            try {
                // Get the HTML content from resources
                val resourceUrl = VersionUpdateNotifier::class.java.getResource("/html/test-generation-overview.html")
                if (resourceUrl != null) {
                    // Create a temporary file
                    val tempFile = File.createTempFile("zest-test-generation-", ".html")
                    tempFile.deleteOnExit()

                    // Copy resource to temp file
                    resourceUrl.openStream().use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    // Open in default browser
                    val desktop = java.awt.Desktop.getDesktop()
                    if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                        desktop.browse(tempFile.toURI())
                    } else {
                        // Fallback to BrowserUtil for better cross-platform support
                        com.intellij.ide.BrowserUtil.browse(tempFile.toURI())
                    }
                }
            } catch (e: Exception) {
                // Fallback: show notification with description
                showFallbackNotification(project)
            }
        }

        private fun showFallbackNotification(project: Project) {
            val notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(
                    "ZEST Test Generation",
                    """
                    <html>
                    <body>
                    <h3>Cách hoạt động:</h3>
                    <ul>
                        <li>• <b>Quy trình 7 giai đoạn</b> với State Machine</li>
                        <li>• <b>Multi-Agent System:</b> Context, Coordinator, Writer, Fixer, Merger</li>
                        <li>• <b>Phát hiện và sửa lỗi biên dịch</b></li>
                        <li>• <b>Hỗ trợ Testcontainers và WireMock</b></li>
                        <li>• <b>Phân tích cấu trúc dự án trong IDE</b></li>
                    </ul>
                    <p><b>Sử dụng:</b> Chọn method → Ctrl+Alt+T hoặc Chuột phải → Generate Tests (AI)</p>
                    </body>
                    </html>
                    """.trimIndent(),
                    NotificationType.INFORMATION
                )
            notification.notify(project)
        }
    }

    override suspend fun execute(project: Project) {
        val settings = ZestGlobalSettings.getInstance()
        val lastVersion = settings.lastNotifiedVersion

        // Check if this is first install or update
        if (lastVersion == null || lastVersion != CURRENT_VERSION) {
            // Show notification about new version
            showUpdateNotification(project, lastVersion == null)

            // Update the last notified version
            settings.lastNotifiedVersion = CURRENT_VERSION
        }
    }

    private fun showUpdateNotification(project: Project, isFirstInstall: Boolean) {
        val title = if (isFirstInstall) {
            "Chào mừng đến với ZEST v$CURRENT_VERSION"
        } else {
            "ZEST đã cập nhật lên v$CURRENT_VERSION"
        }

        val content = """
            <html>
            <body>
            <h3>Phiên bản mới có gì:</h3>
            <ul>
                <li>• <b>Git UI responsive</b> - Cải thiện hiệu năng</li>
                <li>• <b>Test Generation</b> - Hệ thống multi-agent với 7 giai đoạn</li>
                <li>• <b>Browser ổn định</b> - Sửa lỗi null mode</li>
                <li>• <b>Test Scenario</b> - Dialog hiển thị rõ ràng hơn</li>
            </ul>
            <p style='margin-top: 10px;'><b>Mới:</b> Test Generation sử dụng kiến trúc multi-agent để tạo test tự động với khả năng phân tích context và sửa lỗi compile.</p>
            </body>
            </html>
        """.trimIndent()

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(title, content, NotificationType.INFORMATION)

        // Add action to view test generation overview
        notification.addAction(object : NotificationAction("Xem chi tiết Test Generation") {
            override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                showTestGenerationOverview(project)
                notification.expire()
            }
        })

        // Add action to dismiss
        notification.addAction(object : NotificationAction("Đóng") {
            override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                notification.expire()
            }
        })

        notification.notify(project)
    }
}