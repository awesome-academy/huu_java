# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

F&B Store ("foodsndrinks") — một ứng dụng Spring Boot 4.0.10 / Java 25 phục vụ ba phần: trang quản trị SSR (`/admin/**`), storefront SSR cho khách hàng (`/`, `/menu`, `/cart`, ...) và REST API dùng JWT (`/api/v1/**`). Tài liệu riêng cho từng phần nằm ở [docs/admin](docs/admin/README.md), [docs/web](docs/web/README.md), [docs/api](docs/api/README.md) — cập nhật các file này khi thêm endpoint/trang mới.

Đây là mock project đã hoàn thành phần cơ bản; giai đoạn hiện tại là **khóa Advance**: bổ sung các công nghệ ở mục [Lộ trình Advance](#lộ-trình-advance) và mọi code mới phải tuân thủ [OWASP Secure Coding Practices](#owasp-secure-coding--quy-tắc-bắt-buộc).

## Lệnh thường dùng

Dùng Maven Wrapper (`mvnw.cmd` trên Windows, `./mvnw` trên Mac/Linux). Bắt buộc **JDK 25** — JDK 17/21 không build được.

```bash
./mvnw spring-boot:run                       # chạy app (profile mặc định là dev)
./mvnw clean package -DskipTests             # build file jar
./mvnw test                                  # chạy toàn bộ test
./mvnw test -Dtest=AuthProductApiTest        # chạy 1 class test
./mvnw test -Dtest=AuthProductApiTest#login_withValidCredentials_returnsToken   # chạy 1 method test
./mvnw dependency-check:check                # quét CVE dependency (cần NVD_API_KEY trong môi trường; chạy trước PR đổi dependency)
```

Docker (không cần JDK/MySQL local; cần file `.env` copy từ `.env.example`):

```bash
docker compose up -d --build                                 # MySQL 8.4 + ActiveMQ Artemis + app (profile dev)
docker compose up -d mysql artemis                           # chỉ hạ tầng, app chạy bằng JDK local
docker compose run --rm maven test                           # chạy Maven trong container JDK 25
docker compose run --rm maven test -Dtest=AuthProductApiTest
docker compose run --rm --no-deps maven dependency-check:check   # quét CVE (NVD_API_KEY lấy từ .env)
docker compose up -d --build app                             # build lại app sau khi sửa code
docker compose down                                          # thêm -v để xoá dữ liệu MySQL/Artemis
```

- Secret local nằm trong `.env` (git-ignore). Compose dùng nó để thay biến và truyền vào container (`env_file`); app chạy bằng JDK local đọc nó qua `spring.config.import: optional:file:.env[.properties]`, biến môi trường hệ điều hành vẫn được ưu tiên. Thêm biến cấu hình mới thì cập nhật cả `.env.example` và bảng biến môi trường trong README. Trong `.env`, dòng `KEY=` rỗng ghi đè default trong yml thành rỗng — biến không dùng phải để comment.
- Trong compose, app nối DB qua `jdbc:mysql://mysql:3306/...` (ghi đè `DB_FOODSNDRINKS_URL`), đặt `TZ` từ `APP_TZ` trong `.env` (mặc định `Asia/Ho_Chi_Minh`, cũng là `ENV` mặc định trong `Dockerfile`; mọi instance phải chung một giá trị, để `@CreationTimestamp`/`LocalDateTime.now()` giống khi chạy local), `./uploads` và `./logs` được mount vào `/app`. Service `app` chạy bằng `HOST_UID`/`HOST_GID` (mặc định 1000) để ghi được bind mount trên Linux/WSL2 mà file vẫn thuộc user máy host. Service `maven` dùng `docker/maven-entrypoint.sh`: khởi động bằng root, Docker Desktop (project hiện là root) thì chạy Maven bằng root — chạy bằng UID khác sẽ lỗi `utime` trên `target/`; Linux/WSL2 thì chown volume `maven-repo` (`/m2`, chứa `HOME`/`MAVEN_USER_HOME`/`maven.repo.local`) rồi `setpriv` về chủ sở hữu thư mục project — giữ nguyên cơ chế này khi sửa compose. MySQL mở ra host ở `127.0.0.1:33070` và được cố định `--default-time-zone=+00:00` cho khớp `serverTimezone=UTC` (DB lưu thời gian theo UTC; không đặt `TZ` cho service `mysql`). Artemis đã có trong compose (`ARTEMIS_HOST`/`ARTEMIS_PORT` đã được truyền vào app) nhưng app chưa kết nối.
- Migration không có dữ liệu mẫu: tạo admin bằng cách đăng ký tại `/register` rồi `UPDATE users SET role = 'ROLE_ADMIN'` (lệnh cụ thể trong README).
- Cả **chạy app lẫn chạy test** đều cần MySQL (của compose hoặc của máy). `src/test/resources` chỉ có `config/application.yml` (thêm profile `test` để log của test chỉ ra console, không lẫn vào `logs/foodsndrinks.log`), không có DB riêng cho test: test là `@SpringBootTest` chạy thẳng vào DB `foodsndrinks` và dựa vào `@Transactional` để rollback — test mới có đụng tới DB phải giữ `@Transactional` (cho tới khi chuyển sang Testcontainers, xem mục Testing bên dưới).
- Flyway tự chạy migration khi khởi động. Hibernate `ddl-auto` không được cấu hình, nên schema chỉ đến từ Flyway.
- URL ở dev: storefront `http://localhost:8080`, admin `/admin` (cần `ROLE_ADMIN`), Swagger UI `/api-docs/swagger`, OpenAPI JSON `/api/v1/api-docs`. Swagger bị tắt ở prod.
- Chưa cấu hình linter/formatter nào.

## Kiến trúc

Package gốc `com.huuhv.foodsndrinks`, tuân thủ nghiêm 3 lớp: controller → service → repository. Controller không bao giờ gọi repository; toàn bộ business logic nằm ở `service/`. Dữ liệu vào/ra luôn đi qua `dto/request` (`*ReqDto`) và `dto/response` (`*ResDto`) — không bao giờ bind entity từ view/API. Việc map DTO được viết tay trong service (ModelMapper có trong classpath nhưng chưa được dùng; Caffeine chỉ dùng làm cache của `PwnedPasswordsChecker`).

### Security: ba filter chain có thứ tự (`config/SecurityConfig.java`)
1. `@Order(1)` `/actuator/**` — stateless; `/actuator/health` public, còn lại cần `ROLE_ADMIN`.
2. `@Order(2)` `/api/**`, `/api-docs/**` — stateless, tắt CSRF, dùng `JwtAuthenticationFilter` (JWT qua `security/JwtUtil`, HS256, hết hạn sau 1 giờ). Thiếu/sai token → 401 qua `HttpStatusEntryPoint`. `/api/v1/auth/**` là public; GET sản phẩm cần `USER`/`ADMIN`. `JwtAuthenticationFilter` là một `@Component` nhưng việc tự đăng ký servlet filter của nó đã bị tắt tường minh để nó chỉ chạy trong chain này — giữ nguyên như vậy.
3. `@Order(3)` mọi request còn lại — form login dùng session + OAuth2 login (Google qua OIDC → `CustomOidcUserService`; Facebook/Twitter → `CustomOAuth2UserService`, class này bóc object `data` trong response của Twitter). PKCE được bật bắt buộc cho mọi provider. Cả hai OAuth service đều gọi `UserService.provisionOAuthUser(...)` để tìm hoặc tạo `User` local, dùng username local làm principal name (`local_username`), và từ chối tài khoản bị khoá bằng `DisabledOAuth2AccountException` (OAuth2 không đi qua `CustomUserDetailsService` nên phải tự kiểm `isActive`). CSRF bật mặc định (form dùng `th:action` tự có token; `fetch()` lấy token qua `getCsrfHeaders()` trong `static/js/web/main.js`). Có bật remember-me. Success handler ưu tiên quay lại request đã lưu, nếu không thì đưa admin về `/admin` và user về `/`.

URL pattern mới phải được thêm vào danh sách matcher của đúng chain (lưu ý `/products/*/rate` được khai báo trước rule public `/products/**` — thứ tự có ý nghĩa). Endpoint kiểu mới (SOAP `/ws/**`, WebSocket `/ws-stomp/**`) cần chain hoặc matcher riêng — xem mục Lộ trình. Role là các giá trị của enum `Role` (`ROLE_ADMIN`, `ROLE_USER`); controller lấy user hiện tại bằng `userService.getCurrentUser(principal.getName())` (nhận cả username lẫn email).

### Controller và xử lý lỗi được tách theo package
- `controller/admin`, `controller/web`, `controller/api` mỗi package có `@ControllerAdvice` riêng, giới hạn bằng `basePackages`: `exception/AdminExceptionHandler`, `exception/WebExceptionHandler` (trả view lỗi) và `exception/ApiExceptionHandler` (trả JSON `ErrorResponse`). Muốn xử lý một exception nghiệp vụ mới thì sửa handler ứng với package của controller.
- `controller/GlobalErrorController` (`/error`) chỉ xử lý lỗi dispatch cấp servlet và exception đã thoát khỏi mọi advice; nó chọn trả JSON, template `error/admin/*` hay `error/*` dựa theo prefix của URI.
- Mỗi phần có một advice inject model attribute dùng chung cho layout: `AdminLayoutModelAdvice` (`currentPath` và mảng giá trị của tất cả enum cho dropdown) và `WebLayoutModelAdvice` (`cartItemCount` cho badge trên navbar).
- Các exception nghiệp vụ dùng chung giữa các service: `ResourceNotFoundException` (404), `DuplicateResourceException` (409), `IllegalArgumentException` (400).
- Exception mà service ném hoặc exception handler bắt đặt ở `exception/` (kể cả loại kế thừa exception của Spring Security như `LoginThrottledException`), không đặt ở `security/` — `security` đã phụ thuộc `service.security`/`exception`, đặt ngược lại sẽ tạo vòng phụ thuộc giữa package.

### Ghi chú nghiệp vụ
- **Giỏ hàng là một `Order` có status `CART`** (xem `OrderStatus`). Thêm/sửa/xóa thao tác trên các `OrderDetail` của order đó (service luôn kiểm tra quyền sở hữu qua `requireOwnedCartItem`); `OrderService.checkout` chuyển nó sang `PENDING`. Các query admin/thống kê đều loại trừ order `CART`. Các endpoint thay đổi giỏ hàng trong `CartController` được gọi qua `fetch()` từ `static/js/web/cart.js` và trả JSON (`CartPayloadResDto`), không redirect.
- **Thông báo:** `checkout` publish `OrderPlacedEvent`; `service/notification/OrderNotificationListener` bắt event bằng `@TransactionalEventListener(AFTER_COMMIT)` và gọi `SlackNotificationService` (webhook qua `RestClient`) + `EmailNotificationService`, cả hai đều `@Async("taskExecutor")` chạy trên thread pool `notify-*` (`config/AsyncConfig`). `OrderStatisticsScheduler` gửi email báo cáo tháng theo `app.notification.statistics.cron`, chạy theo múi giờ JVM (cố ý không khai báo `zone` để cron cùng múi giờ với `LocalDate.now()` dùng tính tháng). Bật/tắt bằng `SLACK_NOTIFY_ENABLED` / `MAIL_NOTIFY_ENABLED`.
- **Upload:** `FileStorageService` ghi ảnh vào `app.upload.dir` (mặc định `./uploads`, thư mục con `products/`, `avatars/`), chỉ nhận PNG/JPEG khi đuôi file khớp magic bytes (`service/ImageFormat`, không tin `Content-Type`), decode rồi encode lại bằng ImageIO (`service/ImageSanitizer`: bỏ EXIF/payload thừa, áp Orientation, chặn > 40M pixel, cạnh dài ≤ 2048px — không bao giờ lưu byte gốc), đặt tên file bằng UUID + đuôi chuẩn hoá, chặn path traversal, và trả về đường dẫn URL `/uploads/...`; `WebMvcConfig` phục vụ thư mục đó tại `/uploads/**`. Giới hạn 5MB/file, 25MB/request (`spring.servlet.multipart.*` trong `application.yml`, mọi profile).
- **Thư viện front-end** (Bootstrap, Font Awesome, Chart.js) tự host qua **WebJars** (dependency Maven, version trong `<properties>` của `pom.xml`), link bằng URL không chứa version: `@{/webjars/bootstrap/css/bootstrap.min.css}` (`webjars-locator-lite`). Không thêm thẻ CDN mới.
- **Dashboard admin** đã dùng Chart.js 4.4.0 (WebJar); `static/js/admin/dashboard.js` lấy dữ liệu từ `GET /admin/dashboard/charts` (`DashboardService.getChartData`, URL trong `data-charts-url`).
- Slug được sinh bằng `utils/SlugUtils`; trang chi tiết sản phẩm ở storefront tìm sản phẩm theo slug.
- **Logging/audit:** sẵn sàng cho nhiều instance sau load balancer: `app.instance-id` (`INSTANCE_ID`; bắt buộc và cố định ở prod — `config/InstanceIdCheck`, vì hostname container đổi mỗi lần tạo lại; dev mặc định hostname) được in trong mỗi dòng log, là `service.node.name` của `audit.log`, và ở prod mỗi instance ghi file vào thư mục riêng `logs/<instance-id>/` (không bao giờ để nhiều JVM ghi/xoay vòng chung một file); prod bật `server.forward-headers-strategy=native` + `server.tomcat.remoteip.internal-proxies` (`TRUSTED_PROXIES`) để `getRemoteAddr()`/`source.ip` là IP client thật. `logging/RequestIdFilter` (servlet filter chạy đầu tiên, cả dispatch ERROR/ASYNC) đặt MDC `requestId` — luôn là UUID do server sinh, trả lại trong response header `X-Request-Id`; header `X-Request-Id` hợp lệ của client chỉ vào MDC `clientRequestId` (in thành `cid=`), không bao giờ dùng làm requestId. `logging/MdcUserFilter` (ngay sau `springSecurityFilterChain`) đặt MDC `user.name` (tên theo ECS: trong `audit.log` thành object `"user":{"name":...}`; key phẳng `user` xung đột với mapping ECS); bean `TaskDecorator` (`MdcTaskDecorator`, khai báo trong `AsyncConfig`) mang MDC sang thread `@Async`. Sự kiện bảo mật đi qua `service/audit/AuditLogService`: logger `AUDIT`, message ngắn + trường gắn bằng SLF4J fluent API `addKeyValue` (không tự ghép chuỗi `key=value`); tên trường dùng dấu chấm (`event.action`, `target.id`...) và **không được trùng key MDC** (`requestId`, `clientRequestId`, `user.name`) — trùng thì encoder ECS của `audit.log` báo lỗi. Username nhập khi đăng nhập sai chỉ ghi nếu tài khoản tồn tại. Thay đổi dữ liệu chỉ ghi sau commit nên test `@Transactional` không thấy được — test bằng unit test như `AuthenticationAuditTest`. Đăng nhập được audit tự động qua event của `ProviderManager` (`AuthenticationAuditListener`: form, API, remember-me, OAuth2 thành công); các lỗi không sinh event được ghi riêng: lỗi OAuth2 trong `security/OAuth2LoginFailureHandler`, cookie remember-me bị từ chối trong `security/AuditingRememberMeServices` (bean `rememberMeServices` trong `SecurityConfig`). User hiện tại lấy qua `utils/SecurityUtils.currentUsername()`. Thao tác nhạy cảm mới (vd. import Excel) thì thêm method vào `AuditLogService`, không tự tạo logger `AUDIT` ở chỗ khác.

### Cấu hình
`application.yml` (dùng chung: mail, OAuth2 client, Slack, cron) + `application-dev.yml` (datasource local, log DEBUG, bật Swagger, giá trị fallback cho JWT/remember-me) / `application-prod.yml` (mọi thứ lấy từ biến môi trường, log level WARN/INFO, tắt Swagger, actuator chỉ expose `health`). Appender/pattern log nằm ở `logback-spring.xml` (`dev`/`prod`: console + `foodsndrinks.log` text qua `AsyncAppender`, 30 ngày; `prod` thêm `audit.log` JSON ECS ghi đồng bộ, 90 ngày; thư mục `logs/` ở dev, `logs/<app.instance-id>/` ở prod; có profile `test` hoặc không bật dev/prod: chỉ console) — không đặt `logging.file.name` trong yml. Chọn profile qua `SPRING_PROFILES_ACTIVE` (mặc định `dev`). Bảng đầy đủ các biến môi trường nằm trong [README.md](README.md).

Record `@ConfigurationProperties` đặt ở `config/properties/` (đăng ký bằng `@EnableConfigurationProperties` trong class config) để service đọc được mà không import `config` — tránh vòng `config ↔ service`.

## Quy ước

Lấy từ `../CLAUDE.md` cấp workspace và được kiểm tra khi review:
- Chỉ dùng constructor injection: `@RequiredArgsConstructor` + field `private final`. Không dùng `@Autowired` trên field trong code chính (test thì được dùng).
- Entity: `@Entity` + `@Table(name = "<số nhiều>")`, Lombok `@Getter/@Setter/@NoArgsConstructor/@AllArgsConstructor/@Builder` — không dùng `@Data`. `@OneToMany`/`@ManyToMany` phải là `FetchType.LAZY`; load quan hệ bằng `@Query ... LEFT JOIN FETCH` để tránh N+1. Truy vấn một bản ghi trả về `Optional<T>`.
- Controller mỏng: vòng lặp/tính toán đặt ở service. Thông báo thân thiện đi qua `RedirectAttributes` (SSR) hoặc ném exception nghiệp vụ.
- Thymeleaf: các trang dùng Layout Dialect decorate `admin/layout/base.html` hoặc `web/layout/base.html`; link luôn dùng `@{/duong-dan}` (không hardcode host/URL tuyệt đối; không có model attribute global `baseUrl`).
- Flyway migration: `src/main/resources/db/migration/V<yyyyMMddHHmmss>__<mo_ta>.sql`. Không sửa migration đã chạy — thêm version mới.
- Quản lý công việc bằng GitHub issue trên repo `academy` (dự án không dùng Redmine). PR theo `.github/pull_request_template.md` (`Closes #<issue>`, mục thay đổi DB, `./mvnw test` phải pass).
- Mỗi công nghệ Advance làm trên một branch/PR riêng, kèm test và cập nhật docs tương ứng.

## Lộ trình Advance

Hiện trạng so với danh sách công nghệ của khóa (✅ đã có · 🟡 có nhưng cần nâng cấp · ❌ chưa có):

| Công nghệ | Hiện trạng | Việc cần làm |
|---|---|---|
| Spring Security + OAuth2 Login + CSRF | ✅ | Bổ sung hardening theo mục OWASP (lockout, password policy, security headers, session cookie). |
| MySQL | ✅ | Thêm DB cho test (Testcontainers). |
| Messaging: JMS (ActiveMQ Artemis) | ❌ | Chuyển thông báo đơn hàng sang JMS (xem bên dưới). |
| WebSocket + STOMP | ❌ | Realtime đơn mới cho admin, trạng thái đơn cho user. |
| `@Scheduled` + `@Async` | ✅ | Cấu hình scheduler pool, tách executor theo loại việc. |
| `ThreadPoolTaskExecutor` | 🟡 | Đã có `TaskDecorator` truyền MDC; thiếu rejection policy, graceful shutdown. |
| Apache POI (Excel import/export) | ❌ | Export đơn hàng/sản phẩm, import sản phẩm. |
| SOAP (Spring-WS) | ❌ | Expose web service contract-first. |
| Chart (Chart.js) | 🟡 | Đã có 2 chart (WebJar, dữ liệu qua JSON); cần thêm chart, cập nhật realtime. |
| JUnit 5 + Mockito + MockMvc | 🟡 | Chỉ có 2 integration test; thiếu unit test Mockito và test slice `@WebMvcTest`. |
| Swagger / OpenAPI | ✅ | Bổ sung `@Operation`/`@ApiResponse` cho endpoint mới. |
| SLF4J + Logback | ✅ | `logback-spring.xml`, MDC `requestId`/`user.name`, logger `AUDIT` (`audit.log` JSON ECS ở prod). |
| Thymeleaf | ✅ | — |

### Giai đoạn

Thứ tự thực hiện. Tên giai đoạn trùng với dropdown "Roadmap phase" trong `.github/ISSUE_TEMPLATE/*.yml` — đổi ở đây thì đổi cả ở template. Tick khi hoàn thành cả giai đoạn.

- [x] 0. Hạ tầng (Docker)
- [x] 1. Logging (Logback, MDC, audit log)
- [ ] 2. Security hardening (OWASP)
- [ ] 3. Testing (Mockito, WebMvcTest, Testcontainers)
- [ ] 4. Scheduler & Multithread
- [ ] 5. JMS (ActiveMQ Artemis)
- [ ] 6. WebSocket + STOMP + Chart
- [ ] 7. Excel import/export (Apache POI)
- [ ] 8. SOAP (Spring-WS) + Tổng kết

Issue tạo trên repo `academy` (`awesome-academy/huu_java`, public) theo template trong `.github/ISSUE_TEMPLATE/`: Bug → label `bug`, Tính năng → `enhancement`, Task → không có label (repo không có label `feature`/`task` và tài khoản không có quyền tạo). Template chỉ hiện trên GitHub sau khi được merge vào `master` của repo `academy`. Lỗ hổng bảo mật không tạo issue công khai — xem `.github/SECURITY.md`.

### Phạm vi 40 giờ

Ngân sách cố định 40h, ước tính theo mức Lead/Senior server-side có dùng AI (AI viết code + test theo issue, người làm review và kiểm chứng). 23 issue + 1 epic chung, mỗi issue là 1 PR; estimate đã gồm test, Swagger/docs của chính issue đó.

| # | Giai đoạn | Issue | Est |
|---|---|---|---|
| 0.1 | 0 | Docker hoá + issue template + SECURITY.md (đã xong) | 0h |
| 1.1 | 1 | `logback-spring.xml`, `requestId` trong MDC (truyền sang `@Async`), audit log | 1.5h |
| 2.1 | 2 | Chống brute-force đăng nhập (form + API) + mật khẩu 8–64 ký tự | 1.5h |
| 2.2 | 2 | Security headers, SRI cho CDN, cookie session, magic bytes khi upload | 1.5h |
| 2.3 | 2 | CSP chặt: chuyển script inline + handler `on*` ra file JS | 1.5h |
| 2.4 | 2 | JWT kiểm tra `iss`/`aud` + CORS allowlist cho `/api/**` | 1h |
| 2.5 | 2 | OWASP Dependency-Check chạy local + xử lý kết quả quét | 1.5h |
| 3.1 | 3 | Unit test Mockito + `@WebMvcTest` + test bảo mật | 2h |
| 3.2 | 3 | Testcontainers MySQL + JaCoCo | 2h |
| 4.1 | 4 | Nâng cấp `ThreadPoolTaskExecutor` + dashboard thống kê song song (`CompletableFuture`) | 1.5h |
| 5.1 | 5 | Tích hợp ActiveMQ Artemis (embedded/native, converter whitelist, DLQ) | 1.5h |
| 5.2 | 5 | Thông báo đơn hàng qua topic `order.placed` | 2h |
| 5.3 | 5 | Transactional Outbox + job gửi lại | 2.5h |
| 6.1 | 6 | STOMP + bảo mật + admin nhận đơn mới realtime | 3h |
| 6.2 | 6 | Chart top sản phẩm, dashboard cập nhật realtime | 1h |
| 6.3 | 6 | User nhận trạng thái đơn realtime (`/user/queue/orders`) | 1.5h |
| 7.1 | 7 | Export đơn hàng | 1.5h |
| 7.2 | 7 | Import sản phẩm (validate theo dòng) | 2h |
| 7.3 | 7 | Import Excel chạy async qua queue `excel.import` | 2h |
| 7.4 | 7 | Export danh sách sản phẩm | 0.5h |
| 8.1 | 8 | SOAP server contract-first + bảo mật | 2.5h |
| 8.2 | 8 | Tổng kết: docs, kịch bản demo | 1h |
| 8.3 | 8 | SOAP client (`WebServiceTemplate`) | 2h |
| | | Dự phòng (Boot 4/Security 7 còn mới, PR bị yêu cầu sửa) | 3h |
| | | **Tổng** | **40h** |

Nếu vượt giờ, cắt theo thứ tự: 7.4 → 6.2 → 2.4 → 6.3. **Ngoài phạm vi** (không làm trừ khi còn giờ): CI GitHub Actions (cần trainer duyệt chạy workflow trên repo `academy` — thay bằng chạy test/quét local), job dọn giỏ hàng, tách chart "đơn theo ngày".

### Hướng dẫn triển khai từng công nghệ

Các package mới vẫn theo 3 lớp: config ở `config/`, entrypoint (WebSocket/SOAP) ở `controller/websocket`, `controller/soap`, logic ở `service/...`.

**Messaging: JMS với ActiveMQ Artemis.** Khóa yêu cầu JMS, nên dùng Artemis (broker hỗ trợ Jakarta Messaging gốc), không dùng RabbitMQ (RabbitMQ là AMQP, JMS chỉ chạy qua plugin + client riêng và Spring Boot không auto-config). Dependency `spring-boot-starter-artemis`; code dùng API JMS của Spring: `JmsTemplate`, `@JmsListener`, `DefaultJmsListenerContainerFactory`. Config ở `config/JmsConfig`, consumer ở `service/messaging/`.
- Broker: dev/test chạy **embedded** trong JVM (thêm `artemis-jakarta-server`, `spring.artemis.mode=embedded`) — không cần cài gì, test không cần Docker. Container app của docker compose và prod dùng `spring.artemis.mode=native` + `spring.artemis.broker-url` / `user` / `password` lấy từ env (`ARTEMIS_HOST`, `ARTEMIS_PORT`, `ARTEMIS_USER`, `ARTEMIS_PASSWORD` — compose đã truyền sẵn, broker là service `artemis`). Khi làm JMS thì thêm biến chọn mode (vd. `ARTEMIS_MODE`) và đặt `native` cho service `app` trong compose.
- Thể hiện cả hai mô hình JMS:
  - **Topic (publish/subscribe):** `OrderNotificationListener` (AFTER_COMMIT) gửi `OrderPlacedMessage` (DTO, không phải entity) tới topic `order.placed`. Mỗi consumer (email, Slack, WebSocket) là một shared durable subscription riêng (`@JmsListener(destination = "order.placed", subscription = "...")` với factory `setPubSubDomain(true)`, `setSubscriptionDurable(true)`, `setSubscriptionShared(true)`), nên consumer này lỗi không làm consumer khác nhận lại message.
  - **Queue (point-to-point):** job nặng như import Excel gửi vào queue `excel.import`, một consumer xử lý.
- Tin cậy: `setSessionTransacted(true)` để message chỉ được ack khi xử lý xong; lỗi thì broker redeliver, quá số lần thì vào `DLQ`. Consumer phải idempotent (dựa trên `orderId`) vì có thể nhận lặp.
- Message dạng JSON (`TextMessage`) qua message converter Jackson của Spring JMS (Boot 4 dùng Jackson 3), map type id bằng tên ngắn có whitelist (`typeIdMappings`) thay vì tên class đầy đủ. Không dùng `ObjectMessage` (Java deserialization — lỗ hổng OWASP).
- **Transactional Outbox** (issue 5.3): `checkout` ghi bản ghi vào bảng `outbox_messages` (Flyway migration mới) trong cùng transaction với đơn hàng; publisher gửi lên JMS rồi đánh dấu `SENT`. Job `@Scheduled` gửi lại bản ghi `PENDING` quá hạn (broker chết đúng lúc gửi), có giới hạn số lần thử. Consumer vẫn phải idempotent.

**WebSocket + STOMP.** `spring-boot-starter-websocket`, `config/WebSocketConfig` với `@EnableWebSocketMessageBroker`, endpoint `/ws-stomp`, prefix app `/app`, broker `/topic` (broadcast) và `/queue` + user destination `/user`.
- Use case: admin nhận `/topic/admin/orders` khi có đơn mới (subscription WebSocket của topic JMS `order.placed` gửi qua `SimpMessagingTemplate`); user nhận `/user/queue/orders` khi admin đổi trạng thái đơn.
- Bảo mật: xác thực bằng session của chain web (handshake đi qua chain `@Order(3)`); phân quyền destination bằng `@EnableWebSocketSecurity` + bean `AuthorizationManager<Message<?>>` (`/topic/admin/**` chỉ `ADMIN`); giữ yêu cầu CSRF token trong frame CONNECT (gửi từ meta tag `_csrf`); `setAllowedOriginPatterns` chỉ liệt kê origin thật, không dùng `*`.

**Scheduler, `@Async`, `ThreadPoolTaskExecutor`.** Giữ `config/AsyncConfig`, bổ sung:
- `setRejectedExecutionHandler(new CallerRunsPolicy())`, `setWaitForTasksToCompleteOnShutdown(true)`, `setAwaitTerminationSeconds(...)`.
- `TaskDecorator` sao chép MDC sang thread async: đã có bean `MdcTaskDecorator` trong `AsyncConfig`. Executor mới phải tạo từ `ThreadPoolTaskExecutorBuilder` được inject (Boot tự gắn mọi bean `TaskDecorator`), không tự `new ThreadPoolTaskExecutor()`. Spring MVC async (`StreamingResponseBody` khi export Excel) chưa được cấu hình executor — khi cần thì đặt bằng `configureAsyncSupport` với executor tạo từ builder. Không tự truyền `SecurityContext` sang thread khác trừ khi thật sự cần.
- Executor riêng cho việc nặng (vd. `excelExecutor` cho import/export) để không chiếm pool `notify-*`; luôn ghi tên executor tường minh, vd. `@Async("excelExecutor")`.
- Pool cho `@Scheduled` qua `spring.task.scheduling.pool.size`; job dùng `CompletableFuture` khi cần chạy song song (vd. tổng hợp nhiều thống kê dashboard cùng lúc).

**Apache POI.** `poi-ooxml` (bản 5.x ổn định mới nhất), code ở `service/excel/`.
- Export (đơn hàng theo khoảng ngày, danh sách sản phẩm): dùng `SXSSFWorkbook` (streaming) và nhớ `dispose()`; trả về qua `ResponseEntity<StreamingResponseBody>` hoặc ghi thẳng vào `HttpServletResponse` với `Content-Disposition: attachment`.
- Import sản phẩm: đọc từng dòng → map sang `ProductReqDto` → validate bằng Jakarta `Validator` → trả báo cáo lỗi theo số dòng; toàn bộ import trong một transaction hoặc theo lô có ghi rõ chính sách.
- Bảo mật: xem mục File Management trong OWASP (giới hạn kích thước/số dòng, kiểm tra magic bytes, chống formula injection khi export, giữ cấu hình zip-bomb mặc định của POI).

**SOAP (Spring-WS).** Contract-first: `spring-boot-starter-webservices` + `wsdl4j`, XSD đặt ở `src/main/resources/xsd/`, sinh class bằng `jaxb2-maven-plugin` (goal `xjc`) vào `target/generated-sources`.
- Endpoint `@Endpoint` ở `controller/soap/` (vd. `getProductsRequest`, `getOrderStatusRequest`), WSDL tự sinh bằng `DefaultWsdl11Definition` tại `/ws/*.wsdl`. Endpoint chỉ gọi service, map sang class JAXB; không trả entity.
- Thêm filter chain riêng cho `/ws/**`: stateless, tắt CSRF (chỉ cho path này), xác thực HTTP Basic hoặc WS-Security (`Wss4jSecurityInterceptor`, UsernameToken). Bật `PayloadValidatingInterceptor` để validate request theo XSD.
- Lỗi nghiệp vụ map sang SOAP Fault qua `SoapFaultMappingExceptionResolver`/`@SoapFault`, không lộ stack trace.
- **Client** (issue 8.3): `WebServiceTemplate` trong `service/soap/`, URL + tài khoản lấy từ cấu hình (env), có connect/read timeout; lỗi hoặc timeout thì trả kết quả rỗng và ghi log, không làm hỏng trang gọi nó. Demo bằng cách gọi chính endpoint SOAP của app (hoặc endpoint mock), hiển thị kết quả trên dashboard admin.

**OWASP Dependency-Check** (issue 2.5, đã cấu hình). Plugin `org.owasp:dependency-check-maven` (version ở property `dependency-check.version`, không có `<executions>`) chạy riêng bằng `./mvnw dependency-check:check` (hoặc `docker compose run --rm --no-deps maven dependency-check:check`), không gắn vào build mặc định để test vẫn nhanh. NVD API key (bắt buộc từ bản 12+) đọc từ biến môi trường `NVD_API_KEY` (khai báo trong `.env`, không ghi vào `pom.xml`; `./mvnw` không tự đọc `.env`). Fail khi có lỗ hổng CVSS ≥ 7; lỗ hổng không áp dụng thì ghi vào `dependency-check-suppressions.xml` kèm `<notes>` lý do + `until`; ưu tiên sửa bằng cách nâng version qua Spring Boot BOM. OSS Index tắt (cần tài khoản), RetireJS bật (quét JS trong WebJars). Chạy trước khi tạo PR có thêm/đổi dependency.

**Chart.** Giữ Chart.js (không cần thêm ECharts). Thêm chart (top sản phẩm bán chạy, số đơn theo ngày), dữ liệu tính trong `DashboardService`. Chart.js đã tự host qua WebJar và dashboard đã lấy dữ liệu qua endpoint JSON `GET /admin/dashboard/charts` (CSP chặt) — chart mới mở rộng `DashboardChartResDto` + `dashboard.js`. Cập nhật realtime qua STOMP `/topic/admin/orders` (gọi lại endpoint JSON hoặc nhận số liệu trong message).

**Testing.** JUnit 5 + Mockito + MockMvc (đã có `spring-boot-starter-test`, `spring-boot-webmvc-test`, `spring-security-test`, `spring-boot-security-test` — module cuối bắt buộc ở Boot 4 để `@WithMockUser` có tác dụng với MockMvc tự cấu hình).
- Unit test service: `@ExtendWith(MockitoExtension.class)`, `@Mock` repository, `@InjectMocks` service — không khởi động Spring.
- Test controller: `@WebMvcTest(XxxController.class)` + `@MockitoBean` cho service (Boot 4 đã bỏ `@MockBean`), `@WithMockUser(roles = "ADMIN")`, request POST phải có `.with(csrf())`. Luôn có case 401/403 cho endpoint được bảo vệ. Chain web có `AccountStatusFilter` tra DB mỗi request: trong `@SpringBootTest`, user của `@WithMockUser` phải tồn tại trong DB với đúng role (xem `DashboardChartsTest`), nếu không session bị huỷ → 302 `/login`; trong `@WebMvcTest` thì `@MockitoBean AccountStatusService` (mặc định trả `Optional.empty()` = session hợp lệ).
- Integration test: chuyển sang Testcontainers (`spring-boot-testcontainers`, `org.testcontainers:mysql`, `@ServiceConnection`) để không đụng DB dev (cần Docker trên máy). JMS test dùng Artemis embedded, không cần container.
- JaCoCo sinh báo cáo coverage khi `./mvnw test` (issue 3.2); chưa đặt ngưỡng chặn build.

**Logging (SLF4J + Logback)** — đã làm ở issue 1.1 (xem "Logging/audit" trong Ghi chú nghiệp vụ).
- `logback-spring.xml` với `<springProfile>`: `dev`/`prod` ghi console + file `SizeAndTimeBasedRollingPolicy` (qua `AsyncAppender`), `prod` thêm `audit.log` dạng JSON (`StructuredLogEncoder`, format `ecs`). Pattern text dùng converter riêng trong package `logging` thay cho `%msg`/`%wEx`: `%safeMsg` (thay mọi ký tự ngắt dòng, kể cả Unicode, bằng `_`), `%safeEx` (làm sạch message của exception), `%safeKvp` (in key-value audit kiểu logfmt, có quote/escape). `LogbackConfigTest` nạp file thật để kiểm tra — sửa pattern/appender thì chạy lại test này.
- Logger `AUDIT` cho sự kiện bảo mật: đăng nhập thành công/thất bại (kể cả cookie remember-me bị từ chối), khóa/mở khóa user, đổi role, đặt lại mật khẩu, đổi email, user tự đổi mật khẩu, session bị huỷ vì tài khoản bị khoá/đổi role, đổi trạng thái đơn; import Excel (giai đoạn 7) thêm vào `AuditLogService`.
- Luôn dùng placeholder `log.info("... {}", x)`, không nối chuỗi.

## OWASP Secure Coding — quy tắc bắt buộc

Áp dụng cho mọi code mới và khi sửa code cũ (theo nhóm của OWASP Secure Coding Practices Checklist).

**Input validation**
- Mọi dữ liệu vào (form, JSON, query param, Excel, SOAP, STOMP payload) đi qua DTO có Jakarta Validation và `@Valid`/`@Validated`; validate phía server, không tin validate phía client.
- Dùng whitelist: enum cho trạng thái/loại, regex cho định dạng, giới hạn độ dài. Giới hạn `size` phân trang (như `Math.clamp(size, 1, 100)` trong `ProductApiController`).

**Output encoding (XSS)**
- Thymeleaf dùng `th:text` (tự escape). Không dùng `th:utext` với dữ liệu người dùng (hiện không có chỗ nào dùng — giữ vậy).
- JS: đặt dữ liệu bằng `textContent`; nếu phải build HTML thì escape qua helper như `escapeHtml` trong `cart.js`. Dữ liệu từ server cho JS truyền qua thuộc tính `data-*` (Thymeleaf tự escape) hoặc endpoint JSON — không dùng `th:inline="javascript"` (CSP cấm script inline).
- **CSP** (đã bật ở chain web, `SecurityConfig.CONTENT_SECURITY_POLICY`): `script-src 'self'` — mọi JS nằm trong `static/js/**` hoặc WebJars, gắn sự kiện bằng `addEventListener`; không `<script>` inline, không `on*`/`th:on*`, không `javascript:`, không thêm host ngoài (`TemplateCspComplianceTest` fail nếu vi phạm). Hộp xác nhận ở admin dùng `data-confirm="..."` (`static/js/admin/main.js`). JS mới ở giai đoạn 6 (STOMP, chart realtime) theo cùng quy tắc; WebSocket same-origin đã nằm trong `connect-src 'self'`.

**Authentication & password**
- Mật khẩu lưu bằng `PasswordEncoder` (BCrypt), không bao giờ log hay trả về.
- Chính sách mật khẩu (đã làm): mật khẩu mới luôn khai báo `@ValidPassword` + `@NotCompromisedPassword(groups = PasswordBreachCheck.class)` trên field và `@GroupSequence({TheDto.class, PasswordBreachCheck.class})` trên DTO (`PasswordConstraintConventionTest` kiểm tra) — nguồn duy nhất là `service/security/PasswordPolicy`: 8–64 ký tự, ≤ 72 byte UTF-8 vì BCrypt; kiểm tra bị lộ qua `CompromisedPasswordChecker` = `security/PwnedPasswordsChecker` (cache theo prefix) chỉ chạy khi mọi ràng buộc khác đã đạt. Không tự viết `@Size` cho mật khẩu. `POST /register` giới hạn theo IP (`security/RegistrationRateLimitFilter`).
- Chống brute-force (đã làm): `security/LoginAttemptAwareAuthenticationProvider` (bean `AuthenticationProvider` duy nhất, dùng chung form + API) đếm qua `service/security/LoginAttemptService` ở 3 phạm vi: cặp (IP, tên nhập), IP (IPv6 gộp /64), tài khoản (tên nhập + username thật sau khi tra DB); key tên được chuẩn hoá như collation MySQL (NFKD, bỏ dấu, viết thường). Bộ đếm/khoá nằm trong bảng `attempt_limits` (`AttemptLimiter` → `repository/JdbcAttemptLimitRepository`: câu SQL nguyên tử, chạy ngoài transaction của người gọi để lần sai không bị rollback; key băm HMAC bằng `ATTEMPT_LIMITS_KEY_SECRET`; `AttemptLimitCleanupJob` xoá bản ghi hết hạn) nên dùng chung giữa các instance và không mất khi restart; `window`/`lock-duration` ≥ 1 giây (`@DurationMin`). Unit test dùng `InMemoryAttemptLimitRepository` (thư mục test) có cùng ngữ nghĩa. Lần thử được giữ chỗ TRƯỚC khi so BCrypt (`AttemptLimiter`) để request song song không vượt ngưỡng; đăng nhập đúng không xoá bộ đếm theo IP. Trạng thái tài khoản kiểm tra SAU mật khẩu. Thông báo lỗi đăng nhập chung chung, không phân biệt "sai username" với "sai mật khẩu". Ô "mật khẩu hiện tại" khi đổi mật khẩu cũng bị giới hạn (vượt → đăng xuất). Thao tác mới có so mật khẩu thì dùng lại `LoginAttemptService`.
- OAuth2 chỉ tự liên kết theo email khi provider xác minh email và tài khoản có sẵn không có mật khẩu (`UserService.provisionOAuthUser`, chống chiếm trước tài khoản).
- JWT: secret ≥ 256 bit từ env, HS256 cố định, kiểm tra hạn + `iss`/`aud` (`jwt.issuer`/`jwt.audience`, đã làm trong `JwtUtil`); không đặt dữ liệu nhạy cảm vào claims. Filter kiểm tra token trước khi tra DB.

**Session management**
- Cookie session (đã làm): `HttpOnly`, `SameSite=Lax`, `Secure` (`SESSION_COOKIE_SECURE`, prod mặc định `true`) qua `server.servlet.session.cookie.*`; timeout 30 phút. Giữ session fixation protection mặc định. Logout phải xóa session và cookie remember-me (đã làm). Tài khoản bị khoá, bị xoá/đổi username hoặc đổi role sau khi đăng nhập thì session bị huỷ ở request kế tiếp (đã làm: `security/AccountStatusFilter` ở chain web, trước `AnonymousAuthenticationFilter`, gọi `service/security/AccountStatusService` — 1 query/request đã đăng nhập, bỏ qua file tĩnh; audit `SESSION_REVOKED`). Chưa huỷ session khi đổi/đặt lại mật khẩu (issue #37).

**Access control**
- Phân quyền theo URL trong `SecurityConfig` và thêm `@PreAuthorize` ở service/controller cho thao tác nhạy cảm. Mặc định deny (`anyRequest().authenticated()`).
- Chống IDOR: mọi truy cập tài nguyên của user phải lọc theo user hiện tại ở service (như `requireOwnedCartItem`, `getOrderDetailForUser(id, userId)`), không chỉ tin id trên URL. Áp dụng tương tự cho STOMP destination và SOAP operation.

**CSRF & CORS**
- Không tắt CSRF ở chain web. Chỉ tắt cho chain stateless dùng Bearer token/HTTP Basic (`/api/**`, `/ws/**`). STOMP CONNECT phải mang CSRF token.
- CORS chỉ mở cho `/api/**` qua bean `corsConfigurationSource` (`SecurityConfig`) với allowlist `app.cors.allowed-origins` (`CORS_ALLOWED_ORIGINS`, rỗng = same-origin; mỗi giá trị phải đúng `scheme://host[:port]`, `*`/`null`/có path/`http` ngoài localhost làm app không khởi động — `config/properties/CorsProperties`), `allowCredentials=false`. Không mở CORS cho chain web.

**Cryptography & secrets**
- Không commit secret thật vào repo, kể cả làm giá trị mặc định `${VAR:default}` trong `application*.yml`. Default không được là khoá/secret mà app vẫn dùng được (kể cả chuỗi `changeme-*` — repo public nên ai cũng biết): khoá ký/mã hoá ở dev dùng `${VAR:${random.value}${random.value}}` — mỗi `random.value` là 16 byte từ `SecureRandom`, ghép lại đủ 256 bit (như `jwt.secret`, `app.security.remember-me-key`; không dùng `random.uuid`, chỉ có 122 bit ngẫu nhiên), còn lại để trống để app báo lỗi khi thiếu. Secret thật đặt ở biến môi trường hoặc file local đã nằm trong `.gitignore`. Secret nào lỡ bị push thì phải rotate.
- Dùng `SecureRandom`/UUID cho token; không tự viết thuật toán mã hóa.

**Error handling & logging**
- Người dùng chỉ thấy thông báo chung; stack trace chỉ nằm trong log (`ApiExceptionHandler` đã làm cho 500). Không trả `ex.getMessage()` của exception hệ thống/thư viện ra ngoài.
- Không log password, token, JWT, session id, số thẻ, toàn bộ request body. Chống log injection: pattern Logback đã dùng `%safeMsg`/`%safeEx`/`%safeKvp` (thay ký tự ngắt dòng, kể cả U+0085/U+2028/U+2029, bằng `_`) — giữ các converter này khi sửa pattern; giá trị đơn đưa vào MDC thì qua `LogSanitizer.sanitize`.
- Ghi audit log cho sự kiện bảo mật (xem mục Logging).

**Data protection & communication**
- Response API/SOAP chỉ chứa field cần thiết (DTO), không lộ password hash, provider id, dữ liệu nội bộ.
- Prod chạy HTTPS (HSTS — Spring Security chỉ gửi trên request HTTPS). Security headers khai báo ở `SecurityConfig.securityHeaders` (dùng cho cả 3 chain): Referrer-Policy, Permissions-Policy, cùng các header mặc định X-Content-Type-Options, X-Frame-Options, Cache-Control. Chain mới cũng phải gọi `.headers(SecurityConfig::securityHeaders)`. Thư viện front-end tự host qua WebJars, không dùng CDN.

**Database**
- Chỉ dùng JPQL/native query có tham số (`:param`), không nối chuỗi SQL (code hiện tại đều đúng). Tài khoản DB của app không cần quyền DDL ngoài Flyway.

**File management (upload ảnh, import Excel)**
- Giới hạn kích thước (`spring.servlet.multipart.*` trong `application.yml`, mọi profile). Kiểm tra cả extension lẫn magic bytes, vì `Content-Type` do client gửi lên không đáng tin (`FileStorageService` + `ImageFormat` đã làm). Lưu ý: request vượt giới hạn khiến Tomcat không parse được multipart nên `CsrfFilter` không đọc được `_csrf` → 403; form upload nên kiểm tra kích thước phía client để báo lỗi dễ hiểu.
- Tên file lưu luôn là UUID, đường dẫn phải nằm trong thư mục gốc (đã làm).
- Excel: giới hạn số dòng/sheet; giữ cấu hình chống zip bomb mặc định của POI (`ZipSecureFile`). Khi export, ô bắt đầu bằng `=`, `+`, `-`, `@` phải thêm tiền tố `'` để chống formula injection.
- XML/SOAP: không tự tạo `DocumentBuilderFactory`/`TransformerFactory` với cấu hình mặc định; nếu bắt buộc thì tắt DTD và external entity (chống XXE).

**System configuration & dependencies**
- Actuator: prod chỉ expose `health` (đã làm). Swagger tắt ở prod (đã làm).
- OWASP Dependency-Check đã cấu hình (xem mục Lộ trình) — chạy trước PR có thêm/đổi dependency; ưu tiên version do Spring Boot BOM quản lý. Thư viện front-end thêm qua WebJars (Maven) để được quét cùng.

### Lỗ hổng đã biết cần xử lý
- [x] Chưa chống brute-force đăng nhập (form + API).
- [x] `RegisterReqDto` giới hạn mật khẩu 6–32 ký tự → nâng lên tối thiểu 8, tối đa ≥ 64.
- [x] `FileStorageService` chưa kiểm tra magic bytes.
- [x] Chưa có Content-Security-Policy / Referrer-Policy; script CDN (Bootstrap, Font Awesome, Chart.js) chưa có SRI. (Đã bật CSP + Referrer-Policy; thư viện chuyển sang WebJars tự host.)
- [x] Chưa cấu hình cờ cookie session và timeout tường minh.
- [x] Chưa có audit log và `logback-spring.xml`.
- [ ] Chưa quét dependency (OWASP Dependency-Check) — issue 2.5.
- [x] Tài khoản bị admin khoá vẫn đăng nhập được bằng Google/Facebook/X (review PR #35).
- [x] Session đang đăng nhập vẫn dùng được (với quyền cũ) sau khi tài khoản bị khoá hoặc bị đổi role.
