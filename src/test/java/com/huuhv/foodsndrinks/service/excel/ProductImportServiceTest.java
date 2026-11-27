package com.huuhv.foodsndrinks.service.excel;

import com.huuhv.foodsndrinks.dto.request.ProductImportCommand;
import com.huuhv.foodsndrinks.dto.request.ProductReqDto;
import com.huuhv.foodsndrinks.dto.response.ImportRowErrorResDto;
import com.huuhv.foodsndrinks.dto.response.ProductImportResDto;
import com.huuhv.foodsndrinks.entity.Category;
import com.huuhv.foodsndrinks.exception.DuplicateResourceException;
import com.huuhv.foodsndrinks.exception.ExcelFileException;
import com.huuhv.foodsndrinks.exception.ResourceNotFoundException;
import com.huuhv.foodsndrinks.service.CategoryService;
import com.huuhv.foodsndrinks.service.ProductService;
import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static com.huuhv.foodsndrinks.service.excel.ExcelTestFiles.productWorkbook;
import static com.huuhv.foodsndrinks.service.excel.ExcelTestFiles.row;
import static com.huuhv.foodsndrinks.service.excel.ExcelTestFiles.upload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit test {@link ProductImportService}: điều phối kiểm tra file → đọc → validate → ghi, và file mẫu. */
@ExtendWith(MockitoExtension.class)
class ProductImportServiceTest {

    private static final byte[] FILE = productWorkbook(row("Trà đào", "Đồ uống", "DRINK", 35000, null, "Có"));

    @Mock private ExcelUploadValidator uploadValidator;
    @Mock private ProductImportReader reader;
    @Mock private ProductImportValidator validator;
    @Mock private ProductService productService;
    @Mock private CategoryService categoryService;
    @Mock private AuditLogService auditLogService;

    private ProductImportService service;
    private final List<ProductImportRow> rows = List.of(
            new ProductImportRow(2, "Trà đào", "Đồ uống", "DRINK", "35000", null, "Có"));

    @BeforeEach
    void setUp() {
        service = new ProductImportService(uploadValidator, reader, validator, productService, categoryService,
                ExcelTestFiles.properties(), auditLogService);
    }

    @Test
    void validFile_isWrittenInOneCall_audited_andTheTemporaryCopyIsDeleted() {
        List<ProductImportCommand> commands = List.of(command(null), command(null), command(7L));
        ArgumentCaptor<Path> file = ArgumentCaptor.forClass(Path.class);
        when(reader.read(file.capture())).thenReturn(rows);
        when(validator.validate(rows)).thenReturn(new ProductImportPlan(5, commands, 2, List.of()));
        MockMultipartFile upload = upload("san-pham.xlsx", FILE);

        ProductImportResDto result = service.importProducts(upload);

        assertThat(result).isEqualTo(new ProductImportResDto(5, 2, 1, 2, 0, List.of()));
        assertThat(result.isSuccess()).isTrue();
        verify(uploadValidator).validate(upload);
        verify(productService).applyImport(commands);
        verify(auditLogService).productsImported("san-pham.xlsx", 2, 1, 2);
        // File được đọc từ một bản sao tạm có tên do server đặt, và bản sao đã bị xoá
        assertThat(file.getValue().getFileName().toString()).startsWith("product-import-").endsWith(".xlsx")
                .doesNotContain("san-pham");
        assertThat(Files.exists(file.getValue())).isFalse();
    }

    @Test
    void anyRowError_meansNothingIsWritten_andErrorsAreReturned() {
        List<ImportRowErrorResDto> errors = List.of(new ImportRowErrorResDto(3, "Giá", "Giá phải lớn hơn 0!"));
        when(reader.read(any())).thenReturn(rows);
        when(validator.validate(rows)).thenReturn(new ProductImportPlan(4, List.of(), 0, errors));

        ProductImportResDto result = service.importProducts(upload("san-pham.xlsx", FILE));

        assertThat(result).isEqualTo(new ProductImportResDto(4, 0, 0, 0, 1, errors));
        assertThat(result.isSuccess()).isFalse();
        verify(productService, never()).applyImport(any());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void manyErrors_areCountedInFull_butOnlyTheConfiguredNumberIsKeptForDisplay() {
        List<ImportRowErrorResDto> errors = new ArrayList<>(IntStream.rangeClosed(2, 301)
                .mapToObj(rowNumber -> new ImportRowErrorResDto(rowNumber, "Giá", "Giá phải lớn hơn 0!")).toList());
        when(reader.read(any())).thenReturn(rows);
        when(validator.validate(rows)).thenReturn(new ProductImportPlan(300, List.of(), 0, errors));

        ProductImportResDto result = service.importProducts(upload("san-pham.xlsx", FILE));

        assertThat(result.errorCount()).isEqualTo(300);
        assertThat(result.errors()).hasSize(200).first().extracting(ImportRowErrorResDto::row).isEqualTo(2);
        assertThat(result.getHiddenErrorCount()).isEqualTo(100);
    }

    @Test
    void rejectedUpload_isNeitherCopiedNorRead() {
        MockMultipartFile upload = upload("san-pham.xls", FILE);
        doThrow(new ExcelFileException("Chỉ nhận file Excel có đuôi .xlsx!")).when(uploadValidator).validate(upload);

        assertThatExceptionOfType(ExcelFileException.class)
                .isThrownBy(() -> service.importProducts(upload))
                .withMessage("Chỉ nhận file Excel có đuôi .xlsx!");
        verifyNoInteractions(reader, validator, productService, auditLogService);
    }

    @Test
    void unreadableFile_stillDeletesTheTemporaryCopy() {
        ArgumentCaptor<Path> file = ArgumentCaptor.forClass(Path.class);
        when(reader.read(file.capture())).thenThrow(new ExcelFileException("File không đúng mẫu"));

        assertThatExceptionOfType(ExcelFileException.class)
                .isThrownBy(() -> service.importProducts(upload("san-pham.xlsx", FILE)));
        assertThat(Files.exists(file.getValue())).isFalse();
        verifyNoInteractions(productService);
    }

    @Test
    void dataChangedBetweenValidationAndWrite_isReportedAsRetryable_andNotAudited() {
        List<ProductImportCommand> commands = List.of(command(null));
        when(reader.read(any())).thenReturn(rows);
        when(validator.validate(rows)).thenReturn(new ProductImportPlan(1, commands, 0, List.of()));

        for (RuntimeException raced : List.of(new DuplicateResourceException("Tên sản phẩm đã tồn tại!"),
                new ResourceNotFoundException("Không tìm thấy danh mục!"))) {
            doThrow(raced).when(productService).applyImport(commands);

            assertThatExceptionOfType(ExcelFileException.class)
                    .isThrownBy(() -> service.importProducts(upload("san-pham.xlsx", FILE)))
                    .withMessage(ProductImportService.DATA_CHANGED);
        }
        verifyNoInteractions(auditLogService);
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
        return new ProductImportCommand(productId, new ProductReqDto());
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
