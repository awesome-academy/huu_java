package com.huuhv.foodsndrinks.service.notification;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Định dạng số tiền/thời gian dùng chung cho mọi kênh thông báo: email HTML (template gọi qua biến
 * {@value #TEMPLATE_VARIABLE}, vd. {@code ${fmt.money(event.totalPrice())}}), bản chữ thuần của email
 * ({@link OrderMessageFormatter}) và Slack ({@link SlackMessageBuilder}). Một nguồn duy nhất để cùng một đơn hàng hiển
 * thị cùng một con số ở mọi nơi — không dùng {@code #numbers.format*} trong template email.
 * <p>
 * Public vì SpEL của Thymeleaf gọi method bằng reflection; không có trạng thái nên dùng chung {@link #INSTANCE}.
 */
public final class NotificationFormat {

    public static final NotificationFormat INSTANCE = new NotificationFormat();
    static final String TEMPLATE_VARIABLE = "fmt";

    private static final Locale VIETNAMESE = Locale.of("vi", "VN");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private NotificationFormat() {
    }

    /**
     * Số tiền VND (không có đơn vị lẻ): làm tròn {@code HALF_UP} về số nguyên, phân cách hàng nghìn bằng dấu chấm
     * ({@code 150.000}); {@code null} → {@code 0}. NumberFormat không thread-safe nên tạo mới mỗi lần gọi.
     */
    public String money(BigDecimal amount) {
        NumberFormat format = NumberFormat.getIntegerInstance(VIETNAMESE);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(amount == null ? BigDecimal.ZERO : amount);
    }

    /** {@code dd/MM/yyyy HH:mm}; {@code null} → chuỗi rỗng. */
    public String dateTime(LocalDateTime dateTime) {
        return dateTime == null ? "" : dateTime.format(DATE_TIME);
    }
}
