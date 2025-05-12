## Code của bạn có đang ...
✅ Muốn unit test nhưng ngám ngẩm?
✅ Ngập tràn // TODO nhưng ngập ngừng?
✅ Thèm review nhưng ngại ngùng?
 
## Bạn muốn dùng AI nhưng ... 
✅ Mệt mỏi tab sang Chat Zingplay?
✅ Tiếc nuối AI ở mạng trong?
✅ Chán gõ prompt vì lặp đi lặp lại/? 

Đừng lo, đã có .. Zest. 

**Zest** cho phép bạn sử dụng chat zingplay ngay trong IDE, tích hợp MCP server để AI đọc viết code, mang cả không gian AI về Intelij máy bạn. 

**Zest** giúp bạn tự collect context, implement TODOs, viết unit test tự động, soạn văn mẫu để review và thêm comment vào code của bạn, với so sáng before-after change, dễ dàng theo dõi.  


Cài đặt MCP để  điều khiển intelij: cài plugin vào ./mcp-server-plugin.zip

Tích hợp Zest với MCP:  

> *"Tôi đã giảm được một nửa thời gian viết test nhờ ZEST. Plugin đã giúp tôi tạo unit test cho những phương thức phức tạp với code coverage tốt."*

---

# ZEST - Trợ Lý AI Viết Code Java: Từ Unit Test Đến Code Review


## Cài đặt trong 3 phút

### 1. Cài đặt Plugin

1. **Tải file ZIP plugin** từ link được cung cấp
2. **Cài đặt vào IntelliJ IDEA**:
   - **File > Settings > Plugins > ⚙️ > Install Plugin from Disk**
   - Chọn file ZIP vừa tải và khởi động lại IDE

### 2. Thiết lập API Key

1. **Chuẩn bị API Key** từ nhà cung cấp dịch vụ AI
2. **Kích hoạt ZEST**:
   - Nhấp chuột phải vào bất kỳ file Java nào
   - Chọn **Generate > ZPS: Review This Class in Chat ZPS**
   - Nhập API Key khi được nhắc

### 3. Bắt đầu sử dụng ngay!

Hoàn tất! Giờ đây AI đã sẵn sàng làm việc cho bạn.

---

## Những tính năng ấn tượng

### 📊 Tạo Unit Test Tự Động

**Vấn đề**: Viết unit test thường chiếm đến 40% thời gian phát triển.  
**Giải pháp**: Nhấp chuột phải > Generate > **"Ai test?!: One-click Write Test"**

**Lợi ích thực tế:**
- ✅ Tự động phân tích lớp Java và tạo test case phù hợp
- ✅ Tích hợp sẵn với Mockito để mô phỏng các dependency
- ✅ Hỗ trợ cả JUnit 4 và JUnit 5 với code coverage cao

### 🚀 Biến TODO Thành Code Thực Tế

**Vấn đề**: Các TODO thường bị bỏ quên hoặc trì hoãn mãi.  
**Giải pháp**: Chọn đoạn mã > Nhấp chuột phải > Generate > **"ZPS: Implement Your TODOs"**

**Điểm mạnh:**
- ✅ Phân tích ngữ cảnh code hiện tại của bạn
- ✅ Đề xuất triển khai logic phù hợp với style dự án
- ✅ Cho phép kiểm tra thay đổi trước khi áp dụng

### 💬 ZPS Chat - Trợ Lý Lập Trình Riêng

Trợ lý AI tích hợp trong IDE, sẵn sàng hỗ trợ mọi lúc:
- 💡 Giải đáp thắc mắc về code và kiến trúc phần mềm
- 💡 Phân tích đoạn mã được chọn từ editor
- 💡 Tạo code mẫu theo yêu cầu của bạn

### 🔍 Code Review Ngay Lập Tức

**Vấn đề**: Code review thủ công tốn thời gian và dễ bỏ sót vấn đề.  
**Giải pháp**: Nhấp chuột phải > Generate > **"ZPS: Review This Class in Chat ZPS"**

**Ưu điểm vượt trội:**
- ✅ Phát hiện bug tiềm ẩn và vấn đề bảo mật
- ✅ Gợi ý cải thiện code style và hiệu suất
- ✅ Luôn sẵn sàng 24/7, không cần chờ đợi

### 🚀 Cấu hình MCP để chạy tool điều khiển intelij: 

- Cài plugin intelij mcp (file mcp-server-plugin.zip)
- Chạy file mcp.bat trong thư mục ./mcp để bật mcp server
- Config trên chat/talk zingplay: Setting --> Tools --> Add localhost:8000/jetbrains/openapi.json --> Save
- F5 lại trang, xem phần tools trên khung chat thấy có 'JetBrains' là được

### 📝 Comment Code Chuyên Nghiệp

**Giải pháp**: Chọn đoạn code > Generate > **"ZPS: Write Comment for the Selected Text"**

Biến code khó hiểu thành tài liệu rõ ràng:
- Tạo Javadoc chuẩn mực cho method và class
- Giải thích logic phức tạp bằng ngôn ngữ dễ hiểu
- Tăng tính bảo trì của dự án dài hạn

---

## Cấu hình đơn giản

File `zest-plugin.properties` giúp bạn tùy chỉnh trải nghiệm:

```properties
# API Zingplay
apiUrl=https://chat.zingplay.com/api/chat/completions

# Model AI
testModel=unit_test_generator
codeModel=qwen3-32b

# API Key
authToken=YOUR_API_KEY_HERE
```

### API Zingplay Mạnh Mẽ

```properties
apiUrl=https://chat.zingplay.com/api/chat/completions
# Hoặc phiên bản nâng cao
apiUrl=https://talk.zingplay.com/api/chat/completions
```

### Lựa chọn model phù hợp

```properties
# Tạo Unit Test
testModel=unit_test_generator

# Triển khai Code
codeModel=qwen3-32b
```

---
 
### Bảo mật API Key

- ✓ Thêm `*-plugin.properties` vào file `.gitignore`

---

## Mẹo sử dụng hiệu quả

1. **Tự động hóa các công việc lặp lại** để tiết kiệm thời gian
2. **Sử dụng ZPS Chat** khi cần tư vấn về kiến trúc hoặc pattern
3. **Code review thường xuyên** trước khi commit
4. **Tạo comment tự động** để dễ dàng bảo trì code

---

## Yêu cầu hệ thống

- IntelliJ IDEA phiên bản 223.0 trở lên (tương thích đến 243.*)
- Plugin "Java" đã cài đặt
- Kết nối Internet ổn định
- 8GB RAM trở lên

---

## Hỗ trợ kỹ thuật

Đội ngũ phát triển luôn sẵn sàng hỗ trợ:

- **Email**: nhutnm3@vng.com.vn
- **Website**: https://www.vng.com.vn

---

*Tiết kiệm thời gian, nâng cao chất lượng code với ZEST!*

*Cập nhật: Tháng 5, 2025*