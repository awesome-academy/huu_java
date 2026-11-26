package com.huuhv.foodsndrinks.logging;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * Sao chép MDC (requestId, user.name) của thread gửi task sang thread {@code @Async}, để log của job async vẫn truy
 * vết được về request đã tạo ra nó. Sau khi task chạy xong, MDC của thread thực thi được trả về như cũ — cần thiết
 * vì thread trong pool được tái sử dụng, và vì task cũng có thể chạy ngay trên thread gọi (các executor của
 * {@code AsyncConfig} dùng {@code CallerRunsPolicy} khi pool đầy — không được xoá MDC của chính request đó).
 * <p>
 * Không truyền {@code SecurityContext} (xem quy ước trong CLAUDE.md).
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> callerContext = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            setContext(callerContext);
            try {
                runnable.run();
            } finally {
                setContext(previous);
            }
        };
    }

    private static void setContext(Map<String, String> context) {
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}
