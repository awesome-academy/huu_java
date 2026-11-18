package com.huuhv.foodsndrinks.config;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Profile prod: chặn khởi động khi chưa khai báo {@code TRUSTED_PROXIES} (dải IP của load balancer, dùng cho
 * {@code server.tomcat.remoteip.internal-proxies} trong {@code application-prod.yml}).
 * <p>
 * Binder của Spring Boot bỏ qua placeholder không resolve được, nên thiếu biến thì chuỗi {@code ${TRUSTED_PROXIES}}
 * được đưa nguyên văn cho Tomcat thay vì báo lỗi; còn giá trị rỗng không có nghĩa là "không tin proxy nào". Cả hai
 * trường hợp đều làm sai {@code source.ip} của audit log, nên dừng app với thông báo rõ ràng. Giá trị được đọc qua
 * {@link Binder} — đúng giá trị Tomcat sẽ nhận — và bean được tạo trước khi Tomcat nhận kết nối.
 */
@Component
@Profile("prod")
public class TrustedProxiesCheck {

    static final String PROPERTY = "server.tomcat.remoteip.internal-proxies";

    public TrustedProxiesCheck(Environment environment) {
        String internalProxies = Binder.get(environment).bind(PROPERTY, String.class).orElse("");
        if (internalProxies.isBlank() || internalProxies.contains("${")) {
            throw new IllegalStateException("TRUSTED_PROXIES chưa được khai báo hoặc đang rỗng (profile prod): đặt dải "
                    + "IP (CIDR) của load balancer, hoặc 127.0.0.1/32 nếu không chạy sau load balancer");
        }
    }
}
