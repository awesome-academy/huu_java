package com.huuhv.foodsndrinks.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Khoá phân tán cho job {@code @Scheduled} khi chạy nhiều instance sau load balancer: mỗi instance đều kích hoạt cron,
 * nhưng chỉ instance giành được khoá trong bảng {@code shedlock} (MySQL dùng chung) mới chạy job — không gửi trùng
 * email báo cáo tháng. Mọi job {@code @Scheduled} mới phải có {@code @SchedulerLock(name = ...)} với tên riêng.
 * <ul>
 *   <li>{@code usingDbTime()}: hạn khoá tính bằng đồng hồ của MySQL, không phụ thuộc giờ (có thể lệch) của từng
 *       instance.</li>
 *   <li>{@code locked_by} = {@code app.instance-id}: biết instance nào đã chạy job (cùng giá trị trong log).</li>
 *   <li>{@code defaultLockAtMostFor}: instance chết khi đang giữ khoá thì khoá tự hết hạn sau thời gian này.</li>
 * </ul>
 * Job phải được {@code @SchedulerLock} bọc ngoài transaction (không đặt {@code @Transactional} trên chính method job).
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource, @Value("${app.instance-id}") String instanceId) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName("shedlock")
                .usingDbTime()
                .withLockedByValue(instanceId)
                .build());
    }
}
