package com.huuhv.foodsndrinks.service.notification;

import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.Map;

/**
 * Email báo cáo tháng cho admin. Cron mặc định chạy đầu ngày 1 và báo cáo tháng VỪA KẾT THÚC, nên mọi đơn của tháng
 * (kể cả đặt lúc 23h59 ngày cuối) đều có mặt. Mọi số liệu cùng một phạm vi: đơn đặt ({@code orderedAt}) trong tháng
 * đó — doanh thu/số đơn hoàn thành và số đơn theo trạng thái (trạng thái hiện tại) khớp nhau.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatisticsScheduler {

    private final OrderRepository orderRepository;
    private final EmailNotificationService emailNotificationService;

    // Không đặt zone: cron chạy theo múi giờ JVM, cùng múi giờ với YearMonth.now() bên dưới
    @Scheduled(cron = "${app.notification.statistics.cron}")
    // Nhiều instance cùng kích hoạt cron: chỉ instance giành được khoá gửi email (config/SchedulerLockConfig).
    // Job chạy đồng bộ trên thread sched-: 2 query + gửi SMTP (sendMonthlyStatistics không @Async), giữ khoá suốt lúc
    // gửi; lockAtMostFor phải dài hơn thời gian SMTP chậm nhất. lockAtLeastFor: job thường xong trong vài giây, vẫn giữ
    // khoá để instance có đồng hồ/cron trễ vài giây không chạy lại. Không @Transactional: 2 query đọc độc lập, khoá
    // không bị cuốn vào transaction của job.
    @SchedulerLock(name = "orderMonthlyStatistics", lockAtLeastFor = "5m", lockAtMostFor = "30m")
    public void sendMonthlyStatistics() {
        sendMonthlyStatistics(YearMonth.now().minusMonths(1));
    }

    void sendMonthlyStatistics(YearMonth month) {
        LocalDateTime from = month.atDay(1).atStartOfDay();
        LocalDateTime to = month.plusMonths(1).atDay(1).atStartOfDay();

        BigDecimal revenue = orderRepository.sumCompletedRevenueBetween(from, to);

        Map<OrderStatus, Long> statusCounts = new EnumMap<>(OrderStatus.class);
        for (OrderStatus status : OrderStatus.values()) {
            if (status != OrderStatus.CART) {
                statusCounts.put(status, 0L);
            }
        }
        for (Object[] row : orderRepository.countGroupByStatusBetween(from, to)) {
            statusCounts.put((OrderStatus) row[0], ((Number) row[1]).longValue());
        }
        long completedCount = statusCounts.get(OrderStatus.COMPLETED);

        log.info("Sending monthly order statistics for {}", month);
        emailNotificationService.sendMonthlyStatistics(
                month.getYear(), month.getMonthValue(), revenue, completedCount, statusCounts);
    }
}
