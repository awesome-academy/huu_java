package com.huuhv.foodsndrinks.dto.request;

/**
 * Một thao tác ghi của import Excel sau khi dòng đã qua validate: thêm sản phẩm mới hoặc cập nhật sản phẩm đã có.
 *
 * @param row       số dòng trong file Excel (để báo lỗi phát hiện lúc ghi về đúng dòng)
 * @param productId id sản phẩm cần cập nhật; {@code null} = thêm mới
 * @param product   dữ liệu của dòng, đã validate như form thêm/sửa sản phẩm
 */
public record ProductImportCommand(int row, Long productId, ProductReqDto product) {

    public boolean isCreate() {
        return productId == null;
    }
}
