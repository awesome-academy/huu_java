package com.huuhv.foodsndrinks.config;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.server.WebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Profile prod: chặn khởi động khi chưa khai báo {@code WEBSOCKET_ALLOWED_ORIGINS} ({@code app.websocket.allowed-origins}).
 * <p>
 * Danh sách rỗng nghĩa là "chỉ same-origin": Spring so header {@code Origin} của handshake với scheme/host/port của
 * request. Sau load balancer, {@code Host} mà app nhận có thể là tên nội bộ (proxy đổi {@code Host}, nhiều domain trỏ
 * vào cùng cụm) → handshake hợp lệ bị từ chối, hoặc origin lạ được coi là cùng origin. Prod phải ghi rõ origin public
 * (vd. {@code https://shop.example.com}); định dạng do {@code WebSocketProperties} kiểm tra.
 * <p>
 * Là {@link WebServerFactoryCustomizer} để được tạo sớm, trước khi web server khởi động (xem {@link TrustedProxiesCheck}).
 */
@Component
@Profile("prod")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WebSocketOriginCheck implements WebServerFactoryCustomizer<WebServerFactory> {

    static final String PROPERTY = "app.websocket.allowed-origins";

    public WebSocketOriginCheck(Environment environment) {
        List<String> origins = Binder.get(environment).bind(PROPERTY, Bindable.listOf(String.class)).orElse(List.of());
        if (origins.stream().allMatch(String::isBlank)) {
            throw new IllegalStateException("WEBSOCKET_ALLOWED_ORIGINS chưa được khai báo hoặc đang rỗng (profile prod): "
                    + "đặt origin public của trang web, vd. https://shop.example.com (cách nhau bởi dấu phẩy nếu nhiều)");
        }
    }

    /** Không cấu hình gì — kiểm tra đã chạy trong constructor (xem javadoc của class). */
    @Override
    public void customize(WebServerFactory factory) {
        // no-op
    }
}
