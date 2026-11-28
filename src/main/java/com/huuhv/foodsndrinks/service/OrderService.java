package com.huuhv.foodsndrinks.service;

import com.huuhv.foodsndrinks.dto.message.OrderPlacedMessage;
import com.huuhv.foodsndrinks.dto.message.OrderStatusChangedMessage;
import com.huuhv.foodsndrinks.dto.response.OrderResDto;
import com.huuhv.foodsndrinks.dto.response.OrderStatusEventResDto;
import com.huuhv.foodsndrinks.entity.Order;
import com.huuhv.foodsndrinks.entity.OrderDetail;
import com.huuhv.foodsndrinks.entity.Product;
import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.exception.ConcurrentUpdateException;
import com.huuhv.foodsndrinks.exception.ResourceNotFoundException;
import com.huuhv.foodsndrinks.repository.OrderDetailRepository;
import com.huuhv.foodsndrinks.repository.OrderRepository;
import com.huuhv.foodsndrinks.repository.ProductImageRepository;
import com.huuhv.foodsndrinks.repository.ProductRepository;
import com.huuhv.foodsndrinks.repository.projection.OrderExportRow;
import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import com.huuhv.foodsndrinks.service.messaging.OutboxService;
import com.huuhv.foodsndrinks.service.messaging.OutboxType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderService {

    /** Số mã đơn tối đa mỗi lần hỏi trạng thái ({@code GET /orders/statuses}) — trang lịch sử hiện 10 đơn/trang. */
    public static final int MAX_STATUS_IDS = 20;

    private final OrderRepository        orderRepository;
    private final OrderDetailRepository  orderDetailRepository;
    private final ProductRepository      productRepository;
    private final ProductImageRepository productImageRepository;
    private final OutboxService          outboxService;
    private final AuditLogService        auditLogService;

    /**
     * Danh sách đơn của admin theo bộ lọc. {@code from}/{@code to}: ngày đặt (yyyy-MM-dd, tính cả hai đầu), để trống =
     * không chặn đầu đó.
     *
     * @throws IllegalArgumentException khi ngày sai định dạng hoặc {@code from} sau {@code to}
     */
    @Transactional(readOnly = true)
    public Page<OrderResDto> searchOrders(String statusStr, String keyword, Long orderId,
                                          String from, String to, int page, int size) {
        OrderFilter filter = OrderFilter.of(statusStr, keyword, orderId, from, to);

        return orderRepository
                .search(filter.status(), filter.keyword(), filter.orderId(), filter.orderedFrom(),
                        filter.orderedBefore(), PageRequest.of(Math.max(page, 0), size))
                .map(OrderResDto::forList);
    }

    /**
     * Các dòng của file export: đúng các đơn mà {@link #searchOrders} liệt kê với cùng bộ lọc (mọi trang), mới nhất trước.
     *
     * @param maxRows số dòng tối đa của một file
     * @throws IllegalArgumentException khi bộ lọc sai (như {@link #searchOrders}) hoặc khớp nhiều hơn {@code maxRows} đơn
     */
    @Transactional(readOnly = true)
    public List<OrderExportRow> findExportRows(String statusStr, String keyword, Long orderId,
                                               String from, String to, int maxRows) {
        OrderFilter filter = OrderFilter.of(statusStr, keyword, orderId, from, to);

        // Xin thừa 1 dòng: biết bộ lọc vượt giới hạn mà không cần đếm riêng, và không bao giờ đọc quá maxRows + 1 dòng
        List<OrderExportRow> rows = orderRepository.findExportRows(filter.status(), filter.keyword(), filter.orderId(),
                filter.orderedFrom(), filter.orderedBefore(), Limit.of(maxRows + 1));
        if (rows.size() > maxRows) {
            throw new IllegalArgumentException("Số đơn hàng khớp bộ lọc vượt giới hạn " + maxRows
                    + " dòng của một file Excel. Hãy thu hẹp khoảng ngày đặt hoặc bộ lọc!");
        }
        return rows;
    }

    /** Chi tiết đơn cho admin. Giỏ hàng ({@code CART}) không phải đơn → 404 như mọi màn hình admin. */
    @Transactional(readOnly = true)
    public OrderResDto getOrderDetail(Long id) {
        // 1 query: order + user + orderDetails + products (FETCH JOIN, no lazy-load loops)
        Order order = orderRepository.findByIdWithDetailsAndProducts(id)
                .filter(found -> found.getStatus() != OrderStatus.CART)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng #" + id));
        return buildOrderResDto(order, order.getOrderDetails());
    }

    // -------------------------------------------------------
    // User-facing: cart
    // -------------------------------------------------------

    @Transactional(readOnly = true)
    public OrderResDto getCartForUser(Long userId) {
        Order cart = orderRepository.findCartByUserId(userId).orElse(null);
        if (cart == null || cart.getOrderDetails().isEmpty()) return null;
        return buildOrderResDto(cart, cart.getOrderDetails());
    }

    @Transactional(readOnly = true)
    public int getCartItemCount(Long userId) {
        return orderDetailRepository.sumCartQuantityByUserId(userId);
    }

    @Transactional
    public void addToCart(User user, Long productId, int quantity) {
        int qty = Math.max(quantity, 1);
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Sản phẩm không tồn tại!"));
        if (!Boolean.TRUE.equals(product.getIsAvailable())) {
            throw new IllegalArgumentException("Sản phẩm hiện không còn kinh doanh!");
        }

        Order cart = orderRepository.findCartByUserId(user.getId()).orElseGet(() -> createEmptyCart(user));

        OrderDetail existing = cart.getOrderDetails().stream()
                .filter(od -> od.getProduct() != null && od.getProduct().getId().equals(productId))
                .findFirst().orElse(null);

        if (existing != null) {
            existing.setQuantity(existing.getQuantity() + qty);
            existing.setSubtotal(existing.getUnitPrice().multiply(BigDecimal.valueOf(existing.getQuantity())));
        } else {
            OrderDetail detail = new OrderDetail();
            detail.setOrder(cart);
            detail.setProduct(product);
            detail.setQuantity(qty);
            detail.setUnitPrice(product.getPrice());
            detail.setSubtotal(product.getPrice().multiply(BigDecimal.valueOf(qty)));
            cart.getOrderDetails().add(detail);
        }

        recalculateTotal(cart);
        orderRepository.save(cart);
    }

    @Transactional
    public void updateCartItemQuantity(User user, Long orderDetailId, int quantity) {
        OrderDetail detail = requireOwnedCartItem(user, orderDetailId);
        Order cart = detail.getOrder();

        if (quantity < 1) {
            cart.getOrderDetails().remove(detail);
        } else {
            detail.setQuantity(quantity);
            detail.setSubtotal(detail.getUnitPrice().multiply(BigDecimal.valueOf(quantity)));
        }

        recalculateTotal(cart);
        orderRepository.save(cart);
    }

    @Transactional
    public void removeCartItem(User user, Long orderDetailId) {
        OrderDetail detail = requireOwnedCartItem(user, orderDetailId);
        Order cart = detail.getOrder();
        cart.getOrderDetails().remove(detail);
        recalculateTotal(cart);
        orderRepository.save(cart);
    }

    @Transactional
    public void checkout(User user, String shippingAddress, String note) {
        Order cart = orderRepository.findCartByUserId(user.getId())
                .filter(o -> !o.getOrderDetails().isEmpty())
                .orElseThrow(() -> new IllegalArgumentException("Giỏ hàng của bạn đang trống!"));

        if (blank(shippingAddress)) {
            throw new IllegalArgumentException("Vui lòng nhập địa chỉ giao hàng!");
        }

        cart.setShippingAddress(shippingAddress.trim());
        cart.setNote(note);
        cart.setStatus(OrderStatus.PENDING);
        cart.setOrderedAt(LocalDateTime.now());
        orderRepository.save(cart);

        // Cùng transaction với đơn: commit thì message chắc chắn được gửi (ngay, hoặc bởi job nếu broker đang chết)
        outboxService.enqueue(OutboxType.ORDER_PLACED, buildOrderPlacedMessage(cart, user));
    }

    // -------------------------------------------------------
    // User-facing: order history
    // -------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<OrderResDto> getOrderHistoryForUser(Long userId, int page, int size) {
        return orderRepository.findOrderHistoryByUserId(userId, PageRequest.of(Math.max(page, 0), size))
                .map(OrderResDto::forList);
    }

    @Transactional(readOnly = true)
    public OrderResDto getOrderDetailForUser(Long orderId, Long userId) {
        Order order = orderRepository.findByIdAndUserIdWithDetailsAndProducts(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng #" + orderId));
        return buildOrderResDto(order, order.getOrderDetails());
    }

    /** Số đơn chờ xác nhận ({@code PENDING}) — badge của admin, luôn đọc từ DB (đúng cả khi nhiều admin/instance). */
    @Transactional(readOnly = true)
    public long countPendingOrders() {
        return orderRepository.countByStatus(OrderStatus.PENDING);
    }

    /**
     * Trạng thái hiện tại của các đơn {@code ids} của chính user {@code userId} — id của người khác hoặc không tồn tại bị
     * bỏ qua (không lỗi, không lộ đơn đó có tồn tại hay không). Dùng khi trang lịch sử/chi tiết đơn kết nối WebSocket lại.
     *
     * @throws IllegalArgumentException khi {@code ids} rỗng hoặc nhiều hơn {@value #MAX_STATUS_IDS}
     */
    @Transactional(readOnly = true)
    public List<OrderStatusEventResDto> getOrderStatusesForUser(Long userId, Collection<Long> ids) {
        Set<Long> distinct = new LinkedHashSet<>(ids);
        if (distinct.isEmpty() || distinct.size() > MAX_STATUS_IDS) {
            throw new IllegalArgumentException("Cần từ 1 đến " + MAX_STATUS_IDS + " mã đơn");
        }
        return orderRepository.findStatusesByUserIdAndIdIn(userId, distinct).stream()
                .map(view -> OrderStatusEventResDto.of(view.getId(), view.getStatus()))
                .toList();
    }

    /**
     * Đổi trạng thái đơn (admin). Cùng transaction: ghi outbox {@link OrderStatusChangedMessage} → chỉ gửi sau commit
     * (rollback thì không ai nhận), không mất khi broker chết. Chủ đơn đang mở trang đơn hàng và các admin đang online
     * nhận qua WebSocket.
     * <p>
     * Giỏ hàng ({@code CART}) không phải đơn của khách: chỉ {@link #checkout} đưa nó sang {@code PENDING} (kiểm tồn kho,
     * chụp giá, ghi {@code orderedAt}). Admin không thấy giỏ hàng ở mọi màn hình nên id của giỏ hàng → 404 như đơn không
     * tồn tại; đích {@code CART} bị từ chối — kéo đơn về giỏ sẽ trộn nó vào giỏ đang dùng của khách (một user chỉ có
     * một {@code CART}).
     * <p>
     * Hai admin đổi cùng một đơn: trạng thái được ghi bằng UPDATE có điều kiện "vẫn đang ở trạng thái vừa đọc"
     * ({@link OrderRepository#updateStatusIfCurrent}), không phải đọc → sửa entity → {@code save} (request sau sẽ ghi đè
     * request trước mà không ai biết). Request đến sau không đổi được dòng nào → {@link ConcurrentUpdateException}, không
     * ghi audit/outbox; vì vậy {@code order.status.from} trong audit luôn là trạng thái thật trong DB lúc đổi.
     *
     * @throws ConcurrentUpdateException khi đơn vừa được người khác đổi trạng thái
     */
    @Transactional
    public void updateStatus(Long id, OrderStatus newStatus) {
        if (newStatus == OrderStatus.CART) {
            throw new IllegalArgumentException("Không thể chuyển đơn hàng về giỏ hàng");
        }
        Order order = orderRepository.findByIdWithUser(id)
                .filter(found -> found.getStatus() != OrderStatus.CART)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy đơn hàng #" + id));

        OrderStatus current = order.getStatus();
        if (current == newStatus) return;
        // Username lấy từ DB (chủ đơn), không từ request — là Principal#getName() của mọi phiên của chủ đơn.
        // Đọc trước câu UPDATE: nó xoá persistence context nên `order` không còn dùng được sau đó.
        String ownerUsername = order.getUser().getUsername();

        if (orderRepository.updateStatusIfCurrent(id, current, newStatus) == 0) {
            throw new ConcurrentUpdateException("Đơn hàng vừa được người khác cập nhật, vui lòng tải lại trang");
        }
        auditLogService.orderStatusChanged(id, current, newStatus);
        outboxService.enqueue(OutboxType.ORDER_STATUS_CHANGED,
                new OrderStatusChangedMessage(id, ownerUsername, newStatus));
    }

    private OrderResDto buildOrderResDto(Order order, List<OrderDetail> details) {
        // Batch-load primary image URLs in 1 query — avoids N+1 per product
        List<Long> productIds = details.stream()
                .filter(od -> od.getProduct() != null)
                .map(od -> od.getProduct().getId())
                .collect(Collectors.toList());

        Map<Long, String> primaryUrls = productIds.isEmpty() ? Map.of() :
                productImageRepository.findPrimaryUrlsByProductIds(productIds)
                        .stream()
                        .collect(Collectors.toMap(
                                row -> (Long) row[0],
                                row -> (String) row[1],
                                (a, b) -> a
                        ));

        return OrderResDto.forDetail(order, details, primaryUrls);
    }

    /** Flattens order + eagerly-loaded line items into a message snapshot (consumers never touch lazy JPA associations). */
    private OrderPlacedMessage buildOrderPlacedMessage(Order order, User user) {
        List<OrderPlacedMessage.OrderLineItem> items = order.getOrderDetails().stream()
                .map(od -> new OrderPlacedMessage.OrderLineItem(
                        od.getProduct() != null ? od.getProduct().getName() : "N/A",
                        od.getQuantity(), od.getUnitPrice(), od.getSubtotal()))
                .toList();

        return new OrderPlacedMessage(order.getId(), user.getFullName(), user.getEmail(), user.getPhone(),
                order.getShippingAddress(), order.getNote(), order.getTotalPrice(), order.getOrderedAt(), items);
    }

    private Order createEmptyCart(User user) {
        Order cart = new Order();
        cart.setUser(user);
        cart.setStatus(OrderStatus.CART);
        cart.setOrderDetails(new ArrayList<>());
        return orderRepository.save(cart);
    }

    /**
     * Món {@code orderDetailId} trong GIỎ HÀNG của chính {@code user}. Món của người khác được báo y hệt món không tồn
     * tại (cùng exception, cùng thông điệp → cùng 404): id tăng dần nên câu trả lời khác nhau sẽ cho phép dò id nào
     * đang có trong giỏ/đơn của người khác. Món thuộc đơn ĐÃ ĐẶT của chính user là dữ liệu của họ → 400 nói rõ lý do.
     */
    private OrderDetail requireOwnedCartItem(User user, Long orderDetailId) {
        OrderDetail detail = orderDetailRepository.findById(orderDetailId)
                .filter(found -> found.getOrder().getUser() != null
                        && found.getOrder().getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Sản phẩm trong giỏ không tồn tại!"));

        if (detail.getOrder().getStatus() != OrderStatus.CART) {
            throw new IllegalArgumentException("Không thể cập nhật giỏ hàng này!");
        }
        return detail;
    }

    private void recalculateTotal(Order cart) {
        BigDecimal total = cart.getOrderDetails().stream()
                .map(OrderDetail::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        cart.setTotalPrice(total);
    }

    /** Bộ lọc trạng thái của admin; giá trị lạ hoặc {@code CART} (không phải đơn) → không lọc. */
    private static OrderStatus parseStatus(String value) {
        if (blank(value)) return null;
        try {
            OrderStatus status = OrderStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
            return status == OrderStatus.CART ? null : status;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Bộ lọc danh sách đơn của admin sau khi chuẩn hoá — dùng chung cho trang danh sách và file export để hai nơi luôn
     * lọc như nhau. Ngày đặt nằm trong {@code [orderedFrom, orderedBefore)}; {@code null} = không lọc theo trường đó.
     */
    private record OrderFilter(OrderStatus status, String keyword, Long orderId,
                               LocalDateTime orderedFrom, LocalDateTime orderedBefore) {

        /** Khoảng năm chấp nhận: ngoài khoảng này chắc chắn là nhập sai, và tránh tràn khi cộng ngày / đổi sang kiểu của DB. */
        private static final int MIN_YEAR = 2000;
        private static final int MAX_YEAR = 2999;

        static OrderFilter of(String status, String keyword, Long orderId, String from, String to) {
            LocalDate fromDate = parseDate(from, "Ngày bắt đầu");
            LocalDate toDate = parseDate(to, "Ngày kết thúc");
            if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
                throw new IllegalArgumentException("Ngày bắt đầu không được sau ngày kết thúc!");
            }
            return new OrderFilter(parseStatus(status), blank(keyword) ? null : keyword.trim(), orderId,
                    fromDate == null ? null : fromDate.atStartOfDay(),
                    // "Đến ngày" tính cả ngày đó → chặn trên là 00:00 ngày kế tiếp
                    toDate == null ? null : toDate.plusDays(1).atStartOfDay());
        }

        private static LocalDate parseDate(String value, String label) {
            if (blank(value)) return null;
            try {
                LocalDate date = LocalDate.parse(value.trim());
                if (date.getYear() >= MIN_YEAR && date.getYear() <= MAX_YEAR) {
                    return date;
                }
            } catch (DateTimeParseException e) {
                // báo lỗi chung bên dưới — không lặp lại giá trị người dùng nhập
            }
            throw new IllegalArgumentException(label + " không hợp lệ!");
        }
    }
}

