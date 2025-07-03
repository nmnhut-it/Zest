package com.zps.zest.codehealth.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class ShowGuardianHelpAction : AnAction("Code Guardian Help", "Xem hướng dẫn sử dụng", null) {
    
    override fun actionPerformed(e: AnActionEvent) {
        val helpText = """
            <html>
            <h2>Code Guardian - Hướng Dẫn</h2>
            
            <h3>Chức Năng Chính:</h3>
            
            <b>1. Activate Code Guardian</b><br>
            <i>Phân tích code đã thay đổi trong ngày để phát hiện lỗi tiềm ẩn</i><br><br>
            
            <b>2. Guard This Method</b><br>
            <i>Theo dõi method này để kiểm tra chất lượng</i><br><br>
            
            <b>3. Start Guardian Patrol</b><br>
            <i>Bắt đầu kiểm tra tự động tất cả method đang theo dõi</i><br><br>
            
            <b>4. Guardian Daily Report</b><br>
            <i>Tạo báo cáo tổng hợp chất lượng code hàng ngày</i><br><br>
            
            <h3>Debug Tools:</h3>
            
            <b>5. Guardian Watch List</b><br>
            <i>Xem danh sách method đang được theo dõi</i><br><br>
            
            <b>6. Guardian Test Mode</b><br>
            <i>Chế độ test với dữ liệu mẫu</i><br><br>
            
            <hr>
            <small>Tip: Dùng Ctrl+Shift+A và tìm "Guardian" để xem tất cả lệnh</small>
            </html>
        """.trimIndent()
        
        Messages.showMessageDialog(
            e.project,
            helpText,
            "Code Guardian Help",
            Messages.getInformationIcon()
        )
    }
}
