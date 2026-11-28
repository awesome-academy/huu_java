package com.huuhv.foodsndrinks.controller.soap;

import com.huuhv.foodsndrinks.security.RequestBodySizeLimitFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.ws.InvalidXmlException;
import org.springframework.ws.soap.SoapMessageCreationException;
import org.springframework.ws.transport.http.WebServiceMessageReceiverHandlerAdapter;

/**
 * Adapter nhận request SOAP của {@code MessageDispatcherServlet}, thêm xử lý cho request KHÔNG đọc được thành message
 * SOAP: XML hỏng, không phải SOAP envelope, có {@code <!DOCTYPE>} (SAAJ từ chối DTD — chống XXE / "billion laughs"), hoặc
 * body bị cắt vì quá lớn ({@link RequestBodySizeLimitFilter}).
 * <p>
 * Adapter gốc để các lỗi này thoát ra ngoài: client nhận 500 kèm trang lỗi, log đầy stack trace cho mỗi request rác.
 * Ở đây: 400 (hoặc 413), response KHÔNG có body, một dòng WARN chỉ ghi loại lỗi — không ghi thông điệp của parser, vốn
 * lặp lại nội dung request.
 * <p>
 * Lỗi nghiệp vụ/validate theo XSD của một message SOAP hợp lệ không đi qua đây: chúng thành SOAP Fault
 * ({@code config/WebServiceConfig}).
 */
@Slf4j
public class SoapMessageReceiverAdapter extends WebServiceMessageReceiverHandlerAdapter {

    @Override
    public ModelAndView handle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        try {
            return super.handle(request, response, handler);
        } catch (SoapMessageCreationException | InvalidXmlException ex) {
            reject(request, response, ex);
            return null;
        }
    }

    /** Adapter gốc chỉ đặt 400 cho {@link InvalidXmlException}; gom về một chỗ để có cả 413 và log. */
    @Override
    protected void handleInvalidXmlException(HttpServletRequest request, HttpServletResponse response, Object handler,
                                             InvalidXmlException ex) {
        reject(request, response, ex);
    }

    private static void reject(HttpServletRequest request, HttpServletResponse response, Exception ex) {
        boolean tooLarge = RequestBodySizeLimitFilter.isBodyTooLarge(request);
        HttpStatus status = tooLarge ? HttpStatus.CONTENT_TOO_LARGE : HttpStatus.BAD_REQUEST;
        log.warn("Rejected SOAP request with {}: {}", status.value(),
                tooLarge ? "body too large" : ex.getClass().getSimpleName());
        if (!response.isCommitted()) {
            response.resetBuffer();
            response.setStatus(status.value());
        }
    }
}
