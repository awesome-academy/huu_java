package com.huuhv.foodsndrinks.exception;

/**
 * Không lấy được tỷ giá từ dịch vụ SOAP bên ngoài (không kết nối được, quá thời gian, SOAP Fault, sai tài khoản, hoặc
 * response không đúng hợp đồng).
 * <p>
 * Cố ý KHÔNG giữ cause và không lặp lại thông điệp gốc: thông điệp của lỗi phía client SOAP có thể chứa fault string, đoạn
 * XML hay header do máy chủ bên kia trả về — dữ liệu ngoài không được đi vào log của app. {@link #getMessage()} chỉ là mã
 * lý do do app tự đặt (vd. {@code TIMEOUT_OR_IO}, {@code SOAP_FAULT}).
 */
public class ExchangeRateUnavailableException extends RuntimeException {
    public ExchangeRateUnavailableException(String reason) {
        super(reason);
    }
}
