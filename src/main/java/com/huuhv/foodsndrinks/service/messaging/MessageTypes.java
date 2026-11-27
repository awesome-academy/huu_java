package com.huuhv.foodsndrinks.service.messaging;

import com.huuhv.foodsndrinks.dto.message.ExcelImportMessage;
import com.huuhv.foodsndrinks.dto.message.OrderPlacedMessage;
import com.huuhv.foodsndrinks.dto.message.OrderStatusChangedMessage;

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

    /**
     * Tên property JMS (long) chứa id bản ghi {@code outbox_messages} của message — có trên mọi message gửi qua outbox,
     * giữ nguyên khi broker chuyển message vào DLQ.
     */
    public static final String OUTBOX_ID_PROPERTY = "outboxId";

    /** {@link OrderPlacedMessage} trên topic {@code order.placed}. */
    public static final String ORDER_PLACED = "orderPlaced";

    /** {@link OrderStatusChangedMessage} trên topic {@code order.status-changed}. */
    public static final String ORDER_STATUS_CHANGED = "orderStatusChanged";

    /** {@link ExcelImportMessage} trên queue {@code excel.import}. */
    public static final String EXCEL_IMPORT = "excelImport";

    /** alias → class payload. */
    public static final Map<String, Class<?>> TYPE_ID_MAPPINGS = Map.of(
            ORDER_PLACED, OrderPlacedMessage.class,
            ORDER_STATUS_CHANGED, OrderStatusChangedMessage.class,
            EXCEL_IMPORT, ExcelImportMessage.class);

    private MessageTypes() {
    }
}
