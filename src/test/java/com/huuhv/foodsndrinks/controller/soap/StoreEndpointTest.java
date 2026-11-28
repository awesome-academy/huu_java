package com.huuhv.foodsndrinks.controller.soap;

import com.huuhv.foodsndrinks.config.WebServiceConfig;
import com.huuhv.foodsndrinks.dto.response.ProductResDto;
import com.huuhv.foodsndrinks.entity.Category;
import com.huuhv.foodsndrinks.entity.Product;
import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.enums.ProductType;
import com.huuhv.foodsndrinks.exception.ResourceNotFoundException;
import com.huuhv.foodsndrinks.service.OrderService;
import com.huuhv.foodsndrinks.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webservices.test.autoconfigure.server.WebServiceServerTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.ws.test.server.MockWebServiceClient;
import org.springframework.xml.transform.StringSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.ws.test.server.RequestCreators.withPayload;
import static org.springframework.ws.test.server.ResponseMatchers.clientOrSenderFault;
import static org.springframework.ws.test.server.ResponseMatchers.noFault;
import static org.springframework.ws.test.server.ResponseMatchers.serverOrReceiverFault;
import static org.springframework.ws.test.server.ResponseMatchers.xpath;

/**
 * {@link StoreEndpoint} qua {@code MessageDispatcher} thật (interceptor validate XSD + resolver fault của
 * {@link WebServiceConfig}), service là mock: ánh xạ JAXB ↔ service, request sai hợp đồng bị từ chối trước khi tới
 * service, và fault KHÔNG bao giờ mang thông điệp của exception. Xác thực HTTP Basic của {@code /ws/**} kiểm tra ở
 * {@code SoapHttpIntegrationTest}; ở đây người gọi là {@code @WithMockUser}.
 */
@WebServiceServerTest(endpoints = StoreEndpoint.class)
@Import(WebServiceConfig.class)
class StoreEndpointTest {

    private static final Map<String, String> NS = Map.of("s", StoreEndpoint.NAMESPACE);

    @Autowired private MockWebServiceClient client;
    @MockitoBean private ProductService productService;
    @MockitoBean private OrderService orderService;

    // -------------------------------------------------------
    // GetProducts
    // -------------------------------------------------------

    @Test
    @WithMockUser(username = "partner", roles = "USER")
    void getProducts_returnsTheRequestedPage_ofAvailableProductsInACategory() {
        Page<ProductResDto> page = new PageImpl<>(
                List.of(product(7L, "Trà sữa trân châu", "tra-sua-tran-chau", category("Đồ uống"), ProductType.DRINK, "35000.00"),
                        product(8L, "Bánh mì <thịt> & chả", "banh-mi-thit-cha", null, ProductType.FOOD, "25000.50")),
                PageRequest.of(1, 20), 22);
        when(productService.searchProducts(null, 3L, null, "true", 1, 20)).thenReturn(page);

        client.sendRequest(withPayload(new StringSource("""
                        <GetProductsRequest xmlns="urn:foodsndrinks:store">
                            <categoryId>3</categoryId><page>1</page><size>20</size>
                        </GetProductsRequest>""")))
                .andExpect(noFault())
                .andExpect(xpath("count(/s:GetProductsResponse/s:product)", NS).evaluatesTo(2))
                .andExpect(xpath("/s:GetProductsResponse/s:product[1]/s:id", NS).evaluatesTo(7))
                .andExpect(xpath("/s:GetProductsResponse/s:product[1]/s:name", NS).evaluatesTo("Trà sữa trân châu"))
                .andExpect(xpath("/s:GetProductsResponse/s:product[1]/s:slug", NS).evaluatesTo("tra-sua-tran-chau"))
                .andExpect(xpath("/s:GetProductsResponse/s:product[1]/s:category", NS).evaluatesTo("Đồ uống"))
                .andExpect(xpath("/s:GetProductsResponse/s:product[1]/s:type", NS).evaluatesTo("DRINK"))
                .andExpect(xpath("/s:GetProductsResponse/s:product[1]/s:price", NS).evaluatesTo("35000.00"))
                // Ký tự đặc biệt của XML được JAXB escape — đọc lại vẫn nguyên văn
                .andExpect(xpath("/s:GetProductsResponse/s:product[2]/s:name", NS).evaluatesTo("Bánh mì <thịt> & chả"))
                // Sản phẩm chưa thuộc danh mục nào: không có phần tử category (không phải "—" của màn hình admin)
                .andExpect(xpath("count(/s:GetProductsResponse/s:product[2]/s:category)", NS).evaluatesTo(0))
                .andExpect(xpath("/s:GetProductsResponse/s:page", NS).evaluatesTo(1))
                .andExpect(xpath("/s:GetProductsResponse/s:size", NS).evaluatesTo(20))
                .andExpect(xpath("/s:GetProductsResponse/s:totalElements", NS).evaluatesTo(22))
                .andExpect(xpath("/s:GetProductsResponse/s:totalPages", NS).evaluatesTo(2));
    }

    @Test
    @WithMockUser(username = "partner", roles = "USER")
    void getProducts_withoutParameters_usesFirstPageOfTen_andOnlyAvailableProducts() {
        when(productService.searchProducts(any(), any(), any(), anyString(), anyInt(), anyInt())).thenReturn(Page.empty());

        client.sendRequest(withPayload(new StringSource("<GetProductsRequest xmlns=\"urn:foodsndrinks:store\"/>")))
                .andExpect(noFault())
                .andExpect(xpath("count(/s:GetProductsResponse/s:product)", NS).evaluatesTo(0))
                .andExpect(xpath("/s:GetProductsResponse/s:totalElements", NS).evaluatesTo(0));

        // "true" = chỉ sản phẩm đang bán, như API /api/v1/products
        verify(productService).searchProducts(null, null, null, "true", 0, StoreEndpoint.DEFAULT_PAGE_SIZE);
    }

    @Test
    @WithMockUser(username = "partner", roles = "USER")
    void getProducts_pageSizeOverTheLimit_isRejectedByTheContract_beforeReachingTheService() {
        for (String invalid : List.of(
                "<size>101</size>", "<size>0</size>", "<page>-1</page>", "<categoryId>0</categoryId>",
                "<size>abc</size>", "<unknown>1</unknown>")) {
            client.sendRequest(withPayload(new StringSource(
                            "<GetProductsRequest xmlns=\"urn:foodsndrinks:store\">" + invalid + "</GetProductsRequest>")))
                    .andExpect(clientOrSenderFault(WebServiceConfig.FAULT_VALIDATION))
                    // Không kèm chi tiết của parser (lặp lại giá trị client gửi)
                    .andExpect(xpath("count(//detail/*)").evaluatesTo(0));
        }
        verifyNoInteractions(productService);
    }

    // -------------------------------------------------------
    // GetOrderStatus
    // -------------------------------------------------------

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void getOrderStatus_asOrderOwner_asksTheServiceWithTheCallersUsername_notAsAdmin() {
        when(orderService.getPlacedOrderStatus(42L, "alice", false)).thenReturn(OrderStatus.PROCESSING);

        client.sendRequest(withPayload(orderStatusRequest("42")))
                .andExpect(noFault())
                .andExpect(xpath("/s:GetOrderStatusResponse/s:orderId", NS).evaluatesTo(42))
                .andExpect(xpath("/s:GetOrderStatusResponse/s:status", NS).evaluatesTo("PROCESSING"))
                .andExpect(xpath("/s:GetOrderStatusResponse/s:statusLabel", NS).evaluatesTo("Đang xử lý"));
    }

    @Test
    @WithMockUser(username = "boss", roles = "ADMIN")
    void getOrderStatus_asAdmin_mayReadAnyOrder() {
        when(orderService.getPlacedOrderStatus(42L, "boss", true)).thenReturn(OrderStatus.CANCELLED);

        client.sendRequest(withPayload(orderStatusRequest("42")))
                .andExpect(noFault())
                .andExpect(xpath("/s:GetOrderStatusResponse/s:status", NS).evaluatesTo("CANCELLED"));

        verify(orderService).getPlacedOrderStatus(42L, "boss", true);
    }

    @Test
    @WithMockUser(username = "mallory", roles = "USER")
    void getOrderStatus_ofSomeoneElsesOrMissingOrder_isAFixedClientFault_thatSaysNothingAboutTheOrder() {
        // Service ném cùng một exception cho "không tồn tại", "của người khác" và "là giỏ hàng" (OrderServiceTest)
        when(orderService.getPlacedOrderStatus(42L, "mallory", false))
                .thenThrow(new ResourceNotFoundException("Không tìm thấy đơn hàng #42"));

        client.sendRequest(withPayload(orderStatusRequest("42")))
                .andExpect(clientOrSenderFault(WebServiceConfig.FAULT_NOT_FOUND))
                // Thông điệp của exception (có mã đơn) không nằm trong fault
                .andExpect(xpath("count(//*[contains(text(), '42')])").evaluatesTo(0))
                .andExpect(xpath("count(//detail/*)").evaluatesTo(0));
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void getOrderStatus_invalidOrderId_isRejectedByTheContract() {
        for (String invalid : List.of("0", "-5", "abc", "")) {
            client.sendRequest(withPayload(orderStatusRequest(invalid)))
                    .andExpect(clientOrSenderFault(WebServiceConfig.FAULT_VALIDATION));
        }
        verifyNoInteractions(orderService);
    }

    @Test
    @WithMockUser(username = "alice", roles = "USER")
    void unexpectedFailure_isAGenericServerFault_withoutTheExceptionMessage() {
        when(orderService.getPlacedOrderStatus(anyLong(), anyString(), anyBoolean()))
                .thenThrow(new IllegalStateException("Access denied for user 'app'@'10.0.0.5' to database 'foodsndrinks'"));

        client.sendRequest(withPayload(orderStatusRequest("42")))
                .andExpect(serverOrReceiverFault(WebServiceConfig.FAULT_INTERNAL))
                .andExpect(xpath("count(//*[contains(text(), 'Access denied') or contains(text(), 'foodsndrinks')])")
                        .evaluatesTo(0))
                .andExpect(xpath("count(//detail/*)").evaluatesTo(0));
    }

    private static StringSource orderStatusRequest(String orderId) {
        return new StringSource("<GetOrderStatusRequest xmlns=\"urn:foodsndrinks:store\"><orderId>" + orderId
                + "</orderId></GetOrderStatusRequest>");
    }

    private static Category category(String name) {
        Category category = new Category();
        category.setId(3L);
        category.setName(name);
        return category;
    }

    private static ProductResDto product(Long id, String name, String slug, Category category, ProductType type,
                                         String price) {
        Product product = new Product();
        product.setId(id);
        product.setName(name);
        product.setSlug(slug);
        product.setCategory(category);
        product.setType(type);
        product.setPrice(new BigDecimal(price));
        product.setIsAvailable(true);
        return ProductResDto.forList(product, null);
    }
}
