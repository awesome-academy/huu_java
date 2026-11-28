package com.huuhv.foodsndrinks.controller.admin;

import com.huuhv.foodsndrinks.service.excel.ExcelFile;
import com.huuhv.foodsndrinks.service.excel.ExcelSheetWriter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;

import java.io.IOException;

/** Ghi một {@link ExcelFile} đã dựng xong ra response dưới dạng file tải về ({@code Content-Disposition: attachment}). */
final class ExcelDownload {

    private ExcelDownload() {
    }

    /** Ghi thẳng vào response rồi đóng {@code file} (xoá file tạm), kể cả khi client ngắt kết nối giữa chừng. */
    static void write(ExcelFile file, HttpServletResponse response) throws IOException {
        try (file) {
            response.setContentType(ExcelSheetWriter.XLSX_CONTENT_TYPE);
            // Tên file do server đặt (ASCII) — không bao giờ lấy từ dữ liệu người dùng
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename(file.filename()).build().toString());
            file.writeTo(response.getOutputStream());
        }
    }
}
