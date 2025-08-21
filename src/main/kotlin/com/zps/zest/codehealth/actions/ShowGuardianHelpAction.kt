package com.zps.zest.codehealth.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class ShowGuardianHelpAction : AnAction("❓ Help / Trợ Giúp", "View Code Health usage guide / Xem hướng dẫn sử dụng Code Health", null) {
    
    override fun actionPerformed(e: AnActionEvent) {
        val helpText = """
            <html>
            <h2>Code Health - Hướng Dẫn / Usage Guide</h2>
            
            <h3>Chức Năng Chính:</h3>
            
            <b>1. 🏥 Code Health Check</b><br>
            <i>AI analysis for bugs, performance & security / Phân tích AI tìm lỗi, hiệu năng & bảo mật</i><br><br>
            
            <b>2. 🎯 Track This Method</b><br>
            <i>Add method to quality monitoring queue / Thêm method vào hàng đợi giám sát chất lượng</i><br><br>
            
            <b>3. 🔄 Process Review Queue</b><br>
            <i>Review all pending methods in background / Đánh giá các method đang chờ</i><br><br>
            
            <b>4. 📊 Daily Health Report</b><br>
            <i>Generate comprehensive daily code quality report / Tạo báo cáo tổng hợp chất lượng code</i><br><br>
            
            <h3>Debug Tools:</h3>
            
            <b>5. 📋 Review Queue Status</b><br>
            <i>View methods being monitored and their status / Xem các method đang theo dõi và trạng thái</i><br><br>
            
            <b>6. 🧪 Test Mode</b><br>
            <i>Reset and add test data for debugging / Đặt lại và thêm dữ liệu thử nghiệm</i><br><br>
            
            <hr>
            <small>Tip: Dùng Ctrl+Shift+A và tìm "Code Health" để xem tất cả lệnh / Use Ctrl+Shift+A and search "Code Health" to see all commands</small>
            </html>
        """.trimIndent()
        
        Messages.showMessageDialog(
            e.project,
            helpText,
            "Code Health Help",
            Messages.getInformationIcon()
        )
    }
}
