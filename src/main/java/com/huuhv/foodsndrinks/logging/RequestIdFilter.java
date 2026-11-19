package com.huuhv.foodsndrinks.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gắn một {@code requestId} cho mỗi request để truy vết log: đặt vào MDC (pattern log in {@code %X{requestId}})
 * và trả lại trong response header {@value #HEADER}.
 * <p>
 * {@code requestId} luôn do server sinh (UUID), không lấy từ client: nếu nhận nguyên header của client, kẻ tấn công
 * có thể gửi trùng id của một request khác (vd. của admin) để làm rối việc truy vết theo requestId trong audit log.
 * Header {@value #HEADER} của client/proxy, nếu đúng định dạng (chữ, số, {@code -}, tối đa 64 ký tự), được ghi vào MDC
 * riêng {@value #CLIENT_MDC_KEY} (in thành {@code cid=...}) để vẫn nối được log phía client với log server; giá trị sai
 * định dạng bị bỏ qua.
 * <p>
 * Chạy sớm nhất trong chuỗi servlet filter (trước Spring Security) để log của cả security cũng có requestId.
 * Lỗi được Tomcat dispatch lại tới {@code /error} sau khi request gốc đã kết thúc, nên filter cũng chạy ở
 * dispatch ERROR/ASYNC và dùng lại id đã lưu trong request attribute thay vì sinh id mới.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    public static final String CLIENT_MDC_KEY = "clientRequestId";
    static final String REQUEST_ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
    static final String CLIENT_REQUEST_ATTRIBUTE = RequestIdFilter.class.getName() + ".clientRequestId";

    private static final Pattern VALID_CLIENT_REQUEST_ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getAttribute(REQUEST_ATTRIBUTE) instanceof String existing
                ? existing : UUID.randomUUID().toString();
        String clientRequestId = request.getAttribute(CLIENT_REQUEST_ATTRIBUTE) instanceof String existing
                ? existing : validClientRequestId(request.getHeader(HEADER));

        request.setAttribute(REQUEST_ATTRIBUTE, requestId);
        if (clientRequestId != null) {
            request.setAttribute(CLIENT_REQUEST_ATTRIBUTE, clientRequestId);
        }
        if (!response.isCommitted()) {
            response.setHeader(HEADER, requestId);
        }

        try (MdcScope ignored = MdcScope.open().put(MDC_KEY, requestId).put(CLIENT_MDC_KEY, clientRequestId)) {
            chain.doFilter(request, response);
        }
    }

    private static String validClientRequestId(String header) {
        return header != null && VALID_CLIENT_REQUEST_ID.matcher(header).matches() ? header : null;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
