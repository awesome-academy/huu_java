package com.huuhv.foodsndrinks.service.notification;

import com.huuhv.foodsndrinks.enums.OrderStatus;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

/**
 * Email thông báo cho admin. Nội dung HTML render bằng Thymeleaf từ {@code templates/mail/*.html} (quy ước viết template
 * email: xem {@code mail/fragments.html}), gửi kèm bản chữ thuần ({@link OrderMessageFormatter}) dạng
 * {@code multipart/alternative} cho client không hiển thị HTML.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailNotificationService {

    static final String ORDER_PLACED_TEMPLATE = "mail/order-placed";
    static final String MONTHLY_STATISTICS_TEMPLATE = "mail/monthly-statistics";
    private static final Locale VIETNAMESE = Locale.of("vi", "VN");

    private final JavaMailSender mailSender;
    private final ITemplateEngine templateEngine;

    @Value("${app.notification.admin-email}")
    private String adminEmail;

    @Value("${app.notification.mail.enabled:true}")
    private boolean enabled;

    @Async("taskExecutor")
    public void sendNewOrderEmail(OrderPlacedEvent event) {
        if (isDisabled()) {
            return;
        }
        try {
            Context context = new Context(VIETNAMESE);
            context.setVariable("event", event);
            // Đơn vừa checkout luôn ở trạng thái PENDING (OrderService.checkout)
            context.setVariable("status", OrderStatus.PENDING);
            send("[F&B Store] Đơn hàng mới #" + event.orderId(),
                    OrderMessageFormatter.buildOrderSummary(event),
                    templateEngine.process(ORDER_PLACED_TEMPLATE, context));
        } catch (Exception e) {
            log.error("Failed to send admin email for order #{}", event.orderId(), e);
        }
    }

    public void sendMonthlyStatistics(int year, int month, BigDecimal revenue, long completedCount,
                                      Map<OrderStatus, Long> statusCounts) {
        if (isDisabled()) {
            return;
        }
        try {
            Context context = new Context(VIETNAMESE);
            context.setVariable("year", year);
            context.setVariable("month", month);
            context.setVariable("revenue", revenue);
            context.setVariable("completedCount", completedCount);
            context.setVariable("statusCounts", statusCounts);
            send("[F&B Store] Thống kê đơn hàng tháng %02d/%d".formatted(month, year),
                    OrderMessageFormatter.buildMonthlyStatistics(year, month, revenue, completedCount, statusCounts),
                    templateEngine.process(MONTHLY_STATISTICS_TEMPLATE, context));
        } catch (Exception e) {
            log.error("Failed to send admin statistics email", e);
        }
    }

    private boolean isDisabled() {
        if (!enabled || adminEmail == null || adminEmail.isBlank()) {
            log.warn("Admin email notification skipped (disabled or admin-email not configured)");
            return true;
        }
        return false;
    }

    private void send(String subject, String plainText, String html) throws Exception {
        MimeMessage message = mailSender.createMimeMessage();
        // multipart = true: multipart/alternative gồm text/plain + text/html
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setTo(adminEmail);
        helper.setSubject(subject);
        helper.setText(plainText, html);
        mailSender.send(message);
    }
}
