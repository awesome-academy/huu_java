package com.huuhv.foodsndrinks.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** {@link RequestBodySizeLimitFilter}: chặn body quá lớn của request SOAP, có và không có {@code Content-Length}. */
class RequestBodySizeLimitFilterTest {

    private static final int LIMIT = 16;

    private final RequestBodySizeLimitFilter filter = new RequestBodySizeLimitFilter(LIMIT);
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void declaredContentLengthOverTheLimit_is413_withoutReadingTheBody_orCallingTheApp() throws Exception {
        MockHttpServletRequest request = post(new byte[LIMIT + 1]);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).isEmpty();
        verifyNoInteractions(chain);
    }

    @Test
    void bodyWithinTheLimit_isReadUnchanged() throws Exception {
        byte[] body = "0123456789abcdef".getBytes();   // đúng LIMIT byte
        AtomicReference<byte[]> read = new AtomicReference<>();

        filter.doFilter(post(body), response, (req, res) -> read.set(req.getInputStream().readAllBytes()));

        assertThat(read.get()).isEqualTo(body);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void bodyWithoutContentLength_isCutOffWhileReading_andTheRequestIsMarked() throws Exception {
        // Chunked: không biết trước kích thước → Content-Length = -1
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ws") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(new byte[LIMIT * 4]);
        AtomicReference<HttpServletRequest> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> {
            seen.set((HttpServletRequest) req);
            assertThatThrownBy(() -> req.getInputStream().readAllBytes())
                    .isInstanceOf(RequestBodySizeLimitFilter.BodyTooLargeException.class)
                    // IOException: nơi đọc (SAAJ) coi là lỗi đọc, không thành 500
                    .isInstanceOf(IOException.class);
        });

        // Dấu để SoapMessageReceiverAdapter trả 413 thay vì 400 (SAAJ không giữ cause của lỗi đọc)
        assertThat(RequestBodySizeLimitFilter.isBodyTooLarge(request)).isTrue();
        assertThat(RequestBodySizeLimitFilter.isBodyTooLarge(seen.get())).isTrue();
    }

    @Test
    void requestThatWasNotCutOff_isNotMarked() throws Exception {
        MockHttpServletRequest request = post("ok".getBytes());

        filter.doFilter(request, response, (req, res) -> req.getInputStream().readAllBytes());

        assertThat(RequestBodySizeLimitFilter.isBodyTooLarge(request)).isFalse();
    }

    @Test
    void readerWithUnknownClientCharset_isAReadError_notARuntimeException() throws Exception {
        MockHttpServletRequest request = post("ok".getBytes());
        request.setCharacterEncoding("no-such-charset-\u0000");

        filter.doFilter(request, response, (req, res) ->
                assertThatThrownBy(req::getReader).isInstanceOf(UnsupportedEncodingException.class)
                        // Không lặp lại tên charset client gửi
                        .hasMessage("Unsupported request character encoding"));
    }

    @Test
    void reader_isTheSameObjectOnEveryCall_soBufferedBytesAreNotLost() throws Exception {
        byte[] body = "x".repeat(20_000).getBytes(StandardCharsets.ISO_8859_1);
        AtomicReference<String> read = new AtomicReference<>();

        new RequestBodySizeLimitFilter(body.length).doFilter(post(body), response, (req, res) -> {
            // Lần gọi đầu đọc trước một khối vào buffer; lần gọi sau phải đọc tiếp từ đúng chỗ đó
            assertThat(req.getReader().read()).isEqualTo('x');
            assertThat(req.getReader()).isSameAs(req.getReader());
            read.set("x" + req.getReader().lines().collect(Collectors.joining()));
        });

        assertThat(read.get()).hasSize(body.length);
    }

    @Test
    void streamAndReader_cannotBothBeUsed() throws Exception {
        filter.doFilter(post("ok".getBytes()), response, (req, res) -> {
            req.getInputStream();
            assertThatIllegalStateException().isThrownBy(req::getReader);
        });
        filter.doFilter(post("ok".getBytes()), response, (req, res) -> {
            req.getReader();
            assertThatIllegalStateException().isThrownBy(req::getInputStream);
        });
    }

    @Test
    void limitMustBePositive() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RequestBodySizeLimitFilter(0));
    }

    private static MockHttpServletRequest post(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/ws");
        request.setContent(body);
        return request;
    }
}
