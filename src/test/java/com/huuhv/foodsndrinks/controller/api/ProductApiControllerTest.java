package com.huuhv.foodsndrinks.controller.api;

import com.huuhv.foodsndrinks.dto.response.ProductResDto;
import com.huuhv.foodsndrinks.entity.Product;
import com.huuhv.foodsndrinks.exception.ResourceNotFoundException;
import com.huuhv.foodsndrinks.security.CustomUserDetailsService;
import com.huuhv.foodsndrinks.security.JwtUtil;
import com.huuhv.foodsndrinks.service.ProductService;
import com.huuhv.foodsndrinks.support.SecuredWebMvcTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@link ProductApiController} qua chain API thật: stateless, Bearer JWT, thiếu/sai token → 401 (không redirect). */
@SecuredWebMvcTest(ProductApiController.class)
class ProductApiControllerTest {

    private static final String VALID_TOKEN = "valid.jwt.token";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private CustomUserDetailsService userDetailsService;
    @MockitoBean private ProductService productService;

    private void tokenFor(UserDetails user) {
        when(jwtUtil.validUsername(VALID_TOKEN)).thenReturn(Optional.of(user.getUsername()));
        when(userDetailsService.loadUserByUsername(user.getUsername())).thenReturn(user);
    }

    private static UserDetails account(String role, boolean enabled) {
        return User.withUsername("alice").password("unused").roles(role).disabled(!enabled).build();
    }

    // -------------------------------------------------------
    // Bảo mật
    // -------------------------------------------------------

    @Test
    void withoutToken_returns401_withoutRedirectOrSession() throws Exception {
        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        verifyNoInteractions(productService);
    }

    @Test
    void invalidToken_returns401_withoutDatabaseLookup() throws Exception {
        mockMvc.perform(get("/api/v1/products").header(HttpHeaders.AUTHORIZATION, "Bearer forged.or.expired"))
                .andExpect(status().isUnauthorized());
        // Token bị từ chối ở bước kiểm tra chữ ký/hạn/iss/aud, trước khi tra DB
        verify(userDetailsService, never()).loadUserByUsername(anyString());
        verifyNoInteractions(productService);
    }

    @Test
    void nonBearerScheme_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/products").header(HttpHeaders.AUTHORIZATION, "Basic YWxpY2U6cGFzcw=="))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(jwtUtil, productService);
    }

    @Test
    void tokenOfDisabledAccount_returns401() throws Exception {
        tokenFor(account("USER", false));

        mockMvc.perform(get("/api/v1/products").header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(productService);
    }

    @Test
    @WithMockUser(roles = "USER")
    void authenticatedUser_getsProducts_andNoSessionCookie() throws Exception {
        when(productService.searchProducts(null, null, null, "true", 0, 10)).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    // -------------------------------------------------------
    // Chức năng
    // -------------------------------------------------------

    @Test
    void validToken_listsOnlyAvailableProducts_withClampedPageSize() throws Exception {
        tokenFor(account("USER", true));
        Product coffee = Product.builder().id(10L).name("Cà phê sữa").slug("ca-phe-sua")
                .price(new BigDecimal("25000")).build();
        when(productService.searchProducts("cà phê", null, null, "true", 0, 100))
                .thenReturn(new PageImpl<>(List.of(ProductResDto.forList(coffee, "/uploads/products/a.png")),
                        PageRequest.of(0, 100), 1));

        mockMvc.perform(get("/api/v1/products").param("name", "cà phê").param("page", "-1").param("size", "1000")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Cà phê sữa"))
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void validAdminToken_canReadProducts() throws Exception {
        tokenFor(account("ADMIN", true));
        when(productService.searchProducts(null, null, null, "true", 0, 10)).thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/products").header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "USER")
    void unknownProduct_returns404Json() throws Exception {
        when(productService.getProductDetail(404L)).thenThrow(new ResourceNotFoundException("Không tìm thấy sản phẩm"));

        mockMvc.perform(get("/api/v1/products/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @WithMockUser(roles = "USER")
    void unexpectedError_returns500_withoutLeakingDetails() throws Exception {
        when(productService.getProductDetail(1L)).thenThrow(new IllegalStateException("SQL: SELECT secret FROM users"));

        mockMvc.perform(get("/api/v1/products/1"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("SELECT"))));
    }
}
