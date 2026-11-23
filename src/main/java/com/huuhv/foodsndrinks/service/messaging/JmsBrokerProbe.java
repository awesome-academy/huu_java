package com.huuhv.foodsndrinks.service.messaging;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.jms.ConnectionFactoryUnwrapper;
import org.springframework.stereotype.Component;

/**
 * Kiểm tra broker có thực sự trả lời hay không, bằng một kết nối MỚI: mở connection + session (mỗi bước là một lượt hỏi
 * đáp với broker, giới hạn bởi {@code connectTimeoutMillis}/{@code callTimeout} của {@code broker-url}) rồi đóng.
 * <p>
 * Không dùng {@code JmsTemplate}/connection factory của app: Boot bọc factory bằng {@code CachingConnectionFactory}, nó
 * trả lại connection + session đã cache mà không liên lạc với broker — broker treo hay bị chặn vẫn trông như "kết nối
 * được". Chỉ gọi khi gửi đã lỗi (không phải mỗi message).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JmsBrokerProbe {

    private final ConnectionFactory connectionFactory;

    public boolean reachable() {
        ConnectionFactory target = ConnectionFactoryUnwrapper.unwrap(connectionFactory);
        try (Connection connection = target.createConnection()) {
            connection.createSession(false, Session.AUTO_ACKNOWLEDGE).close();
            return true;
        } catch (JMSException | RuntimeException ex) {
            log.debug("JMS broker probe failed: {}", ex.getClass().getSimpleName());
            return false;
        }
    }
}
