package com.huuhv.foodsndrinks.service.notification;

import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Báo cáo tháng: mọi số liệu cùng phạm vi [đầu tháng, đầu tháng sau), số đơn hoàn thành lấy từ bảng trạng thái. */
@ExtendWith(MockitoExtension.class)
class OrderStatisticsSchedulerTest {

    @Mock private OrderRepository orderRepository;
    @Mock private EmailNotificationService emailNotificationService;
    @InjectMocks private OrderStatisticsScheduler scheduler;

    @Test
    void report_coversWholeMonth_andCountsAreConsistent() {
        LocalDateTime from = LocalDateTime.of(2026, 12, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2027, 1, 1, 0, 0);
        when(orderRepository.sumCompletedRevenueBetween(from, to)).thenReturn(new BigDecimal("1250000"));
        when(orderRepository.countGroupByStatusBetween(from, to)).thenReturn(List.<Object[]>of(
                new Object[]{OrderStatus.COMPLETED, 5L},
                new Object[]{OrderStatus.PENDING, 2L}));

        scheduler.sendMonthlyStatistics(YearMonth.of(2026, 12));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<OrderStatus, Long>> counts = ArgumentCaptor.forClass(Map.class);
        verify(emailNotificationService).sendMonthlyStatistics(
                eq(2026), eq(12), eq(new BigDecimal("1250000")), eq(5L), counts.capture());
        // Mọi trạng thái trừ CART, trạng thái không có đơn = 0
        assertThat(counts.getValue()).containsExactly(
                Map.entry(OrderStatus.PENDING, 2L),
                Map.entry(OrderStatus.PROCESSING, 0L),
                Map.entry(OrderStatus.COMPLETED, 5L),
                Map.entry(OrderStatus.CANCELLED, 0L));
    }

    @Test
    void scheduledRun_reportsPreviousMonth() {
        YearMonth previous = YearMonth.now().minusMonths(1);
        when(orderRepository.sumCompletedRevenueBetween(any(), any())).thenReturn(BigDecimal.ZERO);

        scheduler.sendMonthlyStatistics();

        verify(orderRepository).countGroupByStatusBetween(
                previous.atDay(1).atStartOfDay(), previous.plusMonths(1).atDay(1).atStartOfDay());
        verify(emailNotificationService).sendMonthlyStatistics(
                eq(previous.getYear()), eq(previous.getMonthValue()), any(), eq(0L), any());
    }
}
