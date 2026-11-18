package com.huuhv.foodsndrinks.logging;

import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter;

import java.util.Arrays;

/**
 * {@code %safeEx} trong logback-spring.xml: in stack trace như {@code %wEx} của Spring Boot, nhưng làm sạch
 * message của exception (và của cause/suppressed).
 * <p>
 * Message exception thường chứa dữ liệu người dùng, vd. {@code MethodArgumentTypeMismatchException} với giá trị
 * {@code "1\r\n<dòng log giả>"} lấy từ URL. Nếu in nguyên, CR/LF trong đó tạo ra dòng log giả. Ký tự xuống dòng
 * giữa các frame của stack trace vẫn giữ nguyên (do chính converter sinh ra, không phải dữ liệu người dùng).
 */
public class SafeThrowableProxyConverter extends ExtendedWhitespaceThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(IThrowableProxy tp) {
        return super.throwableProxyToString(SanitizedThrowableProxy.wrap(tp));
    }

    /** Bọc {@link IThrowableProxy}: chỉ đổi message, mọi thứ khác (class, frame, cause) ủy quyền cho bản gốc. */
    record SanitizedThrowableProxy(IThrowableProxy delegate) implements IThrowableProxy {

        static IThrowableProxy wrap(IThrowableProxy tp) {
            if (tp == null || tp instanceof SanitizedThrowableProxy) {
                return tp;
            }
            return new SanitizedThrowableProxy(tp);
        }

        @Override
        public String getMessage() {
            return LogSanitizer.stripLineBreaks(delegate.getMessage());
        }

        @Override
        public String getOverridingMessage() {
            return LogSanitizer.stripLineBreaks(delegate.getOverridingMessage());
        }

        @Override
        public String getClassName() {
            return delegate.getClassName();
        }

        @Override
        public StackTraceElementProxy[] getStackTraceElementProxyArray() {
            return delegate.getStackTraceElementProxyArray();
        }

        @Override
        public int getCommonFrames() {
            return delegate.getCommonFrames();
        }

        @Override
        public IThrowableProxy getCause() {
            return wrap(delegate.getCause());
        }

        @Override
        public IThrowableProxy[] getSuppressed() {
            IThrowableProxy[] suppressed = delegate.getSuppressed();
            return suppressed == null ? null
                    : Arrays.stream(suppressed).map(SanitizedThrowableProxy::wrap).toArray(IThrowableProxy[]::new);
        }

        @Override
        public boolean isCyclic() {
            return delegate.isCyclic();
        }
    }
}
