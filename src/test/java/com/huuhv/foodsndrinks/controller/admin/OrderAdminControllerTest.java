package com.huuhv.foodsndrinks.controller.admin;

import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.exception.ResourceNotFoundException;
import com.huuhv.foodsndrinks.service.OrderService;
import com.huuhv.foodsndrinks.support.SecuredWebMvcTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.huuhv.foodsndrinks.service.websocket.StompDestinations;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** {@link OrderAdminController} qua chain web thật: chỉ {@code ROLE_ADMIN}, POST bắt CSRF. */
@SecuredWebMvcTest(OrderAdminController.class)
class OrderAdminControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrderService orderService;

    // -------------------------------------------------------
    // Bảo mật
    // -------------------------------------------------------

    @Test
    void anonymous_isRedirectedToLogin() throws Exception {
        mockMvc.perform(get("/admin/orders"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void user_isForbidden() throws Exception {
        mockMvc.perform(get("/admin/orders")).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/orders/5")).andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/orders/5/status").param("newStatus", "CANCELLED").with(csrf()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(orderService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_post_withoutCsrfToken_isForbidden() throws Exception {
        mockMvc.perform(post("/admin/orders/5/status").param("newStatus", "CANCELLED"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(orderService);
    }

    // -------------------------------------------------------
    // Chức năng
    // -------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_listsOrders_withClampedPageSize() throws Exception {
        when(orderService.searchOrders(eq("PENDING"), isNull(), isNull(), eq(0), anyInt())).thenReturn(Page.empty());

        mockMvc.perform(get("/admin/orders").param("status", "PENDING").param("page", "-3").param("size", "5000"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/orders/list"))
                .andExpect(model().attribute("pageSize", 100))
                .andExpect(model().attribute("totalElements", 0L));
        verify(orderService).searchOrders("PENDING", null, null, 0, 100);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_updatesStatus_andRedirectsToDetail() throws Exception {
        mockMvc.perform(post("/admin/orders/5/status").param("newStatus", "PROCESSING").with(csrf()))
                .andExpect(redirectedUrl("/admin/orders/5"))
                .andExpect(flash().attributeExists("successMessage"));
        verify(orderService).updateStatus(5L, OrderStatus.PROCESSING);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_updatesStatusFromList_andRedirectsToList() throws Exception {
        mockMvc.perform(post("/admin/orders/5/status").param("newStatus", "COMPLETED").param("redirectTo", "list")
                        .with(csrf()))
                .andExpect(redirectedUrl("/admin/orders"));
        verify(orderService).updateStatus(5L, OrderStatus.COMPLETED);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_unknownOrder_returns404Page() throws Exception {
        when(orderService.getOrderDetail(404L)).thenThrow(new ResourceNotFoundException("Không tìm thấy đơn hàng #404"));

        mockMvc.perform(get("/admin/orders/404"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error/admin/404"));
    }

    // -------------------------------------------------------
    // Badge "đơn chờ xác nhận" + realtime
    // -------------------------------------------------------

    @Test
    void pendingCount_anonymous_isRedirectedToLogin() throws Exception {
        mockMvc.perform(get("/admin/orders/pending-count"))
                .andExpect(redirectedUrl("/login"));
        verifyNoInteractions(orderService);
    }

    @Test
    @WithMockUser(roles = "USER")
    void pendingCount_user_isForbidden() throws Exception {
        mockMvc.perform(get("/admin/orders/pending-count")).andExpect(status().isForbidden());
        verifyNoInteractions(orderService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void pendingCount_admin_returnsJson() throws Exception {
        when(orderService.countPendingOrders()).thenReturn(4L);

        mockMvc.perform(get("/admin/orders/pending-count"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.pendingCount").value(4));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminPage_rendersPendingBadge_andRealtimeSettings() throws Exception {
        when(orderService.searchOrders(any(), any(), any(), anyInt(), anyInt())).thenReturn(Page.empty());
        when(orderService.countPendingOrders()).thenReturn(3L);

        String html = mockMvc.perform(get("/admin/orders"))
                .andExpect(status().isOk())
                .andExpect(model().attribute(PendingOrderCountInterceptor.MODEL_ATTRIBUTE, 3L))
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .containsPattern("id=\"pendingOrderBadge\"[^>]*>3</span>")
                .contains("data-ws-url=\"/ws-stomp\"")
                .contains("data-admin-orders-destination=\"" + StompDestinations.ADMIN_ORDERS + "\"")
                .contains("data-pending-count-url=\"/admin/orders/pending-count\"")
                .contains("data-csrf-token-url=\"/csrf-token\"")
                .contains("<meta name=\"_csrf\"")
                // stompjs (WebJar, URL có version do webjars-locator gắn) → realtime.js → admin/realtime.js
                .containsSubsequence("/webjars/stomp__stompjs/", "/bundles/stomp.umd.min.js", "/js/realtime.js",
                        "/js/admin/realtime.js");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void noPendingOrders_badgeIsHidden() throws Exception {
        when(orderService.searchOrders(any(), any(), any(), anyInt(), anyInt())).thenReturn(Page.empty());

        String html = mockMvc.perform(get("/admin/orders"))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).containsPattern("id=\"pendingOrderBadge\"[^>]*hidden");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void pendingCountFailure_stillRendersPage_withBadgeHidden() throws Exception {
        when(orderService.searchOrders(any(), any(), any(), anyInt(), anyInt())).thenReturn(Page.empty());
        when(orderService.countPendingOrders()).thenThrow(new IllegalStateException("lock wait timeout"));

        String html = mockMvc.perform(get("/admin/orders"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/orders/list"))
                .andExpect(model().attributeDoesNotExist(PendingOrderCountInterceptor.MODEL_ATTRIBUTE))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).containsPattern("id=\"pendingOrderBadge\"[^>]*hidden");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void jsonAndRedirectResponses_doNotQueryPendingCount() throws Exception {
        mockMvc.perform(post("/admin/orders/5/status").param("newStatus", "PROCESSING").with(csrf()))
                .andExpect(status().is3xxRedirection());

        verify(orderService, never()).countPendingOrders();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_updateStatusOfUnknownOrder_returns404Page() throws Exception {
        doThrow(new ResourceNotFoundException("Không tìm thấy đơn hàng #404"))
                .when(orderService).updateStatus(404L, OrderStatus.CANCELLED);

        mockMvc.perform(post("/admin/orders/404/status").param("newStatus", "CANCELLED").with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void statusFilterAndDropdown_doNotOfferCart() throws Exception {
        when(orderService.searchOrders(any(), any(), any(), anyInt(), anyInt())).thenReturn(Page.empty());

        String html = mockMvc.perform(get("/admin/orders"))
                .andExpect(model().attribute("orderStatuses", OrderStatus.placedStatuses()))
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("value=\"PENDING\"").doesNotContain("value=\"CART\"").doesNotContain("status=CART");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void movingOrderToCart_isRejected_withFlashError() throws Exception {
        doThrow(new IllegalArgumentException("Không thể chuyển đơn hàng về giỏ hàng"))
                .when(orderService).updateStatus(5L, OrderStatus.CART);

        mockMvc.perform(post("/admin/orders/5/status").param("newStatus", "CART").with(csrf()))
                .andExpect(redirectedUrl("/admin/orders/5"))
                .andExpect(flash().attribute("errorMessage", "Không thể chuyển đơn hàng về giỏ hàng"));
    }
}
