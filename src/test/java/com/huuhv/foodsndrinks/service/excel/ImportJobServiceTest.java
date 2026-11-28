package com.huuhv.foodsndrinks.service.excel;

import com.huuhv.foodsndrinks.dto.message.ExcelImportMessage;
import com.huuhv.foodsndrinks.dto.response.ImportJobResDto;
import com.huuhv.foodsndrinks.dto.response.ImportRowErrorResDto;
import com.huuhv.foodsndrinks.entity.ImportJob;
import com.huuhv.foodsndrinks.enums.ImportJobStatus;
import com.huuhv.foodsndrinks.exception.ExcelFileException;
import com.huuhv.foodsndrinks.exception.ResourceNotFoundException;
import com.huuhv.foodsndrinks.repository.ImportJobRepository;
import com.huuhv.foodsndrinks.repository.UserRepository;
import com.huuhv.foodsndrinks.repository.projection.ImportJobSummary;
import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import com.huuhv.foodsndrinks.service.messaging.OutboxService;
import com.huuhv.foodsndrinks.service.messaging.OutboxType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.huuhv.foodsndrinks.service.excel.ExcelTestFiles.upload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit test {@link ImportJobService}: tạo lần import + outbox, đọc trạng thái, và các lối kết thúc do hệ thống. */
@ExtendWith(MockitoExtension.class)
class ImportJobServiceTest {

    private static final String STORED = "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d.xlsx";
    private static final Path STORED_PATH = Path.of("import-tmp", STORED);

    @Mock private ExcelUploadValidator uploadValidator;
    @Mock private ImportFileStorage storage;
    @Mock private ImportJobRepository repository;
    @Mock private UserRepository userRepository;
    @Mock private OutboxService outboxService;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private AuditLogService auditLogService;

    private ImportJobService service;
    private final MockMultipartFile file = upload("san-pham.xlsx", new byte[]{'P', 'K', 3, 4});

    @BeforeEach
    void setUp() {
        service = new ImportJobService(uploadValidator, storage, repository, userRepository, outboxService,
                transactionManager, ExcelTestFiles.properties(), auditLogService, JsonMapper.builder().build());
    }

    // -------------------------------------------------------
    // submit
    // -------------------------------------------------------

    @Test
    void submit_storesTheFile_thenCreatesThePendingJobAndItsOutboxMessageInOneTransaction() throws IOException {
        when(storage.store(file)).thenReturn(STORED);
        when(storage.resolve(STORED)).thenReturn(STORED_PATH);
        when(userRepository.lockIdByUsername("admin")).thenReturn(Optional.of(1L));
        when(repository.save(any())).thenAnswer(invocation -> {
            ImportJob job = invocation.getArgument(0);
            job.setId(42L);
            return job;
        });

        assertThat(service.submit(file, "admin")).isEqualTo(42L);

        ArgumentCaptor<ImportJob> saved = ArgumentCaptor.forClass(ImportJob.class);
        ArgumentCaptor<TransactionDefinition> transaction = ArgumentCaptor.forClass(TransactionDefinition.class);
        InOrder order = inOrder(uploadValidator, storage, transactionManager, userRepository, repository,
                outboxService, auditLogService);
        order.verify(uploadValidator).validate(file);
        order.verify(repository).countUnfinishedByCreatedBy("admin");   // kiểm tra nhanh, chưa lưu file
        order.verify(storage).store(file);
        order.verify(uploadValidator).validateContent(STORED_PATH);     // dung lượng giải nén, trên file đã lưu
        order.verify(transactionManager).getTransaction(transaction.capture());
        order.verify(userRepository).lockIdByUsername("admin");         // khoá trước khi đếm lại
        order.verify(repository).countUnfinishedByCreatedBy("admin");
        order.verify(repository).save(saved.capture());
        order.verify(outboxService).enqueue(OutboxType.EXCEL_IMPORT, new ExcelImportMessage(42L));
        order.verify(transactionManager).commit(any());
        order.verify(auditLogService).productImportSubmitted(42L, "san-pham.xlsx");
        assertThat(saved.getValue().getFileName()).isEqualTo("san-pham.xlsx");
        assertThat(saved.getValue().getStoredFile()).isEqualTo(STORED);
        assertThat(saved.getValue().getCreatedBy()).isEqualTo("admin");
        // Trạng thái và các số đếm lấy default của DB (PENDING, 0)
        assertThat(saved.getValue().getStatus()).isNull();
        // Đếm lại sau khi có khoá phải thấy lần import vừa commit của request đứng trước
        assertThat(transaction.getValue().getIsolationLevel())
                .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
        verify(storage, never()).delete(any());
    }

    @Test
    void submit_limitReachedByAConcurrentUploadOfTheSameAdmin_isRejectedInsideTheTransaction_andTheFileDeleted()
            throws IOException {
        when(storage.store(file)).thenReturn(STORED);
        when(userRepository.lockIdByUsername("admin")).thenReturn(Optional.of(1L));
        // Lúc kiểm tra nhanh còn chỗ; request song song commit trước khi request này có khoá → hết chỗ
        when(repository.countUnfinishedByCreatedBy("admin")).thenReturn(2L, 3L);

        assertThatExceptionOfType(ExcelFileException.class).isThrownBy(() -> service.submit(file, "admin"))
                .withMessageStartingWith("Bạn đang có 3 file chờ xử lý");

        verify(repository, never()).save(any());
        verify(transactionManager).rollback(any());
        verify(storage).delete(STORED);
        verifyNoInteractions(outboxService, auditLogService);
    }

    @Test
    void submit_accountNoLongerExists_isRejected_andTheFileDeleted() throws IOException {
        when(storage.store(file)).thenReturn(STORED);
        when(userRepository.lockIdByUsername("admin")).thenReturn(Optional.empty());

        assertThatExceptionOfType(ExcelFileException.class).isThrownBy(() -> service.submit(file, "admin"));

        verify(repository, never()).save(any());
        verify(storage).delete(STORED);
    }

    @Test
    void submit_contentRejectedAfterStoring_deletesTheFile_withoutCreatingAJob() throws IOException {
        when(storage.store(file)).thenReturn(STORED);
        when(storage.resolve(STORED)).thenReturn(STORED_PATH);
        doThrow(new ExcelFileException(ExcelUploadValidator.TOO_LARGE_UNCOMPRESSED))
                .when(uploadValidator).validateContent(STORED_PATH);

        assertThatExceptionOfType(ExcelFileException.class).isThrownBy(() -> service.submit(file, "admin"))
                .withMessage(ExcelUploadValidator.TOO_LARGE_UNCOMPRESSED);

        verify(storage).delete(STORED);
        verifyNoInteractions(transactionManager, userRepository, outboxService, auditLogService);
        verify(repository, never()).save(any());
    }

    @Test
    void submit_keepsOnlyADisplayNameOfTheClientFileName() throws IOException {
        when(storage.store(any())).thenReturn(STORED);
        when(userRepository.lockIdByUsername("admin")).thenReturn(Optional.of(1L));
        when(repository.save(any())).thenAnswer(invocation -> {
            ImportJob job = invocation.getArgument(0);
            job.setId(1L);
            return job;
        });
        ArgumentCaptor<ImportJob> saved = ArgumentCaptor.forClass(ImportJob.class);

        service.submit(upload("C:\\Users\\admin\\Desktop\\sản phẩm\r\ntháng 11.xlsx", new byte[]{1}), "admin");
        service.submit(upload("../../etc/" + "x".repeat(300) + ".xlsx", new byte[]{1}), "admin");

        verify(repository, org.mockito.Mockito.times(2)).save(saved.capture());
        // Bỏ đường dẫn, ký tự ngắt dòng; cắt về độ dài của cột — tên này chỉ để hiển thị, không dùng làm đường dẫn
        assertThat(saved.getAllValues().get(0).getFileName()).isEqualTo("sản phẩm_tháng 11.xlsx");
        assertThat(saved.getAllValues().get(1).getFileName()).hasSize(255).startsWith("xxx").doesNotContain("/");
    }

    @Test
    void submit_rejectedFile_isNeitherStoredNorRecorded() {
        doThrow(new ExcelFileException("Chỉ nhận file Excel có đuôi .xlsx!")).when(uploadValidator).validate(file);

        assertThatExceptionOfType(ExcelFileException.class).isThrownBy(() -> service.submit(file, "admin"))
                .withMessage("Chỉ nhận file Excel có đuôi .xlsx!");
        verifyNoInteractions(storage, repository, outboxService, auditLogService);
    }

    @Test
    void submit_tooManyUnfinishedImportsOfThatAdmin_isRejectedBeforeStoringTheFile() {
        when(repository.countUnfinishedByCreatedBy("admin")).thenReturn(3L);

        assertThatExceptionOfType(ExcelFileException.class).isThrownBy(() -> service.submit(file, "admin"))
                .withMessageStartingWith("Bạn đang có 3 file chờ xử lý");
        verifyNoInteractions(storage, outboxService, auditLogService);
        verify(repository, never()).save(any());
    }

    @Test
    void submit_transactionFailure_deletesTheStoredFile_soNoOrphanIsLeft() throws IOException {
        when(storage.store(file)).thenReturn(STORED);
        when(userRepository.lockIdByUsername("admin")).thenReturn(Optional.of(1L));
        when(repository.save(any())).thenThrow(new IllegalStateException("db down"));

        assertThatIllegalStateException().isThrownBy(() -> service.submit(file, "admin"));

        verify(transactionManager).rollback(any());
        verify(storage).delete(STORED);
        verifyNoInteractions(auditLogService);
    }

    @Test
    void submit_fileCannotBeStored_isReportedWithAFriendlyMessage() throws IOException {
        when(storage.store(file)).thenThrow(new IOException("disk full: /app/import-tmp"));

        assertThatExceptionOfType(ExcelFileException.class).isThrownBy(() -> service.submit(file, "admin"))
                .withMessage("Không lưu được file tải lên. Vui lòng thử lại!");
        verify(repository, never()).save(any());
    }

    // -------------------------------------------------------
    // Đọc trạng thái
    // -------------------------------------------------------

    @Test
    void get_mapsTheJob_andParsesItsStoredErrors() {
        ImportJob job = job(7L, ImportJobStatus.FAILED);
        job.setTotalRows(40);
        job.setErrorCount(250);
        job.setErrors("[{\"row\":3,\"column\":\"Giá\",\"message\":\"Giá phải lớn hơn 0!\"}]");
        job.setFinishedAt(LocalDateTime.of(2026, 11, 27, 10, 0, 5));
        when(repository.findById(7L)).thenReturn(Optional.of(job));

        ImportJobResDto dto = service.get(7L);

        assertThat(dto.id()).isEqualTo(7L);
        assertThat(dto.fileName()).isEqualTo("san-pham.xlsx");
        assertThat(dto.status()).isEqualTo(ImportJobStatus.FAILED);
        assertThat(dto.statusLabel()).isEqualTo("Thất bại");
        assertThat(dto.terminal()).isTrue();
        assertThat(dto.totalRows()).isEqualTo(40);
        assertThat(dto.errorCount()).isEqualTo(250);
        assertThat(dto.errors()).containsExactly(new ImportRowErrorResDto(3, "Giá", "Giá phải lớn hơn 0!"));
        assertThat(dto.hiddenErrorCount()).isEqualTo(249);
        assertThat(dto.createdBy()).isEqualTo("admin");
    }

    @Test
    void get_pendingJob_hasZeroCounts_andIsNotTerminal() {
        when(repository.findById(8L)).thenReturn(Optional.of(job(8L, ImportJobStatus.PENDING)));

        ImportJobResDto dto = service.get(8L);

        assertThat(dto.terminal()).isFalse();
        assertThat(dto.statusLabel()).isEqualTo("Đang chờ xử lý");
        assertThat(dto.totalRows() + dto.created() + dto.updated() + dto.unchanged() + dto.errorCount()).isZero();
        assertThat(dto.errors()).isEmpty();
        assertThat(dto.failureMessage()).isNull();
    }

    @Test
    void get_unreadableStoredErrors_doNotBreakTheStatusPage() {
        ImportJob job = job(7L, ImportJobStatus.FAILED);
        job.setErrorCount(2);
        job.setErrors("{not json");
        when(repository.findById(7L)).thenReturn(Optional.of(job));

        assertThat(service.get(7L).errors()).isEmpty();
    }

    @Test
    void get_unknownJob_isNotFound() {
        when(repository.findById(404L)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() -> service.get(404L));
    }

    @Test
    void recent_listsJobsWithoutTheirErrorTables() {
        ImportJobSummary failed = new ImportJobSummary(2L, "a.xlsx", ImportJobStatus.FAILED, 10, null, null, null, 5,
                null, "admin", LocalDateTime.now(), LocalDateTime.now());
        ImportJobSummary completed = new ImportJobSummary(1L, "b.xlsx", ImportJobStatus.COMPLETED, 3, 2, 1, 0, 0,
                null, "admin", LocalDateTime.now(), LocalDateTime.now());
        when(repository.findRecentSummaries(Limit.of(ImportJobService.RECENT_LIMIT)))
                .thenReturn(List.of(failed, completed));

        List<ImportJobResDto> recent = service.recent();

        assertThat(recent).extracting(ImportJobResDto::id).containsExactly(2L, 1L);
        assertThat(recent.getFirst().errorCount()).isEqualTo(5);
        assertThat(recent.getFirst().errors()).isEmpty();
        assertThat(recent.getFirst().created()).isZero();
        assertThat(recent.get(1).created()).isEqualTo(2);
    }

    // -------------------------------------------------------
    // Kết thúc do hệ thống
    // -------------------------------------------------------

    @Test
    void failDeadLettered_marksAnUnfinishedJobFailed_thenDeletesItsFileAfterTheCommit() {
        when(repository.failUnfinished(7L, ImportJobService.SYSTEM_FAILURE)).thenReturn(1);
        when(repository.findById(7L)).thenReturn(Optional.of(job(7L, ImportJobStatus.FAILED)));

        assertThat(service.failDeadLettered(7L)).isTrue();

        InOrder order = inOrder(transactionManager, storage);
        order.verify(transactionManager).commit(any());
        order.verify(storage).delete(STORED);
    }

    @Test
    void failDeadLettered_leavesAFinishedJobAndItsResultAlone() {
        when(repository.failUnfinished(anyLong(), any())).thenReturn(0);

        assertThat(service.failDeadLettered(7L)).isFalse();

        verify(repository, never()).findById(any());
        verifyNoInteractions(storage);
    }

    @Test
    void failStale_failsEveryStaleJob_withTheConfiguredAge_andCountsOnlyThoseItChanged() {
        ImportJob stuck = job(1L, ImportJobStatus.PENDING);
        ImportJob justFinished = job(2L, ImportJobStatus.PROCESSING);
        when(repository.findStale(30 * 60L, 100)).thenReturn(List.of(stuck, justFinished));
        when(repository.failUnfinished(1L, ImportJobService.STALE_FAILURE)).thenReturn(1);
        // Kết thúc giữa lúc tìm và lúc đánh dấu → UPDATE có điều kiện không đổi gì
        when(repository.failUnfinished(2L, ImportJobService.STALE_FAILURE)).thenReturn(0);
        when(repository.findById(1L)).thenReturn(Optional.of(stuck));

        assertThat(service.failStale()).isEqualTo(1);

        verify(storage).delete(STORED);
        verify(repository, never()).findById(2L);
    }

    @Test
    void deleteOrphanFiles_keepsOldFilesOfImportsThatAreStillUnfinished() {
        String finished = "11111111-1111-4111-8111-111111111111.xlsx";
        String orphan = "22222222-2222-4222-8222-222222222222.xlsx";
        // Lần import được nhận việc muộn (outbox chờ broker): file đã cũ nhưng lần import vẫn đang chạy
        String stillRunning = "33333333-3333-4333-8333-333333333333.xlsx";
        when(storage.listOlderThan(Duration.ofMinutes(60))).thenReturn(List.of(finished, orphan, stillRunning));
        when(repository.findUnfinishedStoredFiles(List.of(finished, orphan, stillRunning)))
                .thenReturn(List.of(stillRunning));
        when(storage.delete(finished)).thenReturn(true);
        when(storage.delete(orphan)).thenReturn(true);

        assertThat(service.deleteOrphanFiles(Duration.ofMinutes(60))).isEqualTo(2);

        verify(storage, never()).delete(stillRunning);
    }

    @Test
    void deleteOrphanFiles_databaseError_deletesNothing() {
        when(storage.listOlderThan(any())).thenReturn(List.of(STORED));
        when(repository.findUnfinishedStoredFiles(any())).thenThrow(new IllegalStateException("db down"));

        assertThatIllegalStateException().isThrownBy(() -> service.deleteOrphanFiles(Duration.ofMinutes(60)));

        verify(storage, never()).delete(any());
    }

    @Test
    void deleteOrphanFiles_noOldFile_doesNotQueryTheDatabase() {
        when(storage.listOlderThan(any())).thenReturn(List.of());

        assertThat(service.deleteOrphanFiles(Duration.ofMinutes(60))).isZero();

        verifyNoInteractions(repository);
    }

    @Test
    void failStale_nothingStale_doesNothing() {
        when(repository.findStale(anyLong(), anyInt())).thenReturn(List.of());

        assertThat(service.failStale()).isZero();
        verifyNoInteractions(storage, transactionManager);
    }

    private static ImportJob job(Long id, ImportJobStatus status) {
        return ImportJob.builder().id(id).fileName("san-pham.xlsx").storedFile(STORED).status(status)
                .createdBy("admin").createdAt(LocalDateTime.of(2026, 11, 27, 10, 0)).build();
    }
}
