package com.huuhv.foodsndrinks.service.messaging;

import java.util.Map;

/**
 * Whitelist kiểu payload của message JMS: alias ngắn ghi trong property {@value #TYPE_ID_PROPERTY} của message ↔ class
 * DTO. Converter ({@code config/AliasOnlyJsonMessageConverter}) chỉ đọc/ghi các class có ở đây: alias lạ hoặc tên class
 * đầy đủ trong message bị từ chối (không nạp class theo dữ liệu bên gửi — OWASP A08), gửi class chưa có alias thì lỗi
 * ngay phía gửi.
 * <p>
 * Thêm loại message mới = thêm một dòng vào {@link #TYPE_ID_MAPPINGS}. Không đổi alias của loại đã dùng: message đang nằm
 * trong queue/subscription sẽ không đọc được nữa.
 */
public final class MessageTypes {

    /** Tên property JMS chứa alias của kiểu payload. */
    public static final String TYPE_ID_PROPERTY = "_type";

    /** alias → class payload. */
    public static final Map<String, Class<?>> TYPE_ID_MAPPINGS = Map.of();

    private MessageTypes() {
    }
}
