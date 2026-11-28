package com.huuhv.foodsndrinks.service.excel;

import com.huuhv.foodsndrinks.dto.request.ProductImportCommand;
import com.huuhv.foodsndrinks.dto.request.ProductReqDto;
import com.huuhv.foodsndrinks.dto.response.ImportRowErrorResDto;
import com.huuhv.foodsndrinks.dto.response.ProductImportResDto;
import com.huuhv.foodsndrinks.entity.Category;
import com.huuhv.foodsndrinks.exception.ExcelFileException;
import com.huuhv.foodsndrinks.service.CategoryService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit test {@link ProductImportService}: đọc → validate (không ghi), giới hạn số lỗi hiển thị, và file mẫu. */
@ExtendWith(MockitoExtension.class)
class ProductImportServiceTest {

    @Mock private ProductImportReader reader;
    @Mock private ProductImportValidator validator;
    @Mock private CategoryService categoryService;

    private ProductImportService service;
    private final List<ProductImportRow> rows = List.of(
            new ProductImportRow(2, "Trà đào", "Đồ uống", "DRINK", "35000", null, "Có"));

    @BeforeEach
    void setUp() {
        service = new ProductImportService(reader, validator, categoryService, ExcelTestFiles.properties());
    }

    @Test
    void plan_readsTheFile_thenValidatesItsRows_withoutWritingAnything() {
        Path file = Path.of("some-import.xlsx");
        ProductImportPlan plan = new ProductImportPlan(1, List.of(command(null)), 0, List.of());
        when(reader.read(file)).thenReturn(rows);
        when(validator.validate(rows)).thenReturn(plan);

        assertThat(service.plan(file)).isSameAs(plan);
    }

    @Test
    void plan_rejectedFile_isNotValidated() {
        Path file = Path.of("some-import.xlsx");
        when(reader.read(file)).thenThrow(new ExcelFileException("File không đúng mẫu"));

        assertThatExceptionOfType(ExcelFileException.class).isThrownBy(() -> service.plan(file));
        verifyNoInteractions(validator);
    }

    @Test
    void failed_countsEveryError_butKeepsOnlyTheConfiguredNumberForDisplay() {
        List<ImportRowErrorResDto> errors = new ArrayList<>(IntStream.rangeClosed(2, 301)
                .mapToObj(rowNumber -> new ImportRowErrorResDto(rowNumber, "Giá", "Giá phải lớn hơn 0!")).toList());

        ProductImportResDto result = service.failed(new ProductImportPlan(300, List.of(), 0, errors));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.totalRows()).isEqualTo(300);
        assertThat(result.errorCount()).isEqualTo(300);
        assertThat(result.errors()).hasSize(200).first().extracting(ImportRowErrorResDto::row).isEqualTo(2);
        assertThat(result.getHiddenErrorCount()).isEqualTo(100);
        assertThat(result.created() + result.updated() + result.unchanged()).isZero();
    }

    @Test
    void limits_areExposedForTheImportPage() {
        assertThat(service.maxRows()).isEqualTo(1_000);
        assertThat(service.maxFileSizeMb()).isEqualTo(5);
    }

    @Test
    void template_hasTheImportColumns_dropdowns_andAGuideSheetListingCategories() throws IOException {
        when(categoryService.getAllCategories()).thenReturn(List.of(category("Đồ uống"), category("=SUM(1)")));

        try (ExcelFile template = service.template(); XSSFWorkbook workbook = reopen(template)) {
            assertThat(template.filename()).isEqualTo("foodsndrinks-products-template.xlsx");

            XSSFSheet products = workbook.getSheetAt(0);
            assertThat((Iterable<Cell>) products.getRow(0)).extracting(Cell::getStringCellValue)
                    .containsExactly("Tên", "Danh mục", "Loại", "Giá", "Mô tả", "Còn bán");
            assertThat(products.getLastRowNum()).isZero();
            assertThat(products.getDataValidations())
                    .extracting(validation -> validation.getRegions().getCellRangeAddress(0).formatAsString())
                    .containsExactlyInAnyOrder("C2:C1001", "F2:F1001");
            assertThat(products.getDataValidations()).extracting(DataValidation::getValidationConstraint)
                    .extracting(constraint -> List.of(constraint.getExplicitListValues()))
                    .containsExactlyInAnyOrder(List.of("Đồ ăn", "Đồ uống"), List.of("Có", "Không"));

            XSSFSheet guide = workbook.getSheetAt(1);
            List<String> guideText = new ArrayList<>();
            guide.forEach(guideRow -> guideRow.forEach(cell -> guideText.add(cell.getStringCellValue())));
            assertThat(guideText).contains("Danh mục hiện có", "Đồ uống", "=SUM(1)");
            // Tên danh mục là dữ liệu người dùng: cũng được chống formula injection
            assertThat(guide.getRow(guide.getLastRowNum()).getCell(1).getCellStyle().getQuotePrefixed()).isTrue();
        }
    }

    @Test
    void template_canBeImportedBackOnceFilled() throws IOException {
        // File mẫu và bộ đọc dùng chung một định nghĩa cột
        when(categoryService.getAllCategories()).thenReturn(List.of());
        Path directory = Files.createTempDirectory("template-test");
        try (ExcelFile template = service.template(); XSSFWorkbook workbook = reopen(template)) {
            var dataRow = workbook.getSheetAt(0).createRow(1);
            dataRow.createCell(0).setCellValue("Trà đào");
            dataRow.createCell(3).setCellValue(35000);

            List<ProductImportRow> read = new ProductImportReader(ExcelTestFiles.properties())
                    .read(ExcelTestFiles.write(directory, ExcelTestFiles.bytes(workbook)));

            assertThat(read).containsExactly(new ProductImportRow(2, "Trà đào", null, null, "35000", null, null));
        }
    }

    private static ProductImportCommand command(Long productId) {
        return new ProductImportCommand(2, productId, new ProductReqDto());
    }

    private static Category category(String name) {
        Category category = new Category();
        category.setName(name);
        return category;
    }

    private static XSSFWorkbook reopen(ExcelFile file) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        file.writeTo(out);
        return new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()));
    }
}
