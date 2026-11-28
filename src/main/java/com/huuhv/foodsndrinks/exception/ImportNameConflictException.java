package com.huuhv.foodsndrinks.exception;

import lombok.Getter;

/**
 * Lúc ghi một file import, hai dòng THÊM MỚI hoá ra là cùng một tên theo collation của MySQL (vd. khác nhau ở ký tự ẩn
 * U+200B, "ß"/"ss", hiragana/katakana) — bước kiểm tra trùng trong file chỉ xấp xỉ collation nên không bắt được. Là lỗi
 * của nội dung file: ghi lại bao nhiêu lần cũng trùng như vậy, nên lần import phải kết thúc với lỗi ở dòng {@link #row},
 * không được giao lại. Thông điệp không chứa tên sản phẩm.
 */
@Getter
public class ImportNameConflictException extends RuntimeException {

    /** Dòng bị từ chối. */
    private final int row;
    /** Dòng đứng trước trong cùng file đã tạo sản phẩm mang tên "trùng". */
    private final int conflictingRow;

    public ImportNameConflictException(int row, int conflictingRow, Throwable cause) {
        super("Import row " + row + " has the same name as row " + conflictingRow, cause);
        this.row = row;
        this.conflictingRow = conflictingRow;
    }
}
