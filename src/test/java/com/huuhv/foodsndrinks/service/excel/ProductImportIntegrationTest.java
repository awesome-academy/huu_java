package com.huuhv.foodsndrinks.service.excel;

import com.huuhv.foodsndrinks.dto.request.ProductImportCommand;
import com.huuhv.foodsndrinks.dto.request.ProductReqDto;
import com.huuhv.foodsndrinks.dto.response.ImportRowErrorResDto;
import com.huuhv.foodsndrinks.dto.response.ProductImportResDto;
import com.huuhv.foodsndrinks.entity.Category;
import com.huuhv.foodsndrinks.enums.ProductType;
import com.huuhv.foodsndrinks.exception.ResourceNotFoundException;
import com.huuhv.foodsndrinks.repository.CategoryRepository;
import com.huuhv.foodsndrinks.repository.ProductRepository;
import com.huuhv.foodsndrinks.repository.projection.ProductImportMatch;
import com.huuhv.foodsndrinks.service.ProductService;
import com.huuhv.foodsndrinks.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static com.huuhv.foodsndrinks.service.excel.ExcelTestFiles.productWorkbook;
import static com.huuhv.foodsndrinks.service.excel.ExcelTestFiles.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Import sản phẩm từ file .xlsx thật vào MySQL thật: quy tắc "trùng tên" là của collation MySQL (unit test chỉ giả lập),
 * và tính nguyên tử của bước ghi là của transaction thật. Tên sản phẩm/danh mục gắn hậu tố ngẫu nhiên để không đụng dữ
 * liệu của class test khác trong cùng DB.
 */
@IntegrationTest
class ProductImportIntegrationTest {

    @Autowired private ProductImportService importService;
    @Autowired private ProductService productService;
    @Autowired private ProductRepository productRepository;
    @Autowired private CategoryRepository categoryRepository;

    @TempDir Path directory;

    private final String uid = UUID.randomUUID().toString().substring(0, 8);

    @Test
    @Transactional
    void validFile_addsNewProducts_updatesExactNameMatches_andSkipsUnchangedRows() {
        Category drinks = category("Đồ uống " + uid);
        Category foods = category("Món chính " + uid);
        importOk(row("Trà đào " + uid, drinks.getName(), "Đồ uống", 30000, "cũ", "Có"),
                row("Cơm tấm " + uid, foods.getName(), "Đồ ăn", 45000, null, "Có"));

        ProductImportResDto result = importFile(productWorkbook(
                // đổi giá, danh mục, mô tả, ngừng bán
                row("Trà đào " + uid, foods.getName(), "Đồ uống", 35000.5, "mới", "Không"),
                // giống hệt sản phẩm đang có
                row("Cơm tấm " + uid, foods.getName(), "FOOD", 45000, null, null),
                row("Bánh mì " + uid, foods.getName(), "Đồ ăn", 20000, "=1+1", "Có")));

        assertThat(result).isEqualTo(new ProductImportResDto(3, 1, 1, 1, 0, List.of()));

        ProductImportMatch tea = only("Trà đào " + uid);
        assertThat(tea.categoryId()).isEqualTo(foods.getId());
        assertThat(tea.price()).isEqualByComparingTo("35000.5");
        assertThat(tea.description()).isEqualTo("mới");
        assertThat(tea.isAvailable()).isFalse();
        // Cập nhật giữ nguyên slug (URL trang sản phẩm không đổi)
        assertThat(productRepository.findById(tea.id()).orElseThrow().getSlug()).isEqualTo("tra-dao-" + uid);

        ProductImportMatch bread = only("Bánh mì " + uid);
        assertThat(bread.type()).isEqualTo(ProductType.FOOD);
        // Giá trị trông như công thức được lưu nguyên văn dạng chữ (được chống khi export/hiển thị)
        assertThat(bread.description()).isEqualTo("=1+1");
        assertThat(productRepository.findById(bread.id()).orElseThrow().getSlug()).isEqualTo("banh-mi-" + uid);
    }

    @Test
    @Transactional
    void oneBadRow_meansNoRowIsSaved_andEveryErrorIsReportedWithItsRowAndColumn() {
        Category foods = category("Món chính " + uid);

        ProductImportResDto result = importFile(productWorkbook(
                row("Hợp lệ " + uid, foods.getName(), "Đồ ăn", 10000, null, "Có"),
                row("Sai giá " + uid, foods.getName(), "Đồ ăn", 0, null, "Có"),
                row("Sai danh mục " + uid, "Không có " + uid, "Nước", "abc", null, "Có")));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.totalRows()).isEqualTo(3);
        assertThat(result.errors()).containsExactly(
                new ImportRowErrorResDto(3, "Giá", "Giá phải lớn hơn 0!"),
                new ImportRowErrorResDto(4, "Danh mục",
                        "Danh mục không tồn tại! Hãy tạo danh mục trước hoặc nhập đúng tên danh mục."),
                new ImportRowErrorResDto(4, "Loại", "Loại không hợp lệ! Chỉ nhận: Đồ ăn hoặc Đồ uống."),
                new ImportRowErrorResDto(4, "Giá",
                        "Giá phải là số (nhập số, không kèm chữ, dấu phẩy hay ký hiệu tiền tệ)!"));
        assertThat(productRepository.findImportMatchesByName("Hợp lệ " + uid)).isEmpty();
    }

    @Test
    @Transactional
    void nameDifferingOnlyByCaseOrAccents_matchesInMySql_butIsRejectedInsteadOfUpdated() {
        Category foods = category("Món chính " + uid);
        importOk(row("Bò kho " + uid, foods.getName(), "Đồ ăn", 50000, null, "Có"));

        // Collation của cột coi cả ba cách viết là cùng một tên
        assertThat(productRepository.findImportMatchesByName("BO KHO " + uid.toUpperCase()))
                .extracting(ProductImportMatch::name).containsExactly("Bò kho " + uid);
        assertThat(productRepository.findImportMatchesByName("Bơ khô " + uid)).hasSize(1);

        ProductImportResDto result = importFile(productWorkbook(
                row("Bơ khô " + uid, foods.getName(), "Đồ ăn", 99000, null, "Có"),
                row("Mới " + uid, foods.getName(), "Đồ ăn", 1000, null, "Có")));

        assertThat(result.errors()).singleElement().satisfies(error -> {
            assertThat(error.row()).isEqualTo(2);
            assertThat(error.column()).isEqualTo("Tên");
            assertThat(error.message()).startsWith("Tên gần trùng với một sản phẩm đã có");
        });
        // Sản phẩm "Bò kho" không bị sửa, dòng hợp lệ cũng không được thêm
        assertThat(only("Bò kho " + uid).price()).isEqualByComparingTo("50000");
        assertThat(productRepository.findImportMatchesByName("Mới " + uid)).isEmpty();
    }

    /** Không {@code @Transactional}: kiểm tra đúng transaction của {@code applyImport} rollback mọi dòng đã ghi. */
    @Test
    void writeFailingOnALaterRow_rollsBackTheRowsAlreadyWritten() {
        Category foods = categoryRepository.save(newCategory("Rollback " + uid));
        try {
            ProductReqDto valid = new ProductReqDto();
            valid.setName("Ghi dở " + uid);
            valid.setCategoryId(foods.getId());
            valid.setType(ProductType.FOOD);
            valid.setPrice(new BigDecimal("10000"));

            // Dòng 1 thêm được, dòng 2 cập nhật một sản phẩm không còn tồn tại
            assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() -> productService.applyImport(List.of(
                    new ProductImportCommand(null, valid),
                    new ProductImportCommand(Long.MAX_VALUE, valid))));

            assertThat(productRepository.findImportMatchesByName("Ghi dở " + uid)).isEmpty();
        } finally {
            categoryRepository.delete(foods);
        }
    }

    /** Như {@code ImportJobProcessor}: đọc + validate file thật, không lỗi thì ghi trong một transaction. */
    private ProductImportResDto importFile(byte[] content) {
        ProductImportPlan plan = importService.plan(ExcelTestFiles.write(directory, content));
        if (plan.hasErrors()) {
            return importService.failed(plan);
        }
        productService.applyImport(plan.commands());
        return new ProductImportResDto(plan.totalRows(), plan.createCount(), plan.updateCount(), plan.unchangedCount(),
                0, List.of());
    }

    private void importOk(Object[]... rows) {
        ProductImportResDto result = importFile(productWorkbook(rows));
        assertThat(result.errors()).isEmpty();
        assertThat(result.created()).isEqualTo(rows.length);
    }

    private ProductImportMatch only(String name) {
        List<ProductImportMatch> found = productRepository.findImportMatchesByName(name);
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    private Category category(String name) {
        return categoryRepository.saveAndFlush(newCategory(name));
    }

    private Category newCategory(String name) {
        return Category.builder().name(name).slug("import-" + UUID.randomUUID()).build();
    }
}
