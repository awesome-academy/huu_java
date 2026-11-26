package com.huuhv.foodsndrinks.controller.admin;

import com.huuhv.foodsndrinks.dto.response.OrderResDto;
import com.huuhv.foodsndrinks.dto.response.PendingOrderCountResDto;
import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/orders")
@RequiredArgsConstructor
public class OrderAdminController {

    private final OrderService orderService;

    @GetMapping
    public String listOrders(@RequestParam(required = false) String status,
                             @RequestParam(required = false) String keyword,
                             @RequestParam(required = false) Long orderId,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "15") int size,
                             Model model) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, 100);

        Page<OrderResDto> orderPage =
                orderService.searchOrders(status, keyword, orderId, safePage, safeSize);

        model.addAttribute("orders",        orderPage.getContent());
        model.addAttribute("currentPage",   orderPage.getNumber());
        model.addAttribute("totalPages",    orderPage.getTotalPages());
        model.addAttribute("totalElements", orderPage.getTotalElements());
        model.addAttribute("pageSize",      safeSize);
        model.addAttribute("searchStatus",  status);
        model.addAttribute("searchKeyword", keyword);
        model.addAttribute("searchOrderId", orderId);

        return "admin/orders/list";
    }

    /**
     * Số đơn chờ xác nhận (JSON) — {@code static/js/admin/realtime.js} gọi sau mỗi lần kết nối WebSocket (bù sự kiện lỡ
     * khi mất kết nối/đổi instance) và để kiểm tra session còn sống khi không kết nối lại được.
     */
    @GetMapping(value = "/pending-count", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public PendingOrderCountResDto pendingCount() {
        return new PendingOrderCountResDto(orderService.countPendingOrders());
    }

    @GetMapping("/{id}")
    public String orderDetail(@PathVariable Long id, Model model, RedirectAttributes ra) {
        try {
            model.addAttribute("order", orderService.getOrderDetail(id));
            return "admin/orders/detail";
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/admin/orders";
        }
    }

    @PostMapping("/{id}/status")
    public String updateStatus(@PathVariable Long id,
                               @RequestParam OrderStatus newStatus,
                               @RequestParam(required = false, defaultValue = "detail") String redirectTo,
                               RedirectAttributes ra) {
        try {
            orderService.updateStatus(id, newStatus);
            ra.addFlashAttribute("successMessage",
                    "Đã cập nhật trạng thái đơn #" + id + " → " + newStatus.getLabel());
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "list".equals(redirectTo)
                ? "redirect:/admin/orders"
                : "redirect:/admin/orders/" + id;
    }
}

