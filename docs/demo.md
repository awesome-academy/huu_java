# 🎬 Kịch Bản Demo — Khóa Advance

Kịch bản trình diễn từng công nghệ của khóa Advance, chạy hoàn toàn bằng Docker (không cần JDK/MySQL trên máy). Mỗi mục ghi **làm gì → thấy gì → vì sao đáng chú ý**. Chi tiết kỹ thuật nằm ở [README](../README.md) và `docs/{admin,web,api,soap}`.

## 0. Chuẩn Bị (một lần)

```bash
cp .env.example .env            # điền DB_PASSWORD, MYSQL_ROOT_PASSWORD, ARTEMIS_PASSWORD
docker compose up -d --build    # MySQL + Artemis + app → http://localhost:8080
docker compose logs -f app      # chờ dòng "Started FoodsndrinksApplication"
```

1. Mở `http://localhost:8080/register`, tạo hai tài khoản: `admin1` và `khach1`.
2. Nâng `admin1` thành admin (lệnh SQL trong [README — Quick Start](../README.md#quick-start)), rồi đăng nhập lại.
3. Vào `/admin/categories` và `/admin/products` tạo 1 danh mục + 2–3 sản phẩm (hoặc import bằng Excel ở mục 4).
4. *(Tuỳ chọn)* để thấy email/Slack: điền `MAIL_USERNAME`/`MAIL_PASSWORD` (Mailtrap) và `SLACK_WEBHOOK_URL` trong `.env`. Để thấy tỷ giá USD ở mục 5: điền ba dòng `SOAP_EXCHANGE_RATE_*` (xem `.env.example`). Sau khi sửa `.env`: `docker compose up -d app`.

Dùng **hai trình duyệt** (hoặc một cửa sổ thường + một cửa sổ ẩn danh): A = `admin1`, B = `khach1`.

## 1. Đặt Hàng → JMS → Email/Slack + Admin Nhận Realtime

| Làm | Thấy |
|---|---|
| A mở `/admin` (dashboard). B thêm sản phẩm vào giỏ → `/cart` → **Đặt hàng** | A: toast "Đơn mới" + badge "đơn chờ xác nhận" tăng **không cần tải lại trang**; biểu đồ trạng thái tự vẽ lại sau ~1–3 giây |
| Mở Mailtrap / kênh Slack | Email HTML + tin nhắn Slack của đúng đơn đó (nếu đã cấu hình ở bước 0.4) |
| `docker compose exec mysql sh -c 'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -e "SELECT id,type,status,attempts FROM outbox_messages ORDER BY id DESC LIMIT 3; SELECT * FROM notification_logs ORDER BY id DESC LIMIT 4;"'` | Bản ghi outbox `orderPlaced` = `SENT`; mỗi kênh (EMAIL, SLACK) đúng **một** dòng `notification_logs` |

**Điểm nhấn — không mất thông báo khi broker chết (Transactional Outbox):**

```bash
docker compose stop artemis      # broker JMS chết
# B đặt thêm một đơn → vẫn đặt được bình thường
#   → outbox_messages: bản ghi mới ở trạng thái PENDING, attempts = 0
docker compose start artemis     # broker sống lại
#   → trong ≤ 1 phút job OutboxRelayJob gửi lại: PENDING → SENT, admin nhận thông báo, email/Slack được gửi
```

Web console của broker: `http://localhost:8161` (tài khoản `ARTEMIS_USER`/`ARTEMIS_PASSWORD`) — xem topic `order.placed` với hai subscription `order-placed-email`, `order-placed-slack` và hàng đợi `DLQ.order-placed`.

## 2. Admin Đổi Trạng Thái → Khách Nhận Realtime

| Làm | Thấy |
|---|---|
| B mở `/orders`. A vào `/admin/orders`, đổi đơn vừa đặt sang **Đang xử lý** | B: badge trạng thái đổi + toast, không tải lại trang. Khách khác đang online **không** nhận gì (đích `/user/queue/orders` theo từng người) |
| A đổi tiếp sang **Hoàn thành** | Dashboard của A: biểu đồ doanh thu + top sản phẩm cập nhật |
| **Hai admin cùng sửa một đơn:** mở chi tiết đơn ở hai tab của A; tab 1 chọn *Hoàn thành* → lưu; tab 2 (vẫn đang hiện trạng thái cũ) chọn *Đã hủy* → lưu | Tab 2: *"Đơn hàng vừa được người khác cập nhật, vui lòng tải lại trang"* — không ghi đè. Audit chỉ có một dòng `ORDER_STATUS_CHANGED` với `order.status.from` đúng |

## 3. Bảo Mật: Đăng Nhập Sai, Đổi Mật Khẩu, Audit, `requestId`

| Làm | Thấy |
|---|---|
| Ở `/login`, nhập sai mật khẩu của `khach1` **5 lần** | Lần kế tiếp (kể cả nhập đúng mật khẩu): bị chuyển tới `/login?locked=true` với thông báo tạm khoá — khoá 15 phút. Gỡ nhanh khi demo: `DELETE FROM attempt_limits;` |
| `docker compose logs app \| grep AUDIT \| tail -8` | Các dòng `LOGIN_FAILURE` (`event.reason=BAD_CREDENTIALS`), rồi `LOGIN_LOCKOUT` — có username, IP; **không** có mật khẩu |
| `curl -si http://localhost:8080/login \| grep -i x-request-id`, rồi `docker compose logs app \| grep <giá trị đó>` | Mỗi request một `requestId` do server sinh, in trong **mọi** dòng log của request đó (kể cả thread `@Async`) và trả về ở header `X-Request-Id` |
| **Đổi mật khẩu đăng xuất thiết bị khác:** `khach1` đăng nhập ở cả B và một trình duyệt thứ ba C. Ở B vào `/profile/edit` đổi mật khẩu | B vẫn đăng nhập. C tải lại trang → bị đưa về `/login`; audit `SESSION_REVOKED event.reason=PASSWORD_CHANGED` |
| A khoá `khach1` ở `/admin/users` | Request kế tiếp của B về `/login` (`SESSION_REVOKED event.reason=DISABLED`); socket realtime của B bị đóng trong ≤ 60 giây |

File log: `logs/foodsndrinks.log` (text). Profile `prod` có thêm `logs/<INSTANCE_ID>/audit.log` dạng JSON (ECS).

## 4. Excel (Apache POI)

| Làm | Thấy |
|---|---|
| `/admin/orders` → lọc theo ngày/trạng thái → **Export Excel** | File `.xlsx` gồm đúng các đơn đang lọc; audit `DATA_EXPORTED` |
| `/admin/products` → **Export Excel**, mở file, sửa giá một dòng + thêm một dòng sản phẩm mới | — |
| `/admin/products/import` → tải file vừa sửa lên | Chuyển ngay sang trang trạng thái: *Đang chờ xử lý → Đang xử lý → Hoàn thành* (1 thêm, 1 cập nhật, còn lại "không đổi") — file được xử lý **nền** qua queue JMS `excel.import` |
| Tải một file có dòng sai (giá âm, danh mục không tồn tại) | *Thất bại* + bảng lỗi theo dòng/cột; **không dòng nào** được lưu (tất cả hoặc không) |
| Tải file không phải `.xlsx` (đổi đuôi một file ảnh) | Bị từ chối ngay ở trang import (kiểm tra magic bytes, không tin đuôi file) |

## 5. SOAP (Spring-WS): Server Và Client

**Server** — hệ thống đối tác tra cứu sản phẩm / trạng thái đơn ([docs/soap](soap/README.md)):

```bash
curl -s http://localhost:8080/ws/foodsndrinks.wsdl | head -20          # WSDL sinh từ XSD (contract-first)
curl -si -X POST http://localhost:8080/ws | head -5                     # 401 + WWW-Authenticate, không body

curl -s -u khach1:'<mật khẩu>' http://localhost:8080/ws -H 'Content-Type: text/xml; charset=utf-8' \
  -d '<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"><soapenv:Body>
        <GetProductsRequest xmlns="urn:foodsndrinks:store"><size>2</size></GetProductsRequest>
      </soapenv:Body></soapenv:Envelope>'
```

| Làm | Thấy |
|---|---|
| `GetOrderStatusRequest` với mã đơn của `khach1`, gọi bằng tài khoản `khach1` | Trạng thái đơn |
| Cùng request, gọi bằng một tài khoản khách **khác** | Fault `Resource not found` — y hệt một mã đơn không tồn tại (chống IDOR) |
| `<size>101</size>` | Fault `Validation error` (request bị chặn theo XSD trước khi tới code) |
| Body có `<!DOCTYPE ...>` | `400` — không có XXE |

**Client** — app gọi dịch vụ tỷ giá (ở local là endpoint mock của chính app) để quy đổi doanh thu:

| Làm | Thấy |
|---|---|
| Với `SOAP_EXCHANGE_RATE_*` đã đặt (bước 0.4), A mở `/admin` | Thẻ "Doanh thu": `≈ … USD`, *Tỷ giá 1 USD = 25.4xx đ, lấy lúc HH:mm dd/MM* |
| Đổi `SOAP_EXCHANGE_RATE_PASSWORD` thành giá trị sai → `docker compose up -d app` → mở `/admin` | Trang vẫn tải nhanh; thẻ ghi **"Không lấy được tỷ giá"**; log có `WARN … Không lấy được tỷ giá USD/VND từ dịch vụ SOAP (HTTP_ERROR)` |

## 6. Nhiều Instance Sau Load Balancer (Session Dùng Chung)

```bash
docker compose --profile multi up -d --build     # app + app2 + nginx round-robin KHÔNG sticky → http://localhost:8088
docker compose --profile multi logs -f app app2  # ngoặc vuông thứ hai của mỗi dòng log là INSTANCE_ID (app2 = "app2")
```

| Làm | Thấy |
|---|---|
| Đăng nhập qua `http://localhost:8088`, tải lại trang nhiều lần | Vẫn đăng nhập dù request lần lượt rơi vào `app` và `app2` (session nằm trong bảng `SPRING_SESSION`) |
| A mở `/admin` ở hai tab (mỗi socket nối vào một instance), B đặt một đơn | Cả hai tab đều nhận thông báo (sự kiện đi qua topic JMS tới mọi instance) |
| `docker compose --profile multi stop app2` giữa lúc đang dùng | Không ai bị đăng xuất; trang realtime tự nối lại sang instance còn lại |

Dọn: `docker compose --profile multi down` (thêm `-v` để xoá dữ liệu).

## 7. Scheduler, Thread Pool, ShedLock

- Báo cáo tháng: đặt `ORDER_STATS_CRON=0 * * * * *` trong `.env` (cần cấu hình mail ở bước 0.4) → `ADMIN_NOTIFY_EMAIL` nhận email thống kê tháng trước. Cron kích hoạt mỗi phút nhưng ShedLock giữ khoá tối thiểu 5 phút nên email tới khoảng 5 phút một lần; với profile `multi`, **chỉ một** instance gửi (`SELECT * FROM shedlock;` — cột `locked_by` là instance đã chạy). Nhớ bỏ dòng này sau khi demo.
- Dashboard chạy 5 truy vấn thống kê song song trên pool `dashboard-*` (tên thread hiện trong log DEBUG `Dashboard aggregated in … ms (parallel)`).

## 8. Test, Coverage, Quét Lỗ Hổng

```bash
docker compose run --rm maven-docker test      # toàn bộ test (unit + MockMvc + Testcontainers MySQL + Tomcat thật)
#   → báo cáo coverage: target/site/jacoco/index.html
docker compose run --rm js-test                # unit test JS (node:test, không npm)
docker compose run --rm --no-deps maven dependency-check:check   # quét CVE; fail nếu CVSS ≥ 7
#   → target/dependency-check-report.html
```

Swagger UI của REST API (chỉ profile dev): `http://localhost:8080/api-docs/swagger`.
