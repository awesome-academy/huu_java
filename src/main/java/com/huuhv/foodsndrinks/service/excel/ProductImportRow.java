package com.huuhv.foodsndrinks.service.excel;

/**
 * Một dòng dữ liệu đọc từ file import, CHƯA validate: mọi ô ở dạng chuỗi thô (ô trống = {@code null}; ô số là dạng số
 * không định dạng, vd. {@code 35000}).
 *
 * @param rowNumber số dòng như Excel hiển thị (dòng tiêu đề là 1)
 */
record ProductImportRow(int rowNumber, String name, String category, String type, String price, String description,
                        String available) {
}
