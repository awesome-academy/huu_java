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
├── repository/      # Spring Data JPA repositories
├── security/        # JwtUtil, JwtAuthenticationFilter, CustomUserDetailsService, OAuth2 user services
├── service/         # Toàn bộ business logic (Controller chỉ gọi Service)
│   └── notification/  # Slack/Email notification, order event listener, statistics scheduler
└── utils/           # SlugUtils...

src/main/resources/
├── db/migration/    # Flyway migrations
├── templates/       # Thymeleaf (admin/, web/, error/)
├── static/          # CSS, JS, images
└── application*.yml # Cấu hình theo profile
```

**Quy ước quan trọng:** Constructor Injection qua `@RequiredArgsConstructor` + `private final` (không dùng field injection); Controller mỏng, logic nằm ở Service; query quan hệ dùng `LEFT JOIN FETCH` chống N+1. Xem thêm `CLAUDE.md`.

## Configuration

Profile mặc định là `dev` (đổi qua env `SPRING_PROFILES_ACTIVE=prod` khi deploy).

| File | Vai trò |
|---|---|
| `application.yml` | Cấu hình chung: mail (SMTP), OAuth2 client (Google/Facebook/Twitter), Slack webhook, cron thống kê |
| `application-dev.yml` | Datasource local, logging DEBUG, Swagger UI bật, JWT secret fallback, upload dir |
| `application-prod.yml` | Datasource từ env (bắt buộc), logging ra file `logs/foodsndrinks.log`, **Swagger tắt hoàn toàn** |

### Biến môi trường

Khai báo trong file `.env` (mẫu: [.env.example](.env.example)) hoặc bằng biến môi trường của hệ điều hành (được ưu tiên hơn `.env`). **Không đặt secret thật làm giá trị mặc định `${VAR:default}` trong `application*.yml`.**

| Biến | Bắt buộc | Mô tả |
|---|---|---|
| `DB_USERNAME` / `DB_PASSWORD` | ✅ | Tài khoản MySQL (với Docker: compose tạo user này lần đầu khởi động) |
| `MYSQL_ROOT_PASSWORD` / `DB_NAME` | Docker | Mật khẩu root và tên database của container MySQL |
| `ARTEMIS_USER` / `ARTEMIS_PASSWORD` | Docker | Tài khoản broker ActiveMQ Artemis của compose |
| `DB_HOST_PORT` / `APP_PORT` / `ARTEMIS_PORT` / `ARTEMIS_CONSOLE_PORT` | — | Port compose mở ra máy host (mặc định 33070 / 8080 / 61616 / 8161, chỉ bind `127.0.0.1`) |
| `DB_FOODSNDRINKS_URL` | Prod | JDBC URL (dev mặc định `jdbc:mysql://localhost:3306/foodsndrinks`) |
| `JWT_SECRET` | Prod | Secret ký JWT, ≥ 32 ký tự (dev có fallback) |
| `REMEMBER_ME_KEY` | Prod | Key cho remember-me cookie (dev tự sinh random) |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | Khi dùng login Google | OAuth2 credentials từ Google Console |
| `FACEBOOK_CLIENT_ID` / `FACEBOOK_CLIENT_SECRET` | Khi dùng login Facebook | OAuth2 credentials |
| `TWITTER_CLIENT_ID` / `TWITTER_CLIENT_SECRET` | Khi dùng login Twitter/X | OAuth2 credentials |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_PASSWORD` | Khi gửi mail thật | SMTP (mặc định Mailtrap sandbox) |
| `ADMIN_NOTIFY_EMAIL` | — | Email nhận thông báo đơn hàng & báo cáo tháng |
| `SLACK_WEBHOOK_URL` / `SLACK_NOTIFY_ENABLED` | — | Slack incoming webhook thông báo đơn mới |
| `MAIL_NOTIFY_ENABLED` | — | Bật/tắt email thông báo |
| `ORDER_STATS_CRON` | — | Cron gửi báo cáo tháng (mặc định `0 0 23 L * *`, 23h ngày cuối tháng, giờ VN) |
| `UPLOAD_DIR` | — | Thư mục lưu ảnh upload (mặc định `./uploads`) |
| `SERVER_PORT` | — | Port (mặc định 8080) |

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
- **Báo cáo tháng:** `OrderStatisticsScheduler` chạy theo cron (mặc định 23h ngày cuối tháng, `Asia/Ho_Chi_Minh`) — tổng hợp doanh thu, số đơn theo trạng thái và email cho admin.

## Xử Lý Lỗi

- Mỗi luồng có `@ControllerAdvice` riêng cùng package: `AdminExceptionHandler` (view lỗi admin), `WebExceptionHandler` (view lỗi web), `ApiExceptionHandler` (JSON `ErrorResponse`).
- `GlobalErrorController` (`/error`) chỉ xử lý lỗi dispatch cấp servlet (404/403/405/409 và exception thoát khỏi mọi advice) — tự chọn trả JSON hay view theo prefix URI.

## Monitoring

- Actuator: `/actuator/health` public; các endpoint khác (`info`, `metrics`, `caches` — chỉ dev) yêu cầu `ROLE_ADMIN`. Prod chỉ expose `health`.

## Tài Liệu Chi Tiết

- 🛠 [Admin — trang quản trị](docs/admin/README.md)
- 🔌 [API — REST API cho trang người dùng](docs/api/README.md)
- 🛒 [Web — storefront người dùng](docs/web/README.md)
