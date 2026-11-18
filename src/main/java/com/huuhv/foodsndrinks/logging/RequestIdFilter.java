package com.huuhv.foodsndrinks.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
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
 * Nhận {@value #HEADER} của client/proxy nếu đúng định dạng (chữ, số, {@code -}, tối đa 64 ký tự), ngược lại
 * sinh UUID mới — giá trị sai định dạng có thể chứa ký tự điều khiển dùng để giả mạo log nên không bao giờ được dùng.
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
    static final String REQUEST_ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";

    private static final Pattern VALID_REQUEST_ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        request.setAttribute(REQUEST_ATTRIBUTE, requestId);
        if (!response.isCommitted()) {
            response.setHeader(HEADER, requestId);
        }

        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            restore(previous);
        }
    }

    private static String resolveRequestId(HttpServletRequest request) {
        if (request.getAttribute(REQUEST_ATTRIBUTE) instanceof String existing) {
            return existing;
        }
        String header = request.getHeader(HEADER);
        if (header != null && VALID_REQUEST_ID.matcher(header).matches()) {
            return header;
        }
        return UUID.randomUUID().toString();
    }

    private static void restore(String previous) {
        if (previous == null) {
            MDC.remove(MDC_KEY);
        } else {
            MDC.put(MDC_KEY, previous);
        }
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
