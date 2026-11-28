package com.huuhv.foodsndrinks.service.security;

import com.huuhv.foodsndrinks.repository.DatabaseClockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Thời điểm cho hai mốc được so với nhau khi huỷ session vì đổi mật khẩu ({@link AccountStatusService}): mốc xác thực
 * của session ({@code utils.SessionAuthenticationTime}) và {@code users.password_changed_at}.
 * <p>
 * Lấy theo đồng hồ của MySQL, không theo đồng hồ JVM: hai mốc thường được ghi ở HAI instance khác nhau (load balancer
 * không sticky). Lấy theo JVM thì instance chậm giờ hơn ghi mốc đăng nhập "trước" lần đổi mật khẩu vừa ghi ở instance
 * nhanh giờ hơn — session vừa đăng nhập lại bị huỷ ngay (fail-closed) và lặp lại tới khi hết khoảng lệch.
 */
@Service
@RequiredArgsConstructor
public class AuthenticationClock {

    private final DatabaseClockRepository databaseClockRepository;

    /** "Bây giờ" theo đồng hồ DB, cắt tới mili giây — đúng độ chính xác lưu trong session, so bằng được khi đọc lại. */
    public Instant now() {
        return databaseClockRepository.currentTimestamp().truncatedTo(ChronoUnit.MILLIS);
    }
}
