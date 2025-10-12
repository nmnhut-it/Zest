# Zest Plugin Update Notes

## Update notes tháng 10/2025 (Version 1.9.903)

### 🤖 Agent Mode cải thiện:
- **Response format nhất quán:** AI giờ luôn hiện số lần dùng tool ("Tool calls: 3/5") trong mỗi response - dễ theo dõi
- **Tool budget rõ ràng hơn:** Phân biệt rõ exploration tools (đếm vào limit 5) vs modification tools (unlimited)
- **AI ổn định hơn:** Cải thiện prompts để AI response đúng format, nhất quán hơn, ít "nghịch" hơn

### 💬 Chat Memory nâng cấp:
- **Message detail view tốt hơn:** Xem chi tiết chat history giờ dùng browser riêng - không bị xung đột
- **Render đúng hơn:** Message detail hiển thị đúng, không bị lỗi hoặc nhảy lung tung

### 🎯 Dynamic Context Awareness:
- **AI biết file đang làm:** Agent mode giờ tự động biết file & vị trí cursor hiện tại - không cần nói lại
- **Context response tốt hơn:** AI hiểu rõ hơn đang làm gì, gợi ý chính xác hơn

### 🔧 Code Modification thông minh hơn:
- **Flexible matching:** Fuzzy match code khi sửa - không cần format khớp 100%
- **Error message rõ ràng:** Khi code change fail, hiện preview file để dễ debug
- **Robust hơn:** Xử lý whitespace, formatting khác nhau tốt hơn

### 🚀 Tương thích IntelliJ 2024.3.x:
- Support IntelliJ IDEA 2024.3.x và các version sau
- Tool improvements: code search & file reading với error handling tốt hơn

---

## Update notes tháng 10/2025 (Version 1.9.902)

### 🧪 Test Generation nâng cấp toàn diện:
- **Phase notifications rõ ràng:** Thấy được AI đang ở bước nào (analyze code → collect files → write test cases → write code → merge → save file)
- **UI tự động chuyển tab** theo phase hiện tại - không cần click tay
- **Chat streaming real-time** trong test generation window - xem AI đang làm gì, đang "suy nghĩ" gì, không còn đợi lâu không biết gì
- **Human-in-the-loop:** Có thể can thiệp giữa chừng khi AI đang gen test (dừng lại để review hoặc điều chỉnh)
- **Test strategy thông minh:** AI tự phân biệt unit test vs integration test, suggest đúng approach cho từng loại
- **Context gathering agent cải thiện:** Tự động search và collect đủ context (related files, dependencies, patterns) trước khi viết test
- **Test scenario display đẹp hơn:** Format rõ ràng, dễ đọc hơn
- **Fix bug duplicate:** Test giờ chỉ hiện 1 lần, không bị duplicate display nữa
- **View Chat button fixed:** Giờ xem được conversation history từ TestWriterAgent đúng cách

### 🛠️ Multi-project tool support:
- Mở nhiều project cùng lúc không bị lẫn tool
- Mỗi project có tool server riêng (port 8765-8865)
- Tự động filter tool theo project path - không bao giờ gọi nhầm tool của project khác

### 🤖 Agent Mode với OpenWebUI:
- **Tool tự động đăng ký** vào settings của OpenWebUI khi bật Agent Mode
- Có thể xài tool (đọc file, search code, sửa code) trực tiếp từ chat
- **Tool budget system:** Giới hạn 5 lần search/đọc file (tránh search lung tung), AI phải track rõ "Tools: 1/5 used" trong mỗi response
- **8 categories search patterns** để hiểu code:
  + Class & interface structure (tìm class definition, implements, extends)
  + Instance creation (constructor calls, factory methods, DI, singleton)
  + Method definitions & calls (tìm method, usage, overrides)
  + Field & constant definitions (static final, enums, config values)
  + Dependencies & usage (imports, references, annotations)
  + Test coverage (test files, @Test methods, mocks)
  + Configuration & resources (properties, SQL, API endpoints)
  + Error handling & logging (exceptions, log statements)
- **AI tự sửa code luôn:** Dùng replaceCodeInFile thay vì chỉ show code để user copy - autonomous thật sự!
- **Response format bắt buộc:** Mỗi response phải ngắn gọn (2-3 câu), có tool count, có file:line reference, không dài dòng
- **Workflow chuẩn:** Understand → Plan → Execute → Verify (4 bước rõ ràng)

### 💬 Chat UI hiện đại hơn:
- Redesign giao diện chat - layout sạch hơn, visual hierarchy tốt hơn
- Dark mode cải thiện - dùng ThemeUtils thay vì check Darcula (support hết dark themes)
- Đơn giản hóa tool rendering - bỏ những tool renderer phức tạp, code maintainable hơn

### 🔧 Tool improvements:
- **ReadFileTool:** Nhận nhiều kiểu path (relative, absolute, package-style như com.example.Class)
- **LookupMethodTool & LookupClassTool:** Cải thiện, tìm chính xác hơn
- **Tool handlers:** Tương thích với cả parameter name (filePath) và arg0/arg1 - linh hoạt hơn

### 🏗️ Technical (dọn dẹp code):
- Bỏ ~6000 dòng code cũ phức tạp (Javalin proxy, Node.js MCP server, Agent proxy...)
- Tool server mới dùng Java HttpServer (built-in, không cần framework) - nhẹ hơn, nhanh hơn
- OpenAPI 3.1.0 schema tự gen từ langchain4j @Tool annotations
- tool-injector.js đăng ký tools vào OpenWebUI settings API
- tool-interceptor.js filter tools theo project path trong mỗi request
- FastAPI-style endpoints: /read_file, /search_code (đơn giản, dễ hiểu)
- CORS support đầy đủ cho cross-origin requests

---

## Update notes tháng 9/2025 (Previous versions)

### Version 1.9.901 - Tool-Enabled Chat
- Thêm "Chat with Tools" action trong Tools menu - chat bình thường nhưng có tool hỗ trợ
- AI có thể tự động dùng tool (đọc file, search code, analyze class, list files) khi cần
- UI code block gọn hơn - giảm padding cho dễ đọc

### Version 1.9.900 - Test Generation Bug Fixes
- Fix bug test hiện duplicate
- Fix chat memory - "View Chat" button giờ hoạt động đúng
- TestWriterAgent refactor để stream real-time
- Context agent cải thiện - gather context tốt hơn
- Human-in-the-loop capability trong test generation
- Ripgrep code search cải thiện

### Version 1.9.898 - JSON Parsing & Code Health
- JsonParsingHelper mới với multiple fallback strategies - xử lý LLM response lỗi tốt hơn
- Code Health giờ show partial results ngay cả khi JSON parsing fail
- Issue prioritization - chỉ show 5 critical issues/file để focus
- Simplify completion system - bỏ overlap detection phức tạp
- Code cleanup: ~400 dòng code không cần thiết
