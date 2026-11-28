package com.huuhv.foodsndrinks.service.excel;

import com.huuhv.foodsndrinks.dto.response.ImportRowErrorResDto;
import com.huuhv.foodsndrinks.dto.response.ProductImportResDto;
import com.huuhv.foodsndrinks.entity.ImportJob;
import com.huuhv.foodsndrinks.enums.ImportJobStatus;
import com.huuhv.foodsndrinks.exception.ExcelFileException;
import com.huuhv.foodsndrinks.exception.ImportNameConflictException;
import com.huuhv.foodsndrinks.repository.ImportJobRepository;
import com.huuhv.foodsndrinks.service.ProductService;
import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.List;

/**
 * Xử lý một lần import Excel (gọi từ consumer của queue {@code excel.import}): nhận việc → đọc + validate file → ghi.
 * <p>
 * <b>Không import hai lần, dù message bị giao lại hay hai instance cùng xử lý:</b>
 * <ol>
 *   <li>Nhận việc bằng UPDATE có điều kiện ({@code PENDING}/{@code PROCESSING} → {@code PROCESSING}); lần import đã kết
 *       thúc → bỏ qua message.</li>
 *   <li>Sản phẩm và trạng thái {@code COMPLETED} commit trong CÙNG một transaction, mà câu lệnh đầu tiên là
 *       {@code UPDATE … WHERE status = 'PROCESSING'} (khoá dòng của lần import). Lần xử lý chết giữa chừng chưa commit gì
 *       nên lần giao lại được phép chạy lại; hai lần chạy song song thì lần sau chờ khoá, thấy trạng thái đã đổi và không
 *       ghi gì.</li>
 * </ol>
 * <b>Lỗi nào thì giao lại:</b> lỗi của nội dung file (dòng lỗi, sai mẫu, hai dòng thêm mới mà MySQL coi là cùng tên…) là
 * kết quả cuối — ghi {@code FAILED} và trả về bình thường (ack). Lỗi hệ thống (DB, I/O khi đọc file, không thấy file trên
 * instance này) được ném ra → broker giao lại, quá số lần thì
 * vào DLQ ({@code ExcelImportDlqListener} ghi {@code FAILED}). Dữ liệu đổi giữa lúc validate và lúc ghi cũng được ném ra:
 * lần giao lại validate trên dữ liệu mới.
 * <p>
 * File tạm chỉ bị xoá sau khi trạng thái kết thúc đã commit. Lớp này cố ý không {@code @Transactional}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportJobProcessor {

    static final String UNEXPECTED_DATA = "Không import được file vì dữ liệu không hợp lệ. Kiểm tra lại file rồi thử lại!";

    static String nameConflictMessage(int conflictingRow) {
        return "Trùng tên với dòng " + conflictingRow + " trong file (hệ thống coi hai tên là một — có thể chúng chỉ khác"
                + " nhau ở ký tự ẩn hoặc cách viết đặc biệt). Hãy đổi tên một trong hai dòng!";
    }

    private final ImportJobRepository repository;
    private final ImportFileStorage storage;
    private final ProductImportService importService;
    private final ProductService productService;
    private final PlatformTransactionManager transactionManager;
    private final AuditLogService auditLogService;
    private final JsonMapper jsonMapper;

    /**
     * @throws RuntimeException lỗi hệ thống hoặc dữ liệu vừa đổi — người gọi (listener JMS) để exception thoát ra để broker
     *                          giao lại message
     */
    public void process(long jobId) {
        ImportJob job = claim(jobId);
        if (job == null) {
            log.info("Import job #{} is already finished or does not exist, message skipped", jobId);
            return;
        }

        Path file = existingFile(job);
        if (file == null) {
            log.info("Import job #{} was finished by another run, message skipped", jobId);
            return;
        }
        ProductImportPlan plan;
        try {
            plan = importService.plan(file);
        } catch (ExcelFileException rejected) {
            finishFailed(job, 0, 0, null, rejected.getMessage());
            return;
        }
        if (plan.hasErrors()) {
            ProductImportResDto failed = importService.failed(plan);
            finishFailed(job, plan.totalRows(), failed.errorCount(), jsonMapper.writeValueAsString(failed.errors()), null);
            return;
        }

        boolean completed;
        try {
            completed = Boolean.TRUE.equals(new TransactionTemplate(transactionManager).execute(status -> {
                // Câu lệnh đầu tiên: khoá dòng của lần import và xác nhận nó vẫn do lần xử lý này giữ
                if (repository.complete(jobId, plan.totalRows(), plan.createCount(), plan.updateCount(),
                        plan.unchangedCount()) == 0) {
                    return false;
                }
                productService.applyImport(plan.commands());
                return true;
            }));
        } catch (ImportNameConflictException conflict) {
            // Hai dòng thêm mới mà MySQL coi là cùng tên (validator chỉ so xấp xỉ collation): giao lại cũng trùng như vậy
            // → lỗi ở đúng dòng đó, kết thúc luôn
            log.info("Import job #{} failed while writing: row {} duplicates row {}", jobId, conflict.getRow(),
                    conflict.getConflictingRow());
            ImportRowErrorResDto error = new ImportRowErrorResDto(conflict.getRow(), ProductExcelColumns.NAME,
                    nameConflictMessage(conflict.getConflictingRow()));
            finishFailed(job, plan.totalRows(), 1, jsonMapper.writeValueAsString(List.of(error)), null);
            return;
        } catch (IllegalArgumentException invalid) {
            // Dữ liệu lọt qua validate nhưng bị tầng ghi từ chối: giao lại cũng cho cùng kết quả → kết thúc luôn
            log.error("Import job #{} was rejected while writing", jobId, invalid);
            finishFailed(job, plan.totalRows(), 0, null, UNEXPECTED_DATA);
            return;
        }
        if (!completed) {
            log.info("Import job #{} was finished by another run, nothing written", jobId);
            return;
        }
        storage.delete(job.getStoredFile());
        auditLogService.productsImported(job.getCreatedBy(), jobId, job.getFileName(), plan.createCount(),
                plan.updateCount(), plan.unchangedCount());
        log.info("Import job #{} completed: {} created, {} updated, {} unchanged", jobId, plan.createCount(),
                plan.updateCount(), plan.unchangedCount());
    }

    /** @return lần import vừa nhận, {@code null} nếu đã kết thúc hoặc không tồn tại */
    private ImportJob claim(long jobId) {
        return new TransactionTemplate(transactionManager).execute(status ->
                repository.claim(jobId) == 1 ? repository.findById(jobId).orElse(null) : null);
    }

    /**
     * @return {@code null} khi không còn file VÀ lần import đã kết thúc (lần xử lý khác đã xong và xoá file)
     * @throws IllegalStateException không thấy file trong khi lần import chưa kết thúc — thường là thư mục import không
     *                               dùng chung giữa các instance; giao lại để instance khác (thấy file) xử lý
     */
    private Path existingFile(ImportJob job) {
        if (storage.exists(job.getStoredFile())) {
            return storage.resolve(job.getStoredFile());
        }
        boolean finished = repository.findStatusById(job.getId()).map(ImportJobStatus::isTerminal).orElse(true);
        if (finished) {
            return null;
        }
        log.error("File of import job #{} is not in app.excel.import.dir on this instance — with several instances the "
                + "directory must be a shared volume (EXCEL_IMPORT_DIR)", job.getId());
        throw new IllegalStateException("Import file of job #" + job.getId() + " not found");
    }

    private void finishFailed(ImportJob job, int totalRows, int errorCount, String errorsJson, String failureMessage) {
        Integer updated = new TransactionTemplate(transactionManager).execute(status ->
                repository.fail(job.getId(), totalRows, errorCount, errorsJson, failureMessage));
        if (updated != null && updated == 1) {
            storage.delete(job.getStoredFile());
            log.info("Import job #{} failed: {} row errors{}", job.getId(), errorCount,
                    failureMessage == null ? "" : ", file rejected");
        }
    }
}
