package com.huuhv.foodsndrinks.config;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.web.server.WebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Profile prod: chặn khởi động khi chưa khai báo {@code TRUSTED_PROXIES} (dải IP của load balancer, dùng cho
 * {@code server.tomcat.remoteip.internal-proxies} trong {@code application-prod.yml}), với thông báo rõ ràng.
 * <p>
 * Không có check này thì: thiếu biến → binder của Spring Boot giữ nguyên chuỗi {@code ${TRUSTED_PROXIES}} và Tomcat
 * dừng với {@code PatternSyntaxException: Illegal repetition} khó hiểu; biến rỗng → app vẫn chạy, không rõ proxy nào
 * được tin, {@code source.ip} của audit log có thể sai.
 * <p>
 * Là một {@link WebServerFactoryCustomizer} để được tạo sớm: {@code WebServerFactoryCustomizerBeanPostProcessor} khởi
 * tạo mọi customizer trước khi gọi customizer nào, nên constructor này chạy trước khi customizer của Boot đưa
 * {@code internal-proxies} cho {@code RemoteIpValve}. Giá trị được đọc qua {@link Binder} — đúng giá trị Tomcat nhận.
 */
@Component
@Profile("prod")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TrustedProxiesCheck implements WebServerFactoryCustomizer<WebServerFactory> {

    static final String PROPERTY = "server.tomcat.remoteip.internal-proxies";

    public TrustedProxiesCheck(Environment environment) {
        String internalProxies = Binder.get(environment).bind(PROPERTY, String.class).orElse("");
        if (internalProxies.isBlank() || internalProxies.contains("${")) {
            throw new IllegalStateException("TRUSTED_PROXIES chưa được khai báo hoặc đang rỗng (profile prod): đặt dải "
                    + "IP (CIDR) của load balancer, hoặc 127.0.0.1/32 nếu không chạy sau load balancer");
        }
    }

    /** Không cấu hình gì — kiểm tra đã chạy trong constructor (xem javadoc của class). */
    @Override
    public void customize(WebServerFactory factory) {
        // no-op
    }
}
