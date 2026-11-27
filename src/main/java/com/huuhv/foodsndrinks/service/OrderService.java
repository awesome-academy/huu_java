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
import com.huuhv.foodsndrinks.exception.ResourceNotFoundException;
import com.huuhv.foodsndrinks.repository.OrderDetailRepository;
import com.huuhv.foodsndrinks.repository.OrderRepository;
import com.huuhv.foodsndrinks.repository.ProductImageRepository;
import com.huuhv.foodsndrinks.repository.ProductRepository;
import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import com.huuhv.foodsndrinks.service.messaging.OutboxService;
import com.huuhv.foodsndrinks.service.messaging.OutboxType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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

    @Transactional(readOnly = true)
    public Page<OrderResDto> searchOrders(String statusStr, String keyword,
                                          Long orderId, int page, int size) {
        OrderStatus status = parseStatus(statusStr);
        String kw = blank(keyword) ? null : keyword.trim();

        return orderRepository
                .search(status, kw, orderId, PageRequest.of(Math.max(page, 0), size))
                .map(OrderResDto::forList);
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

        order.setStatus(newStatus);
        orderRepository.save(order);
        auditLogService.orderStatusChanged(id, current, newStatus);
        // Username lấy từ DB (chủ đơn), không từ request — là Principal#getName() của mọi phiên của chủ đơn
        outboxService.enqueue(OutboxType.ORDER_STATUS_CHANGED,
                new OrderStatusChangedMessage(id, order.getUser().getUsername(), newStatus));
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

    private OrderDetail requireOwnedCartItem(User user, Long orderDetailId) {
        OrderDetail detail = orderDetailRepository.findById(orderDetailId)
                .orElseThrow(() -> new ResourceNotFoundException("Sản phẩm trong giỏ không tồn tại!"));

        Order order = detail.getOrder();
        if (order.getUser() == null || !order.getUser().getId().equals(user.getId())
                || order.getStatus() != OrderStatus.CART) {
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
}

