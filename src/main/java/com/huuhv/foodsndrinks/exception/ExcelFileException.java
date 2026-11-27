package com.huuhv.foodsndrinks.exception;

/**
 * File Excel import bị từ chối CẢ FILE (không phải lỗi của một dòng): không phải .xlsx, quá lớn, sai mẫu, quá nhiều dòng,
 * không đọc được... Thông điệp là câu hiển thị thẳng cho admin — không chứa nội dung file hay thông điệp của thư viện.
 */
public class ExcelFileException extends RuntimeException {

    public ExcelFileException(String message) {
        super(message);
    }

    public ExcelFileException(String message, Throwable cause) {
        super(message, cause);
    }
}
