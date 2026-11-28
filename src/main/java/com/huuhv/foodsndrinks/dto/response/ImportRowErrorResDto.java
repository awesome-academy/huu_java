package com.huuhv.foodsndrinks.dto.response;

/**
 * Một lỗi của file import Excel, đủ để admin tìm đúng ô cần sửa.
 *
 * @param row     số dòng như Excel hiển thị (dòng tiêu đề là 1)
 * @param column  tên cột trong file (vd. "Giá")
 * @param message nội dung lỗi — câu cố định, không lặp lại giá trị của ô
 */
public record ImportRowErrorResDto(int row, String column, String message) {
}
