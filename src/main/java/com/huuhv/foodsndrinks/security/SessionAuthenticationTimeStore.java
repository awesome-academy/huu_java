package com.huuhv.foodsndrinks.security;

import com.huuhv.foodsndrinks.utils.SessionAuthenticationTime;
import lombok.RequiredArgsConstructor;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Đọc/ghi mốc xác thực ({@link SessionAuthenticationTime}) THẲNG trong kho session dùng chung (Spring Session JDBC),
 * không chờ tới lúc response commit.
 * <p>
 * Spring Session chỉ ghi thay đổi của session khi response của request đó commit; request khác của CÙNG session chạy
 * song song (tab khác, polling của trang) đọc bản đã lưu trong kho. Dùng khi request đang chạy cần các request song song
 * thấy ngay mốc mới — đổi mật khẩu ({@link PasswordChangeSessionHandler}, {@link AccountStatusFilter}).
 */
@Component
@RequiredArgsConstructor
public class SessionAuthenticationTimeStore {

    private final SessionRepository<? extends Session> sessionRepository;

    /**
     * Ghi mốc vào bản đã lưu của session {@code sessionId} ngay lập tức.
     *
     * @return {@code false} nếu kho chưa có session này (session mới tạo trong request hiện tại — chưa request nào khác
     *         thấy nó) hoặc nó đã hết hạn/bị xoá
     */
    public boolean persist(String sessionId, Instant authenticatedAt) {
        return persist(sessionRepository, sessionId, authenticatedAt.toEpochMilli());
    }

    /** @return mốc xác thực trong bản đã lưu của session; {@code null} nếu không có session hoặc session không mang mốc */
    public Instant reload(String sessionId) {
        Session stored = sessionRepository.findById(sessionId);
        if (stored == null) {
            return null;
        }
        return stored.getAttribute(SessionAuthenticationTime.ATTRIBUTE) instanceof Long millis
                ? Instant.ofEpochMilli(millis) : null;
    }

    private static <S extends Session> boolean persist(SessionRepository<S> repository, String sessionId, long millis) {
        S stored = repository.findById(sessionId);
        if (stored == null) {
            return false;
        }
        stored.setAttribute(SessionAuthenticationTime.ATTRIBUTE, millis);
        repository.save(stored);
        return true;
    }
}
