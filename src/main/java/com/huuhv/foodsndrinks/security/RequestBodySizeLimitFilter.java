package com.huuhv.foodsndrinks.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;

/**
 * Giới hạn kích thước body của request (filter chain SOAP {@code /ws/**}). Tomcat chỉ giới hạn body của form
 * ({@code max-http-form-post-size}) và multipart — XML gửi tới {@code MessageDispatcherServlet} thì không.
 * <ul>
 *   <li>Có {@code Content-Length} vượt giới hạn → 413 ngay, không đọc byte nào.</li>
 *   <li>Không có {@code Content-Length} (chunked): body được đọc qua stream đếm byte; vượt giới hạn thì stream ném
 *       {@link BodyTooLargeException} và đánh dấu request ({@link #isBodyTooLarge}) — nơi đọc body (SAAJ) bọc lỗi đọc
 *       thành lỗi "XML hỏng" mà không giữ cause, nên {@code controller/soap/SoapMessageReceiverAdapter} nhìn dấu này để trả
 *       413 thay vì 400.</li>
 * </ul>
 * Response không có body và log không chứa nội dung request. Không phải {@code @Component}: tạo trong
 * {@code SecurityConfig} để chỉ chạy trong chain của nó.
 */
public class RequestBodySizeLimitFilter extends OncePerRequestFilter {

    private static final String TOO_LARGE_ATTRIBUTE = RequestBodySizeLimitFilter.class.getName() + ".TOO_LARGE";

    private final long maxBytes;

    public RequestBodySizeLimitFilter(long maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
    }

    /** Body của request đã bị cắt vì vượt giới hạn (chỉ có nghĩa sau khi có nơi đọc body). */
    public static boolean isBodyTooLarge(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(TOO_LARGE_ATTRIBUTE));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
            return;
        }
        filterChain.doFilter(new LimitedRequest(request, maxBytes), response);
    }

    /** Body vượt giới hạn — {@link IOException} để nơi đọc xử lý như mọi lỗi đọc khác (không thành 500). */
    public static class BodyTooLargeException extends IOException {
        BodyTooLargeException() {
            super("Request body exceeds the allowed size");
        }
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long maxBytes;
        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedStream(super.getInputStream(), maxBytes, (HttpServletRequest) getRequest());
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            return new BufferedReader(new InputStreamReader(getInputStream(), charset()));
        }

        /** Charset do client khai báo: tên sai/không hỗ trợ là lỗi đọc (IOException) như Tomcat, không phải 500. */
        private Charset charset() throws IOException {
            String encoding = getCharacterEncoding();
            if (encoding == null) {
                return StandardCharsets.ISO_8859_1;
            }
            try {
                return Charset.forName(encoding);
            } catch (IllegalCharsetNameException | UnsupportedCharsetException ex) {
                throw new java.io.UnsupportedEncodingException("Unsupported request character encoding");
            }
        }
    }

    private static final class LimitedStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBytes;
        private final HttpServletRequest request;
        private long read;

        LimitedStream(ServletInputStream delegate, long maxBytes, HttpServletRequest request) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
            this.request = request;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, length);
            if (count > 0) {
                count(count);
            }
            return count;
        }

        private void count(int bytes) throws IOException {
            read += bytes;
            if (read > maxBytes) {
                request.setAttribute(TOO_LARGE_ATTRIBUTE, Boolean.TRUE);
                throw new BodyTooLargeException();
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
