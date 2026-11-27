package com.huuhv.foodsndrinks.dto.request;

/**
 * Một thao tác ghi của import Excel sau khi dòng đã qua validate: thêm sản phẩm mới hoặc cập nhật sản phẩm đã có.
 *
 * @param productId id sản phẩm cần cập nhật; {@code null} = thêm mới
 * @param product   dữ liệu của dòng, đã validate như form thêm/sửa sản phẩm
 */
public record ProductImportCommand(Long productId, ProductReqDto product) {

    public boolean isCreate() {
        return productId == null;
    }
}
