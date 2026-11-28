package com.huuhv.foodsndrinks.config;

import com.huuhv.foodsndrinks.config.properties.AttemptLimitProperties;
import com.huuhv.foodsndrinks.config.properties.CorsProperties;
import com.huuhv.foodsndrinks.config.properties.LoginAttemptProperties;
import com.huuhv.foodsndrinks.config.properties.RegistrationRateLimitProperties;
import com.huuhv.foodsndrinks.config.properties.SoapProperties;
import com.huuhv.foodsndrinks.controller.CsrfTokenController;
import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.exception.LoginThrottledException;
import com.huuhv.foodsndrinks.logging.RequestIdFilter;
import com.huuhv.foodsndrinks.security.AccountStatusFilter;
import com.huuhv.foodsndrinks.security.AuditingRememberMeServices;
import com.huuhv.foodsndrinks.security.CustomOAuth2UserService;
import com.huuhv.foodsndrinks.security.CustomOidcUserService;
import com.huuhv.foodsndrinks.security.CustomUserDetailsService;
import com.huuhv.foodsndrinks.security.JwtAuthenticationFilter;
import com.huuhv.foodsndrinks.security.LoginAttemptAwareAuthenticationProvider;
import com.huuhv.foodsndrinks.security.OAuth2LoginFailureHandler;
import com.huuhv.foodsndrinks.security.RegistrationRateLimitFilter;
import com.huuhv.foodsndrinks.security.RequestBodySizeLimitFilter;
import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import com.huuhv.foodsndrinks.service.security.AccountStatusService;
import com.huuhv.foodsndrinks.service.security.LoginAttemptService;
import com.huuhv.foodsndrinks.service.security.RegistrationRateLimitService;
import com.huuhv.foodsndrinks.service.websocket.StompDestinations;
import com.huuhv.foodsndrinks.utils.SessionAuthenticationTime;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
import org.springframework.security.authentication.event.AuthenticationFailureLockedEvent;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.ExceptionMappingAuthenticationFailureHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity   // enable @PreAuthorize / @Secured on individual methods
@EnableConfigurationProperties({LoginAttemptProperties.class, RegistrationRateLimitProperties.class,
        AttemptLimitProperties.class, CorsProperties.class, SoapProperties.class})
@RequiredArgsConstructor
public class SecurityConfig {

    /** App không dùng camera, micro, vị trí — tắt cho cả trang và mọi iframe. */
    static final String PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=()";

    /** Realm trong header {@code WWW-Authenticate} của chain SOAP. */
    static final String SOAP_REALM = "foodsndrinks-soap";

    /**
     * Content-Security-Policy của chain web (storefront + admin + trang lỗi). Script chỉ từ file của chính app
     * ({@code static/js/**}, {@code /webjars/**}) — KHÔNG script inline, KHÔNG handler {@code on*} (test
     * {@code TemplateCspComplianceTest} chặn). Style: file CSS của app; riêng thuộc tính {@code style="..."} được phép
     * ({@code style-src-attr}) vì template còn dùng nhiều, còn thẻ {@code <style>} inline (dạng tiêm CSS nguy hiểm hơn)
     * vẫn bị chặn. {@code img-src}: {@code data:} cho icon SVG nhúng trong CSS của Bootstrap, cùng các host avatar
     * OAuth2 (Google, Facebook, Twitter/X). Chain API không đặt CSP vì Swagger UI ({@code /api-docs/**}) nằm chung chain.
     */
    static final String CONTENT_SECURITY_POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self'",
            "style-src-attr 'unsafe-inline'",
            "img-src 'self' data: https://*.googleusercontent.com https://platform-lookaside.fbsbx.com"
                    + " https://*.fbcdn.net https://pbs.twimg.com",
            "font-src 'self'",
            "connect-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'");

    @Value("${app.security.remember-me-key}")
    private String rememberMeKey;

    private final CustomOidcUserService  customOidcUserService;
    private final CustomOAuth2UserService customOAuth2UserService;
    private final JwtAuthenticationFilter jwtAuthFilter;
    private final OAuth2LoginFailureHandler oAuth2LoginFailureHandler;

    /** Expose AuthenticationManager so AuthService can authenticate username/password for the JWT login API. */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * Provider username/mật khẩu có chống brute-force. Là bean {@code AuthenticationProvider} duy nhất nên
     * {@code AuthenticationManager} toàn cục (form login + API login) dùng nó thay cho provider Spring tự tạo.
     */
    @Bean
    public LoginAttemptAwareAuthenticationProvider loginAttemptAwareAuthenticationProvider(
            CustomUserDetailsService userDetailsService, PasswordEncoder passwordEncoder,
            LoginAttemptService loginAttemptService) {
        return new LoginAttemptAwareAuthenticationProvider(userDetailsService, passwordEncoder, loginAttemptService);
    }

    /**
     * Thay bean mặc định của Boot chỉ để map thêm {@link LoginThrottledException} → event lỗi (publisher map theo đúng
     * tên class, subclass của LockedException không được publish) — audit log ghi được cả lần đăng nhập bị chặn.
     */
    @Bean
    public AuthenticationEventPublisher authenticationEventPublisher(ApplicationEventPublisher publisher) {
        DefaultAuthenticationEventPublisher eventPublisher = new DefaultAuthenticationEventPublisher(publisher);
        eventPublisher.setAdditionalExceptionMappings(
                Map.of(LoginThrottledException.class, AuthenticationFailureLockedEvent.class));
        return eventPublisher;
    }

    /**
     * CORS chỉ cho REST API {@code /api/**} (chain web dùng session cookie, không mở CORS). Allowlist rỗng → không đăng
     * ký cấu hình nào → trình duyệt chỉ cho gọi same-origin. API xác thực bằng Bearer token nên
     * {@code allowCredentials=false} (trình duyệt không gửi cookie).
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties corsProperties) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (!corsProperties.allowedOrigins().isEmpty()) {
            CorsConfiguration config = new CorsConfiguration();
            config.setAllowedOrigins(corsProperties.allowedOrigins());
            config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
            config.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, RequestIdFilter.HEADER));
            config.setExposedHeaders(List.of(RequestIdFilter.HEADER, HttpHeaders.RETRY_AFTER));
            config.setAllowCredentials(false);
            config.setMaxAge(Duration.ofHours(1));
            source.registerCorsConfiguration("/api/**", config);
        }
        return source;
    }

    /**
     * Header bảo mật dùng chung cho mọi chain. Giữ nguyên các header mặc định của Spring Security:
     * {@code X-Content-Type-Options: nosniff}, {@code X-Frame-Options: DENY}, {@code Cache-Control: no-cache...} và HSTS
     * ({@code max-age=31536000; includeSubDomains}) — HSTS chỉ được gửi trên request HTTPS, tức prod sau load balancer
     * (forward-headers-strategy=native nhận X-Forwarded-Proto từ TRUSTED_PROXIES).
     */
    static void securityHeaders(HeadersConfigurer<HttpSecurity> headers) {
        headers
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .permissionsPolicyHeader(permissions -> permissions.policy(PERMISSIONS_POLICY));
    }

    /** Form login: bị chặn do sai quá nhiều lần → /login?locked=true, lỗi khác → thông báo chung /login?error=true. */
    private static AuthenticationFailureHandler formLoginFailureHandler() {
        ExceptionMappingAuthenticationFailureHandler handler = new ExceptionMappingAuthenticationFailureHandler();
        handler.setDefaultFailureUrl("/login?error=true");
        handler.setExceptionMappings(Map.of(LoginThrottledException.class.getName(), "/login?locked=true"));
        return handler;
    }

    /** jwtAuthFilter is a @Component, so Boot would also auto-register it as a servlet filter for EVERY
     *  request (web pages included). Disable that — it must only run inside the API security chain below. */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthFilterRegistration(JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    // -------------------------------------------------------
    // Chain 1: Actuator endpoints — ROLE_ADMIN only
    // Kept first (highest priority) so the web/api chains
    // never accidentally open an actuator path.
    // -------------------------------------------------------
    @Bean
    @Order(1)
    public SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/actuator/**")
                .headers(SecurityConfig::securityHeaders)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Basic liveness/readiness probe — no credentials needed
                        .requestMatchers("/actuator/health").permitAll()
                        // Everything else (metrics, info, caches, env…) → admins only
                        .anyRequest().hasRole("ADMIN")
                );

        return http.build();
    }

    // -------------------------------------------------------
    // Chain 2: REST API — stateless, JWT-based (no session)
    // -------------------------------------------------------
    @Bean
    @Order(2)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/**", "/api-docs/**")
                .headers(SecurityConfig::securityHeaders)
                .csrf(AbstractHttpConfigurer::disable)   // stateless → CSRF not needed
                // Bean corsConfigurationSource: chỉ origin trong app.cors.allowed-origins, preflight trả lời trước khi xác thực
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Swagger UI & OpenAPI spec (tighten or remove in prod via springdoc.swagger-ui.enabled=false)
                        .requestMatchers("/api-docs/**", "/api/v1/api-docs/**").permitAll()
                        // Auth endpoints (login, register, refresh-token) — public
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/products/**").hasAnyRole("USER", "ADMIN")
                        // Every other API call must be authenticated
                        .anyRequest().authenticated()
                )
                // No entry point configured → Spring falls back to 403; a missing/invalid token must be 401
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                );
         http.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // -------------------------------------------------------
    // Chain 3: SOAP (Spring-WS) — stateless, HTTP Basic
    // -------------------------------------------------------
    /**
     * {@code /ws/**} ({@code MessageDispatcherServlet}): client là hệ thống đối tác, không phải trình duyệt — không
     * session/cookie nên CSRF tắt CHỈ ở chain này; xác thực bằng HTTP Basic với tài khoản của app, đi qua cùng
     * {@code LoginAttemptAwareAuthenticationProvider} với form/API (giới hạn đăng nhập sai, từ chối tài khoản bị khoá —
     * stateless nên được kiểm tra ở MỌI request). Mọi operation cần {@code USER}/{@code ADMIN}; riêng file WSDL (GET) là
     * public — hợp đồng vốn nằm trong repo. Chưa/sai tài khoản → 401 + {@code WWW-Authenticate}, không body.
     * <p>
     * Phân quyền theo từng operation không làm được ở đây: Spring-WS định tuyến theo PAYLOAD, mọi operation chung một URL —
     * endpoint/service tự kiểm tra (vd. {@code OrderService.getPlacedOrderStatus}). Đừng mở {@code permitAll} cho một
     * đường dẫn con của {@code /ws}: mọi operation đều gọi được qua đường dẫn đó.
     */
    @Bean
    @Order(3)
    public SecurityFilterChain soapSecurityFilterChain(HttpSecurity http, SoapProperties soapProperties) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, exception) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"" + SOAP_REALM + "\"");
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
        };
        http
                .securityMatcher(SoapProperties.PATH, SoapProperties.PATH + "/**")
                .headers(SecurityConfig::securityHeaders)
                .csrf(AbstractHttpConfigurer::disable)   // stateless, không cookie → không có gì để CSRF
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(RequestCacheConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, SoapProperties.PATH + "/*.wsdl").permitAll()
                        .anyRequest().hasAnyRole("USER", "ADMIN")
                )
                .httpBasic(basic -> basic.realmName(SOAP_REALM).authenticationEntryPoint(unauthorized))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(unauthorized))
                // Chặn body quá lớn trước khi SAAJ parse (mọi tài khoản tự đăng ký đều gọi được SOAP)
                .addFilterAfter(new RequestBodySizeLimitFilter(soapProperties.maxRequestSize().toBytes()),
                        BasicAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationSuccessHandler customAuthenticationSuccessHandler() {
        return (HttpServletRequest request, HttpServletResponse response, Authentication authentication) -> {

            // Form + OAuth2: ghi mốc xác thực vào session — session đăng nhập trước lần đổi mật khẩu gần nhất bị huỷ
            // (AccountStatusService). Remember-me ghi ở AuditingRememberMeServices.
            SessionAuthenticationTime.stamp(request, SessionAuthenticationTime.now());

            // Honor any saved request (e.g. user was trying to access /profile before being redirected to /login)
            var requestCache = new HttpSessionRequestCache();
            var savedRequest = requestCache.getRequest(request, response);
            if (savedRequest != null) {
                new org.springframework.security.web.DefaultRedirectStrategy()
                        .sendRedirect(request, response, savedRequest.getRedirectUrl());
                return;
            }

            // Lấy danh sách các quyền (Roles) của User hiện tại
            Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();

            String redirectUrl = "/"; // Mặc định nếu không rơi vào các case dưới

            for (GrantedAuthority grantedAuthority : authorities) {
                String authorityName = grantedAuthority.getAuthority();

                if (authorityName.equals(Role.ROLE_ADMIN.name())) {
                    redirectUrl = "/admin"; // Admin thì vào trang quản trị
                    break;
                } else if (authorityName.equals(Role.ROLE_USER.name())) {
                    redirectUrl = "/"; // User thường thì về trang chủ cửa hàng
                    break;
                }
            }

            // Thực hiện redirect thực tế
            new DefaultRedirectStrategy()
                    .sendRedirect(request, response, request.getContextPath() + redirectUrl);
        };
    }

    // -------------------------------------------------------
    // Chain 4: Web / Thymeleaf MVC — session + form login
    // -------------------------------------------------------
    /** Forces PKCE for every OAuth2 login registration — Twitter/X requires it, and it's a strict
     *  security improvement for Google/Facebook too even though they don't mandate it. */
    @Bean
    public OAuth2AuthorizationRequestResolver pkceAuthorizationRequestResolver(ClientRegistrationRepository clientRegistrationRepository) {
        DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository, OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        return resolver;
    }

    /** Remember-me (cookie ký HMAC, 7 ngày) có ghi audit log khi cookie bị từ chối — xem AuditingRememberMeServices. */
    @Bean
    public AuditingRememberMeServices rememberMeServices(CustomUserDetailsService userDetailsService,
                                                         AuditLogService auditLogService) {
        AuditingRememberMeServices services =
                new AuditingRememberMeServices(rememberMeKey, userDetailsService, auditLogService);
        services.setTokenValiditySeconds(7 * 24 * 60 * 60);
        services.setParameter("remember-me");
        return services;
    }

    @Bean
    @Order(4)
    public SecurityFilterChain webSecurityFilterChain(HttpSecurity http,
                                                       OAuth2AuthorizationRequestResolver pkceAuthorizationRequestResolver,
                                                       AuditingRememberMeServices rememberMeServices,
                                                       AccountStatusService accountStatusService,
                                                       AuditLogService auditLogService,
                                                       RegistrationRateLimitService registrationRateLimitService)
            throws Exception {
        http
                .headers(headers -> {
                    securityHeaders(headers);
                    headers.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY));
                })
                .authorizeHttpRequests(auth -> auth
                        // Static assets (thư viện front-end tự host qua WebJars ở /webjars/**)
                        .requestMatchers("/css/**", "/js/**", "/images/**", "/webjars/**", "/uploads/**", "/favicon.ico").permitAll()
                        // Rating submission requires login — must be matched before the public /products/** rule below
                        .requestMatchers("/products/*/rate").hasAnyRole("USER", "ADMIN")
                        // Public pages
                        .requestMatchers("/", "/menu", "/products", "/products/**", "/categories/**", "/contact", "/login", "/register", "/error").permitAll()
                        // OAuth2 login redirect/callback endpoints — hit before the user is authenticated
                        .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                        // Admin dashboard — ROLE_ADMIN only
                        .requestMatchers("/admin", "/admin/**").hasRole("ADMIN")

                        .requestMatchers("/profile", "/cart/**", "/orders/**", "/suggest").hasAnyRole("USER", "ADMIN")
                        // Token CSRF mới cho trang đã mở lâu (controller/CsrfTokenController)
                        .requestMatchers(CsrfTokenController.PATH).hasAnyRole("USER", "ADMIN")
                        // Handshake WebSocket/STOMP (config/WebSocketConfig): xác thực bằng session; phân quyền từng
                        // frame STOMP ở config/WebSocketSecurityConfig. Chưa đăng nhập → redirect /login như mọi trang
                        // (không upgrade được, client JS thấy socket đóng; lần nối lại gọi /csrf-token, bị chuyển hướng
                        // → dừng); request cache mặc định
                        // không lưu request có "Upgrade: websocket" nên đăng nhập xong không bị đưa về /ws-stomp
                        .requestMatchers(StompDestinations.ENDPOINT, StompDestinations.ENDPOINT_PATTERN)
                        .hasAnyRole("USER", "ADMIN")
                        // Anything else requires the user to be logged in
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .usernameParameter("username")
                        .passwordParameter("password")

                        // THAY THẾ .defaultSuccessUrl bằng .successHandler vừa tạo ở trên
                        .successHandler(customAuthenticationSuccessHandler())

                        .failureHandler(formLoginFailureHandler())
                        .permitAll()
                )
                .oauth2Login(oauth2 -> oauth2
                        .loginPage("/login")
                        .successHandler(customAuthenticationSuccessHandler())
                        // Redirect về /login?error=true như failureUrl, kèm audit log (lỗi OAuth2 không sinh event)
                        .failureHandler(oAuth2LoginFailureHandler)
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(pkceAuthorizationRequestResolver))
                        .userInfoEndpoint(userInfo -> userInfo
                                .oidcUserService(customOidcUserService)
                                .userService(customOAuth2UserService)
                        )
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout=true")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID", "remember-me")
                        .permitAll()
                )
                .rememberMe(remember -> remember
                        // Thời hạn cookie, tên tham số... đặt trong bean rememberMeServices bên dưới
                        .rememberMeServices(rememberMeServices)
                        .key(rememberMeKey)
                )
                .exceptionHandling(ex -> ex
                        // Redirect to a friendly 403 page instead of the default whitelabel
                        .accessDeniedPage("/error")
                )
                // Huỷ session của tài khoản bị khoá/đổi role sau khi đăng nhập. Sau remember-me, trước anonymous: request
                // bị huỷ session đi tiếp như khách
                .addFilterBefore(new AccountStatusFilter(accountStatusService, auditLogService, rememberMeServices),
                        AnonymousAuthenticationFilter.class)
                // Giới hạn POST /register theo IP — trước khi validation của form (gồm Pwned Passwords) chạy
                .addFilterBefore(new RegistrationRateLimitFilter(registrationRateLimitService),
                        AnonymousAuthenticationFilter.class);

        return http.build();
    }
}
