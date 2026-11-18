# 🍔 F&B Store — Foods & Drinks

Mock project website bán đồ ăn & thức uống, xây dựng bằng Spring Boot. Dự án gồm **3 phần** chạy chung trong 1 ứng dụng:

| Phần | Đường dẫn | Mô tả | Tài liệu chi tiết |
|---|---|---|---|
| **Admin** | `/admin/**` | Trang quản trị SSR (Thymeleaf): dashboard, quản lý sản phẩm, danh mục, đơn hàng, người dùng, góp ý | [docs/admin/README.md](docs/admin/README.md) |
| **API** | `/api/v1/**` | REST API (JWT) chia sẻ để xây trang người dùng (SPA/mobile) | [docs/api/README.md](docs/api/README.md) |
| **Web** | `/`, `/menu`, ... | Storefront SSR cho người dùng cuối: xem menu, giỏ hàng, đặt hàng, đánh giá sản phẩm | [docs/web/README.md](docs/web/README.md) |

## Tech Stack

- **Ngôn ngữ:** Java 25
- **Framework:** Spring Boot 4.x (`spring-boot-starter-parent` 4.0.7)
- **Build tool:** Maven (Maven Wrapper đi kèm)
- **Database:** MySQL 8 + Spring Data JPA / Hibernate
- **Migration:** Flyway (`src/main/resources/db/migration`)
- **View:** Thymeleaf + Thymeleaf Layout Dialect, Bootstrap 5, Vanilla JS
- **Security:** Spring Security — Form Login + OAuth2 (Google/Facebook/Twitter) cho Web, JWT (jjwt 0.12) cho API
- **Khác:** Lombok, ModelMapper, Caffeine Cache, Spring Mail (Mailtrap), Slack Incoming Webhook, springdoc-openapi 3.x (Swagger UI), Actuator

## Prerequisites

- **Docker** (Docker Desktop trên Windows/Mac) — đủ để build, chạy và test toàn bộ dự án, **không cần cài JDK/Maven/MySQL**.
- *(Tuỳ chọn)* **JDK 25** nếu muốn chạy app trực tiếp trên máy (pom.xml pin `<java.version>25</java.version>` — JDK 17/21 không build được). Không cần cài Maven — dùng Maven Wrapper (`mvnw.cmd` trên Windows, `./mvnw` trên Mac/Linux).

## Quick Start

1. **Tạo file `.env`** từ mẫu và điền các giá trị bắt buộc (`DB_PASSWORD`, `MYSQL_ROOT_PASSWORD`, `ARTEMIS_PASSWORD`; các mục tuỳ chọn như OAuth, mail, Slack xem giải thích trong file):

   ```bash
   cp .env.example .env
   ```

   `.env` đã có trong `.gitignore` — **không commit**. Biến không dùng thì để nguyên dấu `#` (dòng `KEY=` rỗng sẽ ghi đè giá trị mặc định thành rỗng).

2. **Chạy ứng dụng** — Flyway tự tạo bảng khi khởi động.

   **Cách A — Toàn bộ trong Docker (không cần JDK):**

   ```bash
   docker compose up -d --build     # MySQL 8.4 + ActiveMQ Artemis + app (profile dev)
   docker compose logs -f app       # Xem log app
   ```

   **Cách B — Hạ tầng trong Docker, app chạy bằng JDK 25 trên máy:** bỏ comment dòng `DB_FOODSNDRINKS_URL` trong `.env` (trỏ tới MySQL của compose ở `localhost:33070`), rồi:

   ```bash
   docker compose up -d mysql artemis

   mvnw.cmd spring-boot:run     # Windows
   ./mvnw spring-boot:run       # Mac/Linux
   ```

   App tự đọc `.env` ở thư mục chạy lệnh (`spring.config.import: optional:file:.env[.properties]`); biến môi trường của hệ điều hành vẫn được ưu tiên. Muốn dùng MySQL cài sẵn trên máy thì giữ `DB_FOODSNDRINKS_URL` bị comment và đặt `DB_USERNAME`/`DB_PASSWORD` của MySQL đó bằng biến môi trường.

3. **Truy cập:**
   - Web (người dùng): http://localhost:8080
   - Admin: http://localhost:8080/admin (cần tài khoản `ROLE_ADMIN`)
   - Swagger UI (chỉ dev): http://localhost:8080/api-docs/swagger
   - Artemis console: http://localhost:8161 (đăng nhập bằng `ARTEMIS_USER` / `ARTEMIS_PASSWORD`)

4. **Tạo tài khoản admin** (database mới chưa có dữ liệu): đăng ký user tại `/register`, sau đó nâng quyền:

   ```bash
   docker compose exec mysql sh -c 'mysql -u root -p"$MYSQL_ROOT_PASSWORD" foodsndrinks -e "UPDATE users SET role = '\''ROLE_ADMIN'\'' WHERE username = '\''<username>'\''"'
   ```

   Đăng nhập lại để nhận quyền mới.

**Build & test:**

```bash
# Trong Docker (container JDK 25, dùng MySQL của compose — không cần JDK local)
docker compose run --rm maven test                          # Toàn bộ test
docker compose run --rm maven test -Dtest=AuthProductApiTest # Chạy 1 class test
docker compose run --rm maven clean package -DskipTests     # Build file JAR vào target/

# Hoặc bằng JDK local (cần MySQL: compose ở Cách B hoặc MySQL trên máy)
./mvnw test
./mvnw clean package -DskipTests

docker compose up -d --build app     # Build lại image và chạy app sau khi sửa code
docker compose down                  # Dừng tất cả (thêm -v để xoá cả dữ liệu MySQL/Artemis)
```

Test hiện tại là `@SpringBootTest` chạy trên database `foodsndrinks` (của compose hoặc của máy) và rollback sau mỗi test.

## Project Structure

Kiến trúc 3 lớp (3-Tier), package gốc `com.huuhv.foodsndrinks`:

```
src/main/java/com/huuhv/foodsndrinks/
├── config/          # SecurityConfig, WebMvcConfig, AsyncConfig, OpenApiConfig, AppConfig
├── controller/
│   ├── admin/       # Controller trang quản trị (SSR)  → docs/admin/README.md
│   ├── api/         # REST controller /api/v1/**       → docs/api/README.md
│   ├── web/         # Controller storefront (SSR)      → docs/web/README.md
│   └── GlobalErrorController.java  # Xử lý lỗi dispatch cấp servlet (/error)
├── dto/
│   ├── request/     # DTO hứng dữ liệu vào (không dùng Entity cho View/API)
│   └── response/    # DTO trả ra
├── entity/          # User, Product, ProductImage, Category, Order, OrderDetail, Rating, Suggestion
├── enums/           # Role, AuthProvider, CategoryType, ProductType, OrderStatus, SuggestionStatus
├── exception/       # AdminExceptionHandler, WebExceptionHandler, ApiExceptionHandler + custom exceptions
├── logging/         # RequestIdFilter, MdcUserFilter (MDC), MdcTaskDecorator (MDC sang @Async), LogSanitizer + converter Logback chống log injection
├── repository/      # Spring Data JPA repositories
├── security/        # JwtUtil, JwtAuthenticationFilter, CustomUserDetailsService, OAuth2 user services, AuditingRememberMeServices, OAuth2LoginFailureHandler
├── service/         # Toàn bộ business logic (Controller chỉ gọi Service)
│   ├── audit/         # AuditLogService (logger AUDIT), AuthenticationAuditListener
│   └── notification/  # Slack/Email notification, order event listener, statistics scheduler
└── utils/           # SlugUtils, SecurityUtils...

src/main/resources/
├── db/migration/    # Flyway migrations
├── templates/       # Thymeleaf (admin/, web/, error/)
├── static/          # CSS, JS, images
├── application*.yml # Cấu hình theo profile
└── logback-spring.xml # Appender/pattern log theo profile (dev: console + file, prod: thêm audit.log)
```

**Quy ước quan trọng:** Constructor Injection qua `@RequiredArgsConstructor` + `private final` (không dùng field injection); Controller mỏng, logic nằm ở Service; query quan hệ dùng `LEFT JOIN FETCH` chống N+1. Xem thêm `CLAUDE.md`.

## Configuration

Profile mặc định là `dev` (đổi qua env `SPRING_PROFILES_ACTIVE=prod` khi deploy).

| File | Vai trò |
|---|---|
| `application.yml` | Cấu hình chung: mail (SMTP), OAuth2 client (Google/Facebook/Twitter), Slack webhook, cron thống kê |
| `application-dev.yml` | Datasource local, logging DEBUG, Swagger UI bật, JWT secret/remember-me key tự sinh ngẫu nhiên nếu không đặt, upload dir |
| `application-prod.yml` | Datasource từ env (bắt buộc), log level WARN/INFO, **Swagger tắt hoàn toàn** |
| `logback-spring.xml` | Appender + pattern log: `dev` và `prod` ghi console + file xoay vòng, `prod` thêm `audit.log`; không bật profile nào thì chỉ console (xem [Logging](#logging)) |

### Biến môi trường

Khai báo trong file `.env` (mẫu: [.env.example](.env.example)) hoặc bằng biến môi trường của hệ điều hành (được ưu tiên hơn `.env`). **Không đặt secret thật làm giá trị mặc định `${VAR:default}` trong `application*.yml`.**

| Biến | Bắt buộc | Mô tả |
|---|---|---|
| `DB_USERNAME` / `DB_PASSWORD` | ✅ | Tài khoản MySQL (với Docker: compose tạo user này lần đầu khởi động) |
| `MYSQL_ROOT_PASSWORD` / `DB_NAME` | Docker | Mật khẩu root và tên database của container MySQL |
| `ARTEMIS_USER` / `ARTEMIS_PASSWORD` | Docker | Tài khoản broker ActiveMQ Artemis của compose |
| `DB_HOST_PORT` / `APP_PORT` / `ARTEMIS_PORT` / `ARTEMIS_CONSOLE_PORT` | — | Port compose mở ra máy host (mặc định 33070 / 8080 / 61616 / 8161, chỉ bind `127.0.0.1`) |
| `APP_TZ` | Docker | Múi giờ JVM của container, compose truyền vào dưới tên `TZ` (mặc định `Asia/Ho_Chi_Minh`, đặt sẵn trong image; `docker run` không qua compose thì dùng `-e TZ=...`). Ngày/tháng của đơn và báo cáo tính theo múi giờ JVM → mọi instance phải dùng chung một giá trị. App chạy bằng JDK local không đọc biến này từ `.env` (dùng `-Duser.timezone`) |
| `HOST_UID` / `HOST_GID` | Linux/WSL2 nếu khác 1000 | UID/GID user máy host (`id -u` / `id -g`): container app chạy bằng user này để ghi được `uploads/`, `logs/` mà file vẫn thuộc user máy host (mặc định 1000). Container maven tự chạy bằng chủ sở hữu thư mục project (`docker/maven-entrypoint.sh`), không dùng biến này |
| `DB_FOODSNDRINKS_URL` | Prod | JDBC URL (dev mặc định `jdbc:mysql://localhost:3306/foodsndrinks`) |
| `JWT_SECRET` | Prod | Secret ký JWT, ≥ 32 byte / 256 bit (dev không đặt thì tự sinh khoá random 256 bit mỗi khi context khởi động — token cũ hết hiệu lực sau restart, kể cả hot restart của devtools) |
| `REMEMBER_ME_KEY` | Prod | Key cho remember-me cookie (dev tự sinh random 256 bit) |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | Khi dùng login Google | OAuth2 credentials từ Google Console |
| `FACEBOOK_CLIENT_ID` / `FACEBOOK_CLIENT_SECRET` | Khi dùng login Facebook | OAuth2 credentials |
| `TWITTER_CLIENT_ID` / `TWITTER_CLIENT_SECRET` | Khi dùng login Twitter/X | OAuth2 credentials |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_PASSWORD` | Khi gửi mail thật | SMTP (mặc định Mailtrap sandbox) |
| `ADMIN_NOTIFY_EMAIL` | — | Email nhận thông báo đơn hàng & báo cáo tháng |
| `SLACK_WEBHOOK_URL` / `SLACK_NOTIFY_ENABLED` | — | Slack incoming webhook thông báo đơn mới |
| `MAIL_NOTIFY_ENABLED` | — | Bật/tắt email thông báo |
| `ORDER_STATS_CRON` | — | Cron gửi báo cáo tháng (mặc định `0 0 23 L * *`, 23h ngày cuối tháng, theo múi giờ JVM — `APP_TZ` khi chạy bằng compose) |
| `UPLOAD_DIR` | — | Thư mục lưu ảnh upload (mặc định `./uploads`) |
| `SERVER_PORT` | — | Port (mặc định 8080) |
| `INSTANCE_ID` | Khi chạy nhiều instance | Định danh instance: in trong mỗi dòng log, `service.node.name` của `audit.log`, tên thư mục log `logs/<INSTANCE_ID>/` ở prod. Mặc định hostname (container/pod có hostname riêng); mỗi instance phải khác nhau |
| `TRUSTED_PROXIES` | Prod | Dải IP (CIDR, cách nhau bởi dấu phẩy) của load balancer/reverse proxy. Prod chỉ tin `X-Forwarded-For`/`X-Forwarded-Proto` từ các IP này để lấy IP client thật (audit log `source.ip`) và scheme https. **Bắt buộc ở prod** (không có default; thiếu hoặc rỗng thì app không khởi động). Chỉ khai báo đúng dải IP của load balancer — không dùng cả dải nội bộ như `172.16.0.0/12`, vì khi app chạy trong Docker mọi kết nối từ ngoài vào port publish đều tới từ gateway của bridge, ai cũng giả được IP. Prod không có load balancer thì đặt `127.0.0.1/32` (chỉ tin chính máy chủ) |

## Database Migrations

Dùng **Flyway**, tự chạy khi ứng dụng khởi động. File đặt tại `src/main/resources/db/migration` với quy ước tên `V<yyyyMMddHHmmss>__<mo_ta>.sql`:

```
V20260624150700__create_table_users.sql
V20260624150710__create_table_categories.sql
V20260624150720__create_table_products.sql
V20260624150730__create_table_product_images.sql
V20260624150740__create_table_orders.sql
V20260624150750__create_table_order_details.sql
V20260624150760__create_table_ratings.sql
V20260624150770__create_table_suggestions.sql
V20260624150780__create_indexes.sql
V20260702000001__alter_users_phone_nullable.sql
```

Quy ước: tên bảng số nhiều (`users`, `products`...). **Không sửa migration đã chạy** — muốn đổi schema thì thêm file version mới.

## Notification & Batch Job

- **Đơn hàng mới:** sau khi checkout commit thành công (`OrderPlacedEvent` + `@TransactionalEventListener`), hệ thống gửi **Slack** + **email** cho admin (chạy async qua thread pool `notify-*`).
- **Báo cáo tháng:** `OrderStatisticsScheduler` chạy theo cron (mặc định 23h ngày cuối tháng, theo múi giờ JVM — cùng múi giờ dùng để tính tháng) — tổng hợp doanh thu, số đơn theo trạng thái và email cho admin.

## Xử Lý Lỗi

- Mỗi luồng có `@ControllerAdvice` riêng cùng package: `AdminExceptionHandler` (view lỗi admin), `WebExceptionHandler` (view lỗi web), `ApiExceptionHandler` (JSON `ErrorResponse`).
- `GlobalErrorController` (`/error`) chỉ xử lý lỗi dispatch cấp servlet (404/403/405/409 và exception thoát khỏi mọi advice) — tự chọn trả JSON hay view theo prefix URI.

## Logging

- Mỗi dòng log có `[instance] [requestId] [user]` (`instance` = `INSTANCE_ID`, mặc định hostname — phân biệt instance khi chạy nhiều instance sau load balancer). `requestId` luôn do server sinh (UUID), được trả lại trong response header `X-Request-Id` và được mang sang thread `@Async`. Khi người dùng báo lỗi, lấy `X-Request-Id` của response để `grep` toàn bộ log của request đó. Nếu client gửi header `X-Request-Id` hợp lệ (chữ/số/`-`, ≤ 64 ký tự), giá trị đó chỉ được ghi kèm thành `[<requestId> cid=<id của client>]`, không dùng làm requestId (tránh giả mạo id của request khác).
- Chống log injection: ký tự ngắt dòng (CR, LF, U+0085, U+2028, U+2029) và ký tự điều khiển/vô hình trong message, message của exception và giá trị audit bị thay bằng `_`. Hệ quả: message nhiều dòng hợp lệ (vd. báo cáo của devtools) cũng bị gộp thành 1 dòng.
- Profile `dev` và `prod` ghi thêm file trong `logs/` (đổi thư mục bằng `LOGGING_FILE_PATH`); riêng `prod` ghi vào thư mục con `logs/<INSTANCE_ID>/` để nhiều instance dùng chung thư mục log (vd. cùng bind mount `./logs`) không ghi đè/xoay vòng/xoá file của nhau. File xoay vòng theo ngày hoặc khi vượt 10MB, nén `.gz`. `foodsndrinks.log` được ghi bất đồng bộ (`AsyncAppender`, không bỏ log nào); `audit.log` chỉ có ở `prod` và ghi đồng bộ. Khi chạy test luôn có thêm profile `test` (`src/test/resources/config/application.yml`) nên log của test chỉ ra console.

  | File | Nội dung | Định dạng | Giữ lại |
  |---|---|---|---|
  | `logs/foodsndrinks.log` (prod: `logs/<INSTANCE_ID>/foodsndrinks.log`) | Toàn bộ log của app (kể cả audit) | Text | 30 ngày, tối đa 1GB / instance |
  | `logs/<INSTANCE_ID>/audit.log` (chỉ prod) | Chỉ logger `AUDIT` (sự kiện bảo mật); có `service.node.name` = `INSTANCE_ID` | JSON 1 dòng/sự kiện ([ECS](https://www.elastic.co/guide/en/ecs/current/index.html)) | 90 ngày, tối đa 500MB |

- **Audit log** (logger `AUDIT`, luôn ở mức INFO kể cả ở prod): đăng nhập thành công/thất bại (`auth.method` = `FORM`, `API`, `REMEMBER_ME`, `OAUTH2:<provider>`; gồm cả cookie remember-me bị từ chối), admin khoá/mở khoá user, đổi role, đặt lại mật khẩu, đổi email, user tự đổi mật khẩu (thành công/sai mật khẩu hiện tại), đổi trạng thái đơn. Chỉ ghi username/id, hành động và IP — không ghi password, token, session id. Ở prod sau load balancer, `source.ip` là IP client thật lấy từ `X-Forwarded-For` (chỉ khi request đến từ `TRUSTED_PROXIES`, xem bảng biến môi trường); username nhập khi đăng nhập sai chỉ được ghi nếu tài khoản tồn tại (`username=-` nếu không). Sự kiện thay đổi dữ liệu chỉ được ghi sau khi transaction commit. Ví dụ trên console / `foodsndrinks.log` (giá trị có khoảng trắng, `=`, `"` được đặt trong nháy kép):

  ```
  Login failed event.action=LOGIN_FAILURE event.outcome=failure event.reason=BAD_CREDENTIALS auth.method=FORM username=alice source.ip=127.0.0.1
  User role changed event.action=USER_ROLE_CHANGED event.outcome=success actor=admin target.id=5 target.username=dave target.role.from=ROLE_USER target.role.to=ROLE_ADMIN source.ip=127.0.0.1
  ```

  Cùng sự kiện trong `audit.log` (rút gọn):

  ```json
  {"@timestamp":"…","log":{"level":"WARN","logger":"AUDIT"},"message":"Login failed","requestId":"…","event":{"action":"LOGIN_FAILURE","outcome":"failure","reason":"BAD_CREDENTIALS"},"auth":{"method":"FORM"},"username":"alice","source":{"ip":"127.0.0.1"},"ecs":{"version":"8.11"}}
  ```

## Monitoring

- Actuator: `/actuator/health` public; các endpoint khác (`info`, `metrics`, `caches` — chỉ dev) yêu cầu `ROLE_ADMIN`. Prod chỉ expose `health`.

## Tài Liệu Chi Tiết

- 🛠 [Admin — trang quản trị](docs/admin/README.md)
- 🔌 [API — REST API cho trang người dùng](docs/api/README.md)
- 🛒 [Web — storefront người dùng](docs/web/README.md)
