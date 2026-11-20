package com.huuhv.foodsndrinks.controller.admin;

import com.huuhv.foodsndrinks.enums.CategoryType;
import com.huuhv.foodsndrinks.enums.OrderStatus;
import com.huuhv.foodsndrinks.enums.ProductType;
import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.enums.SuggestionStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice(basePackages = "com.huuhv.foodsndrinks.controller.admin")
public class AdminLayoutModelAdvice {

    private final long maxUploadFileSize;

    public AdminLayoutModelAdvice(@Value("${spring.servlet.multipart.max-file-size}") DataSize maxUploadFileSize) {
        this.maxUploadFileSize = maxUploadFileSize.toBytes();
    }

    /** Giới hạn 1 file upload (byte) cho {@code data-max-file-size} của input file — kiểm tra sớm phía client. */
    @ModelAttribute("maxUploadFileSize")
    public long maxUploadFileSize() {
        return maxUploadFileSize;
    }

    @ModelAttribute("currentPath")
    public String currentPath(HttpServletRequest request) {
        return request == null ? "" : request.getRequestURI();
    }

    @ModelAttribute("categoryTypes")
    public CategoryType[] categoryTypes() { return CategoryType.values(); }

    @ModelAttribute("productTypes")
    public ProductType[] productTypes() { return ProductType.values(); }

    @ModelAttribute("orderStatuses")
    public OrderStatus[] orderStatuses() { return OrderStatus.values(); }

    @ModelAttribute("roles")
    public Role[] roles() { return Role.values(); }

    @ModelAttribute("suggestionStatuses")
    public SuggestionStatus[] suggestionStatuses() { return SuggestionStatus.values(); }
}
