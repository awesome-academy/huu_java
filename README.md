# 🍔 F&B Store — Foods & Drinks

Mock project website bán đồ ăn & thức uống, xây dựng bằng Spring Boot. Dự án gồm **3 phần** chạy chung trong 1 ứng dụng:

| Phần | Đường dẫn | Mô tả | Tài liệu chi tiết |
|---|---|---|---|
| **Admin** | `/admin/**` | Trang quản trị SSR (Thymeleaf): dashboard, quản lý sản phẩm, danh mục, đơn hàng, người dùng, góp ý | [docs/admin/README.md](docs/admin/README.md) |
| **API** | `/api/v1/**` | REST API (JWT) chia sẻ để xây trang người dùng (SPA/mobile) | [docs/api/README.md](docs/api/README.md) |
| **Web** | `/`, `/menu`, ... | Storefront SSR cho người dùng cuối: xem menu, giỏ hàng, đặt hàng, đánh giá sản phẩm | [docs/web/README.md](docs/web/README.md) |

## Tech Stack

- **Ngôn ngữ:** Java 25
- **Framework:** Spring Boot 4.x (`spring-boot-starter-parent` 4.0.10)
- **Build tool:** Maven (Maven Wrapper đi kèm)
- **Database:** MySQL 8 + Spring Data JPA / Hibernate
- **Migration:** Flyway (`src/main/resources/db/migration`)
- **View:** Thymeleaf + Thymeleaf Layout Dialect, Bootstrap 5, Vanilla JS
- **Security:** Spring Security — Form Login + OAuth2 (Google/Facebook/Twitter) cho Web, JWT (jjwt 0.12) cho API
- **Messaging:** JMS (Jakarta Messaging) với Apache Artemis 2.57 (trước đây là ActiveMQ Artemis) — broker embedded ở dev/test, broker riêng ở compose/prod
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
# Trong Docker (container JDK 25 — không cần JDK local)
docker compose run --rm maven-docker test                           # Toàn bộ test (mount Docker socket, xem cảnh báo bên dưới)
docker compose run --rm maven-docker test -Dtest=AuthProductApiTest # Chạy 1 class test
docker compose run --rm maven test -DexcludedGroups=integration # Chỉ test không cần Docker (không mount socket)
docker compose run --rm maven clean package -DskipTests             # Build file JAR vào target/

# Hoặc bằng JDK local (cần Docker đang chạy, không cần MySQL)
./mvnw test
./mvnw clean package -DskipTests

docker compose up -d --build app     # Build lại image và chạy app sau khi sửa code
docker compose down                  # Dừng tất cả (thêm -v để xoá cả dữ liệu MySQL/Artemis)
```

**Các loại test:**

| Loại | Ví dụ | Cần |
|---|---|---|
| Unit test Mockito (`@ExtendWith(MockitoExtension.class)`) | `OrderServiceTest`, `UserServiceTest` | — |
| Test slice `@WebMvcTest` + 3 filter chain thật (`@SecuredWebMvcTest`) | `CartControllerTest`, `OrderAdminControllerTest`, `ProductApiControllerTest` | — |
| Integration test `@IntegrationTest` (= `@SpringBootTest` + Testcontainers + profile `test`, tag `integration`) | `AuthProductApiTest`, `FoodsndrinksApplicationTests` | Docker |

Integration test chạy trên **MySQL 8.4 do Testcontainers tạo** (`TestcontainersConfiguration`, `@ServiceConnection`) — không đụng database dev, không cần `DB_USERNAME`/`DB_PASSWORD`. Test chỉ chạy với profile `test` (không bật `dev`), nên không dùng datasource, `JWT_SECRET` hay `REMEMBER_ME_KEY` trong `.env`. Không dùng `@SpringBootTest` trần (`IntegrationTestConventionTest` fail). Một container dùng chung cho cả lần chạy, bị xoá khi test kết thúc; mỗi test vẫn rollback bằng `@Transactional`. Lần chạy đầu tải image `mysql:8.4`.

> ⚠ **`maven-docker` mount Docker socket của máy host** để Testcontainers tạo container: mọi code chạy trong build (dependency, plugin, test) đều có quyền tương đương root trên máy. Chỉ dùng trên máy dev với code đã review; service `maven` (không có socket) vẫn dùng cho build/quét dependency.

**Coverage (JaCoCo):** `./mvnw test` (hoặc `maven-docker test`) sinh báo cáo `target/site/jacoco/index.html` (kèm `jacoco.xml`, `jacoco.csv`). Chưa đặt ngưỡng chặn build.

## Project Structure

Kiến trúc 3 lớp (3-Tier), package gốc `com.huuhv.foodsndrinks`:

```
src/main/java/com/huuhv/foodsndrinks/
├── config/          # SecurityConfig, WebMvcConfig, AsyncConfig, SchedulerLockConfig (ShedLock), OpenApiConfig, AppConfig
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
| `ARTEMIS_USER` / `ARTEMIS_PASSWORD` | Docker, Prod | Tài khoản broker ActiveMQ Artemis (compose tạo tài khoản này cho service `artemis`, container app dùng nó để kết nối) |
| `ARTEMIS_MODE` / `ARTEMIS_EMBEDDED_ENABLED` | — | Broker JMS: mặc định `embedded` / `true` (broker trong JVM — chạy bằng JDK local không cần cài gì). Dùng broker riêng thì đặt **cả hai** `native` / `false` (Spring Boot 4 bật broker embedded theo `ARTEMIS_EMBEDDED_ENABLED`, không xét mode — sai cặp thì app không khởi động). Container app của compose đã đặt sẵn `native`; prod luôn `native` (cố định trong `application-prod.yml`); test luôn embedded |
| `ARTEMIS_HOST` | Prod | Host broker khi `native` (dev mặc định `localhost`, compose tự trỏ tới service `artemis`). Port lấy từ `ARTEMIS_PORT` (mặc định 61616) |
| `DB_HOST_PORT` / `APP_PORT` / `ARTEMIS_PORT` / `ARTEMIS_CONSOLE_PORT` | — | Port compose mở ra máy host (mặc định 33070 / 8080 / 61616 / 8161, chỉ bind `127.0.0.1`) |
| `APP_TZ` | Docker | Múi giờ JVM của container, compose truyền vào dưới tên `TZ` (mặc định `Asia/Ho_Chi_Minh`, đặt sẵn trong image; `docker run` không qua compose thì dùng `-e TZ=...`). Ngày/tháng của đơn và báo cáo tính theo múi giờ JVM → mọi instance phải dùng chung một giá trị. App chạy bằng JDK local không đọc biến này từ `.env` (dùng `-Duser.timezone`) |
| `HOST_UID` / `HOST_GID` | Linux/WSL2 nếu khác 1000 | UID/GID user máy host (`id -u` / `id -g`): container app chạy bằng user này để ghi được `uploads/`, `logs/` mà file vẫn thuộc user máy host (mặc định 1000). Container maven tự chạy bằng chủ sở hữu thư mục project (`docker/maven-entrypoint.sh`), không dùng biến này |
| `DB_FOODSNDRINKS_URL` | Prod | JDBC URL (dev mặc định `jdbc:mysql://localhost:3306/foodsndrinks`) |
| `JWT_SECRET` | Prod | Secret ký JWT, ≥ 32 byte / 256 bit (dev không đặt thì tự sinh khoá random 256 bit mỗi khi context khởi động — token cũ hết hiệu lực sau restart, kể cả hot restart của devtools) |
| `JWT_ISSUER` / `JWT_AUDIENCE` | — | Claim `iss` / `aud` của JWT, bắt buộc khớp khi kiểm tra token (mặc định `foodsndrinks` / `foodsndrinks-api`; đổi thì token cũ hết hiệu lực) |
| `WEBSOCKET_ALLOWED_ORIGINS` | Prod | Origin được mở WebSocket `/ws-stomp` (vd. `https://shop.example.com`, cách nhau bởi dấu phẩy). Rỗng = chỉ same-origin (dev/test). **Bắt buộc ở prod** (rỗng thì app không khởi động): sau load balancer, header `Host` mà app nhận có thể khác origin public. Cùng quy tắc định dạng với `CORS_ALLOWED_ORIGINS` |
| `WEBSOCKET_REVALIDATE_INTERVAL` | — | Chu kỳ đóng kết nối WebSocket của tài khoản bị khoá/đổi role hoặc session đã kết thúc (mặc định `60s`, tối thiểu `1s`) |
| `CORS_ALLOWED_ORIGINS` | Khi SPA khác origin gọi API | Origin được gọi `/api/**` từ trình duyệt, cách nhau bởi dấu phẩy (vd. `https://app.example.com`). Rỗng = chỉ same-origin. Phải đúng dạng `scheme://host[:port]` (không `/` cuối), không `*`, không `null`; `http` chỉ cho localhost — sai thì app không khởi động |
| `REMEMBER_ME_KEY` | Prod | Key cho remember-me cookie (dev tự sinh random 256 bit) |
| `ATTEMPT_LIMITS_KEY_SECRET` | Prod | Khoá HMAC (≥ 32 ký tự) băm IP/tên đăng nhập trước khi lưu bộ đếm chống brute-force và giới hạn đăng ký vào bảng `attempt_limits` (dùng chung giữa các instance, không mất khi restart). Mọi instance phải dùng chung một giá trị; đổi giá trị thì mọi bộ đếm/khoá về 0. Dev không đặt thì tự sinh random 256 bit mỗi lần khởi động |
| `SESSION_COOKIE_SECURE` | — | Cờ `Secure` của cookie session (`JSESSIONID`, chỉ gửi qua HTTPS). Mặc định `false` ở dev (http://localhost), `true` ở prod |
| `NVD_API_KEY` | Khi chạy Dependency-Check | API key của NVD cho `dependency-check:check` (xem [Quét lỗ hổng thư viện](#quét-lỗ-hổng-thư-viện-owasp-dependency-check)); không dùng khi chạy app |
| `PASSWORD_BREACH_CHECK_ENABLED` | — | Từ chối mật khẩu mới nằm trong danh sách bị lộ qua Pwned Passwords (mặc định `true`; chỉ gửi 5 ký tự đầu SHA-1, lỗi/timeout thì cho qua). Test luôn tắt |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | Khi dùng login Google | OAuth2 credentials từ Google Console |
| `FACEBOOK_CLIENT_ID` / `FACEBOOK_CLIENT_SECRET` | Khi dùng login Facebook | OAuth2 credentials |
| `TWITTER_CLIENT_ID` / `TWITTER_CLIENT_SECRET` | Khi dùng login Twitter/X | OAuth2 credentials |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_PASSWORD` | Khi gửi mail thật | SMTP (mặc định Mailtrap sandbox) |
| `ADMIN_NOTIFY_EMAIL` | — | Email nhận thông báo đơn hàng & báo cáo tháng |
| `SLACK_WEBHOOK_URL` / `SLACK_NOTIFY_ENABLED` | — | Slack incoming webhook thông báo đơn mới |
| `MAIL_NOTIFY_ENABLED` | — | Bật/tắt email thông báo |
| `ORDER_STATS_CRON` | — | Cron gửi báo cáo tháng vừa kết thúc (mặc định `0 5 0 1 * *`, 00:05 ngày 1, theo múi giờ JVM — `APP_TZ` khi chạy bằng compose) |
| `UPLOAD_DIR` | — | Thư mục lưu ảnh upload (mặc định `./uploads`) |
| `SERVER_PORT` | — | Port (mặc định 8080) |
| `INSTANCE_ID` | Prod | Định danh instance: in trong mỗi dòng log, `service.node.name` của `audit.log`, tên thư mục log `logs/<INSTANCE_ID>/` ở prod. **Bắt buộc ở prod** (thiếu, rỗng hoặc sai định dạng — chỉ chữ, số, `.`, `_`, `-`, tối đa 64 ký tự — thì app không khởi động): tên cố định, khác nhau cho từng instance và giữ nguyên qua các lần deploy. Không dùng hostname vì container tạo lại có hostname mới → log ghi sang thư mục mới, thư mục cũ không bao giờ được dọn. Dev mặc định lấy hostname |
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
V20261120090000__create_table_shedlock.sql      # khoá ShedLock cho job @Scheduled (nhiều instance)
```

Quy ước: tên bảng số nhiều (`users`, `products`...). **Không sửa migration đã chạy** — muốn đổi schema thì thêm file version mới.

## Notification & Batch Job

- **Đơn hàng mới:** checkout ghi message `OrderPlacedMessage` vào Transactional Outbox cùng transaction với đơn, rồi gửi lên topic JMS `order.placed`; hai subscription độc lập gửi **email** và **Slack** cho admin (xem [Messaging](#messaging-jms--activemq-artemis)): kênh này lỗi chỉ kênh đó được thử lại, mỗi kênh gửi tối đa một lần cho mỗi đơn (bảng `notification_logs`). Email là HTML render bằng Thymeleaf từ `templates/mail/` (kèm bản chữ thuần cho client không hiển thị HTML); Slack dùng Block Kit (Slack không hiển thị HTML). Ở dev, email đi vào hộp thư Mailtrap (`MAIL_*` trong `.env`) để xem giao diện.
- **Báo cáo tháng:** `OrderStatisticsScheduler` chạy theo cron (mặc định 00:05 ngày 1, theo múi giờ JVM — cùng múi giờ dùng để tính tháng) — tổng hợp doanh thu, số đơn theo trạng thái của các đơn đặt trong tháng vừa kết thúc và email HTML (`templates/mail/monthly-statistics.html`) cho admin.

### Thread pool & chạy nhiều instance

- Thread pool (`config/AsyncConfig`, kích thước trong `app.async.*` của `application.yml`): `notify-*` (executor `@Async` mặc định; Slack/email đơn hàng chạy trên listener JMS), `dashboard-*` (truy vấn thống kê dashboard chạy song song, timeout `app.async.dashboard.timeout` — lỗi/quá giờ/pool đầy thì `/admin` trả 503), `outbox-*` (gửi bản ghi outbox ngay sau commit), `sched-*` (`@Scheduled`, `spring.task.scheduling.pool.size`). Pool `notify-*` đầy thì task chạy trên thread gọi (`CallerRunsPolicy`); pool `dashboard-*` đầy thì từ chối (`AbortPolicy`) để truy vấn không chạy trên thread request ngoài timeout; pool `outbox-*` đầy thì bỏ task (bản ghi vẫn `PENDING`, job gửi lại) — không bao giờ gửi trên thread request. Khi tắt app, Tomcat chờ request đang chạy và mỗi pool chờ task còn lại tối đa 30s.
- **Job định kỳ chỉ chạy trên một instance:** mọi instance đều kích hoạt cron, nhưng ShedLock (`config/SchedulerLockConfig`, bảng `shedlock` trong MySQL dùng chung, thời gian theo đồng hồ DB) chỉ cho một instance chạy — không gửi trùng báo cáo tháng. Cột `locked_by` = `INSTANCE_ID` của instance đã chạy.
- **Container cần đủ thời gian dừng:** service `app` của compose có `stop_grace_period: 60s` (mặc định của Docker là 10s rồi SIGKILL). Môi trường khác (Kubernetes `terminationGracePeriodSeconds`, ECS `stopTimeout`...) cũng đặt ≥ 60s.
- **Connection DB:** mỗi instance dùng tối đa `spring.datasource.hikari.maximum-pool-size` = 28 (`minimum-idle` 5). Thread nền có thể giữ connection cùng lúc — dashboard 4 + outbox 2 + listener JMS 4 (email, Slack, DLQ, WebSocket — consumer thông báo giữ connection lúc gọi Slack/SMTP) + `sched-*` 2 = 12 — không được quá nửa pool, kiểm tra lúc khởi động (`config/ConnectionBudgetCheck`, vượt thì app không khởi động): tăng pool nền hay thêm listener JMS thì tăng pool Hikari. Tổng số instance × pool phải nhỏ hơn `max_connections` của MySQL (mặc định 151 → tối đa 5 instance; nhiều hơn thì tăng `max_connections`).
- **Pool `@Scheduled` khai báo tường minh** (`AsyncConfig#taskScheduler`): `@EnableWebSocketMessageBroker` đăng ký scheduler riêng cho heartbeat STOMP, nếu để Spring Boot tự tạo thì job định kỳ sẽ chạy trên pool đó.
- Thông báo đơn hàng không nằm trong hàng đợi bộ nhớ: message được ghi vào `outbox_messages` cùng transaction với đơn, rồi nằm trên broker JMS (subscription bền) — app hay broker chết giữa chừng đều được gửi lại (job outbox / broker giao lại). Job outbox **không** dùng ShedLock: mọi instance chạy và chia nhau bản ghi bằng `SKIP LOCKED`.

## Messaging (JMS — ActiveMQ Artemis)

- **Broker:** dev/test dùng broker **embedded** trong JVM (`spring.artemis.mode=embedded`, chỉ nối in-VM, không lưu message ra đĩa). Container app của compose và prod nối broker riêng (`native`, service `artemis` — console http://localhost:8161). Kết nối có timeout (`connectTimeoutMillis=5000&callTimeout=10000` trong `broker-url`): broker chết thì gửi message báo lỗi sau vài giây, không treo thread.
- **Message:** JSON trong `TextMessage`, kiểu payload ghi bằng alias trong property `_type` theo whitelist `service/messaging/MessageTypes` — alias lạ/tên class bị từ chối, không dùng `ObjectMessage` (Java deserialization). Tên destination: `service/messaging/Destinations`.
- **Retry + DLQ cấu hình trên broker**, một file duy nhất [`src/main/resources/artemis/broker.properties`](src/main/resources/artemis/broker.properties) (cú pháp broker properties của Artemis): listener ném exception → rollback → broker giao lại sau 2s, 4s, 8s, 16s; quá 5 lần → queue `DLQ.order-placed`. Broker embedded nạp file qua `app.jms.embedded-broker-properties` (khoá sai → app không khởi động); compose mount file vào service `artemis` và nạp bằng `artemis run --properties` mỗi lần khởi động (sửa file thì `docker compose restart artemis`).
- **Transactional Outbox** (không mất thông báo khi broker chết): checkout ghi message vào bảng `outbox_messages` trong **cùng transaction** với đơn hàng; sau commit app gửi ngay lên broker (pool `outbox-*`, khách không phải chờ). Broker chết hoặc treo (gửi hết thời gian chờ, address đầy) thì bản ghi ở `PENDING` — **không tính số lần thử**, bao lâu cũng không mất — và job `OutboxRelayJob` gửi lại mỗi `app.outbox.poll-interval` (1 phút): broker sống lại thì thông báo đi trong ≤ 1 chu kỳ. Lỗi riêng của bản ghi khi broker vẫn sống (loại message không còn trong code, broker từ chối message…) mới tính lần thử: quá `app.outbox.max-attempts` (5) lần → `FAILED` + log ERROR; bản ghi hỏng không chặn các bản ghi khác. Mọi instance cùng chạy job, chia nhau bản ghi bằng `SELECT … FOR UPDATE SKIP LOCKED` (không gửi trùng). Bản ghi `SENT` bị xoá sau `app.outbox.retention` (7 ngày). Gửi lại bản ghi `FAILED` sau khi khắc phục nguyên nhân (kênh nào đã gửi rồi sẽ không gửi lần hai):

  ```sql
  SELECT id, type, attempts, last_error, created_at FROM outbox_messages WHERE status = 'FAILED';
  UPDATE outbox_messages SET status = 'PENDING', attempts = 0 WHERE status = 'FAILED';   -- hoặc WHERE id = ...
  ```

  Message vào `DLQ.order-placed` (kênh thông báo lỗi 5 lần, vd. webhook Slack sai) được ghi log ERROR kèm `orderId` + id outbox, bản ghi outbox chuyển sang `DEAD_LETTERED` (không bị xoá theo `retention`) rồi message được lấy khỏi DLQ. Sửa kênh lỗi xong thì gửi lại (kênh đã gửi thành công không gửi lần hai):

  ```sql
  SELECT id, JSON_EXTRACT(payload, '$.orderId') AS order_id, sent_at FROM outbox_messages WHERE status = 'DEAD_LETTERED';
  UPDATE outbox_messages SET status = 'PENDING', attempts = 0 WHERE status = 'DEAD_LETTERED';   -- hoặc WHERE id = ...
  ```
- **Broker prod** phải được khởi động với cùng file: `artemis run --properties <đường dẫn>/broker.properties` — broker gặp khoá sai chỉ ghi log rồi bỏ qua, nên sau khi triển khai kiểm tra trên console rằng address `order.placed` có dead-letter address `DLQ.order-placed` và queue `DLQ.order-placed` tồn tại.
- **Sự kiện realtime** dùng thêm subscription **non-durable, không shared** trên cùng topic (`JmsConfig.TOPIC_BROADCAST_FACTORY`): mỗi instance một bản, không giao lại/DLQ (xem [Realtime](#realtime-websocket--stomp)). Trên console broker chúng hiện là queue tạm, biến mất khi instance tắt.

## Realtime (WebSocket + STOMP)

Admin nhận đơn mới ngay trên mọi trang `/admin/**` (toast + badge đơn chờ xác nhận) — chi tiết giao diện: [docs/admin](docs/admin/README.md#thông-báo-realtime-websocket--stomp).

- **Endpoint** `/ws-stomp` (WebSocket thuần, không SockJS), STOMP với simple broker trong bộ nhớ: `/topic` (broadcast), `/queue` + `/user` (đích theo user), prefix `/app` (chưa có handler). Client: `@stomp/stompjs` tự host qua WebJar (`/webjars/stomp__stompjs/bundles/stomp.umd.min.js`), `static/js/realtime.js`. Heartbeat 10s hai chiều; frame client gửi tối đa 8 KB.
- **Bảo mật:** handshake đi qua filter chain web — phải đăng nhập (session), tài khoản bị khoá bị `AccountStatusFilter` chặn ngay. Frame CONNECT phải mang token CSRF (đọc từ `<meta name="_csrf">`, so với token trong session). Phân quyền từng frame (`config/WebSocketSecurityConfig`): chỉ `ADMIN` SUBSCRIBE `/topic/admin/orders`, client không được SEND vào broker, mọi đích khác bị từ chối. Lỗi trả frame ERROR với thông điệp cố định (`Access denied`), ghi audit `WEBSOCKET_ACCESS_DENIED`. Origin: rỗng = chỉ same-origin; prod bắt buộc `WEBSOCKET_ALLOWED_ORIGINS`.
- **Thu hồi kết nối đang mở:** Spring Security chỉ kiểm tra lúc handshake, nên job `WebSocketSessionRevalidationJob` (mỗi `WEBSOCKET_REVALIDATE_INTERVAL`, mặc định 60s) đóng socket của tài khoản bị khoá/xoá/đổi role (một query cho mọi kết nối, audit `SESSION_REVOKED`) hoặc có session HTTP đã kết thúc. Logout/hết hạn session trên cùng instance thì socket bị đóng ngay (`HttpSessionDestroyedEvent`).

### Chạy nhiều instance sau load balancer

| Thành phần | Cách hoạt động khi có N instance |
|---|---|
| Kết nối WebSocket, simple broker | Nằm trong bộ nhớ instance nhận handshake. Sự kiện tới mọi instance qua topic JMS (mỗi instance một subscription non-durable) rồi mỗi instance đẩy cho client của nó — admin ở instance A thấy đơn đặt ở instance B (`MultiInstanceRealtimeIntegrationTest`). Không dùng broker relay. |
| Số liệu (badge, chart) | Luôn đọc lại từ MySQL, không cộng dồn phía client. |
| Mất kết nối (instance tắt, rolling deploy) | Client nối lại sau 1s → 60s (tăng gấp đôi) + jitter ngẫu nhiên 0–3s để không dồn vào instance còn lại, rồi tải lại số liệu qua JSON để bù sự kiện lỡ. |
| Thu hồi socket | Job chạy trên **mọi** instance (không ShedLock — mỗi instance chỉ đóng socket của nó), đọc trạng thái tài khoản từ DB dùng chung. |
| Session HTTP | **Hiện nằm trong bộ nhớ Tomcat của từng instance → load balancer phải sticky theo cookie `JSESSIONID`** (cả request thường lẫn handshake `/ws-stomp`). Session dùng chung (Spring Session JDBC, không cần sticky) là issue riêng ([#44](https://github.com/awesome-academy/huu_java/issues/44)); khi đó chỉ cần thay bean `HttpSessionLookup`. |

Yêu cầu với load balancer/reverse proxy:

- Chuyển tiếp header `Upgrade`/`Connection` cho `/ws-stomp` (nginx: `proxy_http_version 1.1; proxy_set_header Upgrade $http_upgrade; proxy_set_header Connection "upgrade";`).
- Idle timeout > 2 × heartbeat (≥ 30s; nginx `proxy_read_timeout` mặc định 60s là đủ).
- Giữ `Host` và gửi `X-Forwarded-Proto` (đã dùng cho HTTPS/IP thật — `TRUSTED_PROXIES`).
- Graceful shutdown của app đóng WebSocket với mã 1001; client tự nối lại sang instance khác.

## Xử Lý Lỗi

- Mỗi luồng có `@ControllerAdvice` riêng cùng package: `AdminExceptionHandler` (view lỗi admin), `WebExceptionHandler` (view lỗi web), `ApiExceptionHandler` (JSON `ErrorResponse`).
- `GlobalErrorController` (`/error`) chỉ xử lý lỗi dispatch cấp servlet (404/403/405/409 và exception thoát khỏi mọi advice) — tự chọn trả JSON hay view theo prefix URI.

## Logging

- Mỗi dòng log có `[instance] [requestId] [user]` (`instance` = `INSTANCE_ID`, bắt buộc ở prod, dev mặc định hostname — phân biệt instance khi chạy nhiều instance sau load balancer). `requestId` luôn do server sinh (UUID), được trả lại trong response header `X-Request-Id` và được mang sang thread `@Async`. Khi người dùng báo lỗi, lấy `X-Request-Id` của response để `grep` toàn bộ log của request đó. Nếu client gửi header `X-Request-Id` hợp lệ (chữ/số/`-`, ≤ 64 ký tự), giá trị đó chỉ được ghi kèm thành `[<requestId> cid=<id của client>]`, không dùng làm requestId (tránh giả mạo id của request khác).
- Chống log injection: ký tự ngắt dòng (CR, LF, U+0085, U+2028, U+2029) và ký tự điều khiển/vô hình trong message, message của exception và giá trị audit bị thay bằng `_`. Hệ quả: message nhiều dòng hợp lệ (vd. báo cáo của devtools) cũng bị gộp thành 1 dòng.
- Profile `dev` và `prod` ghi thêm file trong `logs/` (đổi thư mục bằng `LOGGING_FILE_PATH`); riêng `prod` ghi vào thư mục con `logs/<INSTANCE_ID>/` để nhiều instance dùng chung thư mục log (vd. cùng bind mount `./logs`) không ghi đè/xoay vòng/xoá file của nhau. File xoay vòng theo ngày hoặc khi vượt 10MB, nén `.gz`. `foodsndrinks.log` được ghi bất đồng bộ (`AsyncAppender`, không bỏ log nào); `audit.log` chỉ có ở `prod` và ghi đồng bộ. Khi chạy test chỉ bật profile `test` (không bật `dev`; `src/test/resources/config/application.yml`) nên log của test chỉ ra console.

  | File | Nội dung | Định dạng | Giữ lại |
  |---|---|---|---|
  | `logs/foodsndrinks.log` (prod: `logs/<INSTANCE_ID>/foodsndrinks.log`) | Toàn bộ log của app (kể cả audit) | Text | 30 ngày, tối đa 1GB / instance |
  | `logs/<INSTANCE_ID>/audit.log` (chỉ prod) | Chỉ logger `AUDIT` (sự kiện bảo mật); có `service.node.name` = `INSTANCE_ID` | JSON 1 dòng/sự kiện ([ECS](https://www.elastic.co/guide/en/ecs/current/index.html)) | 90 ngày, tối đa 500MB |

- **Audit log** (logger `AUDIT`, luôn ở mức INFO kể cả ở prod): đăng nhập thành công/thất bại (`auth.method` = `FORM`, `API`, `REMEMBER_ME`, `OAUTH2:<provider>`; gồm cả cookie remember-me bị từ chối), admin khoá/mở khoá user, đổi role, đặt lại mật khẩu, đổi email, user tự đổi mật khẩu (thành công/sai mật khẩu hiện tại), session bị huỷ (`SESSION_REVOKED`, `event.reason` = `DISABLED`, `ROLE_CHANGED`, `ACCOUNT_NOT_FOUND` — tài khoản bị khoá/đổi role/xoá sau khi đăng nhập: session bị huỷ ở request kế tiếp), đổi trạng thái đơn. Chỉ ghi username/id, hành động và IP — không ghi password, token, session id. Ở prod sau load balancer, `source.ip` là IP client thật lấy từ `X-Forwarded-For` (chỉ khi request đến từ `TRUSTED_PROXIES`, xem bảng biến môi trường); username nhập khi đăng nhập sai chỉ được ghi nếu tài khoản tồn tại (`username=-` nếu không). Sự kiện thay đổi dữ liệu chỉ được ghi sau khi transaction commit. Ví dụ trên console / `foodsndrinks.log` (giá trị có khoảng trắng, `=`, `"` được đặt trong nháy kép):

  ```
  Login failed event.action=LOGIN_FAILURE event.outcome=failure event.reason=BAD_CREDENTIALS auth.method=FORM username=alice source.ip=127.0.0.1
  User role changed event.action=USER_ROLE_CHANGED event.outcome=success actor=admin target.id=5 target.username=dave target.role.from=ROLE_USER target.role.to=ROLE_ADMIN source.ip=127.0.0.1
  ```

  Cùng sự kiện trong `audit.log` (rút gọn):

  ```json
  {"@timestamp":"…","log":{"level":"WARN","logger":"AUDIT"},"message":"Login failed","requestId":"…","event":{"action":"LOGIN_FAILURE","outcome":"failure","reason":"BAD_CREDENTIALS"},"auth":{"method":"FORM"},"username":"alice","source":{"ip":"127.0.0.1"},"ecs":{"version":"8.11"}}
  ```

## Quét Lỗ Hổng Thư Viện (OWASP Dependency-Check)

Quét CVE của mọi dependency (kể cả thư viện front-end trong WebJars) — chạy **trước khi tạo PR có thêm/đổi dependency**. Không gắn vào `./mvnw test`/`package`, phải gọi riêng:

```bash
# Cần NVD API key (miễn phí): https://nvd.nist.gov/developers/request-an-api-key → NVD_API_KEY trong .env
docker compose run --rm --no-deps maven dependency-check:check

# JDK local: Maven không đọc .env → đặt biến trong shell trước
export NVD_API_KEY=...        # PowerShell: $env:NVD_API_KEY = "..."
./mvnw dependency-check:check
```

- Lần đầu tải toàn bộ dữ liệu NVD (vài phút đến vài chục phút), các lần sau chỉ cập nhật phần thay đổi — cache ở `~/.m2/repository/org/owasp/dependency-check-data` (với Docker là volume `maven-repo`).
- Build **fail khi có lỗ hổng CVSS ≥ 7**. Báo cáo: `target/dependency-check-report.html` (và `.json`).
- Xử lý: ưu tiên nâng version (Spring Boot patch mới nhất, hoặc property version của BOM / WebJar trong `pom.xml`). Lỗ hổng chắc chắn không áp dụng thì thêm vào `dependency-check-suppressions.xml` kèm `<notes>` giải thích và hạn xem lại `until`.

## Monitoring

- Actuator: `/actuator/health` public; các endpoint khác (`info`, `metrics`, `caches` — chỉ dev) yêu cầu `ROLE_ADMIN`. Prod chỉ expose `health`.

## Tài Liệu Chi Tiết

- 🛠 [Admin — trang quản trị](docs/admin/README.md)
- 🔌 [API — REST API cho trang người dùng](docs/api/README.md)
- 🛒 [Web — storefront người dùng](docs/web/README.md)
