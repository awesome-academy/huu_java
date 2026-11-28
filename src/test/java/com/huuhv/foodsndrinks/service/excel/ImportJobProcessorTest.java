package com.huuhv.foodsndrinks.service.excel;

import com.huuhv.foodsndrinks.dto.request.ProductImportCommand;
import com.huuhv.foodsndrinks.dto.request.ProductReqDto;
import com.huuhv.foodsndrinks.dto.response.ImportRowErrorResDto;
import com.huuhv.foodsndrinks.dto.response.ProductImportResDto;
import com.huuhv.foodsndrinks.entity.ImportJob;
import com.huuhv.foodsndrinks.enums.ImportJobStatus;
import com.huuhv.foodsndrinks.exception.DuplicateResourceException;
import com.huuhv.foodsndrinks.exception.ExcelFileException;
import com.huuhv.foodsndrinks.exception.ImportNameConflictException;
import com.huuhv.foodsndrinks.exception.ResourceNotFoundException;
import com.huuhv.foodsndrinks.repository.ImportJobRepository;
import com.huuhv.foodsndrinks.service.ProductService;
import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test {@link ImportJobProcessor}: từng nhánh của việc xử lý một lần import — đặc biệt là khi nào kết thúc (ack) và
 * khi nào ném lỗi để broker giao lại. Tính nguyên tử thật của transaction: {@code ExcelImportIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class ImportJobProcessorTest {

    private static final long JOB_ID = 7L;
    private static final String STORED = "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d.xlsx";
    private static final Path FILE = Path.of("import-tmp", STORED);

    @Mock private ImportJobRepository repository;
    @Mock private ImportFileStorage storage;
    @Mock private ProductImportService importService;
    @Mock private ProductService productService;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private AuditLogService auditLogService;

    private ImportJobProcessor processor;
    private final List<ProductImportCommand> commands = List.of(
            new ProductImportCommand(2, null, new ProductReqDto()),
            new ProductImportCommand(3, null, new ProductReqDto()),
            new ProductImportCommand(4, 5L, new ProductReqDto()));
    private final ProductImportPlan validPlan = new ProductImportPlan(6, commands, 3, List.of());

    @BeforeEach
    void setUp() {
        processor = new ImportJobProcessor(repository, storage, importService, productService, transactionManager,
                auditLogService, JsonMapper.builder().build());
    }

    // -------------------------------------------------------
    // Hoàn thành
    // -------------------------------------------------------

    @Test
    void validFile_marksCompletedFirst_writesProductsInTheSameTransaction_thenDeletesTheFileAndAudits() {
        claimed();
        fileExists();
        when(importService.plan(FILE)).thenReturn(validPlan);
        when(repository.complete(JOB_ID, 6, 2, 1, 3)).thenReturn(1);

        processor.process(JOB_ID);

        InOrder order = inOrder(repository, productService, transactionManager, storage, auditLogService);
        order.verify(repository).claim(JOB_ID);
        order.verify(transactionManager).commit(any());          // nhận việc: transaction riêng
        order.verify(transactionManager).getTransaction(any());  // transaction ghi
        order.verify(repository).complete(JOB_ID, 6, 2, 1, 3);   // câu lệnh đầu tiên: khoá dòng của lần import
        order.verify(productService).applyImport(commands);
        order.verify(transactionManager).commit(any());
        order.verify(storage).delete(STORED);                    // chỉ sau commit
        order.verify(auditLogService).productsImported("admin", JOB_ID, "san-pham.xlsx", 2, 1, 3);
        verify(repository, never()).fail(anyLong(), anyInt(), anyInt(), any(), any());
    }

    // -------------------------------------------------------
    // Bỏ qua message (không import lần hai)
    // -------------------------------------------------------

    @Test
    void jobAlreadyFinishedOrUnknown_isSkippedWithoutTouchingTheFile() {
        when(repository.claim(JOB_ID)).thenReturn(0);

        processor.process(JOB_ID);

        verify(repository, never()).findById(any());
        verifyNoInteractions(storage, importService, productService, auditLogService);
    }

    @Test
    void jobFinishedByAnotherRunWhileThisOneWasValidating_writesNothing() {
        claimed();
        fileExists();
        when(importService.plan(FILE)).thenReturn(validPlan);
        // Lần xử lý song song đã commit COMPLETED: UPDATE có điều kiện của lần này không đổi dòng nào
        when(repository.complete(JOB_ID, 6, 2, 1, 3)).thenReturn(0);

        processor.process(JOB_ID);

        verifyNoInteractions(productService, auditLogService);
        verify(storage, never()).delete(any());
    }

    @Test
    void fileGone_becauseAnotherRunFinishedTheJob_isSkipped() {
        claimed();
        when(storage.exists(STORED)).thenReturn(false);
        when(repository.findStatusById(JOB_ID)).thenReturn(Optional.of(ImportJobStatus.COMPLETED));

        processor.process(JOB_ID);

        verifyNoInteractions(importService, productService, auditLogService);
    }

    // -------------------------------------------------------
    // Thất bại do nội dung file: kết quả cuối, không giao lại
    // -------------------------------------------------------

    @Test
    void rowErrors_failTheJobWithTheErrorTable_writeNothing_andDeleteTheFile() {
        List<ImportRowErrorResDto> errors = List.of(new ImportRowErrorResDto(3, "Giá", "Giá phải lớn hơn 0!"));
        ProductImportPlan plan = new ProductImportPlan(40, List.of(), 0, errors);
        claimed();
        fileExists();
        when(importService.plan(FILE)).thenReturn(plan);
        when(importService.failed(plan)).thenReturn(new ProductImportResDto(40, 0, 0, 0, 250, errors));
        ArgumentCaptor<String> errorsJson = ArgumentCaptor.forClass(String.class);
        when(repository.fail(eq(JOB_ID), eq(40), eq(250), errorsJson.capture(), isNull())).thenReturn(1);

        processor.process(JOB_ID);

        // Tổng số lỗi (250) được ghi đủ; JSON chỉ chứa các lỗi được giữ lại để hiển thị
        JsonNode stored = JsonMapper.builder().build().readTree(errorsJson.getValue());
        assertThat(stored.size()).isEqualTo(1);
        assertThat(stored.get(0).get("row").asInt()).isEqualTo(3);
        assertThat(stored.get(0).get("column").asString()).isEqualTo("Giá");
        assertThat(stored.get(0).get("message").asString()).isEqualTo("Giá phải lớn hơn 0!");
        verify(storage).delete(STORED);
        verifyNoInteractions(productService, auditLogService);
        verify(repository, never()).complete(anyLong(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void fileRejectedAsAWhole_failsTheJobWithTheFriendlyMessage() {
        claimed();
        fileExists();
        when(importService.plan(FILE)).thenThrow(new ExcelFileException("File không đúng mẫu"));
        when(repository.fail(JOB_ID, 0, 0, null, "File không đúng mẫu")).thenReturn(1);

        processor.process(JOB_ID);

        verify(storage).delete(STORED);
        verifyNoInteractions(productService, auditLogService);
    }

    @Test
    void failingAJobThatWasFinishedMeanwhile_keepsItsFile_forWhoeverFinishedIt() {
        claimed();
        fileExists();
        when(importService.plan(FILE)).thenThrow(new ExcelFileException("File không đúng mẫu"));
        when(repository.fail(anyLong(), anyInt(), anyInt(), any(), any())).thenReturn(0);

        processor.process(JOB_ID);

        verify(storage, never()).delete(any());
    }

    @Test
    void dataRejectedByTheWriteLayer_failsTheJob_becauseRedeliveryWouldGiveTheSameResult() {
        claimed();
        fileExists();
        when(importService.plan(FILE)).thenReturn(validPlan);
        when(repository.complete(JOB_ID, 6, 2, 1, 3)).thenReturn(1);
        doThrow(new IllegalArgumentException("Tên sản phẩm không hợp lệ để tạo slug!"))
                .when(productService).applyImport(commands);
        when(repository.fail(JOB_ID, 6, 0, null, ImportJobProcessor.UNEXPECTED_DATA)).thenReturn(1);

        processor.process(JOB_ID);

        // Transaction ghi (kể cả COMPLETED) đã rollback trước khi ghi FAILED
        InOrder order = inOrder(transactionManager, repository);
        order.verify(transactionManager).rollback(any());
        order.verify(repository).fail(JOB_ID, 6, 0, null, ImportJobProcessor.UNEXPECTED_DATA);
        verify(storage).delete(STORED);
        verifyNoInteractions(auditLogService);
    }

    @Test
    void twoNewRowsTheDatabaseConsidersTheSameName_failTheJobWithThatRowsError_insteadOfBeingRedelivered() {
        claimed();
        fileExists();
        when(importService.plan(FILE)).thenReturn(validPlan);
        when(repository.complete(JOB_ID, 6, 2, 1, 3)).thenReturn(1);
        doThrow(new ImportNameConflictException(3, 2, new DuplicateResourceException("Tên sản phẩm đã tồn tại!")))
                .when(productService).applyImport(commands);
        ArgumentCaptor<String> errorsJson = ArgumentCaptor.forClass(String.class);
        when(repository.fail(eq(JOB_ID), eq(6), eq(1), errorsJson.capture(), isNull())).thenReturn(1);

        processor.process(JOB_ID);

        InOrder order = inOrder(transactionManager, repository);
        order.verify(transactionManager).rollback(any());
        order.verify(repository).fail(eq(JOB_ID), eq(6), eq(1), any(), isNull());
        JsonNode stored = JsonMapper.builder().build().readTree(errorsJson.getValue());
        assertThat(stored.size()).isEqualTo(1);
        assertThat(stored.get(0).get("row").asInt()).isEqualTo(3);
        assertThat(stored.get(0).get("column").asString()).isEqualTo("Tên");
        assertThat(stored.get(0).get("message").asString()).isEqualTo(ImportJobProcessor.nameConflictMessage(2));
        verify(storage).delete(STORED);
        verifyNoInteractions(auditLogService);
    }

    // -------------------------------------------------------
    // Ném lỗi → broker giao lại
    // -------------------------------------------------------

    @Test
    void ioErrorWhileReadingTheFile_isRethrown_andTheJobAndFileAreKept() {
        claimed();
        fileExists();
        when(importService.plan(FILE)).thenThrow(new UncheckedIOException("Could not read import file",
                new IOException("Stale file handle")));

        assertThatExceptionOfType(UncheckedIOException.class).isThrownBy(() -> processor.process(JOB_ID));

        verify(repository, never()).fail(anyLong(), anyInt(), anyInt(), any(), any());
        verify(storage, never()).delete(any());
        verifyNoInteractions(productService, auditLogService);
    }

    @Test
    void dataChangedBetweenValidationAndWrite_isRethrown_soRedeliveryValidatesAgain() {
        claimed();
        fileExists();
        when(importService.plan(FILE)).thenReturn(validPlan);
        when(repository.complete(JOB_ID, 6, 2, 1, 3)).thenReturn(1);

        for (RuntimeException raced : List.of(new DuplicateResourceException("Tên sản phẩm đã tồn tại!"),
                new ResourceNotFoundException("Không tìm thấy danh mục!"))) {
            doThrow(raced).when(productService).applyImport(commands);

            assertThatExceptionOfType(raced.getClass()).isThrownBy(() -> processor.process(JOB_ID));
        }

        // Không kết thúc lần import, không xoá file: lần giao lại cần cả hai
        verify(transactionManager, times(2)).rollback(any());
        verify(repository, never()).fail(anyLong(), anyInt(), anyInt(), any(), any());
        verify(storage, never()).delete(any());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void systemErrorWhileWriting_isRethrown_andTheFileIsKept() {
        claimed();
        fileExists();
        when(importService.plan(FILE)).thenReturn(validPlan);
        when(repository.complete(JOB_ID, 6, 2, 1, 3)).thenThrow(new QueryTimeoutException("lock wait timeout"));

        assertThatExceptionOfType(QueryTimeoutException.class).isThrownBy(() -> processor.process(JOB_ID));

        verify(storage, never()).delete(any());
        verifyNoInteractions(productService, auditLogService);
    }

    @Test
    void fileMissingWhileTheJobIsStillOpen_isRethrown_soAnotherInstanceCanFindIt() {
        claimed();
        when(storage.exists(STORED)).thenReturn(false);
        when(repository.findStatusById(JOB_ID)).thenReturn(Optional.of(ImportJobStatus.PROCESSING));

        assertThatIllegalStateException().isThrownBy(() -> processor.process(JOB_ID));

        verify(repository, never()).fail(anyLong(), anyInt(), anyInt(), any(), any());
        verifyNoInteractions(importService, productService);
    }

    private void claimed() {
        when(repository.claim(JOB_ID)).thenReturn(1);
        when(repository.findById(JOB_ID)).thenReturn(Optional.of(ImportJob.builder().id(JOB_ID)
                .fileName("san-pham.xlsx").storedFile(STORED).status(ImportJobStatus.PROCESSING).createdBy("admin")
                .build()));
    }

    private void fileExists() {
        when(storage.exists(STORED)).thenReturn(true);
        when(storage.resolve(STORED)).thenReturn(FILE);
    }
}
