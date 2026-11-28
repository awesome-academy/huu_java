package com.huuhv.foodsndrinks.config;

import com.huuhv.foodsndrinks.repository.HttpSessionStoreRepository;
import com.huuhv.foodsndrinks.security.SessionAuthenticationTimeStore;
import com.huuhv.foodsndrinks.config.properties.SessionStoreProperties;
import com.huuhv.foodsndrinks.support.IntegrationTest;
import com.huuhv.foodsndrinks.utils.SessionAuthenticationTime;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.jdbc.config.annotation.SpringSessionDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kho session HTTP (Spring Session JDBC) trên MySQL thật: pool riêng của kho session KHÔNG thay thế pool chính của app,
 * mọi thao tác session chạy trên pool riêng đó, session đọc lại được nguyên vẹn (kể cả {@code SecurityContext}) và session
 * hết hạn bị xoá khỏi DB.
 * <p>
 * Không {@code @Transactional}: kho session tự mở transaction {@code REQUIRES_NEW} trên pool của nó; test tự xoá session
 * nó tạo.
 */
@IntegrationTest
class SessionStoreConfigTest {

    @Autowired private DataSource primaryDataSource;
    @Autowired @SpringSessionDataSource private DataSource sessionDataSource;
    @Autowired @Qualifier(SessionStoreConfig.TRANSACTION_OPERATIONS_BEAN) private TransactionOperations sessionTransactions;
    @Autowired private JdbcIndexedSessionRepository sessionRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ApplicationContext context;
    @Autowired private Environment environment;
    @Autowired private HttpSessionStoreRepository httpSessionStoreRepository;
    @Autowired private SessionAuthenticationTimeStore sessionAuthenticationTimeStore;

    @Test
    void sessionPool_isASecondPool_andTheAutoConfiguredPrimaryPoolIsStillThere() throws Exception {
        HikariDataSource primary = primaryDataSource.unwrap(HikariDataSource.class);
        HikariDataSource session = sessionDataSource.unwrap(HikariDataSource.class);

        // Bean defaultCandidate=false: DataSource auto-config của Boot không lùi lại, inject không qualifier vẫn là pool chính
        assertThat(primary).isNotSameAs(session);
        assertThat(primary.getPoolName()).isNotEqualTo(SessionStoreProperties.POOL_NAME);
        assertThat(primary.getMaximumPoolSize())
                .isEqualTo(environment.getRequiredProperty("spring.datasource.hikari.maximum-pool-size", Integer.class));
        assertThat(jdbcTemplate.getDataSource()).isSameAs(primaryDataSource);

        assertThat(session.getPoolName()).isEqualTo(SessionStoreProperties.POOL_NAME);
        assertThat(session.getMaximumPoolSize())
                .isEqualTo(environment.getRequiredProperty("app.session.pool-size", Integer.class));
        // Cùng database với pool chính (bảng SPRING_SESSION do Flyway của pool chính tạo)
        assertThat(session.getJdbcUrl()).isEqualTo(primary.getJdbcUrl());
        assertThat(session.getUsername()).isEqualTo(primary.getUsername());
    }

    @Test
    void appStillHasExactlyOneTransactionManager_soTransactionalKeepsUsingJpa() {
        // DataSourceTransactionManager của kho session cố ý không phải bean
        assertThat(context.getBeansOfType(PlatformTransactionManager.class)).hasSize(1).containsValue(transactionManager);
        assertThat(context.getBeanNamesForType(DataSource.class)).hasSize(2);
    }

    @Test
    void sessionRepository_readsAndWritesThroughTheSessionPool_inItsOwnTransactions() {
        JdbcTemplate sessionJdbc = (JdbcTemplate) ReflectionTestUtils.getField(sessionRepository, "jdbcOperations");
        Object transactions = ReflectionTestUtils.getField(sessionRepository, "transactionOperations");

        assertThat(sessionJdbc).isNotNull();
        assertThat(sessionJdbc.getDataSource()).isSameAs(sessionDataSource);
        // Không phải TransactionTemplate dựng trên JpaTransactionManager của app (sẽ lấy connection của pool chính)
        assertThat(transactions).isSameAs(sessionTransactions);
        assertThat(((TransactionTemplate) sessionTransactions).getTransactionManager()).isNotSameAs(transactionManager);
    }

    @Test
    void session_withSecurityContextAndAuthenticationTime_isReadBackIntact() {
        Instant authenticatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        User principal = new User("alice", "", AuthorityUtils.createAuthorityList("ROLE_USER"));
        SecurityContext securityContext = new SecurityContextImpl(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));

        String id = create(sessionRepository, Duration.ofMinutes(30), Instant.now(), session -> {
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);
            session.setAttribute(SessionAuthenticationTime.ATTRIBUTE, authenticatedAt.toEpochMilli());
        });
        try {
            Session restored = sessionRepository.findById(id);

            assertThat(restored).isNotNull();
            SecurityContext restoredContext =
                    restored.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            assertThat(restoredContext.getAuthentication().getName()).isEqualTo("alice");
            assertThat(restored.<Long>getAttribute(SessionAuthenticationTime.ATTRIBUTE))
                    .isEqualTo(authenticatedAt.toEpochMilli());
            assertThat(restored.getMaxInactiveInterval()).isEqualTo(Duration.ofMinutes(30));
        } finally {
            sessionRepository.deleteById(id);
        }
        assertThat(rowsOf(id)).isZero();
    }

    @Test
    void expiredSessions_areDeletedFromTheDatabase_byTheCleanupJob_andLiveOnesAreKept() {
        String expired = create(sessionRepository, Duration.ofMinutes(30), Instant.now().minus(Duration.ofMinutes(31)),
                session -> session.setAttribute("marker", "expired"));
        String live = create(sessionRepository, Duration.ofMinutes(30), Instant.now(),
                session -> session.setAttribute("marker", "live"));
        try {
            assertThat(rowsOf(expired)).isEqualTo(1);

            // Job @Scheduled có sẵn của Spring Session (spring.session.jdbc.cleanup-cron) gọi đúng method này
            sessionRepository.cleanUpExpiredSessions();

            assertThat(rowsOf(expired)).isZero();
            // Attribute của nó cũng mất theo (FK ON DELETE CASCADE)
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM SPRING_SESSION_ATTRIBUTES a
                    LEFT JOIN SPRING_SESSION s ON s.PRIMARY_ID = a.SESSION_PRIMARY_ID
                    WHERE s.PRIMARY_ID IS NULL
                    """, Integer.class)).isZero();
            assertThat(rowsOf(live)).isEqualTo(1);
        } finally {
            sessionRepository.deleteById(live);
            sessionRepository.deleteById(expired);
        }
    }

    @Test
    void liveSessionIds_areFoundInOneQuery_excludingExpiredAndUnknownIds() {
        // Job thu hồi WebSocket hỏi kho session theo lô (thay vì findById từng socket)
        String expired = create(sessionRepository, Duration.ofMinutes(30), Instant.now().minus(Duration.ofMinutes(31)),
                session -> session.setAttribute("marker", "expired"));
        String live = create(sessionRepository, Duration.ofMinutes(30), Instant.now(),
                session -> session.setAttribute("marker", "live"));
        try {
            assertThat(httpSessionStoreRepository.findLiveSessionIds(
                    List.of(live, expired, "no-such-session", live), Instant.now().toEpochMilli()))
                    .containsExactly(live);
            // Chỉ đọc: không xoá session hết hạn (việc của job dọn dẹp), không gia hạn session còn sống
            assertThat(rowsOf(expired)).isEqualTo(1);
        } finally {
            sessionRepository.deleteById(live);
            sessionRepository.deleteById(expired);
        }
    }

    @Test
    void authenticationTime_isWrittenStraightIntoTheStoredSession_andReadBack() {
        Instant loggedInAt = Instant.parse("2026-11-28T01:00:00.123Z");
        Instant passwordChangedAt = Instant.parse("2026-11-28T02:00:00.456Z");
        String id = create(sessionRepository, Duration.ofMinutes(30), Instant.now(),
                session -> session.setAttribute(SessionAuthenticationTime.ATTRIBUTE, loggedInAt.toEpochMilli()));
        try {
            assertThat(sessionAuthenticationTimeStore.reload(id)).isEqualTo(loggedInAt);

            assertThat(sessionAuthenticationTimeStore.persist(id, passwordChangedAt)).isTrue();

            // Request khác của cùng session thấy ngay, không chờ request đang chạy lưu session lúc response commit
            Session stored = sessionRepository.findById(id);
            assertThat(stored.<Long>getAttribute(SessionAuthenticationTime.ATTRIBUTE))
                    .isEqualTo(passwordChangedAt.toEpochMilli());
            assertThat(sessionAuthenticationTimeStore.reload(id)).isEqualTo(passwordChangedAt);
        } finally {
            sessionRepository.deleteById(id);
        }
        // Session không có trong kho (mới tạo trong request hiện tại) → người gọi ghi vào session của request
        assertThat(sessionAuthenticationTimeStore.persist(id, passwordChangedAt)).isFalse();
        assertThat(sessionAuthenticationTimeStore.reload(id)).isNull();
    }

    @Test
    void schemaComesFromFlyway_andTimeoutAndCookieNameAreKept() {
        assertThat(environment.getProperty("spring.session.jdbc.initialize-schema")).isEqualTo("never");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE script LIKE '%create_table_spring_session%'",
                Integer.class)).isEqualTo(1);
        // Hành vi trước khi đổi kho session: hết hạn sau 30 phút, cookie tên JSESSIONID
        assertThat(environment.getProperty("server.servlet.session.timeout", Duration.class))
                .isEqualTo(Duration.ofMinutes(30));
        assertThat(environment.getProperty("server.servlet.session.cookie.name")).isEqualTo("JSESSIONID");
        Session fresh = sessionRepository.createSession();   // chưa save → không ghi gì vào DB
        assertThat(fresh.getMaxInactiveInterval()).isEqualTo(Duration.ofMinutes(30));
    }

    /** Tạo + lưu một session (kiểu session cụ thể của kho là lớp lồng không public → generic). */
    private static <S extends Session> String create(SessionRepository<S> repository, Duration maxInactive,
                                                    Instant lastAccessed, java.util.function.Consumer<Session> setup) {
        S session = repository.createSession();
        session.setMaxInactiveInterval(maxInactive);
        session.setLastAccessedTime(lastAccessed);
        setup.accept(session);
        repository.save(session);
        return session.getId();
    }

    private int rowsOf(String sessionId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION WHERE SESSION_ID = ?", Integer.class,
                sessionId);
    }
}
