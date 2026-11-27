package com.huuhv.foodsndrinks.service.excel;

import com.huuhv.foodsndrinks.config.properties.ExcelProperties;
import com.huuhv.foodsndrinks.dto.message.ExcelImportMessage;
import com.huuhv.foodsndrinks.dto.response.ImportJobResDto;
import com.huuhv.foodsndrinks.dto.response.ImportRowErrorResDto;
import com.huuhv.foodsndrinks.entity.ImportJob;
import com.huuhv.foodsndrinks.exception.ExcelFileException;
import com.huuhv.foodsndrinks.exception.ResourceNotFoundException;
import com.huuhv.foodsndrinks.logging.LogSanitizer;
import com.huuhv.foodsndrinks.repository.ImportJobRepository;
import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import com.huuhv.foodsndrinks.service.messaging.OutboxService;
import com.huuhv.foodsndrinks.service.messaging.OutboxType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;

/**
 * Vòng đời của một lần import Excel chạy nền (bảng {@code import_jobs}): tạo khi admin tải file lên, đọc trạng thái cho
 * trang theo dõi, và các lối kết thúc do hệ thống (DLQ, kẹt quá lâu). Phần xử lý file nằm ở {@link ImportJobProcessor}.
 * <p>
 * Lớp này không {@code @Transactional}: mỗi bước mở transaction ngắn của riêng nó, và file tạm chỉ bị xoá SAU khi trạng
 * thái kết thúc đã commit.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportJobService {

    static final String SYSTEM_FAILURE = "Lỗi hệ thống khi xử lý file. Vui lòng import lại!";
    static final String STALE_FAILURE = "File không được xử lý kịp (hệ thống bận hoặc bị gián đoạn). Vui lòng import lại!";
    private static final int FILE_NAME_MAX_LENGTH = 255;
    private static final int STALE_BATCH_SIZE = 100;
    private static final TypeReference<List<ImportRowErrorResDto>> ERROR_LIST = new TypeReference<>() {
    };

    private final ExcelUploadValidator uploadValidator;
    private final ImportFileStorage storage;
    private final ImportJobRepository repository;
    private final OutboxService outboxService;
    private final PlatformTransactionManager transactionManager;
    private final ExcelProperties properties;
    private final AuditLogService auditLogService;
    private final JsonMapper jsonMapper;

    /**
     * Nhận một file upload: kiểm tra file (rẻ, không đọc nội dung), lưu vào thư mục tạm, rồi trong MỘT transaction tạo
     * lần import {@code PENDING} và ghi message vào outbox — commit thì message chắc chắn được gửi lên queue
     * {@code excel.import} (ngay, hoặc bởi job outbox nếu broker đang chết). Trả về ngay, không chờ xử lý file.
     *
     * @param username username của admin đang đăng nhập
     * @return id lần import
     * @throws ExcelFileException file bị từ chối, hoặc admin đang có quá nhiều file chờ xử lý — thông điệp hiển thị được
     */
    public long submit(MultipartFile upload, String username) {
        uploadValidator.validate(upload);
        int limit = properties.imports().maxActiveJobsPerUser();
        if (repository.countUnfinishedByCreatedBy(username) >= limit) {
            throw new ExcelFileException("Bạn đang có " + limit + " file chờ xử lý. Hãy đợi các file đó xong rồi thử lại!");
        }

        String storedFile;
        try {
            storedFile = storage.store(upload);
        } catch (IOException e) {
            throw new ExcelFileException("Không lưu được file tải lên. Vui lòng thử lại!", e);
        }
        String fileName = displayName(upload.getOriginalFilename());
        Long id;
        try {
            id = new TransactionTemplate(transactionManager).execute(status -> {
                ImportJob job = repository.save(ImportJob.builder()
                        .fileName(fileName).storedFile(storedFile).createdBy(username).build());
                outboxService.enqueue(OutboxType.EXCEL_IMPORT, new ExcelImportMessage(job.getId()));
                return job.getId();
            });
        } catch (RuntimeException e) {
            // Không có lần import nào trỏ tới file này → xoá ngay, không để lại file mồ côi
            storage.delete(storedFile);
            throw e;
        }
        auditLogService.productImportSubmitted(id, fileName);
        return id;
    }

    /** @throws ResourceNotFoundException không có lần import {@code id} */
    public ImportJobResDto get(long id) {
        return repository.findById(id).map(this::toDto)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy lần import #" + id));
    }

    /** 10 lần import gần nhất (của mọi admin — dữ liệu quản trị dùng chung), mới nhất trước; không kèm bảng lỗi. */
    public List<ImportJobResDto> recent() {
        return repository.findTop10ByOrderByIdDesc().stream()
                .map(job -> ImportJobResDto.of(job, List.of()))
                .toList();
    }

    /**
     * Message của lần import đã vào DLQ (hết số lần giao): nếu lần import chưa kết thúc thì đánh dấu thất bại vì lỗi hệ
     * thống và xoá file tạm.
     *
     * @return {@code false} nếu lần import đã kết thúc từ trước (một lần xử lý muộn đã xong) hoặc không tồn tại
     */
    public boolean failDeadLettered(long id) {
        return failUnfinished(id, SYSTEM_FAILURE);
    }

    /**
     * Đánh dấu thất bại các lần import chưa kết thúc quá {@code app.excel.import.stale-after} (message mất, outbox không
     * gửi được, instance chết mà broker không giao lại) — không để chúng treo mãi và chiếm suất của admin.
     *
     * @return số lần import vừa bị đánh dấu
     */
    public int failStale() {
        int failed = 0;
        for (ImportJob job : repository.findStale(properties.imports().staleAfter().toSeconds(), STALE_BATCH_SIZE)) {
            if (failUnfinished(job.getId(), STALE_FAILURE)) {
                log.warn("Import job #{} did not finish in time and was marked FAILED", job.getId());
                failed++;
            }
        }
        return failed;
    }

    private boolean failUnfinished(long id, String message) {
        String storedFile = new TransactionTemplate(transactionManager).execute(status ->
                repository.failUnfinished(id, message) == 1
                        ? repository.findById(id).map(ImportJob::getStoredFile).orElse(null) : null);
        if (storedFile == null) {
            return false;
        }
        // Sau commit: lần xử lý nào còn đang chạy sẽ thấy trạng thái FAILED ở câu UPDATE cuối và không ghi gì
        storage.delete(storedFile);
        return true;
    }

    private ImportJobResDto toDto(ImportJob job) {
        return ImportJobResDto.of(job, readErrors(job));
    }

    private List<ImportRowErrorResDto> readErrors(ImportJob job) {
        if (job.getErrors() == null) {
            return List.of();
        }
        try {
            return jsonMapper.readValue(job.getErrors(), ERROR_LIST);
        } catch (JacksonException e) {
            // Không làm hỏng trang trạng thái vì một cột JSON đọc không được (do phiên bản khác ghi)
            log.warn("Could not read stored errors of import job #{}: {}", job.getId(), e.getClass().getSimpleName());
            return List.of();
        }
    }

    /** Tên file chỉ để hiển thị: bỏ phần thư mục (trình duyệt cũ gửi cả đường dẫn), làm sạch ký tự điều khiển, cắt ngắn. */
    private static String displayName(String originalFilename) {
        String name = originalFilename == null ? "" : originalFilename;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = LogSanitizer.stripLineBreaks(name).strip();
        if (name.isEmpty()) {
            return "import.xlsx";
        }
        return name.length() > FILE_NAME_MAX_LENGTH ? name.substring(0, FILE_NAME_MAX_LENGTH) : name;
    }
}
