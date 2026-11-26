package com.huuhv.foodsndrinks.controller.web;

import com.huuhv.foodsndrinks.dto.response.OrderStatusEventResDto;
import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.service.OrderService;
import com.huuhv.foodsndrinks.service.UserService;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.security.Principal;
import java.util.List;

/**
 * {@code GET /orders/statuses?ids=1&ids=2}: trạng thái hiện tại các đơn của chính user — {@code static/js/web/order-status.js}
 * gọi sau khi kết nối WebSocket lại (có thể đã lỡ sự kiện lúc mất kết nối/đổi instance). Session + {@code ROLE_USER}/
 * {@code ROLE_ADMIN} ({@code SecurityConfig}: {@code /orders/**}); service lọc theo user hiện tại (chống IDOR).
 * <p>
 * Tách khỏi {@link OrderController} để lỗi đầu vào trả 400 không body: {@code WebExceptionHandler} của package web biến
 * mọi exception còn lại thành trang 500 — handler cục bộ ở đây chỉ áp dụng cho endpoint JSON này.
 */
@RestController
@RequiredArgsConstructor
public class OrderStatusController {

    private final OrderService orderService;
    private final UserService userService;

    @GetMapping(value = "/orders/statuses", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<OrderStatusEventResDto> orderStatuses(
            @RequestParam @Size(min = 1, max = OrderService.MAX_STATUS_IDS) List<Long> ids, Principal principal) {
        User user = userService.getCurrentUser(principal.getName());
        return orderService.getOrderStatusesForUser(user.getId(), ids);
    }

    /** Thiếu {@code ids}, sai định dạng, quá {@value OrderService#MAX_STATUS_IDS} mã → 400, không lộ chi tiết lỗi. */
    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class, IllegalArgumentException.class})
    public ResponseEntity<Void> badRequest() {
        return ResponseEntity.badRequest().build();
    }
}
