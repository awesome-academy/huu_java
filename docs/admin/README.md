# 🛠 Admin — Trang Quản Trị (SSR)

Trang quản trị hệ thống, render phía server bằng Thymeleaf + Bootstrap 5. Toàn bộ đường dẫn `/admin/**` yêu cầu **`ROLE_ADMIN`**, đăng nhập bằng form login tại `/login` (session-based, hỗ trợ remember-me 7 ngày).

> Quay lại [README tổng quan](../../README.md) · Xem thêm: [API](../api/README.md) · [Web](../web/README.md)

## Tổng Quan

| Thành phần | Vị trí |
|---|---|
| Controllers | `com.huuhv.foodsndrinks.controller.admin` |
| Templates | `src/main/resources/templates/admin/` (layout: `admin/layout/base.html`, dùng Thymeleaf Layout Dialect) |
| Xử lý lỗi | `AdminExceptionHandler` (`@ControllerAdvice` scoped package admin) + view `error/admin/{404,403,405,409,500}` |
| Model advice | `AdminLayoutModelAdvice` — inject `currentPath` và các enum (categoryTypes, productTypes, orderStatuses, roles, suggestionStatuses) vào mọi view admin; `PendingOrderCountInterceptor` thêm `pendingOrderCount` (badge "đơn chờ xác nhận" ở sidebar) — chỉ khi render view, không chạy cho endpoint JSON/redirect |

## Chức Năng & Routes

### Dashboard — `AdminController`

| Method | Path | Mô tả |
|---|---|---|
| GET | `/admin`, `/admin/dashboard` | Dashboard thống kê tổng quan. Các truy vấn thống kê chạy song song trên pool `dashboard-*` (`DashboardService`); một truy vấn lỗi, quá `app.async.dashboard.timeout` (mặc định 10s) hoặc pool đầy (nhiều admin cùng tải) → 503, trang lỗi admin với thông báo chung |
| GET | `/admin/dashboard/charts` | JSON dữ liệu 3 biểu đồ (`DashboardChartResDto`: doanh thu 6 tháng, số đơn theo trạng thái, top 5 sản phẩm bán chạy theo số lượng — chỉ đơn hoàn thành) cho `static/js/admin/dashboard.js` — thay cho script inline (CSP). `ROLE_USER` → 403 |

**Biểu đồ realtime:** `dashboard.js` vẽ 3 biểu đồ (cột doanh thu, doughnut trạng thái + danh sách số đơn, cột ngang top 5 sản phẩm) và tải lại `/admin/dashboard/charts` khi `realtime.js` phát `admin:orders-changed` (mỗi sự kiện đơn hàng) hoặc `admin:realtime-resync` (sau mỗi lần kết nối, kể cả lần đầu — bù sự kiện giữa lúc tải trang và lúc kết nối xong). Server cache dữ liệu biểu đồ trong bộ nhớ mỗi instance (`DashboardChartCache`, `app.dashboard.chart-cache-ttl` = 30s) và xoá ngay khi nhận sự kiện đơn hàng, nên nhiều admin cùng mở dashboard chỉ tốn một lần truy vấn mỗi sự kiện. Lần tính chạy trên pool `dashboard-*` với cùng timeout `app.async.dashboard.timeout`: DB chậm, truy vấn lỗi hay pool đầy → `/admin/dashboard/charts` trả 503 (biểu đồ cũ giữ nguyên, hiện thông báo lỗi). Truy vấn badge "đơn chờ xác nhận" lỗi thì trang admin vẫn hiển thị, chỉ ẩn badge (WARN trong log). Các sự kiện liên tiếp được gộp: một lần tải sau 1s + jitter 0–2s, đang tải thì tải thêm đúng 1 lần sau; tab ẩn thì hoãn tới khi hiện lại. Số liệu luôn đọc lại từ DB (không cộng dồn từ sự kiện). Đơn mới (chờ xác nhận) chỉ đổi biểu đồ trạng thái; doanh thu và top sản phẩm chỉ tính đơn hoàn thành nên đổi khi đơn chuyển sang hoàn thành (sự kiện đổi trạng thái — issue #24). Thẻ KPI và bảng "Đơn hàng gần đây" không tự cập nhật (tải lại trang).

JS của admin nằm ở `static/js/admin/`: `main.js` (hộp xác nhận qua thuộc tính `data-confirm="..."` trên `<form>`/`<button>`, kiểm tra kích thước file qua `data-max-file-size`), `realtime.js` (đơn mới realtime — xem [Thông báo realtime](#thông-báo-realtime-websocket--stomp)), `dashboard.js` (Chart.js, tự cập nhật theo sự kiện realtime). Không viết `onclick`/`onsubmit` hay `<script>` inline — CSP chặn.

### Quản lý sản phẩm — `ProductAdminController` (`/admin/products`)

| Method | Path | Mô tả |
|---|---|---|
| GET | `/admin/products` | Danh sách phân trang, filter theo tên / danh mục / loại / trạng thái bán |
| GET | `/admin/products/add` | Form thêm sản phẩm |
| POST | `/admin/products/add` | Tạo sản phẩm, upload nhiều ảnh (`newImages`, tối đa 5MB/file, 25MB/lần gửi) |
| GET | `/admin/products/edit/{id}` | Form sửa sản phẩm |
| POST | `/admin/products/edit/{id}` | Cập nhật (thêm/xóa ảnh, chọn ảnh đại diện) |
| POST | `/admin/products/{id}/delete` | Xóa sản phẩm |

Ảnh upload lưu tại thư mục `UPLOAD_DIR` (mặc định `./uploads`), serve qua `/uploads/**` (cấu hình trong `WebMvcConfig`). Chỉ nhận PNG, JPEG — nội dung file (magic bytes) phải khớp đuôi file, `Content-Type` do trình duyệt gửi không được dùng. Ảnh được decode rồi encode lại (bỏ EXIF/GPS và dữ liệu thừa, áp hướng xoay EXIF, từ chối ảnh trên 40 triệu pixel, thu nhỏ cạnh dài về 2048px); tên file lưu là UUID. GIF/WEBP không được nhận vì JDK không có codec WebP và encode lại GIF làm mất animation.

### Quản lý danh mục — `CategoryAdminController` (`/admin/categories`)

| Method | Path | Mô tả |
|---|---|---|
| GET | `/admin/categories` | Danh sách phân trang + filter |
| GET / POST | `/admin/categories/add` | Thêm danh mục (validate, check trùng) |
| GET / POST | `/admin/categories/edit/{id}` | Sửa danh mục |
| POST | `/admin/categories/{id}/delete` | Xóa danh mục |

### Quản lý đơn hàng — `OrderAdminController` (`/admin/orders`)

| Method | Path | Mô tả |
|---|---|---|
| GET | `/admin/orders` | Danh sách phân trang, filter theo trạng thái / keyword / mã đơn |
| GET | `/admin/orders/{id}` | Chi tiết đơn hàng |
| POST | `/admin/orders/{id}/status` | Cập nhật trạng thái đơn (`OrderStatus`, trừ `CART` — không chuyển đơn về giỏ hàng: báo lỗi). Giỏ hàng của khách (`CART`) không phải đơn: không hiện ở danh sách/bộ lọc, mở chi tiết hay đổi trạng thái → 404; chỉ khách checkout mới đưa giỏ sang chờ xác nhận |
| GET | `/admin/orders/pending-count` | JSON `{"pendingCount": n}` — số đơn chờ xác nhận; `static/js/admin/realtime.js` gọi sau mỗi lần kết nối WebSocket, kể cả lần đầu (bù sự kiện lỡ) và để biết session còn sống |

### Quản lý người dùng — `UserAdminController` (`/admin/users`)

| Method | Path | Mô tả |
|---|---|---|
| GET | `/admin/users` | Danh sách phân trang, filter theo keyword / role / trạng thái active |
| GET / POST | `/admin/users/{id}/edit` | Sửa thông tin người dùng; ô mật khẩu mới để trống = giữ nguyên, nếu nhập thì theo chính sách mật khẩu 8–64 ký tự (xem [docs/web](../web/README.md#form-login--đăng-ký--authcontroller)) |
| POST | `/admin/users/{id}/toggle-active` | Khóa / mở khóa tài khoản |

### Quản lý góp ý — `SuggestionAdminController` (`/admin/suggestions`)

| Method | Path | Mô tả |
|---|---|---|
| GET | `/admin/suggestions` | Danh sách phân trang, filter theo trạng thái / keyword |
| GET | `/admin/suggestions/{id}` | Chi tiết + form sửa |
| POST | `/admin/suggestions/{id}/update` | Lưu chỉnh sửa góp ý |
| POST | `/admin/suggestions/{id}/status` | Cập nhật trạng thái (`SuggestionStatus`) |

## Thông Báo Cho Admin

Admin nhận thông báo tự động (không cần thao tác trên UI):

- **Đơn hàng mới:** sau khi user checkout thành công → gửi **Slack** (incoming webhook) + **email** tới `ADMIN_NOTIFY_EMAIL`. Chạy async (`@Async`), fire sau khi transaction commit (`@TransactionalEventListener`).
- **Báo cáo doanh thu tháng:** `OrderStatisticsScheduler` chạy cron `ORDER_STATS_CRON` (mặc định 00:05 ngày 1, giờ VN) — email báo cáo tháng vừa kết thúc: tổng doanh thu, số đơn hoàn thành, số đơn theo từng trạng thái (cùng phạm vi: đơn đặt trong tháng đó).

Bật/tắt qua `SLACK_NOTIFY_ENABLED`, `MAIL_NOTIFY_ENABLED`. Xem package `service/notification/`.

### Thông báo realtime (WebSocket + STOMP)

Admin đang mở **bất kỳ trang `/admin/**`** thấy đơn mới ngay, không cần tải lại trang:

- Toast "Đơn mới #id" (số món, tổng tiền, link tới chi tiết đơn) ở góc phải dưới.
- Badge số đơn **chờ xác nhận** (`PENDING`) cạnh mục "Manage Orders" của sidebar — số luôn tính lại từ DB lúc gửi, nên đúng cả khi nhiều admin cùng xử lý đơn.
- Một admin đổi trạng thái đơn (`POST /admin/orders/{id}/status`) → sau commit, mọi admin đang online nhận sự kiện `ORDER_STATUS_CHANGED` (badge cập nhật, dashboard tải lại biểu đồ — doanh thu/top sản phẩm đổi khi đơn sang hoàn thành; không toast) và **chủ đơn** đang mở trang đơn hàng thấy trạng thái mới ([docs/web](../web/README.md#trạng-thái-đơn-realtime)). Luồng: `OrderService.updateStatus` ghi outbox → topic JMS `order.status-changed` → `OrderStatusChangedWebSocketConsumer` → `OrderStatusNotifier` (chủ đơn) + `AdminOrderNotifier`.

Luồng: checkout → outbox → topic JMS `order.placed` → `OrderPlacedWebSocketConsumer` (mỗi instance một subscription non-durable) → `AdminOrderNotifier` → STOMP `/topic/admin/orders` (payload `AdminOrderEventResDto`: `type`, `orderId`, `itemCount`, `totalPrice`, `pendingCount` — không có tên/email/SĐT/địa chỉ khách). Client: `static/js/realtime.js` (kết nối dùng chung) + `static/js/admin/realtime.js`, nạp ở `admin/layout/base.html`; script của từng trang nghe `CustomEvent` `admin:orders-changed` (mỗi sự kiện) và `admin:realtime-resync` (sau mỗi lần kết nối lại).

Bảo mật (chi tiết: [README — Realtime](../../README.md#realtime-websocket--stomp)):

| Tình huống | Kết quả |
|---|---|
| Chưa đăng nhập mở `/ws-stomp` | Chuyển hướng `/login`, không upgrade được |
| `ROLE_USER` SUBSCRIBE `/topic/admin/orders` | Frame ERROR `Access denied`, kết nối bị đóng, audit `WEBSOCKET_ACCESS_DENIED` |
| Frame CONNECT thiếu/sai token CSRF | Frame ERROR `Access denied` (không lặp lại token) |
| Handshake từ origin khác | 403 |
| Client SEND vào `/topic/**`, `/queue/**`, `/app/**` | Bị từ chối (không giả được sự kiện cho admin khác) |
| Admin bị khoá/hạ quyền khi đang mở trang | Socket bị đóng trong ≤ `WEBSOCKET_REVALIDATE_INTERVAL` (60s), audit `SESSION_REVOKED` (`session.type=websocket`) |
| Logout / session hết hạn | Socket bị đóng; logout thì trang không kết nối lại được (handshake bị chuyển hướng `/login`) |

Frame WebSocket không gia hạn session HTTP: trang để yên quá 30 phút (timeout session) thì socket bị đóng; trang tự kết nối lại được nếu còn cookie remember-me (đăng nhập lại tự động), không thì realtime dừng — tải lại trang để đăng nhập. Mỗi lần nối lại, `realtime.js` lấy token CSRF của session hiện tại qua `GET /csrf-token` (token trong meta của trang gắn với session cũ); bị chuyển hướng `/login` → dừng hẳn. Đăng nhập lại ở tab khác của cùng trình duyệt (id session đổi) → socket đang mở bị đóng ngay rồi nối lại với danh tính mới.

## Xử Lý Lỗi

- Exception nghiệp vụ (`ResourceNotFoundException` → 404, `DuplicateResourceException` → 409, `Exception` khác → 500) được `AdminExceptionHandler` bắt và render view `error/admin/*`.
- Lỗi dispatch cấp servlet (404 URL không tồn tại, 403, 405...) do `GlobalErrorController` xử lý — với URI prefix `/admin/*` sẽ trả về view lỗi admin.
- Thông báo thao tác thành công/thất bại trên UI dùng `RedirectAttributes` (flash message).

## Monitoring

Admin đã đăng nhập có thể truy cập Actuator: `/actuator/health` (chi tiết), `/actuator/metrics`, `/actuator/info`, `/actuator/caches` (chỉ dev).
