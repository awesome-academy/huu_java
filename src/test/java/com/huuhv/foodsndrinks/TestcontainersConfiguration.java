package com.huuhv.foodsndrinks;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

/**
 * Database của integration test: MySQL 8.4 do Testcontainers tạo (cần Docker), không đụng tới database dev.
 * Không import trực tiếp: dùng {@link com.huuhv.foodsndrinks.support.IntegrationTest @IntegrationTest} (import class
 * này và chỉ bật profile {@code test}). {@code IntegrationTestConventionTest} kiểm tra. Context không có class này thì
 * {@code spring.datasource.url} trong {@code src/test/resources/config/application.yml} trỏ tới host không tồn tại nên
 * test báo lỗi ngay ({@code TestEnvironmentIsolationTest}).
 * <p>
 * {@code @ServiceConnection}: Spring Boot lấy URL/user/password từ container (ghi đè {@code spring.datasource.*}),
 * Flyway tạo schema như khi chạy thật. Test vẫn giữ {@code @Transactional} để rollback, vì các test class dùng chung DB.
 * <p>
 * Dùng chung MỘT container cho cả JVM (singleton container): mỗi cấu hình test khác nhau (vd. {@code @TestPropertySource},
 * {@code RANDOM_PORT}) là một Spring context riêng, mỗi context gọi bean này nhưng nhận lại cùng một container — không
 * phải khởi động MySQL + chạy migration lại cho từng context. Context đóng không được dừng container vì các context
 * khác còn dùng ({@code stop()} không làm gì); container bị xoá khi JVM kết thúc (Ryuk của Testcontainers).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** Cùng phiên bản và cấu hình với service {@code mysql} trong docker-compose.yaml. */
    static final DockerImageName MYSQL_IMAGE = DockerImageName.parse("mysql:8.4");

    private static final MySQLContainer MYSQL = new MySQLContainer(MYSQL_IMAGE) {
        @Override
        public void stop() {
            // Dùng chung cho mọi context — Ryuk dọn khi JVM kết thúc
        }
    }
            .withDatabaseName("foodsndrinks")
            // Testcontainers publish cổng ngẫu nhiên trên mọi interface của host (maven-docker đi qua gateway nên không
            // bind riêng 127.0.0.1). Không dùng tài khoản mặc định test/test (root cũng là test): user riêng, mật khẩu
            // ngẫu nhiên cho mỗi lần chạy (cũng là mật khẩu root). @ServiceConnection tự lấy, test không cần biết.
            .withUsername("foodsndrinks_test")
            .withPassword(UUID.randomUUID().toString())
            // Như URL của dev/compose: Connector/J đổi LocalDateTime từ múi giờ JVM sang UTC trước khi gửi. Thiếu tham số
            // này, getJdbcUrl() (chỉ thêm useSSL/allowPublicKeyRetrieval) để driver dùng múi giờ JVM (vd. Asia/Ho_Chi_Minh
            // trong container maven) và thời gian được lưu khác dev/prod.
            .withUrlParam("serverTimezone", "UTC")
            .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_unicode_ci",
                    // Khớp serverTimezone=UTC của JDBC URL, như compose (DB lưu thời gian theo UTC)
                    "--default-time-zone=+00:00");

    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return MYSQL;
    }
}
