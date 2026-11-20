package com.huuhv.foodsndrinks.support;

import com.huuhv.foodsndrinks.config.SecurityConfig;
import com.huuhv.foodsndrinks.security.CustomOAuth2UserService;
import com.huuhv.foodsndrinks.security.CustomOidcUserService;
import com.huuhv.foodsndrinks.security.CustomUserDetailsService;
import com.huuhv.foodsndrinks.security.JwtUtil;
import com.huuhv.foodsndrinks.security.OAuth2LoginFailureHandler;
import com.huuhv.foodsndrinks.service.OrderService;
import com.huuhv.foodsndrinks.service.UserService;
import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import com.huuhv.foodsndrinks.service.security.AccountStatusService;
import com.huuhv.foodsndrinks.service.security.LoginAttemptService;
import com.huuhv.foodsndrinks.service.security.RegistrationRateLimitService;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@code @WebMvcTest} chạy với 3 filter chain THẬT của {@link SecurityConfig} (phân quyền URL, CSRF, entry point 401
 * của API, redirect /login của web) — slice mặc định không nạp {@code SecurityConfig} vì nó là {@code @Configuration}.
 * <p>
 * Mọi bean mà {@code SecurityConfig}, các filter ({@code JwtAuthenticationFilter}, {@code AccountStatusFilter},
 * {@code RegistrationRateLimitFilter}) và {@code WebLayoutModelAdvice} cần đều là mock Mockito, nên slice không cần DB.
 * Muốn stub thì {@code @Autowired} mock đó vào test (khai báo lại bằng {@code @MockitoBean} sẽ bị trùng). Mặc định:
 * <ul>
 *   <li>{@link AccountStatusService} trả {@code Optional.empty()} = session hợp lệ;</li>
 *   <li>{@link JwtUtil#validUsername} trả {@code Optional.empty()} = mọi Bearer token đều sai;</li>
 *   <li>{@link RegistrationRateLimitService#tryAcquire} trả {@code false} = {@code POST /register} bị chặn — test
 *       đăng ký phải stub {@code true}.</li>
 * </ul>
 * Service riêng của controller đang test (vd. {@code ProductService}) khai báo {@code @MockitoBean} trong test class.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@WebMvcTest
@Import(SecurityConfig.class)
@MockitoBean(types = {
        // Bean SecurityConfig inject
        CustomOidcUserService.class, CustomOAuth2UserService.class, OAuth2LoginFailureHandler.class,
        CustomUserDetailsService.class, PasswordEncoder.class, LoginAttemptService.class, AuditLogService.class,
        AccountStatusService.class, RegistrationRateLimitService.class, ClientRegistrationRepository.class,
        // JwtAuthenticationFilter (Filter → được slice nạp)
        JwtUtil.class,
        // WebLayoutModelAdvice (@ControllerAdvice → được slice nạp)
        UserService.class, OrderService.class
})
public @interface SecuredWebMvcTest {

    /** Controller cần nạp (như {@code @WebMvcTest(controllers = ...)}). */
    @AliasFor(annotation = WebMvcTest.class, attribute = "controllers")
    Class<?>[] value() default {};
}
