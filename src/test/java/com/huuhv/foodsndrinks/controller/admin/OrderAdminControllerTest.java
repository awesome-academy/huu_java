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

import static org.mockito.ArgumentMatchers.anyInt;
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

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_updateStatusOfUnknownOrder_returns404Page() throws Exception {
        doThrow(new ResourceNotFoundException("Không tìm thấy đơn hàng #404"))
                .when(orderService).updateStatus(404L, OrderStatus.CANCELLED);

        mockMvc.perform(post("/admin/orders/404/status").param("newStatus", "CANCELLED").with(csrf()))
                .andExpect(status().isNotFound());
    }
}
