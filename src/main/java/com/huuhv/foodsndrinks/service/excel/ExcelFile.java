package com.huuhv.foodsndrinks.service.excel;

import java.io.IOException;
import java.io.OutputStream;

/**
 * File .xlsx đã dựng xong, chờ ghi ra response. Service dựng xong toàn bộ nội dung rồi mới trả về, nên mọi lỗi nghiệp vụ
 * (bộ lọc sai, vượt giới hạn dòng) xảy ra trước khi controller đụng tới response. Luôn đóng (try-with-resources) để xoá
 * file tạm.
 *
 * @param filename tên file gợi ý cho trình duyệt ({@code Content-Disposition})
 * @param rowCount số dòng dữ liệu trong file
 */
public record ExcelFile(String filename, int rowCount, ExcelSheetWriter content) implements AutoCloseable {

    public void writeTo(OutputStream out) throws IOException {
        content.writeTo(out);
    }

    @Override
    public void close() {
        content.close();
    }
}
