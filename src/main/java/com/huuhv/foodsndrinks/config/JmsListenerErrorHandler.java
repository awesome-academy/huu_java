package com.huuhv.foodsndrinks.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.support.converter.MessageConversionException;
import org.springframework.util.ErrorHandler;

/**
 * Ghi log mọi exception thoát khỏi {@code @JmsListener} (Boot 4 không tự gắn {@link ErrorHandler} cho listener factory;
 * thiếu nó thì container chỉ log WARN chung chung). Message vẫn bị rollback và broker giao lại — retry/DLQ do broker
 * quản lý ({@code artemis/broker.properties}), không retry trong code.
 * <ul>
 *   <li>Lỗi chuyển đổi payload ({@link MessageConversionException}): 1 dòng WARN, không stack trace. Thông điệp đã được
 *       {@link AliasOnlyJsonMessageConverter} làm sạch; lỗi này lặp lại ở mọi lần giao nên stack trace chỉ làm phình log.</li>
 *   <li>Lỗi khác: ERROR kèm stack trace (consumer tự log ngữ cảnh nghiệp vụ như {@code orderId} trước khi ném lại).</li>
 * </ul>
 */
@Slf4j
public class JmsListenerErrorHandler implements ErrorHandler {

    @Override
    public void handleError(Throwable t) {
        MessageConversionException conversion = findConversionException(t);
        if (conversion != null) {
            log.warn("JMS message rejected, will be redelivered then dead-lettered: {}", conversion.getMessage());
            return;
        }
        log.error("JMS listener failed, message will be redelivered", t);
    }

    /** Container bọc lỗi của listener (vd. {@code ListenerExecutionFailedException}) → tìm trong chuỗi cause. */
    private static MessageConversionException findConversionException(Throwable t) {
        for (Throwable current = t; current != null; current = current.getCause()) {
            if (current instanceof MessageConversionException conversion) {
                return conversion;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return null;
    }
}
