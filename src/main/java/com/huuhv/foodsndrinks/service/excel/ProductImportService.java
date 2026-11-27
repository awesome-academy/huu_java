package com.huuhv.foodsndrinks.service.excel;

import com.huuhv.foodsndrinks.config.properties.ExcelProperties;
import com.huuhv.foodsndrinks.dto.response.ImportRowErrorResDto;
import com.huuhv.foodsndrinks.dto.response.ProductImportResDto;
import com.huuhv.foodsndrinks.entity.Category;
import com.huuhv.foodsndrinks.enums.ProductType;
import com.huuhv.foodsndrinks.exception.DuplicateResourceException;
import com.huuhv.foodsndrinks.exception.ExcelFileException;
import com.huuhv.foodsndrinks.exception.ResourceNotFoundException;
import com.huuhv.foodsndrinks.service.CategoryService;
import com.huuhv.foodsndrinks.service.ProductService;
import com.huuhv.foodsndrinks.service.audit.AuditLogService;
import com.huuhv.foodsndrinks.service.excel.ExcelSheetWriter.Column;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Import sản phẩm từ file Excel theo mẫu: kiểm tra file → đọc → validate mọi dòng → ghi.
 * <p>
 * <b>Chính sách ghi:</b> có bất kỳ dòng nào lỗi thì KHÔNG dòng nào được ghi (admin sửa file rồi import lại cả file);
 * không có lỗi thì mọi dòng được ghi trong một transaction ({@link ProductService#applyImport}). Đọc và validate chạy
 * ngoài transaction ghi — lớp này cố ý không {@code @Transactional}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductImportService {

    static final String TEMPLATE_FILENAME = "foodsndrinks-products-template.xlsx";
    static final String DATA_CHANGED = "Dữ liệu sản phẩm hoặc danh mục vừa thay đổi trong lúc import. Vui lòng import lại!";

    private final ExcelUploadValidator uploadValidator;
    private final ProductImportReader reader;
    private final ProductImportValidator validator;
    private final ProductService productService;
    private final CategoryService categoryService;
    private final ExcelProperties properties;
    private final AuditLogService auditLogService;

    /**
     * Import đồng bộ một file upload.
     *
     * @return kết quả: thành công (số sản phẩm thêm/cập nhật/không đổi) hoặc danh sách lỗi theo dòng (không ghi gì)
     * @throws ExcelFileException file bị từ chối cả file (không phải .xlsx, quá lớn, sai mẫu, quá nhiều dòng...) hoặc dữ
     *                            liệu đổi giữa lúc validate và lúc ghi — thông điệp hiển thị được cho admin
     */
    public ProductImportResDto importProducts(MultipartFile upload) {
        uploadValidator.validate(upload);

        ProductImportPlan plan;
        Path file = copyToTempFile(upload);
        try {
            plan = plan(file);
        } finally {
            deleteQuietly(file);
        }
        if (plan.hasErrors()) {
            return failed(plan);
        }

        try {
            productService.applyImport(plan.commands());
        } catch (DuplicateResourceException | ResourceNotFoundException e) {
            // Transaction của applyImport đã rollback toàn bộ; import lại sẽ validate trên dữ liệu mới
            throw new ExcelFileException(DATA_CHANGED, e);
        }
        auditLogService.productsImported(upload.getOriginalFilename(), plan.createCount(), plan.updateCount(),
                plan.unchangedCount());
        return new ProductImportResDto(plan.totalRows(), plan.createCount(), plan.updateCount(),
                plan.unchangedCount(), 0, List.of());
    }

    /** Số dòng dữ liệu tối đa của một file import — để trang import hiển thị đúng giới hạn đang cấu hình. */
    public int maxRows() {
        return properties.imports().maxRows();
    }

    /** Kích thước file import tối đa, tính bằng MB. */
    public long maxFileSizeMb() {
        return properties.imports().maxFileSize().toMegabytes();
    }

    /**
     * Đọc + validate một file .xlsx trên đĩa, chưa ghi gì — tách riêng để luồng import bất đồng bộ dùng lại.
     *
     * @throws ExcelFileException file bị từ chối cả file
     */
    public ProductImportPlan plan(Path file) {
        return validator.validate(reader.read(file));
    }

    /** Kết quả thất bại của một kế hoạch có lỗi: giữ tối đa {@code app.excel.import.max-errors} lỗi để hiển thị. */
    public ProductImportResDto failed(ProductImportPlan plan) {
        List<ImportRowErrorResDto> errors = plan.errors();
        int shown = Math.min(errors.size(), properties.imports().maxErrors());
        return new ProductImportResDto(plan.totalRows(), 0, 0, 0, errors.size(), List.copyOf(errors.subList(0, shown)));
    }

    /**
     * File mẫu import: sheet đầu là dòng tiêu đề + danh sách chọn cho cột Loại / Còn bán; sheet "Hướng dẫn" giải thích
     * từng cột và liệt kê danh mục hiện có. Người gọi phải đóng file sau khi ghi ra response.
     */
    public ExcelFile template() {
        int maxRows = properties.imports().maxRows();
        ExcelSheetWriter sheet = new ExcelSheetWriter(ProductExcelColumns.SHEET_NAME, ProductExcelColumns.COLUMNS);
        try {
            sheet.dropdown(ProductExcelColumns.HEADERS.indexOf(ProductExcelColumns.TYPE), maxRows,
                    Arrays.stream(ProductType.values()).map(ProductType::getLabel).toList());
            sheet.dropdown(ProductExcelColumns.HEADERS.indexOf(ProductExcelColumns.AVAILABLE), maxRows,
                    List.of(ProductExcelColumns.YES, ProductExcelColumns.NO));

            sheet.newSheet("Hướng dẫn", List.of(new Column("Cột", 18), new Column("Cách nhập", 100)));
            sheet.row().text(ProductExcelColumns.NAME).text("Bắt buộc, tối đa 255 ký tự. Tên trùng CHÍNH XÁC một sản phẩm"
                    + " đã có → cập nhật sản phẩm đó; tên chưa có → thêm sản phẩm mới.");
            sheet.row().text(ProductExcelColumns.CATEGORY).text("Bắt buộc. Tên một danh mục đã có (danh sách bên dưới).");
            sheet.row().text(ProductExcelColumns.TYPE).text("Bắt buộc. " + ProductType.FOOD.getLabel() + " hoặc "
                    + ProductType.DRINK.getLabel() + ".");
            sheet.row().text(ProductExcelColumns.PRICE).text("Bắt buộc. Số lớn hơn 0, tối đa 2 chữ số thập phân (nhập"
                    + " số, không kèm chữ hay ký hiệu tiền tệ).");
            sheet.row().text(ProductExcelColumns.DESCRIPTION).text("Không bắt buộc, tối đa 16.000 ký tự. Khi cập nhật,"
                    + " để trống sẽ xoá mô tả hiện có.");
            sheet.row().text(ProductExcelColumns.AVAILABLE).text(ProductExcelColumns.YES + " hoặc "
                    + ProductExcelColumns.NO + ". Để trống = " + ProductExcelColumns.YES + ".");
            sheet.row().text("Lưu ý").text("Tối đa " + maxRows + " dòng dữ liệu mỗi file. Có một dòng lỗi thì cả file"
                    + " không được lưu. Chỉ sheet đầu tiên được đọc; không đổi tên hay thứ tự các cột.");
            sheet.row();
            sheet.row().text("Danh mục hiện có");
            for (Category category : categoryService.getAllCategories()) {
                sheet.row().text(null).text(category.getName());
            }
        } catch (RuntimeException e) {
            sheet.close();
            throw e;
        }
        return new ExcelFile(TEMPLATE_FILENAME, 0, sheet);
    }

    private static Path copyToTempFile(MultipartFile upload) {
        Path file = null;
        try {
            // Tên file do JVM sinh trong thư mục tạm (không dùng tên client gửi), quyền chỉ chủ sở hữu đọc/ghi
            file = Files.createTempFile("product-import-", ExcelUploadValidator.EXTENSION);
            upload.transferTo(file);
            return file;
        } catch (IOException e) {
            if (file != null) {
                deleteQuietly(file);
            }
            throw new ExcelFileException("Không lưu được file tải lên. Vui lòng thử lại!", e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete temporary import file {}", file.getFileName(), e);
        }
    }
}
