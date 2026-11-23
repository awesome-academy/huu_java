package com.huuhv.foodsndrinks.config;

import com.huuhv.foodsndrinks.config.properties.AsyncProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.jms.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Ngân sách connection JDBC: các thread nền có thể giữ connection cùng lúc — truy vấn dashboard ({@code dashboard-*}),
 * gửi outbox ({@code outbox-*}, khoá bản ghi lúc gửi), listener JMS (consumer thông báo giữ connection suốt lúc gọi
 * Slack/SMTP — {@code NotificationLogService}) và job {@code @Scheduled} ({@code sched-*}) — cộng lại không được quá NỬA
 * {@code spring.datasource.hikari.maximum-pool-size}, để luôn còn connection cho request web. Vượt thì quá trình khởi
 * động thất bại và app tắt, thay vì chạy tiếp rồi cạn pool lúc SMTP chậm: request chờ {@code connectionTimeout} 30s rồi
 * lỗi. Thêm listener JMS hay tăng kích thước pool nền thì phải tăng pool Hikari tương ứng.
 * <p>
 * Kiểm tra chạy ở {@link ContextRefreshedEvent}: lúc đó cần mọi listener container đã được tạo để đếm consumer. Spring phát
 * event này SAU khi start các bean {@code Lifecycle}, nên khi cấu hình vượt ngân sách, listener JMS và Tomcat đã chạy
 * trong một khoảng ngắn trước khi app tắt: consumer có thể đã kịp xử lý và ack vài message đang chờ. Điều này chấp nhận
 * được vì các message đó vẫn được xử lý đúng ({@code notification_logs} chống gửi lặp) và đây chỉ là kiểm tra cấu hình
 * lúc triển khai. Muốn chặn trước khi bất cứ thứ gì start thì phải chuyển sang {@code SmartLifecycle} có phase nhỏ hơn
 * {@link JmsListenerEndpointRegistry}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectionBudgetCheck {

    static final String SCHEDULING_POOL_SIZE = "spring.task.scheduling.pool.size";

    private final AsyncProperties asyncProperties;
    private final Environment environment;
    private final ObjectProvider<JmsListenerEndpointRegistry> jmsListenerRegistry;

    @EventListener(ContextRefreshedEvent.class)
    public void verify() {
        int jmsListeners = jmsListenerRegistry.stream()
                .flatMap(registry -> registry.getListenerContainers().stream())
                .mapToInt(ConnectionBudgetCheck::maxConsumers)
                .sum();
        int scheduler = Binder.get(environment).bind(SCHEDULING_POOL_SIZE, Integer.class).orElse(1);
        int background = asyncProperties.dashboard().maxSize() + asyncProperties.outbox().maxSize() + jmsListeners
                + scheduler;
        int hikariMax = AsyncConfig.hikariMaxPoolSize(environment);
        if (background > hikariMax / 2) {
            throw new IllegalStateException("Background threads may hold " + background + " JDBC connections (dashboard "
                    + asyncProperties.dashboard().maxSize() + " + outbox " + asyncProperties.outbox().maxSize()
                    + " + JMS listeners " + jmsListeners + " + scheduler " + scheduler + "), more than half of "
                    + AsyncConfig.HIKARI_MAX_POOL_SIZE + " (" + hikariMax + "): increase the Hikari pool or shrink them");
        }
        log.debug("JDBC connection budget: background {} / Hikari {}", background, hikariMax);
    }

    private static int maxConsumers(MessageListenerContainer container) {
        return container instanceof DefaultMessageListenerContainer dmlc ? dmlc.getMaxConcurrentConsumers() : 1;
    }
}
