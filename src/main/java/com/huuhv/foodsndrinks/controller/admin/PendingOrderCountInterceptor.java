package com.huuhv.foodsndrinks.controller.admin;

import com.huuhv.foodsndrinks.service.OrderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.RedirectView;

/**
 * Đưa {@value #MODEL_ATTRIBUTE} (badge "đơn chờ xử lý" ở sidebar admin) vào model của trang admin.
 * <p>
 * Interceptor thay vì {@code @ModelAttribute} trong {@link AdminLayoutModelAdvice}: method {@code @ModelAttribute} chạy
 * cho MỌI handler của package, kể cả endpoint JSON ({@code /admin/dashboard/charts}) và redirect — tốn một query vô
 * ích. {@code postHandle} chỉ thêm khi thật sự render view. Đăng ký cho {@code /admin/**} ở {@code WebMvcConfig}.
 * Trang lỗi ({@code AdminExceptionHandler}) không qua đây: template ẩn badge khi thiếu giá trị — cũng là cách xử lý
 * khi truy vấn đếm lỗi (WARN, trang vẫn hiển thị).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingOrderCountInterceptor implements HandlerInterceptor {

    public static final String MODEL_ATTRIBUTE = "pendingOrderCount";
    private static final String REDIRECT_PREFIX = "redirect:";

    private final OrderService orderService;

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler,
                           ModelAndView modelAndView) {
        if (modelAndView == null || modelAndView.getView() instanceof RedirectView
                || (modelAndView.getViewName() != null && modelAndView.getViewName().startsWith(REDIRECT_PREFIX))) {
            return;
        }
        try {
            modelAndView.addObject(MODEL_ATTRIBUTE, orderService.countPendingOrders());
        } catch (RuntimeException ex) {
            // Badge chỉ để trang trí: handler đã chạy xong, không biến trang thành 500 vì truy vấn đếm lỗi
            log.warn("Could not count pending orders for the admin badge: {}", ex.toString());
        }
    }
}
