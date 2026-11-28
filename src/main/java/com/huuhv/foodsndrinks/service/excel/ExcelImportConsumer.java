package com.huuhv.foodsndrinks.service.excel;

import com.huuhv.foodsndrinks.dto.message.ExcelImportMessage;
import com.huuhv.foodsndrinks.service.messaging.Destinations;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumer của QUEUE {@code excel.import} (point-to-point): mỗi message "xử lý lần import N" tới đúng một consumer — khi
 * chạy nhiều instance, các instance chia nhau việc. Dùng listener factory kiểu queue mặc định
 * ({@code jmsListenerContainerFactory}, session transacted): xử lý xong mới ack; exception thoát ra → rollback → broker
 * giao lại theo {@code artemis/broker.properties}, quá số lần thì vào {@code DLQ.excel-import}.
 * <p>
 * Đặt ở {@code service.excel} chứ không ở {@code service.messaging}: {@code service} đã phụ thuộc
 * {@code service.messaging} (outbox), đặt ngược lại sẽ tạo vòng phụ thuộc giữa package.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExcelImportConsumer {

    private final ImportJobProcessor processor;
    private final Validator validator;

    // Một thread mỗi instance: mỗi lần import giữ 1 connection DB lúc ghi → tính 1 trong ngân sách connection
    // (ConnectionBudgetCheck); không khai báo thì kế thừa max-concurrency 3 của factory
    @JmsListener(destination = Destinations.EXCEL_IMPORT, concurrency = "1")
    public void onImportRequested(ExcelImportMessage message,
                                  @Header(name = "JMSXDeliveryCount", required = false) Integer deliveryCount) {
        if (message == null || !validator.validate(message).isEmpty()) {
            // Giao lại cũng không đọc được → bỏ (ack). Lần import tương ứng, nếu có, được job dọn dẹp đánh dấu thất bại
            log.warn("Dropped {} message without a valid importJobId", Destinations.EXCEL_IMPORT);
            return;
        }
        try {
            processor.process(message.importJobId());
        } catch (RuntimeException ex) {
            // Stack trace do JmsListenerErrorHandler ghi; ở đây chỉ thêm ngữ cảnh rồi ném lại để broker giao lại
            log.warn("Import job #{} failed on delivery {} and will be redelivered (dead-lettered to {} after the last "
                    + "attempt)", message.importJobId(), deliveryCount, Destinations.EXCEL_IMPORT_DLQ);
            throw ex;
        }
    }
}
