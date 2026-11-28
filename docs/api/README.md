# 🔌 API — REST API Cho Trang Người Dùng

REST API dưới prefix `/api/v1`, trả JSON, xác thực bằng **JWT (Bearer token)**. Đây là API chia sẻ để xây trang người dùng dạng SPA/mobile app trong tương lai.

> Quay lại [README tổng quan](../../README.md) · Xem thêm: [Admin](../admin/README.md) · [Web](../web/README.md)

## Tổng Quan

| Thành phần | Vị trí |
|---|---|
| Controllers | `com.huuhv.foodsndrinks.controller.api` (`@RestController`) |
| Security | Chain riêng `apiSecurityFilterChain` (`@Order(2)`, matcher `/api/**`) — **stateless**, CSRF off, `JwtAuthenticationFilter` |
| JWT | `JwtUtil` (jjwt 0.12), HS256, secret từ `JWT_SECRET`, hết hạn sau **1 giờ** (`jwt.expiration: 3600000`) |
| Xử lý lỗi | `ApiExceptionHandler` (`@RestControllerAdvice`) — trả JSON `ErrorResponse` |
| OpenAPI | `OpenApiConfig` + springdoc-openapi 3.x |

## Swagger / OpenAPI

Chỉ bật ở profile **dev** (prod tắt hoàn toàn):

- **Swagger UI:** `http://localhost:8080/api-docs/swagger`
- **OpenAPI JSON:** `http://localhost:8080/api/v1/api-docs`

Swagger có nút **Authorize** (security scheme `bearerAuth`) — dán JWT lấy từ endpoint login để gọi các API cần xác thực.

## Xác Thực

### Đăng nhập lấy token

```
POST /api/v1/auth/login
Content-Type: application/json

{
  "username": "user@example.com",   // username hoặc email
  "password": "secret"
}
```

Response (`AuthResDto`) chứa JWT. Các request sau gửi kèm header:

```
Authorization: Bearer <token>
```

Token sai/thiếu/hết hạn → **401 Unauthorized**. Sai username/email hoặc mật khẩu → 401 với message chung (không phân biệt sai username hay sai mật khẩu). Tài khoản bị vô hiệu hoá → 401 `Tài khoản đã bị vô hiệu hoá.` — chỉ trả khi đã nhập **đúng** mật khẩu, nên không dò được tài khoản nào bị khoá.

JWT ký HS256, sống 1 giờ, có claim `iss` = `jwt.issuer` (`JWT_ISSUER`, mặc định `foodsndrinks`) và `aud` = `jwt.audience` (`JWT_AUDIENCE`, mặc định `foodsndrinks-api`). Token chỉ hợp lệ khi đúng chữ ký, còn hạn và khớp cả `iss` lẫn `aud`; đổi một trong hai giá trị làm mọi token đã cấp hết hiệu lực. Claims không chứa dữ liệu nhạy cảm (`sub` = username, `role`). **Giới hạn đã biết:** đổi/đặt lại mật khẩu huỷ ngay các session web khác nhưng **không** thu hồi JWT đã cấp — token cũ dùng được tới khi hết hạn (tối đa 1 giờ); tài khoản bị khoá thì token bị từ chối ngay (filter tra trạng thái tài khoản mỗi request).

### CORS

Mặc định API chỉ gọi được same-origin từ trình duyệt. Muốn SPA ở origin khác gọi `/api/**` thì khai báo `CORS_ALLOWED_ORIGINS` (origin đầy đủ `scheme://host[:port]`, không path hay `/` cuối, cách nhau bởi dấu phẩy, vd. `https://app.example.com,http://localhost:5173`; `*`, `null`, `http` với host khác localhost bị từ chối khi khởi động). Cho phép method `GET/POST/PUT/DELETE`, header `Authorization`, `Content-Type`, `X-Request-Id`; expose `X-Request-Id`, `Retry-After`; không gửi cookie (`allowCredentials=false` — xác thực bằng Bearer token). Preflight từ origin ngoài danh sách → 403. Trang web (session cookie) không mở CORS.

### Giới hạn đăng nhập sai

Mỗi lần đăng nhập được đếm ở 3 phạm vi (cửa sổ cố định 15 phút tính từ lần thử đầu tiên); vượt ngưỡng phạm vi nào thì phạm vi đó bị tạm khoá **15 phút**, kể cả khi sau đó nhập đúng mật khẩu:

| Phạm vi | Ngưỡng | Chặn |
|---|---|---|
| Cặp (IP, username/email) | 5 lần sai | Dò mật khẩu một tài khoản — người dùng thật gõ sai chỉ khoá chính mình trên máy mình |
| IP (IPv6: cả mạng /64) | 20 lần sai, mọi username | Password spraying (một mật khẩu thử trên nhiều tài khoản) |
| Tài khoản, mọi IP | 50 lần sai | Tấn công phân tán; muốn khoá tài khoản người khác phải có nhiều IP |

- Response **429 Too Many Requests**, message chung, header `Retry-After: <số giây còn lại>`.
- Lần thử được tính TRƯỚC khi so mật khẩu: gửi song song nhiều request không vượt được ngưỡng (request vượt số lần thử đang chạy nhận 429, `Retry-After: 1`).
- Tên đăng nhập được chuẩn hoá giống collation của MySQL (bỏ dấu, ký tự fullwidth, hoa/thường): `ádmin`, `ＡＤＭＩＮ` đếm chung với `admin`. Phạm vi tài khoản đếm thêm theo username thật sau khi tra DB, nên username và email của cùng tài khoản dùng chung bộ đếm này.
- Áp dụng y hệt cho username không tồn tại (không lộ tài khoản nào có thật). Đăng nhập thành công xoá bộ đếm của tên đăng nhập/tài khoản (không xoá bộ đếm theo IP).
- Bộ đếm dùng chung với form login `/login`.
- Cấu hình: `app.security.login-attempts.{max-failures,max-failures-per-ip,max-failures-per-account,window,lock-duration}` (`window`, `lock-duration` ≥ 1 giây, không thì app không khởi động). Bộ đếm nằm trong bảng `attempt_limits` (key băm HMAC bằng `ATTEMPT_LIMITS_KEY_SECRET`): mọi instance sau load balancer dùng chung, restart/deploy không xoá khoá; bản ghi hết hạn được job dọn mỗi 5 phút.
- Audit log: `LOGIN_LOCKOUT` khi khoá (`auth.lock.scope` = `IP_USER` / `IP` / `ACCOUNT`), các lần bị chặn sau đó ghi `LOGIN_FAILURE` với `event.reason=LOGIN_THROTTLED`.

### Truy vết request (`X-Request-Id`)

Mọi response đều có header `X-Request-Id` chứa id do server sinh (UUID) cho request đó. Khi báo lỗi, gửi kèm giá trị này. Client có thể tự gửi header `X-Request-Id` (chữ, số, `-`, tối đa 64 ký tự) để nối log phía client với log server: giá trị đó được ghi vào log server dạng `cid=<giá trị>` bên cạnh requestId, nhưng response vẫn trả id của server. Giá trị sai định dạng bị bỏ qua.

## Endpoints

### Auth — `AuthApiController` (`/api/v1/auth`)

| Method | Path | Auth | Mô tả |
|---|---|---|---|
| POST | `/api/v1/auth/login` | Public | Đăng nhập bằng username/email + password, trả JWT |

### Products — `ProductApiController` (`/api/v1/products`)

| Method | Path | Auth | Mô tả |
|---|---|---|---|
| GET | `/api/v1/products` | `USER` / `ADMIN` | Danh sách sản phẩm phân trang (chỉ `isAvailable=true`), filter: `name`, `categoryId`, `type` |
| GET | `/api/v1/products/{id}` | `USER` / `ADMIN` | Chi tiết sản phẩm kèm danh sách ảnh |

Response danh sách bọc trong `PageResDto` (nội dung + thông tin phân trang).

### Ví dụ với curl

```bash
# 1. Login
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"user@example.com","password":"secret"}'

# 2. Gọi API với token
curl http://localhost:8080/api/v1/products?page=0&size=10 \
  -H "Authorization: Bearer <token>"
```

## Định Dạng Lỗi

`ApiExceptionHandler` trả JSON `ErrorResponse` thống nhất:

| Exception | HTTP status |
|---|---|
| `ResourceNotFoundException` | 404 |
| `IllegalArgumentException` | 400 |
| `MethodArgumentNotValidException` (validation) | 400 — kèm map `field → message` |
| `DuplicateResourceException` | 409 |
| `BadCredentialsException` / `DisabledException` / `AuthenticationException` khác | 401 — message cố định, không trả message của Spring Security |
| `LoginThrottledException` (sai mật khẩu quá nhiều lần) | 429 — kèm header `Retry-After` |
| `Exception` khác | 500 |

Lỗi dispatch cấp servlet (ví dụ 404 URL không tồn tại) do `GlobalErrorController` xử lý — URI prefix `/api/*` vẫn nhận JSON `ErrorResponse`, không bao giờ nhận HTML.

## Quy Ước Khi Thêm API Mới

- Đặt controller trong `controller.api`, base path `/api/v1/...`, thêm `@Tag` để Swagger gom nhóm (springdoc chỉ scan package `controller.api`).
- Request/response dùng DTO trong `dto/request` / `dto/response` — **không expose Entity**.
- Khai báo rule phân quyền cho path mới trong `apiSecurityFilterChain` (`SecurityConfig`).
- Exception nghiệp vụ mới → thêm handler vào `ApiExceptionHandler`, không sửa `GlobalErrorController`.
