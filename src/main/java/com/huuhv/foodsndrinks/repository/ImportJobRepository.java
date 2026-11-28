package com.huuhv.foodsndrinks.repository;

import com.huuhv.foodsndrinks.entity.ImportJob;
import com.huuhv.foodsndrinks.enums.ImportJobStatus;
import com.huuhv.foodsndrinks.repository.projection.ImportJobSummary;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Bảng {@code import_jobs}. Mọi thay đổi trạng thái là MỘT câu UPDATE có điều kiện theo trạng thái hiện tại và trả về số
 * dòng đổi: 0 = lần import không còn ở trạng thái mong đợi (lần xử lý khác đã làm xong, job dọn dẹp/DLQ đã đánh dấu thất
 * bại) → người gọi dừng lại. Thời gian luôn là đồng hồ của DB.
 */
@Repository
public interface ImportJobRepository extends JpaRepository<ImportJob, Long> {

    /**
     * Nhận việc: {@code PENDING} → {@code PROCESSING}. Lần import đang {@code PROCESSING} cũng được nhận lại — message chỉ
     * được broker giao lại khi lần xử lý trước không ack (instance chết, lỗi hệ thống), và lần đó chưa commit gì: sản phẩm
     * và trạng thái {@code COMPLETED} luôn commit cùng một transaction ({@link #complete}).
     *
     * @return 0 nếu lần import đã kết thúc hoặc không tồn tại
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE import_jobs SET status = 'PROCESSING', started_at = CURRENT_TIMESTAMP(3)
            WHERE id = :id AND status IN ('PENDING', 'PROCESSING')
            """, nativeQuery = true)
    int claim(@Param("id") long id);

    /**
     * {@code PROCESSING} → {@code COMPLETED}. Phải là câu lệnh ĐẦU TIÊN của transaction ghi sản phẩm: nó khoá dòng của lần
     * import, nên hai lần xử lý song song không thể cùng ghi — lần sau chờ khoá rồi nhận 0.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE import_jobs
            SET status = 'COMPLETED', total_rows = :totalRows, created_count = :created, updated_count = :updated,
                unchanged_count = :unchanged, error_count = 0, errors = NULL, failure_message = NULL,
                finished_at = CURRENT_TIMESTAMP(3)
            WHERE id = :id AND status = 'PROCESSING'
            """, nativeQuery = true)
    int complete(@Param("id") long id, @Param("totalRows") int totalRows, @Param("created") int created,
                 @Param("updated") int updated, @Param("unchanged") int unchanged);

    /** {@code PROCESSING} → {@code FAILED} do nội dung file: có dòng lỗi ({@code errors}) hoặc cả file bị từ chối. */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE import_jobs
            SET status = 'FAILED', total_rows = :totalRows, error_count = :errorCount, errors = :errors,
                failure_message = :failureMessage, finished_at = CURRENT_TIMESTAMP(3)
            WHERE id = :id AND status = 'PROCESSING'
            """, nativeQuery = true)
    int fail(@Param("id") long id, @Param("totalRows") int totalRows, @Param("errorCount") int errorCount,
             @Param("errors") String errors, @Param("failureMessage") String failureMessage);

    /**
     * Lần import CHƯA kết thúc (kể cả còn {@code PENDING}) → {@code FAILED} vì lý do hệ thống: message vào DLQ, hoặc kẹt quá
     * lâu (job dọn dẹp).
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE import_jobs
            SET status = 'FAILED', failure_message = :failureMessage, finished_at = CURRENT_TIMESTAMP(3)
            WHERE id = :id AND status IN ('PENDING', 'PROCESSING')
            """, nativeQuery = true)
    int failUnfinished(@Param("id") long id, @Param("failureMessage") String failureMessage);

    /**
     * Lần import chưa kết thúc mà lần nhận việc gần nhất (chưa nhận lần nào thì lúc tạo) đã cũ hơn
     * {@code staleSeconds} — bị kẹt: message mất, outbox FAILED, hoặc instance xử lý đã chết và broker không giao lại.
     */
    @Query(value = """
            SELECT * FROM import_jobs
            WHERE status IN ('PENDING', 'PROCESSING')
              AND COALESCE(started_at, created_at) < DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL :staleSeconds SECOND)
            ORDER BY id
            LIMIT :limit
            """, nativeQuery = true)
    List<ImportJob> findStale(@Param("staleSeconds") long staleSeconds, @Param("limit") int limit);

    /** Trong {@code storedFiles}: các file mà một lần import CHƯA kết thúc còn trỏ tới (không được xoá). */
    @Query("SELECT j.storedFile FROM ImportJob j WHERE j.storedFile IN :storedFiles"
            + " AND j.status IN ('PENDING', 'PROCESSING')")
    List<String> findUnfinishedStoredFiles(@Param("storedFiles") Collection<String> storedFiles);

    @Query("SELECT j.status FROM ImportJob j WHERE j.id = :id")
    Optional<ImportJobStatus> findStatusById(@Param("id") long id);

    /** Số lần import chưa kết thúc của một admin. */
    @Query("SELECT COUNT(j) FROM ImportJob j WHERE j.createdBy = :createdBy AND j.status IN ('PENDING', 'PROCESSING')")
    long countUnfinishedByCreatedBy(@Param("createdBy") String createdBy);

    /** Các lần import mới nhất trước, KHÔNG kèm cột {@code errors} (danh sách không hiển thị bảng lỗi). */
    @Query("""
            SELECT new com.huuhv.foodsndrinks.repository.projection.ImportJobSummary(
                j.id, j.fileName, j.status, j.totalRows, j.createdCount, j.updatedCount, j.unchangedCount,
                j.errorCount, j.failureMessage, j.createdBy, j.createdAt, j.finishedAt)
            FROM ImportJob j
            ORDER BY j.id DESC
            """)
    List<ImportJobSummary> findRecentSummaries(Limit limit);
}
