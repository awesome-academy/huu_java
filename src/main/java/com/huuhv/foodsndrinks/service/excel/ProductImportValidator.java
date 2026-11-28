package com.huuhv.foodsndrinks.service.excel;

import com.huuhv.foodsndrinks.dto.request.ProductImportCommand;
import com.huuhv.foodsndrinks.dto.request.ProductReqDto;
import com.huuhv.foodsndrinks.dto.response.ImportRowErrorResDto;
import com.huuhv.foodsndrinks.entity.Category;
import com.huuhv.foodsndrinks.enums.ProductType;
import com.huuhv.foodsndrinks.repository.projection.ProductImportMatch;
import com.huuhv.foodsndrinks.service.CategoryService;
import com.huuhv.foodsndrinks.service.ProductService;
import com.huuhv.foodsndrinks.utils.CollationKeys;
import com.huuhv.foodsndrinks.utils.SlugUtils;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.huuhv.foodsndrinks.service.excel.ProductExcelColumns.AVAILABLE;
import static com.huuhv.foodsndrinks.service.excel.ProductExcelColumns.CATEGORY;
import static com.huuhv.foodsndrinks.service.excel.ProductExcelColumns.DESCRIPTION;
import static com.huuhv.foodsndrinks.service.excel.ProductExcelColumns.HEADERS;
import static com.huuhv.foodsndrinks.service.excel.ProductExcelColumns.NAME;
import static com.huuhv.foodsndrinks.service.excel.ProductExcelColumns.PRICE;
import static com.huuhv.foodsndrinks.service.excel.ProductExcelColumns.TYPE;

/**
 * Validate các dòng của file import sản phẩm và quyết định mỗi dòng là THÊM MỚI hay CẬP NHẬT — không ghi gì vào DB.
 * <p>
 * Mỗi dòng được chuyển thành {@link ProductReqDto} rồi qua đúng bộ ràng buộc Jakarta Validation của form thêm/sửa sản
 * phẩm, nên import và form không bao giờ lệch quy tắc. Thông báo lỗi là câu cố định, không lặp lại giá trị của ô.
 * <p>
 * <b>Upsert theo tên.</b> MySQL so sánh tên không phân biệt hoa/thường và dấu ("Bơ khô" = "Bò kho"), và form admin đã
 * coi các tên như vậy là trùng. Vì thế một dòng chỉ được CẬP NHẬT khi tên của nó trùng CHÍNH XÁC tên một sản phẩm đã có;
 * tên chỉ "gần trùng" là lỗi dòng — không bao giờ âm thầm sửa một sản phẩm trông khác tên. Dòng cập nhật mang trạng thái
 * đầy đủ của sản phẩm như form sửa (danh mục, loại, giá, mô tả, còn bán); tên và slug giữ nguyên.
 * <p>
 * Chỉ làm việc trên projection ({@link ProductImportMatch}), không trên entity — chạy được cả trên thread không có session
 * Hibernate (consumer JMS).
 */
@Component
@RequiredArgsConstructor
public class ProductImportValidator {

    /** Tên field của {@link ProductReqDto} → tên cột trong file. */
    private static final Map<String, String> COLUMN_BY_PROPERTY = Map.of(
            "name", NAME, "categoryId", CATEGORY, "type", TYPE, "price", PRICE, "description", DESCRIPTION,
            "isAvailable", AVAILABLE);

    private static final Set<String> YES_VALUES = Set.of("co", "true", "1", "yes", "x");
    private static final Set<String> NO_VALUES = Set.of("khong", "false", "0", "no");

    private static final Comparator<ImportRowErrorResDto> BY_ROW_THEN_COLUMN = Comparator
            .comparingInt(ImportRowErrorResDto::row)
            .thenComparingInt(error -> HEADERS.indexOf(error.column()))
            .thenComparing(ImportRowErrorResDto::message);

    private final Validator validator;
    private final CategoryService categoryService;
    private final ProductService productService;

    public ProductImportPlan validate(List<ProductImportRow> rows) {
        Map<String, Long> categoryIds = categoryIdsByName();
        Map<String, Integer> firstRowByNameKey = new HashMap<>();
        List<Candidate> candidates = new ArrayList<>(rows.size());

        for (ProductImportRow row : rows) {
            Candidate candidate = new Candidate(row.rowNumber());
            candidate.product = toProduct(row, categoryIds, candidate);
            for (ConstraintViolation<ProductReqDto> violation : validator.validate(candidate.product)) {
                // Cột đã có lỗi chuyển kiểu (vd. "Giá phải là số") thì không báo thêm "Vui lòng nhập giá" cho cùng ô
                candidate.addViolation(COLUMN_BY_PROPERTY.get(violation.getPropertyPath().toString()),
                        violation.getMessage());
            }
            checkNameWithinFile(candidate, firstRowByNameKey);
            candidates.add(candidate);
        }

        matchExistingProducts(candidates);

        List<ImportRowErrorResDto> errors = candidates.stream()
                .flatMap(candidate -> candidate.errors.stream())
                .sorted(BY_ROW_THEN_COLUMN)
                .toList();
        if (!errors.isEmpty()) {
            return new ProductImportPlan(rows.size(), List.of(), 0, errors);
        }
        List<ProductImportCommand> commands = candidates.stream()
                .filter(candidate -> !candidate.unchanged)
                .map(candidate -> new ProductImportCommand(candidate.rowNumber, candidate.existingId, candidate.product))
                .toList();
        int unchanged = candidates.size() - commands.size();
        return new ProductImportPlan(rows.size(), commands, unchanged, List.of());
    }

    // -------------------------------------------------------
    // Chuyển kiểu từng ô
    // -------------------------------------------------------

    private static ProductReqDto toProduct(ProductImportRow row, Map<String, Long> categoryIds, Candidate candidate) {
        ProductReqDto product = new ProductReqDto();
        product.setName(row.name() == null ? null : row.name().strip());
        product.setCategoryId(categoryId(row.category(), categoryIds, candidate));
        product.setType(type(row.type(), candidate));
        product.setPrice(price(row.price(), candidate));
        product.setDescription(row.description());
        product.setIsAvailable(available(row.available(), candidate));
        return product;
    }

    private static Long categoryId(String cell, Map<String, Long> categoryIds, Candidate candidate) {
        if (cell == null) {
            return null;
        }
        Long id = categoryIds.get(CollationKeys.of(cell));
        if (id == null) {
            candidate.addConversionError(CATEGORY, "Danh mục không tồn tại! Hãy tạo danh mục trước hoặc nhập đúng tên danh mục.");
        }
        return id;
    }

    private static ProductType type(String cell, Candidate candidate) {
        if (cell == null) {
            return null;
        }
        String key = CollationKeys.of(cell);
        for (ProductType type : ProductType.values()) {
            if (CollationKeys.of(type.name()).equals(key) || CollationKeys.of(type.getLabel()).equals(key)) {
                return type;
            }
        }
        candidate.addConversionError(TYPE, "Loại không hợp lệ! Chỉ nhận: " + ProductType.FOOD.getLabel() + " hoặc "
                + ProductType.DRINK.getLabel() + ".");
        return null;
    }

    private static BigDecimal price(String cell, Candidate candidate) {
        if (cell == null) {
            return null;
        }
        try {
            return new BigDecimal(cell.strip());
        } catch (NumberFormatException e) {
            candidate.addConversionError(PRICE, "Giá phải là số (nhập số, không kèm chữ, dấu phẩy hay ký hiệu tiền tệ)!");
            return null;
        }
    }

    /** Ô trống = đang bán, như mặc định của form thêm sản phẩm. */
    private static Boolean available(String cell, Candidate candidate) {
        if (cell == null) {
            return Boolean.TRUE;
        }
        String key = CollationKeys.of(cell);
        if (YES_VALUES.contains(key)) {
            return Boolean.TRUE;
        }
        if (NO_VALUES.contains(key)) {
            return Boolean.FALSE;
        }
        candidate.addConversionError(AVAILABLE, "Còn bán chỉ nhận: " + ProductExcelColumns.YES + " hoặc " + ProductExcelColumns.NO + "!");
        return Boolean.TRUE;
    }

    /** Tên danh mục → id, khoá gần với cách MySQL so sánh (tên danh mục là duy nhất theo collation). */
    private Map<String, Long> categoryIdsByName() {
        Map<String, Long> ids = new HashMap<>();
        for (Category category : categoryService.getAllCategories()) {
            ids.putIfAbsent(CollationKeys.of(category.getName()), category.getId());
        }
        return ids;
    }

    // -------------------------------------------------------
    // Tên: trong file và so với sản phẩm đã có
    // -------------------------------------------------------

    private static void checkNameWithinFile(Candidate candidate, Map<String, Integer> firstRowByNameKey) {
        if (candidate.failed(NAME)) {
            return;
        }
        String name = candidate.product.getName();
        if (SlugUtils.toSlug(name).isBlank()) {
            candidate.add(NAME, "Tên sản phẩm phải có ít nhất một chữ cái hoặc chữ số!");
            return;
        }
        Integer firstRow = firstRowByNameKey.putIfAbsent(CollationKeys.of(name), candidate.rowNumber);
        if (firstRow != null) {
            candidate.add(NAME, "Trùng tên với dòng " + firstRow + " trong file!");
        }
    }

    private void matchExistingProducts(List<Candidate> candidates) {
        List<Candidate> named = candidates.stream().filter(candidate -> !candidate.failed(NAME)).toList();
        if (named.isEmpty()) {
            return;
        }
        Map<String, List<ProductImportMatch>> matches = productService.findImportMatches(
                named.stream().map(candidate -> candidate.product.getName()).toList());

        for (Candidate candidate : named) {
            String name = candidate.product.getName();
            List<ProductImportMatch> found = matches.getOrDefault(name, List.of());
            if (found.isEmpty()) {
                continue; // tên mới → thêm sản phẩm
            }
            if (found.size() > 1) {
                candidate.add(NAME, "Có nhiều sản phẩm trùng tên này trong hệ thống. Hãy sửa trên trang quản trị trước!");
                continue;
            }
            ProductImportMatch existing = found.getFirst();
            if (!sameText(existing.name(), name)) {
                candidate.add(NAME, "Tên gần trùng với một sản phẩm đã có (chỉ khác chữ hoa/thường hoặc dấu). Nhập đúng"
                        + " chính xác tên sản phẩm đó để cập nhật, hoặc đổi sang tên khác!");
                continue;
            }
            candidate.existingId = existing.id();
            candidate.product.setId(existing.id());
            // Giữ nguyên cách viết tên đang lưu (cùng tên, có thể khác dạng chuẩn hoá Unicode)
            candidate.product.setName(existing.name());
            candidate.unchanged = sameValues(existing, candidate.product);
        }
    }

    /** Bằng nhau chính xác từng ký tự, không phụ thuộc dạng tổ hợp Unicode (NFC/NFD) mà Excel hay trình duyệt dùng. */
    private static boolean sameText(String a, String b) {
        return Normalizer.normalize(a, Normalizer.Form.NFC).equals(Normalizer.normalize(b, Normalizer.Form.NFC));
    }

    private static boolean sameValues(ProductImportMatch existing, ProductReqDto row) {
        return Objects.equals(existing.categoryId(), row.getCategoryId())
                && existing.type() == row.getType()
                && existing.price() != null && row.getPrice() != null
                && existing.price().compareTo(row.getPrice()) == 0
                && Objects.equals(blankToNull(existing.description()), blankToNull(row.getDescription()))
                && Boolean.TRUE.equals(existing.isAvailable()) == Boolean.TRUE.equals(row.getIsAvailable());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Trạng thái validate của một dòng. */
    private static final class Candidate {

        private final int rowNumber;
        private final List<ImportRowErrorResDto> errors = new ArrayList<>(0);
        /** Cột không chuyển kiểu được — giá trị sau chuyển kiểu là null nên vi phạm "bắt buộc nhập" của cùng ô là thừa. */
        private final Set<String> unconvertible = new HashSet<>(0);

        private ProductReqDto product;
        /** id sản phẩm sẽ được cập nhật; {@code null} = thêm mới. */
        private Long existingId;
        private boolean unchanged;

        private Candidate(int rowNumber) {
            this.rowNumber = rowNumber;
        }

        private void add(String column, String message) {
            errors.add(new ImportRowErrorResDto(rowNumber, column, message));
        }

        private void addConversionError(String column, String message) {
            add(column, message);
            unconvertible.add(column);
        }

        private void addViolation(String column, String message) {
            if (!unconvertible.contains(column)) {
                add(column, message);
            }
        }

        private boolean failed(String column) {
            return errors.stream().anyMatch(error -> error.column().equals(column));
        }
    }
}
