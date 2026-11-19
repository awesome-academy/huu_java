package com.huuhv.foodsndrinks.config;

import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.logging.RequestIdFilter;
import com.huuhv.foodsndrinks.security.AuditingRememberMeServices;
import com.huuhv.foodsndrinks.security.CustomOAuth2UserService;
import com.huuhv.foodsndrinks.security.CustomOidcUserService;
import com.huuhv.foodsndrinks.security.CustomUserDetailsService;
import com.huuhv.foodsndrinks.security.JwtAuthenticationFilter;
import com.huuhv.foodsndrinks.security.LoginAttemptAwareAuthenticationProvider;
import com.huuhv.foodsndrinks.security.LoginThrottledException;
import com.huuhv.foodsndrinks.security.OAuth2LoginFailureHandler;
import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import com.huuhv.foodsndrinks.service.security.LoginAttemptService;
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
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.ExceptionMappingAuthenticationFailureHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
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
@EnableConfigurationProperties({LoginAttemptProperties.class, CorsProperties.class})
@RequiredArgsConstructor
public class SecurityConfig {

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

    @Bean
    public AuthenticationSuccessHandler customAuthenticationSuccessHandler() {
        return (HttpServletRequest request, HttpServletResponse response, Authentication authentication) -> {

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
    // Chain 3: Web / Thymeleaf MVC — session + form login
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
    @Order(3)
    public SecurityFilterChain webSecurityFilterChain(HttpSecurity http,
                                                       OAuth2AuthorizationRequestResolver pkceAuthorizationRequestResolver,
                                                       AuditingRememberMeServices rememberMeServices) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // Static assets
                        .requestMatchers("/css/**", "/js/**", "/images/**", "/uploads/**", "/favicon.ico").permitAll()
                        // Rating submission requires login — must be matched before the public /products/** rule below
                        .requestMatchers("/products/*/rate").hasAnyRole("USER", "ADMIN")
                        // Public pages
                        .requestMatchers("/", "/menu", "/products", "/products/**", "/categories/**", "/contact", "/login", "/register", "/error").permitAll()
                        // OAuth2 login redirect/callback endpoints — hit before the user is authenticated
                        .requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll()
                        // Admin dashboard — ROLE_ADMIN only
                        .requestMatchers("/admin", "/admin/**").hasRole("ADMIN")

                        .requestMatchers("/profile", "/cart/**", "/orders/**", "/suggest").hasAnyRole("USER", "ADMIN")
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
                );

        return http.build();
    }
}
