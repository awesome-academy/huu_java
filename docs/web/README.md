# 🛒 Web — Storefront Người Dùng (SSR)

Trang bán hàng cho người dùng cuối, render phía server bằng Thymeleaf + Bootstrap 5 + Vanilla JS. Khách vãng lai xem được menu/sản phẩm; các tính năng giỏ hàng, đặt hàng, đánh giá, góp ý yêu cầu đăng nhập (`ROLE_USER` hoặc `ROLE_ADMIN`).

> Quay lại [README tổng quan](../../README.md) · Xem thêm: [Admin](../admin/README.md) · [API](../api/README.md)

## Tổng Quan

| Thành phần | Vị trí |
|---|---|
| Controllers | `com.huuhv.foodsndrinks.controller.web` |
| Templates | `src/main/resources/templates/web/` (layout: `web/layout/base.html`, Thymeleaf Layout Dialect) |
| Security | Chain `webSecurityFilterChain` (`@Order(3)`) — session-based, form login + OAuth2, remember-me 7 ngày |
| Xử lý lỗi | `WebExceptionHandler` (`@ControllerAdvice` scoped package web) + view `error/{404,409,500}` |
| Model advice | `WebLayoutModelAdvice` — inject `cartItemCount` (badge giỏ hàng trên navbar, 0 với khách) |

## Xác Thực

### Form login & đăng ký — `AuthController`

| Method | Path | Mô tả |
|---|---|---|
| GET | `/login` | Trang đăng nhập (form + nút social login) |
| GET / POST | `/register` | Đăng ký tài khoản (check trùng, check password nhập lại) → redirect `/login` |
| POST | `/logout` | Đăng xuất → `/login?logout=true`, xóa session + remember-me cookie |
| GET | `/csrf-token` | (`CsrfTokenController`, cần đăng nhập) JSON `{"headerName", "token"}` — token CSRF đã mask của session hiện tại; `static/js/realtime.js` gọi trước mỗi lần nối lại WebSocket vì session có thể đã đổi từ lúc render trang. Chưa đăng nhập → `/login`. Không cache, không mở CORS |

Sau khi login: `ADMIN` → `/admin`, `USER` → `/` (hoặc quay lại trang yêu cầu trước đó).

- **Đăng nhập sai:** mọi lỗi → `/login?error=true` với một thông báo chung (không phân biệt sai username/email hay sai mật khẩu).
- **Giới hạn đăng nhập sai:** sai 5 lần trong 15 phút với cùng username/email từ cùng IP (hoặc 20 lần từ một IP với mọi tên, hoặc 50 lần với một tài khoản từ mọi IP) → khoá 15 phút → `/login?locked=true` (thông báo chung, áp dụng cả với tên không tồn tại). Bộ đếm dùng chung với API login — chi tiết ở [docs/api](../api/README.md#giới-hạn-đăng-nhập-sai). OAuth2 login không bị ảnh hưởng.
- **Đổi mật khẩu ở hồ sơ:** sai mật khẩu hiện tại 5 lần trong 15 phút → đăng xuất (huỷ session + cookie remember-me) → `/login?reauth=true`; đổi mật khẩu bị khoá 15 phút. Audit `PASSWORD_CHANGE_LOCKOUT`.
- **Giới hạn đăng ký:** tối đa 10 lần gửi form `POST /register` mỗi giờ từ một IP (`app.security.registration.{max-per-ip,window}`); vượt → `/register?throttled=true`, audit `REGISTRATION_THROTTLED`.
- **Chính sách mật khẩu** (đăng ký, đổi mật khẩu ở hồ sơ, admin đặt lại mật khẩu — `@ValidPassword` + `@NotCompromisedPassword` / `PasswordPolicy`): 8–64 ký tự, không giới hạn loại ký tự, tối đa 72 byte UTF-8 (giới hạn của BCrypt — chỉ ảnh hưởng mật khẩu dài nhiều ký tự có dấu), và không nằm trong danh sách mật khẩu bị lộ (Pwned Passwords, chỉ gửi 5 ký tự đầu SHA-1; chỉ gọi khi mọi field khác của form đã hợp lệ; cache theo prefix 10 phút; dịch vụ lỗi/timeout thì cho qua và ghi WARN; tắt bằng `PASSWORD_BREACH_CHECK_ENABLED=false`).
- **Social login trùng email:** Google/Facebook chỉ tự gắn vào tài khoản có sẵn cùng email khi provider đã xác minh email VÀ tài khoản đó không có mật khẩu (được tạo bằng social login). Email trùng tài khoản đăng ký bằng mật khẩu → `/login?oauthEmailExists=true` (đăng nhập bằng mật khẩu), audit `LOGIN_FAILURE reason=EMAIL_CONFLICT`. Email chưa xác minh không được lưu cho tài khoản mới.

### Social login (OAuth2)

Hỗ trợ **Google** (OIDC), **Facebook**, **Twitter/X** — cấu hình trong `application.yml`, cần set env `*_CLIENT_ID` / `*_CLIENT_SECRET` tương ứng:

- PKCE được bật cho mọi provider (`pkceAuthorizationRequestResolver`).
- Google map tài khoản theo provider id, sau đó theo email đã verify; xử lý ở `CustomOidcUserService`.
- Mọi provider đều dùng username nội bộ làm principal key (claim/attribute tổng hợp `local_username`), nên `Authentication#getName()` (audit log, MDC `user.name`) giống nhau dù đăng nhập bằng form, API, Google, Facebook hay X.
- Tài khoản local đã bị admin khoá không đăng nhập được bằng bất kỳ provider nào (giống form/API): cả hai service ném `DisabledOAuth2AccountException` → `/login?error=true` với thông báo chung, audit ghi `LOGIN_FAILURE reason=DISABLED auth.method=OAUTH2:<provider>` kèm username.
- **Đổi mật khẩu đăng xuất các thiết bị khác:** khi bạn đổi mật khẩu ở hồ sơ, mọi session khác của tài khoản (trình duyệt/thiết bị đăng nhập bằng mật khẩu cũ) bị huỷ ở request kế tiếp của chúng — audit `SESSION_REVOKED`, `event.reason=PASSWORD_CHANGED`; cookie remember-me cũ cũng hết hiệu lực. Session vừa đổi mật khẩu được giữ lại (đổi session id, cấp lại cookie remember-me nếu trình duyệt đang ghi nhớ đăng nhập). Admin đặt lại mật khẩu của một tài khoản thì **mọi** session của tài khoản đó bị huỷ, phải đăng nhập lại bằng mật khẩu mới. Tài khoản chưa từng đổi mật khẩu (kể cả tài khoản chỉ đăng nhập Google/Facebook/X) không bị ảnh hưởng.
- Người dùng đang đăng nhập (mọi cách: form, remember-me, OAuth2) mà bị admin khoá hoặc đổi role thì session bị huỷ ở request kế tiếp (`AccountStatusFilter`): cookie remember-me bị xoá, trang public hiển thị như khách, trang cần đăng nhập chuyển về `/login`; audit ghi `SESSION_REVOKED`. Đổi role (kể cả nâng quyền) cũng phải đăng nhập lại để nhận quyền mới.
- Facebook/Twitter xử lý ở `CustomOAuth2UserService`; Twitter là provider cấu hình thủ công (không nằm trong `CommonOAuth2Provider`).
- Provider của tài khoản lưu ở enum `AuthProvider`.

## Chức Năng & Routes

### Menu & sản phẩm — `WebController`

| Method | Path | Auth | Mô tả |
|---|---|---|---|
| GET | `/`, `/menu` | Public | Trang menu: filter, sort, phân trang sản phẩm |
| GET | `/categories/{slug}` | Public | Sản phẩm theo danh mục |
| GET | `/products/{slug}` | Public | Chi tiết sản phẩm + danh sách đánh giá (+ đánh giá của tôi nếu đã login) |
| GET | `/contact` | Public | Trang liên hệ |

### Giỏ hàng & checkout — `CartController`

| Method | Path | Auth | Mô tả |
|---|---|---|---|
| GET | `/cart` | USER | Trang giỏ hàng |
| POST | `/cart/add` | USER | Thêm sản phẩm (AJAX, trả JSON `CartPayloadResDto`) |
| POST | `/cart/update/{detailId}` | USER | Đổi số lượng (AJAX, JSON) |
| POST | `/cart/remove/{detailId}` | USER | Xóa item (AJAX, JSON) |
| POST | `/cart/checkout` | USER | Đặt hàng → redirect `/orders` (đồng thời bắn Slack/email cho admin) |

Các endpoint AJAX vẫn thuộc chain web (session + CSRF token), không phải API JWT.

Lỗi của ba endpoint AJAX cũng là JSON `{"message": "..."}` (`main.js` hiện `message` thành toast), không phải trang lỗi HTML:

| Mã | Khi nào |
|---|---|
| `404` | Sản phẩm không tồn tại (`/cart/add`); món `detailId` không tồn tại **hoặc thuộc giỏ/đơn của người khác** (`update`, `remove`) — hai trường hợp trả lời giống hệt nhau nên không dò được id của người khác |
| `400` | Sản phẩm đã ngừng kinh doanh; món thuộc một đơn **đã đặt** của chính bạn (không còn là giỏ hàng) |
| `403` | Thiếu/sai CSRF token |

Chưa đăng nhập → redirect `/login` như mọi trang.

### Đơn hàng — `OrderController`

| Method | Path | Auth | Mô tả |
|---|---|---|---|
| GET | `/orders` | USER | Lịch sử đơn hàng của tôi (phân trang) |
| GET | `/orders/{id}` | USER | Chi tiết đơn hàng (chỉ đơn của chính mình) |
| GET | `/orders/statuses?ids=1&ids=2` | USER | (`OrderStatusController`) JSON trạng thái hiện tại của tối đa 20 đơn — chỉ đơn của chính mình, id của người khác bị bỏ qua (chống IDOR). Thiếu/sai định dạng/quá 20 mã → 400; lỗi khác → 404/500 không body (không trả trang HTML cho `fetch()`) |

### Trạng thái đơn realtime

Trang `/orders` (khi có đơn) và `/orders/{id}` nối WebSocket `/ws-stomp` và SUBSCRIBE `/user/queue/orders` (`static/js/web/order-status.js` + `static/js/realtime.js`, cấu hình trong fragment `web/fragments/order-status-realtime.html`). Admin đổi trạng thái đơn → sau khi transaction commit (outbox → topic JMS `order.status-changed`), chủ đơn thấy badge trạng thái đổi (chữ + màu) và toast "Đơn #id: <trạng thái>", không cần tải lại trang.

- **Chỉ chủ đơn nhận:** server gửi bằng `convertAndSendToUser(<username chủ đơn lấy từ DB>, "/queue/orders", ...)`; Spring chỉ chuyển tới phiên WebSocket có cùng tên principal — username local với mọi kiểu đăng nhập (form, remember-me, Google/Facebook/X). User khác đang online không nhận; SUBSCRIBE `/user/<tên khác>/queue/orders` hay `/queue/...` trực tiếp bị từ chối (`Access denied`).
- **Luôn là trạng thái mới nhất:** mỗi lần đổi trạng thái là một message riêng, có thể tới sai thứ tự (gửi lại sau khi broker lỗi). Server không dùng trạng thái trong message mà đọc trạng thái hiện tại của đơn từ DB lúc gửi (lọc theo chủ đơn), nên trang không bao giờ lùi về trạng thái cũ.
- SUBSCRIBE `/user/queue/orders` lần thứ hai trên cùng kết nối, hoặc mở quá 10 kết nối/tab trên một instance → bị từ chối (chống DoS).
- Transaction đổi trạng thái rollback → không ai nhận. Không phát cho giỏ hàng (`CART`).
- Sau mỗi lần kết nối — kể cả lần đầu (sự kiện giữa lúc tải trang và lúc kết nối xong) và khi nối lại (mất mạng, instance tắt/rolling deploy) — trang gọi `GET /orders/statuses` cho các đơn đang hiện để bù sự kiện lỡ, không toast; gọi thêm một lần sau 1,5s vì server đăng ký SUBSCRIBE bất đồng bộ. Session hết hạn hoặc quá số tab → dừng và hiện thông báo "Tải lại trang" (như trang admin); lỗi mạng → thử lại mãi, tab hiện lại thì nối ngay.
- Toast chỉ hiện khi trạng thái khác trạng thái trang đang biết của đơn đó — hai lần đổi liên tiếp có thể tới thành hai sự kiện cùng mang trạng thái mới nhất, chỉ báo một lần.
- Payload: `{orderId, status, statusLabel, badgeClass}` (`OrderStatusEventResDto`) — không có dữ liệu cá nhân.

### Đánh giá sản phẩm — `RatingController`

| Method | Path | Auth | Mô tả |
|---|---|---|---|
| POST | `/products/{slug}/rate` | USER | Gửi đánh giá (số sao + bình luận) → redirect về trang sản phẩm |

Dưới mỗi sản phẩm có nút chia sẻ mạng xã hội (client-side, trong template chi tiết sản phẩm).

### Góp ý — `SuggestionController`

| Method | Path | Auth | Mô tả |
|---|---|---|---|
| GET | `/suggest` | USER | Danh sách góp ý của tôi (phân trang) |
| POST | `/suggest` | USER | Gửi góp ý mới |

### Hồ sơ cá nhân — `ProfileController`

| Method | Path | Auth | Mô tả |
|---|---|---|---|
| GET | `/profile` | USER | Trang hồ sơ (thông tin + đơn gần đây + số item giỏ hàng) |
| GET / POST | `/profile/edit` | USER | Cập nhật hồ sơ (validate, check trùng). Đổi mật khẩu ở đây huỷ mọi session khác của tài khoản — xem [Xác thực](#xác-thực) |

## Xử Lý Lỗi

- Exception nghiệp vụ (`ResourceNotFoundException` → 404, `DuplicateResourceException` → 409, khác → 500) do `WebExceptionHandler` bắt, render view `error/*`.
- Lỗi dispatch cấp servlet do `GlobalErrorController` xử lý — URI ngoài `/api/*` và `/admin/*` nhận view lỗi web.
- Thông báo trên UI dùng `RedirectAttributes` (flash message).

## Quy Ước Template

- Layout chung `web/layout/base.html` (Thymeleaf Layout Dialect) — trang con chỉ định nghĩa fragment content.
- **Không hardcode domain** hay đường dẫn tuyệt đối — dùng cú pháp context-path của Thymeleaf `@{/duong-dan}`.
- Static assets: `/css`, `/js`, `/images` (trong `src/main/resources/static`), ảnh sản phẩm upload serve qua `/uploads/**`. Bootstrap/Font Awesome/Chart.js tự host qua WebJars: `@{/webjars/bootstrap/css/bootstrap.min.css}` (không ghi version, không dùng CDN).
- **Content-Security-Policy** (`SecurityConfig.CONTENT_SECURITY_POLICY`): `script-src 'self'` — không `<script>` inline, không thuộc tính `onclick`/`onsubmit`/`th:on*`, không `javascript:`. JS đặt trong `static/js/**`, gắn sự kiện bằng `addEventListener` (thường là delegate trên `document` + `data-action="..."`), dữ liệu từ server qua `data-*` hoặc endpoint JSON. Thuộc tính `style="..."` vẫn được phép, thẻ `<style>` thì không. Ảnh ngoài chỉ từ host avatar OAuth2 (Google/Facebook/Twitter). Test `TemplateCspComplianceTest` quét toàn bộ template.
