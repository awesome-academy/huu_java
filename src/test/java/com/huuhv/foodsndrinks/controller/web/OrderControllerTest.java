package com.huuhv.foodsndrinks.controller.web;

import com.huuhv.foodsndrinks.dto.response.OrderResDto;
import com.huuhv.foodsndrinks.entity.Order;
import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.service.OrderService;
import com.huuhv.foodsndrinks.service.UserService;
import com.huuhv.foodsndrinks.service.websocket.StompDestinations;
import com.huuhv.foodsndrinks.support.SecuredWebMvcTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Trang lịch sử/chi tiết đơn của khách: badge trạng thái mang dữ liệu cho {@code static/js/web/order-status.js}, cấu hình
 * realtime (đích STOMP khớp {@link StompDestinations#USER_ORDERS} — template viết literal) và thứ tự script.
 */
@SecuredWebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrderService orderService;
    @Autowired private UserService userService;

    private final User alice = User.builder().id(1L).username("alice").fullName("Alice").email("a@example.com").build();

    @BeforeEach
    void currentUser() {
        when(userService.getCurrentUser("alice")).thenReturn(alice);
    }

    @Test
    void anonymous_isRedirectedToLogin() throws Exception {
        mockMvc.perform(get("/orders")).andExpect(redirectedUrl("/login"));
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void history_rendersStatusBadgeData_andRealtimeConfig() throws Exception {
        Page<OrderResDto> page = new PageImpl<>(List.of(OrderResDto.forList(order(7L, OrderStatus.PENDING))),
                PageRequest.of(0, 10), 1);
        when(orderService.getOrderHistoryForUser(eq(1L), eq(0), anyInt())).thenReturn(page);

        String html = render("/orders");

        assertThat(html)
                .containsPattern("data-order-status-badge=\"7\"[^>]*data-status=\"PENDING\"[^>]*data-badge-class=\""
                        + OrderStatus.PENDING.getBadgeClass() + "\"");
        assertRealtime(html);
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void emptyHistory_doesNotOpenWebSocket() throws Exception {
        when(orderService.getOrderHistoryForUser(eq(1L), eq(0), anyInt())).thenReturn(Page.empty());

        String html = render("/orders");

        assertThat(html).doesNotContain("id=\"orderRealtime\"", "/js/web/order-status.js", "stomp.umd.min.js");
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void detail_rendersStatusBadgeData_andRealtimeConfig() throws Exception {
        when(orderService.getOrderDetailForUser(9L, 1L))
                .thenReturn(OrderResDto.forDetail(order(9L, OrderStatus.PROCESSING), List.of(), Map.of()));

        String html = render("/orders/9");

        assertThat(html).containsPattern("data-order-status-badge=\"9\"[^>]*data-status=\"PROCESSING\"");
        assertRealtime(html);
    }

    private String render(String path) throws Exception {
        return mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static void assertRealtime(String html) {
        assertThat(html)
                .contains("id=\"orderRealtime\"")
                .contains("data-ws-url=\"/ws-stomp\"")
                .contains("data-csrf-token-url=\"/csrf-token\"")
                .contains("data-destination=\"" + StompDestinations.USER_ORDERS + "\"")
                .contains("data-statuses-url=\"/orders/statuses\"")
                // main.js (showToast) → stompjs (WebJar, URL có version) → realtime.js → order-status.js
                .containsSubsequence("/js/web/main.js", "/webjars/stomp__stompjs/", "/bundles/stomp.umd.min.js",
                        "/js/realtime.js", "/js/web/order-status.js");
    }

    private Order order(Long id, OrderStatus status) {
        LocalDateTime at = LocalDateTime.of(2026, 11, 26, 9, 0);
        return Order.builder().id(id).user(alice).status(status).totalPrice(new BigDecimal("120000"))
                .orderedAt(at).createdAt(at).updatedAt(at).build();
    }
}
