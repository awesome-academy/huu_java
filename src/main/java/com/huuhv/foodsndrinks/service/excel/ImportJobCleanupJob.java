package com.huuhv.foodsndrinks.service.excel;

import com.huuhv.foodsndrinks.config.properties.ExcelProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Dọn các lần import Excel không tự kết thúc được, 5 phút một lần:
 * <ul>
 *   <li>{@link #failStaleJobs}: lần import chưa kết thúc quá {@code app.excel.import.stale-after} → {@code FAILED} + xoá
 *       file. Làm việc trên bảng dùng chung nên có {@code @SchedulerLock} — chỉ một instance chạy mỗi chu kỳ.</li>
 *   <li>{@link #deleteOrphanFiles}: file tạm cũ hơn 2 × {@code stale-after} mà không còn lần import chưa kết thúc nào trỏ
 *       tới (tra {@code import_jobs} — tuổi file không cho biết lần import đã xong chưa: lần import được nhận việc muộn
 *       vẫn có thể đang chạy). Cố ý KHÔNG {@code @SchedulerLock}, như {@code OutboxRelayJob}/
 *       {@code WebSocketSessionRevalidationJob}: mỗi instance quét thư mục nó nhìn thấy (đúng cả khi cấu hình sai và các
 *       instance không dùng chung thư mục), chỉ đọc DB, xoá là idempotent.</li>
 * </ul>
 * Method job không {@code @Transactional} (quy ước của ShedLock trong dự án): service tự mở transaction ngắn.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportJobCleanupJob {

    private final ImportJobService importJobService;
    private final ExcelProperties properties;

    @Scheduled(initialDelay = 2, fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    // Job chạy rất nhanh: giữ khoá gần hết chu kỳ để instance có lịch lệch vài giây không chạy lại ngay sau đó
    @SchedulerLock(name = "importJobFailStale", lockAtMostFor = "10m", lockAtLeastFor = "4m")
    public void failStaleJobs() {
        try {
            int failed = importJobService.failStale();
            if (failed > 0) {
                log.warn("Marked {} unfinished import job(s) as FAILED", failed);
            }
        } catch (RuntimeException ex) {
            log.error("Failing stale import jobs failed, will retry next cycle", ex);
        }
    }

    @Scheduled(initialDelay = 3, fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    public void deleteOrphanFiles() {
        try {
            int deleted = importJobService.deleteOrphanFiles(properties.imports().staleAfter().multipliedBy(2));
            if (deleted > 0) {
                log.warn("Deleted {} leftover import file(s)", deleted);
            }
        } catch (RuntimeException ex) {
            // DB lỗi → không biết file nào còn dùng → không xoá gì chu kỳ này
            log.error("Deleting leftover import files failed, will retry next cycle", ex);
        }
    }
}
