package com.huuhv.foodsndrinks.config;

import com.huuhv.foodsndrinks.config.properties.SessionStoreProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.session.jdbc.config.annotation.SpringSessionDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/**
 * Kho session HTTP dùng chung giữa các instance: Spring Session JDBC (bảng {@code SPRING_SESSION*} do Flyway tạo,
 * {@code spring.session.jdbc.initialize-schema=never}). Request và handshake WebSocket tới instance nào cũng thấy cùng
 * session — load balancer không cần sticky, instance chết không làm user bị đăng xuất.
 * <p>
 * <b>Vì sao kho session có pool connection riêng</b> (cùng database, khác pool): Open Session in View làm thread request
 * giữ một connection của pool chính từ câu truy vấn đầu tiên tới hết lúc render; Spring Session lại ghi session khi response
 * commit — với trang lớn hơn buffer (8KB) là NGAY GIỮA lúc render — bằng một transaction {@code REQUIRES_NEW}, tức cần
 * thêm một connection. Dùng chung pool thì mỗi request có thể "giữ một, chờ một": đủ nhiều request cùng lúc sẽ giữ hết
 * pool và chờ lẫn nhau tới hết connection-timeout (kéo theo cả job nền). Pool riêng cắt vòng chờ đó: thao tác trên kho
 * session ngắn, tự đủ, không bao giờ chờ pool chính trong lúc giữ connection của pool session.
 * <p>
 * Hai bean dưới đây là {@code defaultCandidate = false}: chỉ được inject khi nơi nhận ghi qualifier
 * ({@link SpringSessionDataSource} / tên {@code springSessionTransactionOperations} — đúng hai điểm
 * {@code JdbcHttpSessionConfiguration} của Spring Session tìm). Nhờ vậy {@code DataSource} auto-config của Boot (pool
 * chính: JPA, Flyway, ShedLock, {@code JdbcTemplate}...) KHÔNG lùi lại và mọi chỗ inject {@code DataSource} không
 * qualifier vẫn nhận pool chính — {@code SessionStoreConfigTest} kiểm tra.
 * <p>
 * Transaction của kho session phải chạy trên pool session: không khai báo {@code TransactionOperations} riêng thì Spring
 * Session dùng {@code JpaTransactionManager} của app, mỗi lần ghi session lại mở một transaction JPA (lấy connection của
 * pool chính) — đúng thứ cần tránh. {@code DataSourceTransactionManager} ở đây cố ý không phải bean (thêm một
 * {@code PlatformTransactionManager} sẽ làm {@code @Transactional} của app không còn chọn được duy nhất một cái).
 * <p>
 * Dọn session hết hạn: job có sẵn của Spring Session ({@code spring.session.jdbc.cleanup-cron}, mặc định mỗi phút) chạy
 * trên MỌI instance, không ShedLock — ngoại lệ có chủ ý như {@code AttemptLimitCleanupJob}: chỉ là một câu
 * {@code DELETE ... WHERE EXPIRY_TIME < ?}, chạy trùng thì instance sau xoá 0 dòng.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SessionStoreProperties.class)
public class SessionStoreConfig {

    /** Tên bean mà {@code JdbcHttpSessionConfiguration} tìm ({@code @Qualifier}) — không đổi. */
    static final String TRANSACTION_OPERATIONS_BEAN = "springSessionTransactionOperations";

    /** Cùng URL/tài khoản với pool chính (kể cả MySQL của Testcontainers — lấy từ {@link JdbcConnectionDetails}). */
    @Bean(defaultCandidate = false)
    @SpringSessionDataSource
    public HikariDataSource sessionDataSource(JdbcConnectionDetails connection, SessionStoreProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName(SessionStoreProperties.POOL_NAME);
        dataSource.setJdbcUrl(connection.getJdbcUrl());
        dataSource.setUsername(connection.getUsername());
        dataSource.setPassword(connection.getPassword());
        dataSource.setDriverClassName(connection.getDriverClassName());
        dataSource.setMaximumPoolSize(properties.poolSize());
        dataSource.setMinimumIdle(Math.min(2, properties.poolSize()));
        dataSource.setConnectionTimeout(properties.connectionTimeout().toMillis());
        return dataSource;
    }

    /** {@code REQUIRES_NEW} như mặc định của Spring Session: ghi session không dính vào transaction của người gọi. */
    @Bean(name = TRANSACTION_OPERATIONS_BEAN, defaultCandidate = false)
    public TransactionOperations springSessionTransactionOperations(@SpringSessionDataSource DataSource sessionDataSource) {
        TransactionTemplate template = new TransactionTemplate(new DataSourceTransactionManager(sessionDataSource));
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }
}
