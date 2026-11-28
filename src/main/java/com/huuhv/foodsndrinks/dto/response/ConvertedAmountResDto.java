package com.huuhv.foodsndrinks.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Một số tiền VND đã quy đổi sang ngoại tệ bằng tỷ giá lấy từ dịch vụ SOAP (thẻ "Doanh thu" của dashboard admin).
 *
 * @param amount        số tiền sau quy đổi, làm tròn 2 chữ số thập phân
 * @param currency      mã ngoại tệ, vd. {@code USD}
 * @param rate          tỷ giá đã dùng: 1 {@code currency} = {@code rate} VND
 * @param rateFetchedAt lúc app lấy được tỷ giá này (giờ của máy chủ) — để người xem biết con số cũ hay mới
 */
public record ConvertedAmountResDto(BigDecimal amount, String currency, BigDecimal rate, LocalDateTime rateFetchedAt) {
}
