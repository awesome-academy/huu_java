package com.huuhv.foodsndrinks.controller.web;

import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.exception.ResourceNotFoundException;
import com.huuhv.foodsndrinks.service.OrderService;
import com.huuhv.foodsndrinks.service.UserService;
import com.huuhv.foodsndrinks.support.SecuredWebMvcTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** {@link CartController} qua chain web thật: bắt đăng nhập, bắt CSRF, JSON cho fetch() và lỗi nghiệp vụ → 400. */
@SecuredWebMvcTest(CartController.class)
class CartControllerTest {

    private static final User ALICE = User.builder().id(1L).username("alice").build();

    @Autowired private MockMvc mockMvc;
    @Autowired private OrderService orderService;
    @Autowired private UserService userService;

    @BeforeEach
    void currentUser() {
        when(userService.getCurrentUser("alice")).thenReturn(ALICE);
    }

    // -------------------------------------------------------
    // Bảo mật
    // -------------------------------------------------------

    @Test
    void anonymous_isRedirectedToLogin() throws Exception {
        mockMvc.perform(get("/cart"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void anonymousPost_withCsrf_isRedirectedToLogin() throws Exception {
        mockMvc.perform(post("/cart/add").param("productId", "10").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
        verifyNoInteractions(orderService);
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void post_withoutCsrfToken_isForbidden() throws Exception {
        mockMvc.perform(post("/cart/add").param("productId", "10"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/cart/checkout").param("shippingAddress", "1 Lê Lợi"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(orderService);
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void post_withInvalidCsrfToken_isForbidden() throws Exception {
        mockMvc.perform(post("/cart/remove/500").with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(orderService);
    }

    // -------------------------------------------------------
    // Chức năng
    // -------------------------------------------------------

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void cartPage_rendersForLoggedInUser() throws Exception {
        mockMvc.perform(get("/cart"))
                .andExpect(status().isOk())
                .andExpect(view().name("web/cart"));
        verify(orderService).getCartForUser(1L);
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void add_returnsCartPayloadAsJson() throws Exception {
        when(orderService.getCartItemCount(1L)).thenReturn(3);

        mockMvc.perform(post("/cart/add").param("productId", "10").param("quantity", "2").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cartItemCount").value(3));
        verify(orderService).addToCart(ALICE, 10L, 2);
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void add_unavailableProduct_returns400WithMessage() throws Exception {
        doThrow(new IllegalArgumentException("Sản phẩm hiện không còn kinh doanh!"))
                .when(orderService).addToCart(any(), anyLong(), anyInt());

        mockMvc.perform(post("/cart/add").param("productId", "10").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Sản phẩm hiện không còn kinh doanh!"));
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void update_itemOfAnotherUser_returns400() throws Exception {
        doThrow(new IllegalArgumentException("Không thể cập nhật giỏ hàng này!"))
                .when(orderService).updateCartItemQuantity(ALICE, 999L, 5);

        mockMvc.perform(post("/cart/update/999").param("quantity", "5").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Không thể cập nhật giỏ hàng này!"));
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void remove_unknownItem_returns400() throws Exception {
        doThrow(new ResourceNotFoundException("Sản phẩm trong giỏ không tồn tại!"))
                .when(orderService).removeCartItem(ALICE, 404L);

        mockMvc.perform(post("/cart/remove/404").with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Sản phẩm trong giỏ không tồn tại!"));
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void checkout_success_redirectsToOrders() throws Exception {
        mockMvc.perform(post("/cart/checkout").param("shippingAddress", "1 Lê Lợi").with(csrf()))
                .andExpect(redirectedUrl("/orders"))
                .andExpect(flash().attributeExists("successMessage"));
        verify(orderService).checkout(ALICE, "1 Lê Lợi", null);
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void checkout_emptyCart_redirectsBackWithError() throws Exception {
        doThrow(new IllegalArgumentException("Giỏ hàng của bạn đang trống!"))
                .when(orderService).checkout(any(), anyString(), any());

        mockMvc.perform(post("/cart/checkout").param("shippingAddress", "1 Lê Lợi").with(csrf()))
                .andExpect(redirectedUrl("/cart"))
                .andExpect(flash().attribute("errorMessage", "Giỏ hàng của bạn đang trống!"));
    }
}
