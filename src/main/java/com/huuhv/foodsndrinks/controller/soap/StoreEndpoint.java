package com.huuhv.foodsndrinks.controller.soap;

import com.huuhv.foodsndrinks.dto.response.ProductResDto;
import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.service.OrderService;
import com.huuhv.foodsndrinks.service.ProductService;
import com.huuhv.foodsndrinks.soap.store.GetOrderStatusRequest;
import com.huuhv.foodsndrinks.soap.store.GetOrderStatusResponse;
import com.huuhv.foodsndrinks.soap.store.GetProductsRequest;
import com.huuhv.foodsndrinks.soap.store.GetProductsResponse;
import com.huuhv.foodsndrinks.soap.store.OrderStatus;
import com.huuhv.foodsndrinks.soap.store.Product;
import com.huuhv.foodsndrinks.soap.store.ProductType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;

/**
 * SOAP service cho hệ thống đối tác (hợp đồng {@code xsd/foodsndrinks.xsd}, WSDL {@code /ws/foodsndrinks.wsdl}). Mọi
 * operation cần HTTP Basic bằng tài khoản của app (filter chain {@code /ws/**} trong {@code SecurityConfig}).
 * <p>
 * Endpoint chỉ chuyển đổi giữa class JAXB sinh từ XSD ({@code soap.store}) và service — không có nghiệp vụ, không trả
 * entity. Request đã được validate theo XSD trước khi tới đây; lỗi thành SOAP Fault ({@code config/WebServiceConfig}).
 */
@Endpoint
@RequiredArgsConstructor
public class StoreEndpoint {

    /** Target namespace của {@code xsd/foodsndrinks.xsd}. */
    public static final String NAMESPACE = "urn:foodsndrinks:store";

    static final int DEFAULT_PAGE_SIZE = 10;
    static final int MAX_PAGE_SIZE = 100;

    private final ProductService productService;
    private final OrderService orderService;

    /** Sản phẩm ĐANG BÁN, lọc theo danh mục, phân trang (size 1..100 — XSD đã chặn, ở đây kẹp lại lần nữa). */
    @PayloadRoot(namespace = NAMESPACE, localPart = "GetProductsRequest")
    @ResponsePayload
    public GetProductsResponse getProducts(@RequestPayload GetProductsRequest request) {
        int page = request.getPage() == null ? 0 : Math.max(request.getPage(), 0);
        int size = Math.clamp(request.getSize() == null ? DEFAULT_PAGE_SIZE : request.getSize(), 1, MAX_PAGE_SIZE);

        Page<ProductResDto> products =
                productService.searchProducts(null, request.getCategoryId(), null, "true", page, size);

        GetProductsResponse response = new GetProductsResponse();
        products.forEach(product -> response.getProduct().add(toProduct(product)));
        response.setPage(products.getNumber());
        response.setSize(products.getSize());
        response.setTotalElements(products.getTotalElements());
        response.setTotalPages(products.getTotalPages());
        return response;
    }

    /**
     * Trạng thái một đơn: chủ đơn xem đơn của mình, {@code ROLE_ADMIN} xem mọi đơn. Đơn của người khác được báo y hệt đơn
     * không tồn tại ({@code OrderService.getPlacedOrderStatus} — chống IDOR).
     */
    @PayloadRoot(namespace = NAMESPACE, localPart = "GetOrderStatusRequest")
    @ResponsePayload
    public GetOrderStatusResponse getOrderStatus(@RequestPayload GetOrderStatusRequest request) {
        Authentication caller = SecurityContextHolder.getContext().getAuthentication();
        boolean admin = caller.getAuthorities().stream()
                .anyMatch(authority -> Role.ROLE_ADMIN.name().equals(authority.getAuthority()));

        com.huuhv.foodsndrinks.enums.OrderStatus status =
                orderService.getPlacedOrderStatus(request.getOrderId(), caller.getName(), admin);

        GetOrderStatusResponse response = new GetOrderStatusResponse();
        response.setOrderId(request.getOrderId());
        response.setStatus(OrderStatus.valueOf(status.name()));
        response.setStatusLabel(status.getLabel());
        return response;
    }

    private static Product toProduct(ProductResDto dto) {
        Product product = new Product();
        product.setId(dto.getId());
        product.setName(dto.getName());
        product.setSlug(dto.getSlug());
        if (dto.getCategoryId() != null) {
            product.setCategory(dto.getCategoryName());
        }
        product.setType(ProductType.valueOf(dto.getType().name()));
        product.setPrice(dto.getPrice());
        return product;
    }
}
