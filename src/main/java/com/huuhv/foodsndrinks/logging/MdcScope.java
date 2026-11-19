package com.huuhv.foodsndrinks.logging;

import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

/**
 * Đặt tạm các key MDC trong một khối try-with-resources; khi đóng, từng key được trả về giá trị nó có trước đó
 * (hoặc bị xoá nếu trước đó chưa có).
 * <p>
 * Khác {@code MDC.putCloseable}: hàm đó luôn xoá key khi đóng, nên làm mất giá trị của tầng ngoài khi filter chạy
 * lồng nhau trên cùng thread. Dùng chung cho các filter đặt MDC ({@link RequestIdFilter}, {@link MdcUserFilter}).
 */
public final class MdcScope implements AutoCloseable {

    private final Map<String, String> previous = new HashMap<>();

    private MdcScope() {
    }

    public static MdcScope open() {
        return new MdcScope();
    }

    /** Đặt {@code key} trong phạm vi này; {@code value} null thì key bị xoá cho tới khi đóng. */
    public MdcScope put(String key, String value) {
        if (!previous.containsKey(key)) {
            previous.put(key, MDC.get(key));
        }
        set(key, value);
        return this;
    }

    @Override
    public void close() {
        previous.forEach(MdcScope::set);
    }

    private static void set(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }
}
