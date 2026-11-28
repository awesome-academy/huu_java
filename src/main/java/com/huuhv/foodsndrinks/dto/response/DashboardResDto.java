package com.huuhv.foodsndrinks.dto.response;

import com.huuhv.foodsndrinks.entity.Order;
import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.enums.OrderStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Getter
@Builder
public class DashboardResDto {

    // KPI cards
    private final BigDecimal totalRevenue;
    private final long       totalCompletedOrders;
    private final long       activeOrders;       // PENDING + PROCESSING
    private final long       totalProducts;
    private final long       totalUsers;

    // Charts
    private final Map<String, Long>       statusCounts;     // nhãn trạng thái → số đơn (biểu đồ lấy qua DashboardChartResDto)

    // Tables (top sản phẩm là biểu đồ: DashboardChartResDto)
    private final List<RecentOrderDto>    recentOrders;     // last 8

    // -------------------------------------------------------

    @Getter
    @Builder
    public static class MonthlyRevenueDto {
        private final String     label;      // "Th6/2026"
        private final BigDecimal revenue;
    }

    @Getter
    public static class RecentOrderDto {
        private final Long          id;
        private final String        username;
        private final String        userFullName;
        private final BigDecimal    totalPrice;
        private final OrderStatus   status;
        /** Thời điểm ĐẶT đơn — {@code createdAt} của order là lúc tạo giỏ hàng. */
        private final LocalDateTime orderedAt;

        public RecentOrderDto(Order o) {
            User u          = o.getUser();
            this.id          = o.getId();
            this.username    = u != null ? u.getUsername() : "—";
            this.userFullName = u != null ? u.getFullName() : "—";
            this.totalPrice  = o.getTotalPrice();
            this.status      = o.getStatus();
            this.orderedAt   = o.getOrderedAt();
        }
    }
}

