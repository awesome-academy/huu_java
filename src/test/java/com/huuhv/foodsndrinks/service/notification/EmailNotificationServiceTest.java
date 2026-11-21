package com.huuhv.foodsndrinks.service.notification;

import com.huuhv.foodsndrinks.enums.OrderStatus;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Email admin render từ template thật trong {@code templates/mail/} (engine cấu hình như Spring Boot: prefix
 * {@code templates/}, HTML, UTF-8), gửi dạng text/plain + text/html.
 */
@ExtendWith(MockitoExtension.class)
class EmailNotificationServiceTest {

    @Mock private JavaMailSender mailSender;

    private EmailNotificationService service;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        service = new EmailNotificationService(mailSender, engine);
        ReflectionTestUtils.setField(service, "adminEmail", "admin@example.com");
        ReflectionTestUtils.setField(service, "enabled", true);
    }

    @Test
    void newOrderEmail_isHtmlWithPlainTextAlternative() throws Exception {
        MimeMessage message = stubMimeMessage();

        service.sendNewOrderEmail(order("Giao giờ hành chính"));

        verify(mailSender).send(message);
        assertThat(message.getSubject()).isEqualTo("[F&B Store] Đơn hàng mới #42");
        assertThat(message.getAllRecipients()).extracting(Object::toString).containsExactly("admin@example.com");

        Map<String, String> parts = textParts(message);
        assertThat(parts.get("text/html"))
                .contains("Đơn hàng mới #42", "Chờ xác nhận", "Nguyễn Văn A", "0901234567", "Trà sữa trân châu",
                        "35.000 ₫", "70.000 ₫", "120.000 ₫", "20/11/2026 09:30", "Giao giờ hành chính")
                // không còn thuộc tính/thẻ Thymeleaf chưa xử lý (fragment header/footer đã được chèn)
                .doesNotContain("th:text", "th:replace", "th:block", "th:if")
                .contains("F&amp;B Store", "Thông báo đơn hàng mới", "vui lòng không trả lời");
        assertThat(parts.get("text/plain"))
                .contains("Đơn hàng mới #42", "Trà sữa trân châu x2 = 70.000 VND", "Tổng tiền: 120.000 VND");
    }

    @Test
    void newOrderEmail_escapesUserInput() throws Exception {
        MimeMessage message = stubMimeMessage();

        service.sendNewOrderEmail(order("<script>alert(1)</script><img src=x>"));

        String html = textParts(message).get("text/html");
        assertThat(html)
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;&lt;img src=x&gt;")
                .doesNotContain("<script", "<img");
    }

    @Test
    void newOrderEmail_omitsOptionalRows_whenPhoneAndNoteMissing() throws Exception {
        MimeMessage message = stubMimeMessage();
        OrderPlacedEvent event = new OrderPlacedEvent(7L, "B", "b@example.com", null, "HN", " ",
                new BigDecimal("10000"), null, List.of(
                new OrderPlacedEvent.OrderLineItem("Cà phê", 1, new BigDecimal("10000"), new BigDecimal("10000"))));

        service.sendNewOrderEmail(event);

        assertThat(textParts(message).get("text/html"))
                .contains("Đơn hàng mới #7", "10.000 ₫")
                .doesNotContain("Số điện thoại", "Ghi chú", "Đặt lúc");
    }

    @Test
    void fractionalAmount_isFormattedIdentically_inHtmlPlainTextAndSlack() throws Exception {
        MimeMessage message = stubMimeMessage();
        OrderPlacedEvent event = new OrderPlacedEvent(9L, "C", "c@example.com", null, "HN", null,
                new BigDecimal("150000.50"), null, List.of(new OrderPlacedEvent.OrderLineItem(
                "Trà", 1, new BigDecimal("150000.50"), new BigDecimal("150000.50"))));

        service.sendNewOrderEmail(event);

        // VND làm tròn HALF_UP về số nguyên, giống nhau ở mọi kênh
        Map<String, String> parts = textParts(message);
        assertThat(parts.get("text/html")).contains("150.001 ₫").doesNotContain("150.000 ₫", "150.000,5");
        assertThat(parts.get("text/plain")).contains("Tổng tiền: 150.001 VND").doesNotContain("150.000,5");
        assertThat(SlackMessageBuilder.orderPlaced(event).toString()).contains("150.001 ₫").doesNotContain("150.000,5");
    }

    @Test
    void monthlyStatistics_isHtml() throws Exception {
        MimeMessage message = stubMimeMessage();
        Map<OrderStatus, Long> counts = new EnumMap<>(OrderStatus.class);
        counts.put(OrderStatus.PENDING, 2L);
        counts.put(OrderStatus.COMPLETED, 5L);

        service.sendMonthlyStatistics(2026, 3, new BigDecimal("1250000"), 5, counts);

        assertThat(message.getSubject()).isEqualTo("[F&B Store] Thống kê đơn hàng tháng 03/2026");
        Map<String, String> parts = textParts(message);
        assertThat(parts.get("text/html"))
                .contains("Thống kê đơn hàng tháng 03/2026", "1.250.000 ₫", "Chờ xác nhận", "Hoàn thành");
        assertThat(parts.get("text/plain")).contains("Doanh thu: 1.250.000 VND", "  - Hoàn thành: 5");
    }

    @Test
    void disabled_sendsNothing() {
        ReflectionTestUtils.setField(service, "enabled", false);

        service.sendNewOrderEmail(order(null));

        verifyNoInteractions(mailSender);
    }

    // -------------------------------------------------------

    private MimeMessage stubMimeMessage() {
        MimeMessage message = new MimeMessage((Session) null);
        when(mailSender.createMimeMessage()).thenReturn(message);
        return message;
    }

    private static OrderPlacedEvent order(String note) {
        return new OrderPlacedEvent(42L, "Nguyễn Văn A", "a@example.com", "0901234567", "12 Lê Lợi, Q1",
                note, new BigDecimal("120000"), LocalDateTime.of(2026, 11, 20, 9, 30), List.of(
                new OrderPlacedEvent.OrderLineItem("Trà sữa trân châu", 2, new BigDecimal("35000"), new BigDecimal("70000")),
                new OrderPlacedEvent.OrderLineItem("Bánh mì", 1, new BigDecimal("50000"), new BigDecimal("50000"))));
    }

    /** Nội dung các phần text của email theo MIME type (text/plain, text/html), duyệt cả multipart lồng nhau. */
    private static Map<String, String> textParts(MimeMessage message) throws Exception {
        // Header Content-Type của từng phần chỉ được ghi khi saveChanges (JavaMailSender.send làm việc này khi gửi thật)
        message.saveChanges();
        Map<String, String> result = new HashMap<>();
        collect(message, result);
        return result;
    }

    private static void collect(Part part, Map<String, String> result) throws Exception {
        Object content = part.getContent();
        if (content instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                collect(child, result);
            }
        } else if (part.isMimeType("text/html")) {
            result.put("text/html", (String) content);
        } else if (part.isMimeType("text/plain")) {
            result.put("text/plain", (String) content);
        }
    }
}
