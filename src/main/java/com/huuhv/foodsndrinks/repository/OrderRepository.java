package com.huuhv.foodsndrinks.repository;

import com.huuhv.foodsndrinks.entity.Order;
import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.repository.projection.OrderExportRow;
import com.huuhv.foodsndrinks.repository.projection.OrderStatusView;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * Danh sách đơn của admin. Không bao giờ trả giỏ hàng ({@code CART}) — kể cả khi {@code status} = CART: mọi thao
     * tác admin trên giỏ hàng trả 404 ({@code OrderService.getOrderDetail}/{@code updateStatus}).
     * <p>
     * {@code orderedFrom}/{@code orderedBefore}: ngày đặt trong {@code [orderedFrom, orderedBefore)}, {@code null} = không
     * chặn đầu đó. Điều kiện WHERE phải giống hệt {@link #findExportRows} — file export là đúng danh sách đang lọc; sửa một
     * câu thì sửa cả ba (kể cả {@code countQuery}).
     */
    @Query(value = """
            SELECT o FROM Order o LEFT JOIN FETCH o.user u
            WHERE o.status <> 'CART'
              AND (:status   IS NULL OR o.status = :status)
              AND (:orderId  IS NULL OR o.id     = :orderId)
              AND (:keyword  IS NULL
                   OR LOWER(u.username) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(u.email)    LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (:orderedFrom   IS NULL OR o.orderedAt >= :orderedFrom)
              AND (:orderedBefore IS NULL OR o.orderedAt <  :orderedBefore)
            ORDER BY o.id DESC
            """,
            countQuery = """
            SELECT COUNT(o) FROM Order o LEFT JOIN o.user u
            WHERE o.status <> 'CART'
              AND (:status   IS NULL OR o.status = :status)
              AND (:orderId  IS NULL OR o.id     = :orderId)
              AND (:keyword  IS NULL
                   OR LOWER(u.username) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(u.email)    LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (:orderedFrom   IS NULL OR o.orderedAt >= :orderedFrom)
              AND (:orderedBefore IS NULL OR o.orderedAt <  :orderedBefore)
            """)
    Page<Order> search(@Param("status")  OrderStatus status,
                       @Param("keyword") String keyword,
                       @Param("orderId") Long orderId,
                       @Param("orderedFrom") LocalDateTime orderedFrom,
                       @Param("orderedBefore") LocalDateTime orderedBefore,
                       Pageable pageable);

    /**
     * Các dòng của file export đơn hàng: cùng bộ lọc và thứ tự với {@link #search}, không nạp entity. {@code limit} chặn
     * số dòng đọc lên (người gọi xin {@code max + 1} để nhận ra bộ lọc vượt giới hạn).
     */
    @Query("""
            SELECT new com.huuhv.foodsndrinks.repository.projection.OrderExportRow(
                       o.id, u.fullName, u.username, o.orderedAt, o.status, o.totalPrice,
                       (SELECT COALESCE(SUM(od.quantity), 0) FROM OrderDetail od WHERE od.order = o))
            FROM Order o LEFT JOIN o.user u
            WHERE o.status <> 'CART'
              AND (:status   IS NULL OR o.status = :status)
              AND (:orderId  IS NULL OR o.id     = :orderId)
              AND (:keyword  IS NULL
                   OR LOWER(u.username) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(u.email)    LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (:orderedFrom   IS NULL OR o.orderedAt >= :orderedFrom)
              AND (:orderedBefore IS NULL OR o.orderedAt <  :orderedBefore)
            ORDER BY o.id DESC
            """)
    List<OrderExportRow> findExportRows(@Param("status")  OrderStatus status,
                                        @Param("keyword") String keyword,
                                        @Param("orderId") Long orderId,
                                        @Param("orderedFrom") LocalDateTime orderedFrom,
                                        @Param("orderedBefore") LocalDateTime orderedBefore,
                                        Limit limit);

    /** Fetch order + user eagerly for detail view */
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.user WHERE o.id = :id")
    Optional<Order> findByIdWithUser(@Param("id") Long id);

    /**
     * Fetch order + user + orderDetails + products in 1 query for detail view.
     * products is @ManyToOne (not a collection) so no MultipleBagFetchException.
     */
    @Query("""
            SELECT DISTINCT o FROM Order o
            LEFT JOIN FETCH o.user
            LEFT JOIN FETCH o.orderDetails od
            LEFT JOIN FETCH od.product
            WHERE o.id = :id
            """)
    Optional<Order> findByIdWithDetailsAndProducts(@Param("id") Long id);

    // -------------------------------------------------------
    // User-facing: cart & order history
    // -------------------------------------------------------

    /**
     * Trạng thái của các đơn trong {@code ids} THUỘC {@code userId} (chống IDOR: id của người khác bị bỏ qua), trừ giỏ
     * hàng — trang của chủ đơn tải lại badge sau khi kết nối WebSocket lại.
     */
    @Query("""
            SELECT o.id AS id, o.status AS status FROM Order o
            WHERE o.user.id = :userId AND o.id IN :ids AND o.status <> 'CART'
            """)
    List<OrderStatusView> findStatusesByUserIdAndIdIn(@Param("userId") Long userId,
                                                      @Param("ids") Collection<Long> ids);

    /**
     * Trạng thái HIỆN TẠI của đơn {@code id} nếu thuộc user {@code username} — gửi WebSocket cho chủ đơn luôn lấy từ DB lúc
     * gửi (message JMS có thể tới sai thứ tự), và chỉ khi đơn thật sự của user đó.
     */
    @Query("SELECT o.status FROM Order o WHERE o.id = :id AND o.user.username = :username")
    Optional<OrderStatus> findStatusByIdAndOwnerUsername(@Param("id") Long id, @Param("username") String username);

    /** The user's active cart (status = CART), with items + products pre-loaded */
    @Query("""
            SELECT DISTINCT o FROM Order o
            LEFT JOIN FETCH o.orderDetails od
            LEFT JOIN FETCH od.product
            WHERE o.user.id = :userId AND o.status = 'CART'
            """)
    Optional<Order> findCartByUserId(@Param("userId") Long userId);

    /** Same as {@link #findByIdWithDetailsAndProducts}, scoped to the owning user (prevents IDOR) */
    @Query("""
            SELECT DISTINCT o FROM Order o
            LEFT JOIN FETCH o.user
            LEFT JOIN FETCH o.orderDetails od
            LEFT JOIN FETCH od.product
            WHERE o.id = :id AND o.user.id = :userId
            """)
    Optional<Order> findByIdAndUserIdWithDetailsAndProducts(@Param("id") Long id, @Param("userId") Long userId);

    /** Past orders (everything except CART) for the "order history" page */
    @Query(value = """
            SELECT o FROM Order o
            WHERE o.user.id = :userId AND o.status <> 'CART'
            ORDER BY o.createdAt DESC
            """,
            countQuery = """
            SELECT COUNT(o) FROM Order o
            WHERE o.user.id = :userId AND o.status <> 'CART'
            """)
    Page<Order> findOrderHistoryByUserId(@Param("userId") Long userId, Pageable pageable);

    // -------------------------------------------------------
    // Dashboard queries
    // -------------------------------------------------------

    @Query("SELECT COALESCE(SUM(o.totalPrice), 0) FROM Order o WHERE o.status = 'COMPLETED'")
    BigDecimal sumCompletedRevenue();

    /** Số đơn ở một trạng thái (badge "đơn chờ xử lý" của admin). */
    long countByStatus(OrderStatus status);

    /** Status counts excluding CART */
    @Query("SELECT o.status, COUNT(o) FROM Order o WHERE o.status <> 'CART' GROUP BY o.status")
    List<Object[]> countGroupByStatus();

    /** Doanh thu đơn COMPLETED đặt (orderedAt) trong {@code [from, to)} — báo cáo tháng. */
    @Query("""
            SELECT COALESCE(SUM(o.totalPrice), 0) FROM Order o
            WHERE o.status = 'COMPLETED' AND o.orderedAt >= :from AND o.orderedAt < :to
            """)
    BigDecimal sumCompletedRevenueBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Số đơn (trừ CART) đặt (orderedAt) trong {@code [from, to)}, theo trạng thái hiện tại — báo cáo tháng. */
    @Query("""
            SELECT o.status, COUNT(o) FROM Order o
            WHERE o.status <> 'CART' AND o.orderedAt >= :from AND o.orderedAt < :to
            GROUP BY o.status
            """)
    List<Object[]> countGroupByStatusBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Recent N orders (excluding CART) with user info */
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.user WHERE o.status <> 'CART' ORDER BY o.createdAt DESC")
    List<Order> findRecent(Pageable pageable);

    /**
     * Monthly revenue for COMPLETED orders since a given date.
     * Returns Object[]: [year(int), month(int), revenue(BigDecimal), count(long)]
     */
    @Query("""
            SELECT FUNCTION('YEAR', o.createdAt),
                   FUNCTION('MONTH', o.createdAt),
                   SUM(o.totalPrice),
                   COUNT(o)
            FROM Order o
            WHERE o.status = 'COMPLETED' AND o.createdAt >= :from
            GROUP BY FUNCTION('YEAR', o.createdAt), FUNCTION('MONTH', o.createdAt)
            ORDER BY FUNCTION('YEAR', o.createdAt), FUNCTION('MONTH', o.createdAt)
            """)
    List<Object[]> findMonthlyRevenue(@Param("from") LocalDateTime from);
}

