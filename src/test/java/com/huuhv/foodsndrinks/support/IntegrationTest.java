package com.huuhv.foodsndrinks.support;

import com.huuhv.foodsndrinks.TestcontainersConfiguration;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Integration test ({@code @SpringBootTest}) chạy trên MySQL của Testcontainers và chỉ với profile {@code test}.
 * Không dùng {@code @SpringBootTest} trần: {@code IntegrationTestConventionTest} sẽ fail.
 * <ul>
 *   <li>{@code @ActiveProfiles("test")}: {@code SpringBootContextLoader} đặt {@code spring.profiles.active=test} ở
 *       property source ưu tiên cao nhất, thắng cả {@code SPRING_PROFILES_ACTIVE} của môi trường, nên profile
 *       {@code dev} không bật. Khi {@code dev} bật, {@code application-dev.yml} thắng {@code config/application.yml}
 *       vì file theo profile luôn thắng file không theo profile cùng nhóm vị trí. Khi đó test sẽ dùng datasource,
 *       {@code JWT_SECRET} và {@code REMEMBER_ME_KEY} của dev (lấy từ {@code .env}).</li>
 *   <li>{@code @TestPropertySource}: inline property thắng cả biến môi trường. Cần vì biến môi trường {@code JWT_SECRET}
 *       (vd. {@code env_file: .env} của compose) được Spring Boot map thẳng sang {@code jwt.secret} (relaxed binding),
 *       thắng mọi file yml. Tương tự {@code SPRING_DATASOURCE_URL}. Giá trị trùng {@code config/application.yml}
 *       (file đó vẫn dùng cho test không qua annotation này, vd. {@code @WebMvcTest}).</li>
 *   <li>{@code @Import(TestcontainersConfiguration.class)}: {@code @ServiceConnection} cấp kết nối tới container.</li>
 *   <li>{@code @Tag("integration")}: cần Docker. Bỏ qua bằng {@code -DexcludedGroups=integration} (service
 *       {@code maven} của compose, không có Docker socket).</li>
 * </ul>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:mysql://testcontainers-required.invalid:3306/foodsndrinks",
        "jwt.secret=${random.value}${random.value}",
        "app.security.remember-me-key=${random.value}${random.value}",
        "app.security.attempt-limits.key-secret=${random.value}${random.value}",
        // Broker embedded trong JVM, kể cả khi môi trường có SPRING_ARTEMIS_* (relaxed binding thắng file yml)
        "spring.artemis.mode=embedded",
        "spring.artemis.embedded.enabled=true"
})
@Tag("integration")
public @interface IntegrationTest {

    @AliasFor(annotation = SpringBootTest.class)
    SpringBootTest.WebEnvironment webEnvironment() default SpringBootTest.WebEnvironment.MOCK;
}
