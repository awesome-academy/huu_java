package com.huuhv.foodsndrinks.repository;

import com.huuhv.foodsndrinks.entity.ImportJob;
import com.huuhv.foodsndrinks.enums.ImportJobStatus;
import com.huuhv.foodsndrinks.repository.projection.ImportJobSummary;
import com.huuhv.foodsndrinks.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Các UPDATE có điều kiện của {@code import_jobs} trên MySQL thật — chính chúng bảo đảm message giao lại không import lần
 * hai và kết quả của một lần import đã kết thúc không bị ghi đè.
 */
@IntegrationTest
@Transactional
class ImportJobRepositoryTest {

    @Autowired private ImportJobRepository repository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @PersistenceContext private EntityManager entityManager;

    private final String admin = "admin-" + UUID.randomUUID().toString().substring(0, 8);

    @Test
    void newJob_startsPendingWithDatabaseDefaults() {
        ImportJob job = find(newJob());

        assertThat(job.getStatus()).isEqualTo(ImportJobStatus.PENDING);
        assertThat(job.getFileName()).isEqualTo("san-pham.xlsx");
        assertThat(job.getCreatedBy()).isEqualTo(admin);
        assertThat(job.getTotalRows()).isZero();
        assertThat(job.getErrorCount()).isZero();
        assertThat(job.getErrors()).isNull();
        assertThat(job.getCreatedAt()).isNotNull();
        assertThat(job.getStartedAt()).isNull();
        assertThat(job.getFinishedAt()).isNull();
    }

    @Test
    void claim_takesAPendingJob_andMayRetakeAJobLeftProcessingByACrashedRun() {
        long id = newJob();

        assertThat(repository.claim(id)).isEqualTo(1);
        assertThat(find(id).getStatus()).isEqualTo(ImportJobStatus.PROCESSING);
        assertThat(find(id).getStartedAt()).isNotNull();
        // Message giao lại sau khi lần xử lý trước chết: chưa có gì được commit nên được nhận lại
        assertThat(repository.claim(id)).isEqualTo(1);
        assertThat(repository.claim(Long.MAX_VALUE)).isZero();
    }

    @Test
    void complete_onlyWorksOnceFromProcessing_soARedeliveredMessageCannotImportTwice() {
        long id = newJob();
        // Chưa nhận việc thì không hoàn thành được
        assertThat(repository.complete(id, 6, 3, 2, 1)).isZero();
        repository.claim(id);

        assertThat(repository.complete(id, 6, 3, 2, 1)).isEqualTo(1);

        ImportJob done = find(id);
        assertThat(done.getStatus()).isEqualTo(ImportJobStatus.COMPLETED);
        assertThat(done.getTotalRows()).isEqualTo(6);
        assertThat(done.getCreatedCount()).isEqualTo(3);
        assertThat(done.getUpdatedCount()).isEqualTo(2);
        assertThat(done.getUnchangedCount()).isEqualTo(1);
        assertThat(done.getFinishedAt()).isNotNull();
        // Lần giao lại / lần chạy song song: không nhận được việc, không hoàn thành được, không đánh dấu thất bại được
        assertThat(repository.claim(id)).isZero();
        assertThat(repository.complete(id, 99, 99, 99, 99)).isZero();
        assertThat(repository.fail(id, 1, 1, "[]", null)).isZero();
        assertThat(repository.failUnfinished(id, "Lỗi hệ thống")).isZero();
        assertThat(find(id).getCreatedCount()).isEqualTo(3);
        assertThat(find(id).getStatus()).isEqualTo(ImportJobStatus.COMPLETED);
    }

    @Test
    void fail_recordsRowErrorsOrAFileLevelReason_andIsFinal() {
        long withRowErrors = newJob();
        repository.claim(withRowErrors);
        String errors = "[{\"row\":3,\"column\":\"Giá\",\"message\":\"Giá phải lớn hơn 0!\"}]";

        assertThat(repository.fail(withRowErrors, 40, 250, errors, null)).isEqualTo(1);

        ImportJob failed = find(withRowErrors);
        assertThat(failed.getStatus()).isEqualTo(ImportJobStatus.FAILED);
        assertThat(failed.getTotalRows()).isEqualTo(40);
        assertThat(failed.getErrorCount()).isEqualTo(250);
        assertThat(failed.getErrors()).isEqualTo(errors);
        assertThat(failed.getFailureMessage()).isNull();
        assertThat(repository.complete(withRowErrors, 1, 1, 0, 0)).isZero();
        assertThat(repository.claim(withRowErrors)).isZero();

        long rejected = newJob();
        repository.claim(rejected);
        assertThat(repository.fail(rejected, 0, 0, null, "File không đúng mẫu")).isEqualTo(1);
        assertThat(find(rejected).getFailureMessage()).isEqualTo("File không đúng mẫu");
        assertThat(find(rejected).getErrors()).isNull();
    }

    @Test
    void errorsColumn_onlyAcceptsJson() {
        long id = newJob();
        repository.claim(id);

        // CHECK (JSON_VALID(errors)) của bảng
        assertThatExceptionOfType(DataAccessException.class)
                .isThrownBy(() -> repository.fail(id, 1, 1, "not json", null));
    }

    @Test
    void failUnfinished_failsPendingAndProcessingJobs_withASystemReason() {
        long pending = newJob();
        long processing = newJob();
        repository.claim(processing);

        assertThat(repository.failUnfinished(pending, "Lỗi hệ thống")).isEqualTo(1);
        assertThat(repository.failUnfinished(processing, "Lỗi hệ thống")).isEqualTo(1);

        assertThat(find(pending).getStatus()).isEqualTo(ImportJobStatus.FAILED);
        assertThat(find(pending).getFailureMessage()).isEqualTo("Lỗi hệ thống");
        assertThat(find(processing).getFinishedAt()).isNotNull();
        // Lần xử lý còn đang chạy của job vừa bị đánh dấu: không ghi đè được kết quả
        assertThat(repository.complete(processing, 6, 3, 2, 1)).isZero();
    }

    @Test
    void findStale_returnsUnfinishedJobsWhoseLastClaimOrCreationIsOld() {
        long oldPending = newJob();
        long oldProcessing = newJob();
        long reclaimedRecently = newJob();
        long freshPending = newJob();
        long oldButFinished = newJob();
        repository.claim(oldProcessing);
        repository.claim(reclaimedRecently);
        repository.claim(oldButFinished);
        repository.complete(oldButFinished, 1, 1, 0, 0);
        age("created_at", oldPending, oldProcessing, reclaimedRecently, oldButFinished);
        age("started_at", oldProcessing);

        assertThat(repository.findStale(30 * 60, 100)).extracting(ImportJob::getId)
                .contains(oldPending, oldProcessing)
                // vừa được nhận lại (đang xử lý), vừa tạo, hoặc đã kết thúc → không kẹt
                .doesNotContain(reclaimedRecently, freshPending, oldButFinished);
        assertThat(repository.findStale(30 * 60, 1)).hasSize(1);
    }

    @Test
    void countUnfinished_countsOnlyPendingAndProcessingJobsOfThatAdmin() {
        long finished = newJob();
        repository.claim(finished);
        repository.complete(finished, 1, 1, 0, 0);
        newJob();
        repository.claim(newJob());
        repository.saveAndFlush(ImportJob.builder().fileName("khac.xlsx").storedFile(UUID.randomUUID() + ".xlsx")
                .createdBy("someone-else-" + admin).build());

        assertThat(repository.countUnfinishedByCreatedBy(admin)).isEqualTo(2);
        assertThat(repository.findStatusById(finished)).contains(ImportJobStatus.COMPLETED);
        assertThat(repository.findStatusById(Long.MAX_VALUE)).isEmpty();
    }

    @Test
    void findUnfinishedStoredFiles_returnsOnlyFilesOfPendingOrProcessingJobs() {
        long pending = newJob();
        long processing = newJob();
        repository.claim(processing);
        long finished = newJob();
        repository.claim(finished);
        repository.complete(finished, 1, 1, 0, 0);
        String unknown = UUID.randomUUID() + ".xlsx";

        assertThat(repository.findUnfinishedStoredFiles(List.of(find(pending).getStoredFile(),
                find(processing).getStoredFile(), find(finished).getStoredFile(), unknown)))
                .containsExactlyInAnyOrder(find(pending).getStoredFile(), find(processing).getStoredFile());
    }

    @Test
    void recentJobs_areListedNewestFirst_withoutTheErrorsColumn() {
        long first = newJob();
        long second = newJob();
        repository.claim(second);
        repository.fail(second, 4, 1, "[{\"row\":2,\"column\":\"Giá\",\"message\":\"x\"}]", null);

        List<ImportJobSummary> recent = repository.findRecentSummaries(Limit.of(10));

        assertThat(recent).extracting(ImportJobSummary::id).containsSubsequence(second, first)
                .hasSizeLessThanOrEqualTo(10);
        assertThat(recent.getFirst()).satisfies(job -> {
            assertThat(job.id()).isEqualTo(second);
            assertThat(job.status()).isEqualTo(ImportJobStatus.FAILED);
            assertThat(job.totalRows()).isEqualTo(4);
            assertThat(job.errorCount()).isEqualTo(1);
            assertThat(job.fileName()).isEqualTo("san-pham.xlsx");
            assertThat(job.createdBy()).isEqualTo(admin);
        });
        assertThat(repository.findRecentSummaries(Limit.of(1))).hasSize(1);
    }

    private long newJob() {
        return repository.saveAndFlush(ImportJob.builder().fileName("san-pham.xlsx")
                .storedFile(UUID.randomUUID() + ".xlsx").createdBy(admin).build()).getId();
    }

    /** Đọc lại từ DB, không lấy entity còn trong persistence context (cột có default của DB chưa được nạp lại). */
    private ImportJob find(long id) {
        entityManager.clear();
        return repository.findById(id).orElseThrow();
    }

    private void age(String column, Long... ids) {
        for (Long id : ids) {
            jdbcTemplate.update("UPDATE import_jobs SET " + column + " = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 31 MINUTE) "
                    + "WHERE id = ?", id);
        }
    }
}
