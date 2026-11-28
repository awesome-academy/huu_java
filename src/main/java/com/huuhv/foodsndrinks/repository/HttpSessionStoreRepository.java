package com.huuhv.foodsndrinks.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.session.jdbc.config.annotation.SpringSessionDataSource;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Truy vấn theo lô trên bảng {@code SPRING_SESSION} của kho session HTTP (Spring Session JDBC) — thứ
 * {@code SessionRepository} không có: {@code findById} nạp và deserialize MỌI attribute của từng session (SecurityContext,
 * token OAuth2...), hỏi N session là N query.
 * <p>
 * Chạy trên pool riêng của kho session ({@link SpringSessionDataSource}, {@code config/SessionStoreConfig}) như chính
 * Spring Session, chỉ đọc, không gia hạn session nào. Tên bảng/cột theo migration {@code V20261128120000}.
 */
@Repository
public class HttpSessionStoreRepository {

    /** Số id tối đa trong một câu {@code IN (...)}. */
    static final int BATCH_SIZE = 500;

    // EXPIRY_TIME (epoch millis) = lần truy cập cuối + thời gian không hoạt động tối đa; cùng quy tắc với
    // Session#isExpired (findById trả null) và job dọn session hết hạn (xoá EXPIRY_TIME < now)
    private static final String FIND_LIVE_SESSION_IDS =
            "SELECT SESSION_ID FROM SPRING_SESSION WHERE SESSION_ID IN (:ids) AND EXPIRY_TIME >= :now";

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public HttpSessionStoreRepository(@SpringSessionDataSource DataSource sessionDataSource) {
        this.jdbcTemplate = new NamedParameterJdbcTemplate(sessionDataSource);
    }

    /**
     * @param sessionIds id session HTTP cần kiểm tra
     * @param nowMillis  thời điểm hiện tại (epoch millis)
     * @return các id trong {@code sessionIds} còn trong kho và chưa hết hạn — một query mỗi {@value #BATCH_SIZE} id
     */
    public Set<String> findLiveSessionIds(Collection<String> sessionIds, long nowMillis) {
        List<String> distinct = sessionIds.stream().distinct().toList();
        Set<String> live = new HashSet<>();
        for (int from = 0; from < distinct.size(); from += BATCH_SIZE) {
            List<String> batch = distinct.subList(from, Math.min(from + BATCH_SIZE, distinct.size()));
            live.addAll(jdbcTemplate.queryForList(FIND_LIVE_SESSION_IDS,
                    new MapSqlParameterSource("ids", batch).addValue("now", nowMillis), String.class));
        }
        return live;
    }
}
