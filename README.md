# Mục Lục

- [Giới thiệu](#giới-thiệu)
- [ZEST - Plugin Hỗ Trợ Phát Triển Phần Mềm với AI](#zest---plugin-hỗ-trợ-phát-triển-phần-mềm-với-ai)
  - [Hướng dẫn cài đặt](#hướng-dẫn-cài-đặt)
    - [1. Cài đặt Plugin](#1-cài-đặt-plugin)
    - [2. Cài đặt MCP Server](#2-cài-đặt-mcp-server)
    - [3. Khởi động và sử dụng](#3-khởi-động-và-sử-dụng)
  - [Cấu trúc Menu](#cấu-trúc-menu)
  - [Các tính năng chính](#các-tính-năng-chính)
    - [Inline Completion - Gợi ý code thông minh](#inline-completion---gợi-ý-code-thông-minh)
    - [ZPS Chat - Trợ lý lập trình](#zps-chat---trợ-lý-lập-trình)
    - [Code Guardian - Bảo vệ chất lượng code](#code-guardian---bảo-vệ-chất-lượng-code)
    - [Test Advisor](#test-advisor)
    - [Code Review](#code-review)
    - [Tạo Commit Message](#tạo-commit-message)
  - [Agent Proxy - Kết nối AI với IDE](#agent-proxy---kết-nối-ai-với-ide)
    - [Giới thiệu Agent Proxy](#giới-thiệu-agent-proxy)
    - [Cài đặt và cấu hình](#cài-đặt-và-cấu-hình)
    - [Sử dụng với Open WebUI](#sử-dụng-với-open-webui)
- [Cấu hình](#cấu-hình)
  - [Cấu hình cơ bản](#cấu-hình-cơ-bản)
  - [Cấu hình nâng cao](#cấu-hình-nâng-cao)
  - [Bảo mật](#bảo-mật)
- [Khuyến nghị sử dụng](#khuyến-nghị-sử-dụng)
- [Yêu cầu hệ thống](#yêu-cầu-hệ-thống)
- [Hỗ trợ](#hỗ-trợ)
- [Các vấn đề thường gặp](#các-vấn-đề-thường-gặp)

---

## Giới thiệu

### Thách thức trong phát triển phần mềm

Trong quá trình phát triển phần mềm, các developer thường gặp phải những thách thức sau:
- Viết code lặp đi lặp lại cho các pattern phổ biến
- Khó phát hiện bugs và issues trong code mới viết
- Thiếu thời gian để review code trước khi commit
- Viết commit message mô tả chính xác các thay đổi
- Chuyển đổi liên tục giữa IDE và các công cụ AI

### Hạn chế khi sử dụng AI tools hiện tại

Việc sử dụng các công cụ AI để hỗ trợ coding hiện tại còn một số hạn chế:
- Phải chuyển đổi giữa nhiều ứng dụng khác nhau
- Mất thời gian copy-paste code giữa IDE và chat interface
- Khó maintain context khi làm việc với code phức tạp
- Thiếu khả năng phát hiện lỗi real-time trong quá trình coding

### Giải pháp ZEST

**ZEST** là một plugin cho IntelliJ IDEA, tích hợp chat AI và các công cụ thông minh trực tiếp vào môi trường phát triển. Plugin cho phép developer sử dụng AI để hỗ trợ các tác vụ lập trình mà không cần rời khỏi IDE.

Với việc tích hợp MCP (Model Context Protocol) server, ZEST mang lại khả năng tương tác sâu giữa AI và code của bạn, giúp tự động hóa nhiều tác vụ phát triển phần mềm.

**Phiên bản 1.9.869** bổ sung các tính năng:
- Inline Completion với hỗ trợ Cocos
- Code Guardian - Tự động review code và cảnh báo lỗi
- Tối ưu hiệu suất với file JAR nhỏ hơn

---

# ZEST - Plugin Hỗ Trợ Phát Triển Phần Mềm với AI

## Hướng dẫn cài đặt

### 1. Cài đặt Plugin

1. **Tải xuống file plugin** (định dạng ZIP) từ nguồn được cung cấp
2. **Cài đặt vào IntelliJ IDEA**:
   - Truy cập menu: File → Settings → Plugins → ⚙️ → Install Plugin from Disk
   - Chọn file ZIP đã tải xuống
   - Khởi động lại IDE để hoàn tất cài đặt
   - Mở cửa sổ chat bằng cách nhấn vào icon Z ở thanh công cụ bên phải
   - Trong cửa sổ chat, chọn model "Code Expert" và nhấn "Set as Default"
   - Để nhận tài khoản sử dụng, vui lòng liên hệ AnhNT22

### 2. Cài đặt MCP Server

MCP Server cho phép AI tương tác trực tiếp với IntelliJ:
- Cài đặt plugin IntelliJ MCP từ file mcp-server-plugin.zip
- Khởi động MCP server bằng cách chạy file mcp.bat trong thư mục ./mcp
- Cấu hình trong chat/talk interface:
  - Vào Settings → Tools
  - Thêm URL: localhost:8000/jetbrains/openapi.json
  - Lưu cấu hình
- Refresh trang web và kiểm tra mục Tools để xác nhận 'JetBrains' đã xuất hiện

### 3. Khởi động và sử dụng

Sau khi hoàn tất cài đặt, ZEST đã sẵn sàng hỗ trợ công việc phát triển của bạn.

## Cấu trúc Menu

Khi nhấp chuột phải vào code, menu **Zest** sẽ hiển thị với các chức năng:

### Main Actions
- **Test Advisor (Chat)** - Tư vấn về việc viết test
- **Code Review (Chat)** - Review code và đưa ra gợi ý
- **Generate Commit Message (Chat)** - Tạo commit message tự động

### Code Guardian
- **Activate Code Guardian** - Kích hoạt bảo vệ chất lượng code
- **Guard This Method** - Theo dõi method cụ thể
- **Start Guardian Patrol** - Kiểm tra tự động các method
- **Guardian Daily Report** - Báo cáo chất lượng hàng ngày

### Tools
- **Start Tools** - Khởi động MCP tools cho chat

---

## Các tính năng chính

### Inline Completion - Gợi ý code thông minh

**Mục đích**: Tự động gợi ý code trong quá trình lập trình, giảm thiểu việc gõ code lặp lại.

**Kích hoạt**: 
- Tự động: Gợi ý xuất hiện khi bạn dừng gõ
- Thủ công: Ctrl+Space để trigger ngay lập tức

**Phím tắt**:
- **Tab**: Chấp nhận toàn bộ gợi ý
- **Ctrl+Tab**: Chấp nhận từng dòng (chế độ LEAN)
- **Escape**: Hủy gợi ý

**Đặc điểm**:
- Hỗ trợ đặc biệt cho Cocos2d-x
- Học từ context code hiện tại
- Gợi ý multi-line thông minh
- Tối ưu cho Java và JavaScript/TypeScript

### ZPS Chat - Trợ lý lập trình

**Mục đích**: Cung cấp trợ lý AI trong IDE để hỗ trợ các tác vụ lập trình.

**Tính năng**:
- Giải đáp thắc mắc về code và architecture
- Phân tích code được chọn
- Tạo code mẫu theo yêu cầu
- Hỗ trợ đặc biệt cho Cocos2d-x với hashtag #cocos
- Tích hợp với MCP tools để tương tác trực tiếp với project

**Sử dụng**:
1. Mở ZPS Chat từ icon Z trên thanh công cụ
2. Chọn model phù hợp (khuyến nghị: Code Expert)
3. Gửi câu hỏi hoặc yêu cầu
4. Tương tác với AI để làm rõ hoặc điều chỉnh kết quả

### Code Guardian - Bảo vệ chất lượng code

**Mục đích**: Tự động phát hiện và cảnh báo về các vấn đề trong code mới viết hoặc sửa đổi.

**Kích hoạt**: Menu chuột phải → Zest → Code Guardian → Activate Code Guardian

**Tính năng chính**:
- **Auto Review**: Tự động review các thay đổi code nhỏ (method/file)
- **Daily Report**: Báo cáo tổng hợp lúc 13h hàng ngày
- **Real-time Alert**: Cảnh báo ngay khi phát hiện issue
- **Smart Tracking**: Theo dõi các method quan trọng

**Quy trình sử dụng**:
1. Kích hoạt Code Guardian cho project
2. Code Guardian tự động theo dõi các thay đổi
3. Nhận cảnh báo real-time khi có issue
4. Xem báo cáo tổng hợp hàng ngày

**Guardian Tools**:
- **Guardian Watch List**: Xem danh sách method đang theo dõi
- **Guardian Test Mode**: Chế độ test với dữ liệu mẫu
- **Guardian Help**: Hướng dẫn sử dụng chi tiết

### Test Advisor

**Mục đích**: Phân tích code và đưa ra lời khuyên về cách viết test hiệu quả.

**Kích hoạt**: Chọn code → Menu chuột phải → Zest → Test Advisor (Chat)

**Tính năng**:
- Phân tích độ phức tạp của code
- Đề xuất test scenarios
- Hướng dẫn mock dependencies
- Gợi ý best practices cho testing

### Code Review

**Mục đích**: Cung cấp code review tự động, phát hiện vấn đề tiềm ẩn.

**Kích hoạt**: Chọn code → Menu chuột phải → Zest → Code Review (Chat)

**Phân tích bao gồm**:
- Phát hiện bug tiềm ẩn
- Vấn đề về performance
- Code style và best practices
- Security concerns
- Đề xuất cải thiện

### Tạo Commit Message

**Mục đích**: Tạo commit message có ý nghĩa dựa trên changes.

**Kích hoạt**: Menu chuột phải → Zest → Generate Commit Message (Chat)

**Quy trình**:
1. Thực hiện changes trong code
2. Kích hoạt Generate Commit Message
3. AI phân tích diff và tạo message
4. Review và chỉnh sửa nếu cần
5. Copy message để sử dụng

---

## Agent Proxy - Kết nối AI với IDE

### Giới thiệu Agent Proxy

Agent Proxy là một server trung gian cho phép AI tools (như Open WebUI) tương tác trực tiếp với IntelliJ IDEA của bạn. Điều này mang lại khả năng:

- **Đọc và phân tích code**: AI có thể truy cập trực tiếp vào project files
- **Thực thi commands**: Chạy các lệnh trong IDE từ AI chat
- **Context-aware responses**: AI hiểu rõ context của project hiện tại
- **Tool integration**: Sử dụng các tools trong IDE thông qua AI

### Cài đặt và cấu hình

1. **Khởi động Agent Proxy**:
   - Menu: Zest → Start Tools
   - Hoặc: Tools → Start Tools
   - Server sẽ khởi động trên port 8765 (mặc định)

2. **Kiểm tra trạng thái**:
   - Mở trình duyệt: http://localhost:8765/health
   - Xem API docs: http://localhost:8765/zest/docs
   - OpenAPI spec: http://localhost:8765/zest/openapi.json

3. **Monitor Window**:
   - Hiển thị real-time activity
   - Theo dõi requests và responses
   - Xem performance metrics

### Sử dụng với Open WebUI

**Bước 1: Cấu hình Tools trong Open WebUI**

1. Truy cập Open WebUI (chat.zingplay.com hoặc talk.zingplay.com)
2. Vào Settings → Tools
3. Thêm URL: `http://localhost:8765/zest/openapi.json`
4. Nhấn Save và refresh trang

**Bước 2: Kích hoạt Tools trong chat**

1. Trong chat window, click vào biểu tượng "+" ở góc dưới bên trái
2. Chọn các tools muốn sử dụng:
   - `find_files_by_name`: Tìm files theo tên
   - `get_file_text`: Đọc nội dung file
   - `search_in_files`: Tìm kiếm trong code
   - `list_files_in_folder`: Liệt kê files trong thư mục
   - Và nhiều tools khác...

**Bước 3: Sử dụng trong hội thoại**

Khi chat với AI, bạn có thể yêu cầu AI sử dụng tools để:
- Đọc và phân tích code trong project
- Tìm kiếm files và nội dung cụ thể
- Navigate qua cấu trúc project
- Thực hiện phân tích deep với nhiều files

Ví dụ các câu hỏi bạn có thể hỏi:
- "Hãy dùng tools để tìm tất cả các file Service trong project"
- "Đọc file UserController.java và phân tích code"
- "Tìm tất cả các TODO trong project bằng tools"
- "Dùng tools để review security của AuthenticationService"

**Lợi ích**:
- AI có full context của project
- Không cần copy-paste code
- Phân tích deep với nhiều files
- Tự động navigate đến code khi cần

**Lưu ý**: Hiện tại mỗi port chỉ phục vụ cho một project. Nếu làm việc với nhiều projects, cần khởi động Agent Proxy với port khác cho mỗi project.

---

## Cấu hình

### Cấu hình cơ bản

ZEST sử dụng IntelliJ's settings system. Truy cập qua:
- File → Settings → Tools → Zest Plugin

**Các thiết lập chính**:

```properties
# API Configuration
API URL: https://chat.zingplay.com/api/chat/completions
Auth Token: [Your API Token]

# Model Configuration  
Test Model: unit_test_generator
Code Model: code-expert
Max Iterations: 3

# Feature Toggles
Enable Inline Completion: ✓
Enable Auto-trigger: ✓
Enable Code Guardian: ✓
```

### Cấu hình nâng cao

**Inline Completion**:
- Auto-trigger completion: Tự động gợi ý khi dừng gõ
- Continuous completion: Tiếp tục gợi ý sau khi accept
- Background context: Thu thập context trong background

**Code Guardian**:
- Enable daily check: Bật kiểm tra hàng ngày
- Check time: 13:00 (có thể tùy chỉnh)
- Max methods to track: Giới hạn số method theo dõi

**Agent Proxy**:
- Port: 8765 (có thể thay đổi nếu bị conflict)
- Max tool calls: Giới hạn số lần gọi tool
- Timeout: Thời gian timeout cho mỗi request

### Bảo mật

- API Token được lưu encrypted trong IntelliJ settings
- Agent Proxy chỉ chạy trên localhost
- Không expose sensitive data qua API
- Có thể stop server bất cứ lúc nào

---

## Khuyến nghị sử dụng

1. **Inline Completion**: Tận dụng tối đa cho code lặp lại và patterns phổ biến
2. **Code Guardian**: Bật ngay từ đầu project để theo dõi chất lượng code liên tục
3. **Agent Proxy**: Sử dụng khi cần phân tích deep hoặc làm việc với nhiều files
4. **Chat Integration**: Kết hợp với MCP tools để AI hiểu context project tốt hơn
5. **Daily Review**: Kiểm tra báo cáo Code Guardian hàng ngày lúc 13h
6. **Test Advisor**: Sử dụng trước khi viết test để có hướng đi đúng

---

## Yêu cầu hệ thống

- IntelliJ IDEA phiên bản 223.0 trở lên (tương thích đến 259.*)
- Plugin "Java" đã cài đặt
- Kết nối Internet ổn định
- RAM tối thiểu 8GB
- Lưu ý: Hiện tại plugin hoạt động ổn định nhất với code Java. Với JavaScript/TypeScript, inline completion và chat đảm bảo ổn định.

---

## Hỗ trợ

Để nhận hỗ trợ kỹ thuật, vui lòng liên hệ:

- **Quản lý tài khoản và backend**: AnhNT22
- **Email hỗ trợ**: nhutnm3@vng.com.vn
- **Website**: https://www.vng.com.vn

## Các vấn đề thường gặp

### Đăng nhập
- Tài khoản chat (mạng ngoài) và talk (mạng trong) là riêng biệt
- Nếu chưa có tài khoản, vui lòng liên hệ AnhNT22
- Plugin có thể yêu cầu đăng nhập lại trong lần sử dụng đầu tiên

### Agent Proxy
- Nếu port 8765 bị chiếm, có thể đổi sang port khác trong settings
- Monitor window có thể đóng mà không ảnh hưởng đến server
- Restart IDE nếu tools không xuất hiện trong Open WebUI

### Vấn đề đã biết
- Một số chức năng có thể gặp lỗi nếu cửa sổ chat chưa được mở
- Git Client trong cửa sổ chat không tự động refresh sau khi commit
- Git submodule support đang trong quá trình phát triển

---

*ZEST - Nâng cao hiệu suất phát triển phần mềm với sự hỗ trợ của AI*

*Cập nhật: Tháng 7, 2025 - Phiên bản 1.9.869*