package com.huuhv.foodsndrinks.service.notification;

import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatisticsScheduler {

    private final OrderRepository orderRepository;
    private final EmailNotificationService emailNotificationService;

    // Không đặt zone: cron chạy theo múi giờ JVM, cùng múi giờ với LocalDate.now() bên dưới
    @Scheduled(cron = "${app.notification.statistics.cron}")
    // Nhiều instance cùng kích hoạt cron: chỉ instance giành được khoá gửi email (config/SchedulerLockConfig).
    // lockAtLeastFor: giữ khoá dù job xong trong vài ms (email gửi @Async) để instance có đồng hồ/cron trễ vài giây
    // không chạy lại. Không @Transactional: 2 query đọc độc lập, khoá không bị cuốn vào transaction của job.
    @SchedulerLock(name = "orderMonthlyStatistics", lockAtLeastFor = "5m", lockAtMostFor = "30m")
    public void sendMonthlyStatistics() {
        LocalDate today = LocalDate.now();
        LocalDateTime startOfMonth = today.withDayOfMonth(1).atStartOfDay();

        List<Object[]> monthlyRevenue = orderRepository.findMonthlyRevenue(startOfMonth);
        BigDecimal revenue = BigDecimal.ZERO;
        long completedCount = 0;
        for (Object[] row : monthlyRevenue) {
            int year = ((Number) row[0]).intValue();
            int month = ((Number) row[1]).intValue();
            if (year == today.getYear() && month == today.getMonthValue()) {
                revenue = (BigDecimal) row[2];
                completedCount = ((Number) row[3]).longValue();
                break;
            }
        }

        Map<OrderStatus, Long> statusCounts = new EnumMap<>(OrderStatus.class);
        for (Object[] row : orderRepository.countGroupByStatus()) {
            statusCounts.put((OrderStatus) row[0], (Long) row[1]);
        }

        log.info("Sending monthly order statistics for {}/{}", today.getMonthValue(), today.getYear());
        emailNotificationService.sendMonthlyStatistics(
                today.getYear(), today.getMonthValue(), revenue, completedCount, statusCounts);
    }
}
