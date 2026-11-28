package com.huuhv.foodsndrinks.controller.admin;

import com.huuhv.foodsndrinks.dto.response.OrderResDto;
import com.huuhv.foodsndrinks.dto.response.PendingOrderCountResDto;
import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.service.OrderService;
import com.huuhv.foodsndrinks.service.excel.ExcelFile;
import com.huuhv.foodsndrinks.service.excel.OrderExportService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;

@Controller
@RequestMapping("/admin/orders")
@RequiredArgsConstructor
public class OrderAdminController {

    private final OrderService orderService;
    private final OrderExportService orderExportService;

    /** {@code from}/{@code to}: ngày đặt (yyyy-MM-dd, tính cả hai đầu) — nhận dạng chuỗi để ngày sai thành thông báo, không thành 500. */
    @GetMapping
    public String listOrders(@RequestParam(required = false) String status,
                             @RequestParam(required = false) String keyword,
                             @RequestParam(required = false) Long orderId,
                             @RequestParam(required = false) String from,
                             @RequestParam(required = false) String to,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "15") int size,
                             Model model) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, 100);

        Page<OrderResDto> orderPage;
        try {
            orderPage = orderService.searchOrders(status, keyword, orderId, from, to, safePage, safeSize);
        } catch (IllegalArgumentException e) {
            // Khoảng ngày sai: báo lỗi và hiển thị danh sách không lọc theo ngày. Bỏ luôn ngày sai khỏi model — link
            // Export/phân trang phải mang đúng bộ lọc của danh sách đang hiện ("lọc gì xuất nấy"), không mang lại lỗi
            model.addAttribute("errorMessage", e.getMessage());
            from = null;
            to = null;
            orderPage = orderService.searchOrders(status, keyword, orderId, null, null, safePage, safeSize);
        }

        model.addAttribute("orders",        orderPage.getContent());
        model.addAttribute("currentPage",   orderPage.getNumber());
        model.addAttribute("totalPages",    orderPage.getTotalPages());
        model.addAttribute("totalElements", orderPage.getTotalElements());
        model.addAttribute("pageSize",      safeSize);
        model.addAttribute("searchStatus",  status);
        model.addAttribute("searchKeyword", keyword);
        model.addAttribute("searchOrderId", orderId);
        model.addAttribute("searchFrom",    from);
        model.addAttribute("searchTo",      to);

        return "admin/orders/list";
    }

    /**
     * Tải file .xlsx gồm đúng các đơn mà {@link #listOrders} liệt kê với cùng bộ lọc (mọi trang). Bộ lọc sai hoặc vượt
     * giới hạn số dòng → không tạo file, quay lại danh sách kèm thông báo.
     */
    @GetMapping("/export")
    public String exportOrders(@RequestParam(required = false) String status,
                               @RequestParam(required = false) String keyword,
                               @RequestParam(required = false) Long orderId,
                               @RequestParam(required = false) String from,
                               @RequestParam(required = false) String to,
                               HttpServletResponse response,
                               RedirectAttributes ra) throws IOException {
        ExcelFile file;
        try {
            file = orderExportService.export(status, keyword, orderId, from, to);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
            ra.addAttribute("status", status);
            ra.addAttribute("keyword", keyword);
            ra.addAttribute("orderId", orderId);
            ra.addAttribute("from", from);
            ra.addAttribute("to", to);
            return "redirect:/admin/orders";
        }
        // File đã dựng xong trước khi đụng tới response; trả null = response đã được ghi trực tiếp
        ExcelDownload.write(file, response);
        return null;
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

