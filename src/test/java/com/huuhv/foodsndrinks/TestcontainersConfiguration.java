package com.huuhv.foodsndrinks;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Database của integration test: MySQL 8.4 do Testcontainers tạo (cần Docker), không đụng tới database dev.
 * Mọi {@code @SpringBootTest} khai báo {@code @Import(TestcontainersConfiguration.class)} —
 * {@code IntegrationTestConventionTest} kiểm tra; thiếu thì {@code spring.datasource.url} trong
 * {@code src/test/resources/config/application.yml} trỏ tới host không tồn tại nên test báo lỗi ngay.
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
