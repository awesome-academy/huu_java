package com.huuhv.foodsndrinks.service.excel;

import com.huuhv.foodsndrinks.dto.message.ExcelImportMessage;
import com.huuhv.foodsndrinks.dto.response.ImportJobResDto;
import com.huuhv.foodsndrinks.dto.response.ImportRowErrorResDto;
import com.huuhv.foodsndrinks.entity.Category;
import com.huuhv.foodsndrinks.entity.ImportJob;
import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.enums.ImportJobStatus;
import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.exception.ExcelFileException;
import com.huuhv.foodsndrinks.repository.CategoryRepository;
import com.huuhv.foodsndrinks.repository.ImportJobRepository;
import com.huuhv.foodsndrinks.repository.ProductRepository;
import com.huuhv.foodsndrinks.repository.UserRepository;
import com.huuhv.foodsndrinks.repository.projection.ProductImportMatch;
import com.huuhv.foodsndrinks.service.messaging.AbstractMessagingIntegrationTest;
import com.huuhv.foodsndrinks.service.messaging.OutboxType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.huuhv.foodsndrinks.service.excel.ExcelTestFiles.productWorkbook;
import static com.huuhv.foodsndrinks.service.excel.ExcelTestFiles.row;
import static com.huuhv.foodsndrinks.service.excel.ExcelTestFiles.upload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Import Excel chạy nền, đầu-cuối: upload → outbox → queue {@code excel.import} trên broker thật (embedded) → consumer →
 * MySQL thật + file trên đĩa. Kế thừa nền chung của integration test JMS để dùng chung một Spring context.
 * <p>
 * Không {@code @Transactional} (consumer chạy trên thread khác, chỉ thấy dữ liệu đã commit): mỗi test dùng tên ngẫu nhiên
 * và tự xoá sản phẩm, danh mục, lần import, bản ghi outbox của nó.
 */
class ExcelImportIntegrationTest extends AbstractMessagingIntegrationTest {

    @Autowired private ImportJobService importJobService;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private ImportFileStorage storage;
    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private UserRepository userRepository;

    private final String uid = UUID.randomUUID().toString().substring(0, 8);
    private final String admin = "admin-" + uid;
    private final List<Long> jobIds = new ArrayList<>();
    private Category category;

    @BeforeEach
    void createAdminAndCategory() {
        // submit khoá dòng users của người tải lên → tài khoản phải tồn tại như khi đăng nhập thật
        userRepository.save(User.builder().username(admin).email(admin + "@example.com").fullName("Import Test")
                .role(Role.ROLE_ADMIN).isActive(true).build());
        category = categoryRepository.save(Category.builder().name("Đồ uống " + uid).slug("import-" + uid).build());
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM products WHERE name LIKE ?", "%" + uid);
        categoryRepository.deleteById(category.getId());
        for (Long id : jobIds) {
            jdbcTemplate.update("DELETE FROM outbox_messages WHERE JSON_EXTRACT(payload, '$.importJobId') = ?", id);
            importJobRepository.findById(id).ifPresent(job -> storage.delete(job.getStoredFile()));
            jdbcTemplate.update("DELETE FROM import_jobs WHERE id = ?", id);
        }
        jdbcTemplate.update("DELETE FROM users WHERE username = ?", admin);
    }

    @Test
    void validFile_isImportedInTheBackground_andItsTemporaryFileIsDeleted() {
        long id = submit(productWorkbook(
                row("Trà đào " + uid, category.getName(), "Đồ uống", 35000, "Trà đào cam sả", "Có"),
                row("Cà phê sữa " + uid, category.getName(), "DRINK", 29000, null, "Không")));
        // Upload trả về ngay: lần import đã có, file nằm trong thư mục tạm (không phải thư mục public)
        String storedFile = importJobRepository.findById(id).orElseThrow().getStoredFile();

        ImportJobResDto done = awaitFinished(id);

        assertThat(done.status()).isEqualTo(ImportJobStatus.COMPLETED);
        assertThat(done.totalRows()).isEqualTo(2);
        assertThat(done.created()).isEqualTo(2);
        assertThat(done.updated() + done.unchanged() + done.errorCount()).isZero();
        assertThat(done.createdBy()).isEqualTo(admin);
        assertThat(done.finishedAt()).isNotNull();
        ProductImportMatch tea = product("Trà đào " + uid);
        assertThat(tea.price()).isEqualByComparingTo("35000");
        assertThat(tea.categoryId()).isEqualTo(category.getId());
        assertThat(product("Cà phê sữa " + uid).isAvailable()).isFalse();
        assertThat(storage.exists(storedFile)).isFalse();
        assertThat(outboxStatus(id)).isEqualTo("SENT");
    }

    @Test
    void secondImportOfTheSameFile_updatesNothing_andAnEditedFileUpdatesByExactName() {
        awaitFinished(submit(productWorkbook(row("Trà đào " + uid, category.getName(), "Đồ uống", 35000, null, "Có"))));

        ImportJobResDto same = awaitFinished(submit(productWorkbook(
                row("Trà đào " + uid, category.getName(), "Đồ uống", 35000, null, "Có"))));
        ImportJobResDto edited = awaitFinished(submit(productWorkbook(
                row("Trà đào " + uid, category.getName(), "Đồ uống", 39000, "Giá mới", "Có"))));

        assertThat(same.status()).isEqualTo(ImportJobStatus.COMPLETED);
        assertThat(same.unchanged()).isEqualTo(1);
        assertThat(same.created() + same.updated()).isZero();
        assertThat(edited.updated()).isEqualTo(1);
        assertThat(product("Trà đào " + uid).price()).isEqualByComparingTo("39000");
        assertThat(productRepository.findImportMatchesByName("Trà đào " + uid)).hasSize(1);
    }

    @Test
    void fileWithRowErrors_failsWithTheErrorTable_andSavesNoRowAtAll() {
        long id = submit(productWorkbook(
                row("Hợp lệ " + uid, category.getName(), "Đồ uống", 10000, null, "Có"),
                row("Sai giá " + uid, category.getName(), "Đồ uống", 0, null, "Có")));
        String storedFile = importJobRepository.findById(id).orElseThrow().getStoredFile();

        ImportJobResDto failed = awaitFinished(id);

        assertThat(failed.status()).isEqualTo(ImportJobStatus.FAILED);
        assertThat(failed.totalRows()).isEqualTo(2);
        assertThat(failed.errorCount()).isEqualTo(1);
        assertThat(failed.errors()).containsExactly(new ImportRowErrorResDto(3, "Giá", "Giá phải lớn hơn 0!"));
        assertThat(failed.failureMessage()).isNull();
        // Dòng hợp lệ cũng không được lưu
        assertThat(productRepository.findImportMatchesByName("Hợp lệ " + uid)).isEmpty();
        assertThat(storage.exists(storedFile)).isFalse();
        // Lỗi của nội dung file là kết quả cuối: message được ack, không vào DLQ
        assertThat(outboxStatus(id)).isEqualTo("SENT");
    }

    @Test
    void fileThatDoesNotFollowTheTemplate_failsWithAReason_insteadOfBeingRedelivered() {
        long id = submit(ExcelTestFiles.workbook(row("Sản phẩm", "Giá"), row("Trà đào " + uid, 35000)));

        ImportJobResDto failed = awaitFinished(id);

        assertThat(failed.status()).isEqualTo(ImportJobStatus.FAILED);
        assertThat(failed.failureMessage()).startsWith("File không đúng mẫu");
        assertThat(failed.errorCount()).isZero();
        assertThat(outboxStatus(id)).isEqualTo("SENT");
    }

    @Test
    void redeliveredOrDuplicateMessage_doesNotImportASecondTime(CapturedOutput output) {
        long id = submit(productWorkbook(row("Trà đào " + uid, category.getName(), "Đồ uống", 35000, null, "Có")));
        ImportJobResDto first = awaitFinished(id);

        // Message thứ hai cho cùng lần import (broker giao lại sau khi đã xử lý xong, hoặc gửi trùng)
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                outboxService.enqueue(OutboxType.EXCEL_IMPORT, new ExcelImportMessage(id)));
        await().atMost(WAIT).untilAsserted(() -> assertThat(output)
                .contains("Import job #" + id + " is already finished or does not exist, message skipped"));

        assertThat(productRepository.findImportMatchesByName("Trà đào " + uid)).hasSize(1);
        ImportJobResDto after = importJobService.get(id);
        assertThat(after.status()).isEqualTo(ImportJobStatus.COMPLETED);
        assertThat(after.created()).isEqualTo(first.created()).isEqualTo(1);
        assertThat(after.finishedAt()).isEqualTo(first.finishedAt());
    }

    @Test
    void systemFailureOnEveryDelivery_endsInTheDeadLetterQueue_andTheJobIsMarkedFailed(CapturedOutput output) {
        // Lần import có thật nhưng file không có trên instance này (thư mục import không dùng chung giữa các instance)
        long id = new TransactionTemplate(transactionManager).execute(status -> {
            ImportJob job = importJobRepository.save(ImportJob.builder().fileName("mat-file.xlsx")
                    .storedFile(UUID.randomUUID() + ".xlsx").createdBy(admin).build());
            outboxService.enqueue(OutboxType.EXCEL_IMPORT, new ExcelImportMessage(job.getId()));
            return job.getId();
        });
        jobIds.add(id);

        ImportJobResDto failed = awaitFinished(id);

        assertThat(failed.status()).isEqualTo(ImportJobStatus.FAILED);
        assertThat(failed.failureMessage()).isEqualTo(ImportJobService.SYSTEM_FAILURE);
        // Đúng 5 lần giao (artemis/broker.properties) rồi mới vào DLQ
        assertThat(output.getOut().split("Import job #" + id + " failed on delivery ", -1)).hasSize(6);
        assertThat(output).contains("Import job #" + id + " dead-lettered after all delivery attempts");
        await().atMost(WAIT).untilAsserted(() -> assertThat(outboxStatus(id)).isEqualTo("DEAD_LETTERED"));
    }

    @Test
    void uploadBeyondTheNumberOfUnfinishedImportsAllowed_isRejected() {
        // 3 lần import chưa kết thúc của admin này (không có message → không ai xử lý chúng)
        for (int i = 0; i < 3; i++) {
            jobIds.add(importJobRepository.save(ImportJob.builder().fileName("cho.xlsx")
                    .storedFile(UUID.randomUUID() + ".xlsx").createdBy(admin).build()).getId());
        }

        org.assertj.core.api.Assertions.assertThatExceptionOfType(ExcelFileException.class)
                .isThrownBy(() -> importJobService.submit(upload("san-pham.xlsx", productWorkbook(
                        row("Trà đào " + uid, category.getName(), "Đồ uống", 35000, null, "Có"))), admin))
                .withMessageStartingWith("Bạn đang có 3 file chờ xử lý");
    }

    /**
     * Nhiều upload cùng lúc của cùng một admin khi chỉ còn MỘT chỗ: kiểm tra nhanh ngoài transaction đều thấy "còn
     * chỗ", nhưng lần đếm lại sau khi khoá dòng users chỉ để đúng một request qua.
     */
    @Test
    void concurrentUploadsOfTheSameAdmin_neverGoBeyondTheLimit() throws Exception {
        // 2 lần import chưa kết thúc (không có message → không ai xử lý chúng) → còn đúng 1 chỗ trong giới hạn 3
        for (int i = 0; i < 2; i++) {
            jobIds.add(importJobRepository.save(ImportJob.builder().fileName("cho.xlsx")
                    .storedFile(UUID.randomUUID() + ".xlsx").createdBy(admin).build()).getId());
        }
        byte[] content = productWorkbook(row("Song song " + uid, category.getName(), "Đồ uống", 35000, null, "Có"));
        int uploads = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(uploads);
        List<Long> accepted = new ArrayList<>();
        int rejected = 0;
        try {
            List<Future<Long>> results = new ArrayList<>();
            for (int i = 0; i < uploads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return importJobService.submit(upload("san-pham.xlsx", content), admin);
                }));
            }
            start.countDown();
            for (Future<Long> result : results) {
                try {
                    accepted.add(result.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(ExcelFileException.class)
                            .hasMessageStartingWith("Bạn đang có 3 file chờ xử lý");
                    rejected++;
                }
            }
        } finally {
            pool.shutdownNow();
            jobIds.addAll(accepted);
        }

        assertThat(accepted).hasSize(1);
        assertThat(rejected).isEqualTo(uploads - 1);
        // Để consumer xử lý xong trước khi dọn dữ liệu
        awaitFinished(accepted.getFirst());
    }

    @Test
    void twoNewRowsThatMySqlConsidersTheSameName_failAtTheSecondRow_insteadOfGoingToTheDeadLetterQueue() {
        // "ß" = "ss" với collation của cột; bước kiểm tra trùng trong file không bắt được
        long id = submit(productWorkbook(
                row("Strasse " + uid, category.getName(), "Đồ uống", 10000, null, "Có"),
                row("Straße " + uid, category.getName(), "Đồ uống", 20000, null, "Có")));

        ImportJobResDto failed = awaitFinished(id);

        assertThat(failed.status()).isEqualTo(ImportJobStatus.FAILED);
        assertThat(failed.errorCount()).isEqualTo(1);
        assertThat(failed.errors()).containsExactly(
                new ImportRowErrorResDto(3, "Tên", ImportJobProcessor.nameConflictMessage(2)));
        assertThat(productRepository.findImportMatchesByName("Strasse " + uid)).isEmpty();
        // Kết quả cuối ngay lần giao đầu: message được ack, không giao lại tới DLQ
        assertThat(outboxStatus(id)).isEqualTo("SENT");
    }

    private long submit(byte[] content) {
        long id = importJobService.submit(upload("san-pham.xlsx", content), admin);
        jobIds.add(id);
        return id;
    }

    private ImportJobResDto awaitFinished(long id) {
        return await().atMost(WAIT).until(() -> importJobService.get(id), ImportJobResDto::terminal);
    }

    private ProductImportMatch product(String name) {
        List<ProductImportMatch> found = productRepository.findImportMatchesByName(name);
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    private String outboxStatus(long jobId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_messages WHERE JSON_EXTRACT(payload, '$.importJobId') = ? ORDER BY id LIMIT 1",
                String.class, jobId);
    }
}
