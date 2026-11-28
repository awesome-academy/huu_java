package com.huuhv.foodsndrinks.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Đồng hồ của MySQL — một nguồn thời gian chung cho mọi instance (đồng hồ JVM của từng instance có thể lệch nhau). */
@Repository
@RequiredArgsConstructor
public class DatabaseClockRepository {

    // UTC_TIMESTAMP không phụ thuộc time_zone của session/server; độ chính xác mili giây
    private static final String CURRENT_UTC_TIMESTAMP = "SELECT UTC_TIMESTAMP(3)";

    private final JdbcTemplate jdbcTemplate;

    public Instant currentTimestamp() {
        LocalDateTime utc = jdbcTemplate.queryForObject(CURRENT_UTC_TIMESTAMP, LocalDateTime.class);
        if (utc == null) {
            throw new IllegalStateException("Database returned no current timestamp");
        }
        return utc.toInstant(ZoneOffset.UTC);
    }
}
