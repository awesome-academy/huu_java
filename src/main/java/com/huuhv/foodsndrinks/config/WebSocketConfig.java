package com.huuhv.foodsndrinks.config;

import com.huuhv.foodsndrinks.config.properties.WebSocketProperties;
import com.huuhv.foodsndrinks.security.StompErrorHandler;
import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import com.huuhv.foodsndrinks.service.websocket.HttpSessionLookup;
import com.huuhv.foodsndrinks.service.websocket.StompDestinations;
import com.huuhv.foodsndrinks.service.websocket.WebSocketSessionTracker;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * WebSocket + STOMP: endpoint {@value StompDestinations#ENDPOINT} (WebSocket thuần cùng origin, không SockJS), simple
 * broker trong bộ nhớ cho {@code /topic} (broadcast) và {@code /queue} (đích theo user qua {@code /user}).
 * <p>
 * <b>Nhiều instance:</b> simple broker chỉ phát tới client đang nối vào chính instance này. Sự kiện nghiệp vụ tới mọi
 * instance qua topic JMS (mỗi instance một subscription non-durable — {@code JmsConfig.TOPIC_BROADCAST_FACTORY}), rồi
 * mỗi instance tự đẩy STOMP cho client của nó. Không dùng broker relay.
 * <p>
 * Bảo mật:
 * <ul>
 *   <li>Handshake đi qua filter chain web ({@code SecurityConfig}): phải đăng nhập (session), chưa đăng nhập → chuyển
 *       hướng {@code /login} như mọi trang (không upgrade được);
 *       {@link HttpSessionLookup} ghi lại session HTTP để thu hồi socket sau này.</li>
 *   <li>Origin: rỗng = chỉ same-origin (mặc định của Spring); prod bắt buộc liệt kê origin public
 *       ({@link WebSocketOriginCheck}). Không dùng {@code *}.</li>
 *   <li>Phân quyền frame + CSRF ở frame CONNECT: {@link WebSocketSecurityConfig}. Lỗi → {@link StompErrorHandler}
 *       (thông điệp cố định, không lộ token).</li>
 *   <li>Giới hạn frame client gửi {@value #MESSAGE_SIZE_LIMIT} byte (client chỉ gửi CONNECT/SUBSCRIBE), buffer gửi
 *       {@value #SEND_BUFFER_SIZE_LIMIT} byte và {@value #SEND_TIME_LIMIT_MS} ms cho client chậm (vượt thì Spring đóng
 *       kết nối thay vì giữ message trong bộ nhớ).</li>
 *   <li>{@link WebSocketSessionTracker} + {@code WebSocketSessionRevalidationJob}: đóng socket khi tài khoản bị khoá/đổi
 *       role hoặc session HTTP kết thúc.</li>
 * </ul>
 * <b>Bẫy scheduler:</b> {@code @EnableWebSocketMessageBroker} đăng ký bean {@code TaskScheduler}
 * ({@code messageBrokerTaskScheduler}, dùng cho heartbeat) → Spring Boot không tạo {@code taskScheduler} nữa và
 * {@code @Scheduled} sẽ chạy trên pool {@code MessageBroker-*}. {@link AsyncConfig#taskScheduler} khai báo lại tường minh.
 */
@Configuration
@EnableWebSocketMessageBroker
@EnableConfigurationProperties(WebSocketProperties.class)
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    static final int MESSAGE_SIZE_LIMIT = 8 * 1024;
    static final int SEND_BUFFER_SIZE_LIMIT = 512 * 1024;
    static final int SEND_TIME_LIMIT_MS = 10_000;

    private final WebSocketProperties properties;
    private final HttpSessionLookup httpSessionLookup;
    private final WebSocketSessionTracker sessionTracker;
    private final AuditLogService auditLogService;
    private final TaskScheduler messageBrokerTaskScheduler;

    /** {@code @Lazy}: bean scheduler được tạo bởi chính cấu hình WebSocket mà class này tham gia. */
    public WebSocketConfig(WebSocketProperties properties, HttpSessionLookup httpSessionLookup,
                           WebSocketSessionTracker sessionTracker, AuditLogService auditLogService,
                           @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler messageBrokerTaskScheduler) {
        this.properties = properties;
        this.httpSessionLookup = httpSessionLookup;
        this.sessionTracker = sessionTracker;
        this.auditLogService = auditLogService;
        this.messageBrokerTaskScheduler = messageBrokerTaskScheduler;
    }

    /**
     * Phát {@code HttpSessionDestroyedEvent} khi session HTTP bị huỷ/hết hạn — {@link WebSocketSessionTracker} đóng ngay
     * socket của session đó (đường nhanh; job kiểm tra định kỳ vẫn là đường chính). Spring Boot tự đăng ký bean
     * {@code HttpSessionListener} vào servlet container.
     */
    @Bean
    public static HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        StompWebSocketEndpointRegistration endpoint = registry.addEndpoint(StompDestinations.ENDPOINT)
                .addInterceptors(httpSessionLookup);
        if (!properties.allowedOrigins().isEmpty()) {
            endpoint.setAllowedOrigins(properties.allowedOrigins().toArray(String[]::new));
        }
        registry.setErrorHandler(new StompErrorHandler(auditLogService));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        long heartbeat = properties.heartbeat().toMillis();
        registry.enableSimpleBroker(StompDestinations.TOPIC_PREFIX, StompDestinations.QUEUE_PREFIX)
                .setHeartbeatValue(new long[]{heartbeat, heartbeat})
                .setTaskScheduler(messageBrokerTaskScheduler);
        registry.setApplicationDestinationPrefixes(StompDestinations.APP_PREFIX);
        registry.setUserDestinationPrefix(StompDestinations.USER_PREFIX);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(MESSAGE_SIZE_LIMIT)
                .setSendBufferSizeLimit(SEND_BUFFER_SIZE_LIMIT)
                .setSendTimeLimit(SEND_TIME_LIMIT_MS)
                .addDecoratorFactory(sessionTracker);
    }
}
