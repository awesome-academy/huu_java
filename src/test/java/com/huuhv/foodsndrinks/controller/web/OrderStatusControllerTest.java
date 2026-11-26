package com.huuhv.foodsndrinks.controller.web;

import com.huuhv.foodsndrinks.dto.response.OrderStatusEventResDto;
import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.service.OrderService;
import com.huuhv.foodsndrinks.service.UserService;
import com.huuhv.foodsndrinks.support.SecuredWebMvcTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /orders/statuses}: chỉ người đã đăng nhập; service nhận id của CHÍNH user hiện tại (lọc IDOR ở service —
 * {@code OrderServiceTest}); đầu vào sai → 400 không body (không phải trang 500 của {@code WebExceptionHandler}).
 */
@SecuredWebMvcTest(OrderStatusController.class)
class OrderStatusControllerTest {

    private static final String URL = "/orders/statuses";

    @Autowired private MockMvc mockMvc;
    @Autowired private OrderService orderService;
    @Autowired private UserService userService;

    @BeforeEach
    void currentUser() {
        when(userService.getCurrentUser("alice")).thenReturn(User.builder().id(1L).username("alice").build());
    }

    @Test
    void anonymous_isRedirectedToLogin() throws Exception {
        mockMvc.perform(get(URL).param("ids", "7").accept(MediaType.APPLICATION_JSON))
                .andExpect(redirectedUrl("/login"));
        verify(orderService, never()).getOrderStatusesForUser(anyLong(), any());
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void user_getsStatusesOfOwnOrders_scopedToCurrentUser() throws Exception {
        when(orderService.getOrderStatusesForUser(1L, List.of(7L, 8L)))
                .thenReturn(List.of(OrderStatusEventResDto.of(7L, OrderStatus.PROCESSING)));

        mockMvc.perform(get(URL).param("ids", "7", "8").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].orderId").value(7))
                .andExpect(jsonPath("$[0].status").value("PROCESSING"))
                .andExpect(jsonPath("$[0].statusLabel").value(OrderStatus.PROCESSING.getLabel()))
                .andExpect(jsonPath("$[0].badgeClass").value(OrderStatus.PROCESSING.getBadgeClass()));
        verify(orderService).getOrderStatusesForUser(1L, List.of(7L, 8L));
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void missingMalformedOrTooManyIds_returns400_withoutCallingService() throws Exception {
        String tooMany = LongStream.rangeClosed(1, OrderService.MAX_STATUS_IDS + 1)
                .mapToObj(Long::toString).collect(Collectors.joining(","));

        mockMvc.perform(get(URL)).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL).param("ids", "abc")).andExpect(status().isBadRequest());
        mockMvc.perform(get(URL).param("ids", tooMany)).andExpect(status().isBadRequest())
                .andExpect(content().string(""));
        verify(orderService, never()).getOrderStatusesForUser(anyLong(), any());
    }
}
