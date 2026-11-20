package com.huuhv.foodsndrinks.exception;

import com.huuhv.foodsndrinks.dto.response.ErrorResponse;
import com.huuhv.foodsndrinks.security.LoginThrottledException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@Slf4j
// Chỉ bắt lỗi phát sinh từ thư mục api.
// @RestControllerAdvice tự động chuyển output thành JSON
@RestControllerAdvice(basePackages = "com.huuhv.foodsndrinks.controller.api")
public class ApiExceptionHandler {

    private static final String BAD_CREDENTIALS_MESSAGE = "Tên đăng nhập, email hoặc mật khẩu không chính xác.";
    private static final String LOGIN_THROTTLED_MESSAGE = "Bạn đã đăng nhập sai quá nhiều lần. Vui lòng thử lại sau.";

    // Handle 404 Not found
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFoundException(ResourceNotFoundException ex, HttpServletRequest request) {
        log.warn("Resource not found at {}: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    // Handle 400 - lỗi nghiệp vụ (dữ liệu đầu vào không hợp lệ)
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException ex, HttpServletRequest request) {
        log.warn("Bad request at {}: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    // Handle 400 - lỗi validation của @Valid (trả về map field -> message)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationExceptions(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> validationErrors = new HashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            validationErrors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        log.warn("Validation error at {}: {}", request.getRequestURI(), validationErrors);
        return buildErrorResponse(HttpStatus.BAD_REQUEST, validationErrors, request);
    }

    // Handle 409 Conflict (e.g., duplicate resource)
    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<ErrorResponse> handleResourceConflictException(DuplicateResourceException ex, HttpServletRequest request) {
        log.warn("Resource conflict at {}: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    // Handle 429 - sai mật khẩu quá nhiều lần, tên đăng nhập đang bị tạm khoá (kể cả tên không tồn tại)
    @ExceptionHandler(LoginThrottledException.class)
    public ResponseEntity<ErrorResponse> handleLoginThrottled(LoginThrottledException ex, HttpServletRequest request) {
        log.warn("Login throttled at {}", request.getRequestURI());
        ResponseEntity<ErrorResponse> body = buildErrorResponse(HttpStatus.TOO_MANY_REQUESTS, LOGIN_THROTTLED_MESSAGE, request);
        return ResponseEntity.status(body.getStatusCode())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(body.getBody());
    }

    // Handle 401 - sai thông tin đăng nhập. Message cố định: không phân biệt sai username hay sai mật khẩu,
    // không trả ex.getMessage() của Spring Security ra ngoài.
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex, HttpServletRequest request) {
        log.warn("Bad credentials at {}", request.getRequestURI());
        return buildErrorResponse(HttpStatus.UNAUTHORIZED, BAD_CREDENTIALS_MESSAGE, request);
    }

    // Handle 401 - tài khoản bị vô hiệu hoá. Chỉ xảy ra khi đã nhập ĐÚNG mật khẩu (trạng thái được kiểm tra sau mật
    // khẩu, xem LoginAttemptAwareAuthenticationProvider) nên báo rõ lý do không làm lộ tài khoản.
    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ErrorResponse> handleDisabled(DisabledException ex, HttpServletRequest request) {
        log.warn("Disabled account login at {}", request.getRequestURI());
        return buildErrorResponse(HttpStatus.UNAUTHORIZED, "Tài khoản đã bị vô hiệu hoá.", request);
    }

    // Handle 401 - mọi lỗi xác thực khác (trước đây rơi vào handler 500)
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(AuthenticationException ex, HttpServletRequest request) {
        log.warn("Authentication failed at {}: {}", request.getRequestURI(), ex.getClass().getSimpleName());
        return buildErrorResponse(HttpStatus.UNAUTHORIZED, BAD_CREDENTIALS_MESSAGE, request);
    }

    // Handle other exceptions (500 Internal Server Error)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGlobalException(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception occurred at {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request);
    }

    private ResponseEntity<ErrorResponse> buildErrorResponse(HttpStatus status, Object message, HttpServletRequest request) {
        ErrorResponse errorResponse = ErrorResponse.builder()
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .path(request.getRequestURI())
                .build();
        return new ResponseEntity<>(errorResponse, status);
    }
}
