package com.huuhv.foodsndrinks.service.excel;

import com.huuhv.foodsndrinks.dto.request.ProductImportCommand;
import com.huuhv.foodsndrinks.dto.request.ProductReqDto;
import com.huuhv.foodsndrinks.dto.response.ImportRowErrorResDto;
import com.huuhv.foodsndrinks.entity.Category;
import com.huuhv.foodsndrinks.enums.ProductType;
import com.huuhv.foodsndrinks.repository.projection.ProductImportMatch;
import com.huuhv.foodsndrinks.service.CategoryService;
import com.huuhv.foodsndrinks.service.ProductService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ProductImportValidator} với Jakarta Validator thật (đúng ràng buộc của {@link ProductReqDto}); danh mục và sản
 * phẩm đã có được giả lập — phần "MySQL coi tên nào là trùng" được kiểm tra ở {@code ProductImportIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProductImportValidatorTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    private static final long DRINKS = 10L;
    private static final long FOODS = 20L;

    @Mock private CategoryService categoryService;
    @Mock private ProductService productService;

    private ProductImportValidator validator;
    /** Sản phẩm "đã có trong DB": tên trong file → các sản phẩm MySQL coi là trùng tên. */
    private final Map<String, List<ProductImportMatch>> existing = new HashMap<>();

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    @BeforeEach
    void setUp() {
        validator = new ProductImportValidator(VALIDATOR, categoryService, productService);
        when(categoryService.getAllCategories()).thenReturn(List.of(category(DRINKS, "Đồ uống"), category(FOODS, "Món chính")));
        when(productService.findImportMatches(any())).thenAnswer(invocation -> {
            Collection<String> names = invocation.getArgument(0);
            Map<String, List<ProductImportMatch>> found = new HashMap<>();
            names.forEach(name -> found.put(name, existing.getOrDefault(name, List.of())));
            return found;
        });
    }

    // -------------------------------------------------------
    // Thêm mới
    // -------------------------------------------------------

    @Test
    void validNewRows_becomeCreateCommands_inFileOrder() {
        ProductImportPlan plan = validator.validate(List.of(
                row(2, "  Trà đào  ", "đồ uống", "Đồ uống", "35000", "Trà đào cam sả", "Có"),
                row(3, "Cơm tấm", "MÓN CHÍNH", "food", "45000.50", null, "không")));

        assertThat(plan.hasErrors()).isFalse();
        assertThat(plan.totalRows()).isEqualTo(2);
        assertThat(plan.createCount()).isEqualTo(2);
        assertThat(plan.updateCount()).isZero();
        assertThat(plan.unchangedCount()).isZero();

        ProductImportCommand first = plan.commands().get(0);
        assertThat(first.isCreate()).isTrue();
        assertThat(first.product().getName()).isEqualTo("Trà đào");
        assertThat(first.product().getCategoryId()).isEqualTo(DRINKS);
        assertThat(first.product().getType()).isEqualTo(ProductType.DRINK);
        assertThat(first.product().getPrice()).isEqualByComparingTo("35000");
        assertThat(first.product().getDescription()).isEqualTo("Trà đào cam sả");
        assertThat(first.product().getIsAvailable()).isTrue();

        ProductReqDto second = plan.commands().get(1).product();
        assertThat(second.getCategoryId()).isEqualTo(FOODS);
        assertThat(second.getType()).isEqualTo(ProductType.FOOD);
        assertThat(second.getPrice()).isEqualByComparingTo("45000.5");
        assertThat(second.getDescription()).isNull();
        assertThat(second.getIsAvailable()).isFalse();
    }

    @Test
    void nameIsTrimmedExactlyLikeTheForm_soAnExportedNameWithUnicodeSpaceStillMatches() {
        // Form/ProductService cắt bằng trim() (chỉ ký tự ≤ U+0020) nên tên đã lưu có thể còn khoảng trắng Unicode ở
        // cuối (U+3000); collation NO PAD coi nó là một phần của tên → bộ đọc file phải giữ nguyên như vậy
        String stored = "Trà sữa　";
        existing.put(stored, List.of(match(5L, stored, DRINKS, ProductType.DRINK, "30000", null, true)));

        ProductImportPlan plan = validator.validate(List.of(
                row(2, " " + stored + " ", "Đồ uống", "Đồ uống", "30000", null, "Có")));

        assertThat(plan.hasErrors()).isFalse();
        assertThat(plan.unchangedCount()).isEqualTo(1);
        assertThat(plan.createCount()).isZero();
    }

    @Test
    void categoryAndTypeTypedWithPlainD_matchLikeMySqlDoes() {
        // utf8mb4_0900_ai_ci coi "đ" = "d": "Do uong" là danh mục "Đồ uống", "Do an" là loại "Đồ ăn"
        ProductImportPlan plan = validator.validate(List.of(row(2, "Trà đào", "Do uong", "Do an", "1000", null, null)));

        assertThat(plan.errors()).isEmpty();
        assertThat(plan.commands().getFirst().product().getCategoryId()).isEqualTo(DRINKS);
        assertThat(plan.commands().getFirst().product().getType()).isEqualTo(ProductType.FOOD);
    }

    @Test
    void blankAvailableCell_defaultsToAvailable_likeTheAddForm() {
        for (String yes : new String[]{null, "Có", "co", "TRUE", "1"}) {
            assertThat(validator.validate(List.of(row(2, "Trà đào", "Đồ uống", "DRINK", "1000", null, yes)))
                    .commands().getFirst().product().getIsAvailable()).as("available=%s", yes).isTrue();
        }
        for (String no : new String[]{"Không", "khong", "false", "0"}) {
            assertThat(validator.validate(List.of(row(2, "Trà đào", "Đồ uống", "DRINK", "1000", null, no)))
                    .commands().getFirst().product().getIsAvailable()).as("available=%s", no).isFalse();
        }
    }

    // -------------------------------------------------------
    // Lỗi theo dòng và cột
    // -------------------------------------------------------

    @Test
    void requiredCellsLeftBlank_reportTheSameMessagesAsTheForm() {
        ProductImportPlan plan = validator.validate(List.of(row(7, null, null, null, null, null, null)));

        assertThat(plan.commands()).isEmpty();
        assertThat(plan.errors()).containsExactly(
                new ImportRowErrorResDto(7, "Tên", "Tên sản phẩm không được để trống!"),
                new ImportRowErrorResDto(7, "Danh mục", "Vui lòng chọn danh mục!"),
                new ImportRowErrorResDto(7, "Loại", "Vui lòng chọn loại sản phẩm!"),
                new ImportRowErrorResDto(7, "Giá", "Vui lòng nhập giá sản phẩm!"));
        // Tên không dùng được → không tra DB cho dòng đó
        verify(productService, never()).findImportMatches(any());
    }

    @Test
    void unconvertibleCells_reportOneErrorPerCell_withoutTheRedundantRequiredMessage() {
        ProductImportPlan plan = validator.validate(List.of(
                row(2, "Trà đào", "Không có danh mục này", "Nước", "35,000 đ", null, "có lẽ")));

        assertThat(plan.errors()).extracting(ImportRowErrorResDto::column)
                .containsExactly("Danh mục", "Loại", "Giá", "Còn bán");
        assertThat(plan.errors()).extracting(ImportRowErrorResDto::message)
                .allSatisfy(message -> assertThat(message)
                        // Thông báo cố định: không lặp lại nội dung ô (có thể chứa dữ liệu tuỳ ý)
                        .doesNotContain("Không có danh mục này").doesNotContain("Nước").doesNotContain("35,000")
                        .doesNotContain("có lẽ"))
                .noneMatch(message -> message.startsWith("Vui lòng"));
    }

    @Test
    void valuesOutsideTheDatabaseLimits_areRejected_insteadOfFailingAtInsert() {
        ProductImportPlan plan = validator.validate(List.of(
                row(2, "x".repeat(256), "Đồ uống", "DRINK", "0", "d".repeat(16_001), null),
                row(3, "Giá quá lớn", "Đồ uống", "DRINK", "100000000", null, null),
                row(4, "Giá lẻ ba số", "Đồ uống", "DRINK", "10.125", null, null),
                row(5, "Giá âm", "Đồ uống", "DRINK", "-5", null, null)));

        assertThat(plan.errors()).extracting(error -> error.row() + ":" + error.column()).containsExactly(
                "2:Tên", "2:Giá", "2:Mô tả", "3:Giá", "4:Giá", "5:Giá");
        assertThat(plan.errors().getFirst().message()).isEqualTo("Tên sản phẩm tối đa 255 ký tự!");
    }

    @Test
    void nameWithoutAnyLetterOrDigit_isRejected_becauseNoSlugCanBeMade() {
        assertThat(validator.validate(List.of(row(2, "!!! ???", "Đồ uống", "DRINK", "1000", null, null))).errors())
                .containsExactly(new ImportRowErrorResDto(2, "Tên",
                        "Tên sản phẩm phải có ít nhất một chữ cái hoặc chữ số!"));
    }

    @Test
    void sameNameTwiceInTheFile_isAnErrorOnTheLaterRow_ignoringCaseAndAccents() {
        ProductImportPlan plan = validator.validate(List.of(
                row(2, "Bò kho", "Món chính", "FOOD", "50000", null, null),
                row(3, "Trà đào", "Đồ uống", "DRINK", "35000", null, null),
                row(4, "BO KHO", "Món chính", "FOOD", "55000", null, null)));

        assertThat(plan.errors()).containsExactly(
                new ImportRowErrorResDto(4, "Tên", "Trùng tên với dòng 2 trong file!"));
        assertThat(plan.commands()).isEmpty();
    }

    @Test
    void errors_areSortedByRowThenColumnOrder_andOneBadRowBlocksTheWholeFile() {
        ProductImportPlan plan = validator.validate(List.of(
                row(2, "Trà đào", "Đồ uống", "DRINK", "35000", null, null),
                row(3, "Cơm tấm", "Sai danh mục", "FOOD", "abc", null, null),
                row(4, null, "Đồ uống", "DRINK", "1000", null, null)));

        assertThat(plan.errors()).extracting(error -> error.row() + ":" + error.column())
                .containsExactly("3:Danh mục", "3:Giá", "4:Tên");
        // Dòng 2 hợp lệ nhưng không được ghi: có lỗi thì không lưu dòng nào
        assertThat(plan.commands()).isEmpty();
        assertThat(plan.totalRows()).isEqualTo(3);
    }

    // -------------------------------------------------------
    // Upsert theo tên
    // -------------------------------------------------------

    @Test
    void exactNameOfAnExistingProduct_becomesAnUpdate_keepingTheStoredName() {
        // DB lưu tên dạng NFC; Excel trên macOS có thể ghi dạng tổ hợp NFD — vẫn là cùng một tên
        String stored = "Trà đào";
        String typed = Normalizer.normalize(stored, Normalizer.Form.NFD);
        existing.put(typed, List.of(match(5L, stored, DRINKS, ProductType.DRINK, "30000", "cũ", true)));

        ProductImportPlan plan = validator.validate(List.of(row(2, typed, "Món chính", "FOOD", "35000", "mới", "Không")));

        assertThat(plan.hasErrors()).isFalse();
        assertThat(plan.updateCount()).isEqualTo(1);
        ProductImportCommand command = plan.commands().getFirst();
        assertThat(command.productId()).isEqualTo(5L);
        assertThat(command.product().getName()).isEqualTo(stored);
        assertThat(command.product().getCategoryId()).isEqualTo(FOODS);
        assertThat(command.product().getType()).isEqualTo(ProductType.FOOD);
        assertThat(command.product().getPrice()).isEqualByComparingTo("35000");
        assertThat(command.product().getDescription()).isEqualTo("mới");
        assertThat(command.product().getIsAvailable()).isFalse();
    }

    @Test
    void rowIdenticalToTheExistingProduct_isCountedAsUnchanged_andNotWritten() {
        existing.put("Trà đào", List.of(match(5L, "Trà đào", DRINKS, ProductType.DRINK, "35000.00", "", true)));
        existing.put("Cơm tấm", List.of(match(6L, "Cơm tấm", FOODS, ProductType.FOOD, "45000.00", "ngon", false)));

        ProductImportPlan plan = validator.validate(List.of(
                // 35000 = 35000.00, mô tả rỗng = không có mô tả, ô "Còn bán" trống = Có
                row(2, "Trà đào", "Đồ uống", "Đồ uống", "35000", null, null),
                // Chỉ đổi mô tả
                row(3, "Cơm tấm", "Món chính", "Đồ ăn", "45000", "rất ngon", "Không")));

        assertThat(plan.unchangedCount()).isEqualTo(1);
        assertThat(plan.updateCount()).isEqualTo(1);
        assertThat(plan.createCount()).isZero();
        assertThat(plan.commands()).extracting(ProductImportCommand::productId).containsExactly(6L);
    }

    @Test
    void blankDescriptionOnAnUpdate_clearsTheExistingDescription() {
        existing.put("Trà đào", List.of(match(5L, "Trà đào", DRINKS, ProductType.DRINK, "35000", "mô tả cũ", true)));

        ProductImportPlan plan = validator.validate(List.of(row(2, "Trà đào", "Đồ uống", "DRINK", "35000", null, "Có")));

        assertThat(plan.updateCount()).isEqualTo(1);
        assertThat(plan.commands().getFirst().product().getDescription()).isNull();
    }

    @Test
    void nameEqualOnlyByCollation_isARowError_neverASilentUpdateOfADifferentLookingProduct() {
        // MySQL (ai_ci) coi "Bơ khô" = "Bò kho": trả về sản phẩm "Bò kho" cho tên "Bơ khô"
        existing.put("Bơ khô", List.of(match(9L, "Bò kho", FOODS, ProductType.FOOD, "50000", null, true)));
        existing.put("trà đào", List.of(match(5L, "Trà đào", DRINKS, ProductType.DRINK, "35000", null, true)));

        ProductImportPlan plan = validator.validate(List.of(
                row(2, "Bơ khô", "Món chính", "FOOD", "99000", null, null),
                row(3, "trà đào", "Đồ uống", "DRINK", "1000", null, null)));

        assertThat(plan.commands()).isEmpty();
        assertThat(plan.errors()).hasSize(2).allSatisfy(error -> {
            assertThat(error.column()).isEqualTo("Tên");
            assertThat(error.message()).startsWith("Tên gần trùng với một sản phẩm đã có");
        });
    }

    @Test
    void severalExistingProductsWithThatName_isARowError() {
        existing.put("Trà đào", List.of(match(5L, "Trà đào", DRINKS, ProductType.DRINK, "35000", null, true),
                match(6L, "tra dao", DRINKS, ProductType.DRINK, "35000", null, true)));

        assertThat(validator.validate(List.of(row(2, "Trà đào", "Đồ uống", "DRINK", "1000", null, null))).errors())
                .singleElement().extracting(ImportRowErrorResDto::message).asString()
                .startsWith("Có nhiều sản phẩm trùng tên này");
    }

    @Test
    void existingProductWithoutCategory_isUpdatedWhenTheRowNamesOne() {
        existing.put("Trà đào", List.of(match(5L, "Trà đào", null, ProductType.DRINK, "35000", null, true)));

        ProductImportPlan plan = validator.validate(List.of(row(2, "Trà đào", "Đồ uống", "DRINK", "35000", null, null)));

        assertThat(plan.updateCount()).isEqualTo(1);
        assertThat(plan.commands().getFirst().product().getCategoryId()).isEqualTo(DRINKS);
    }

    private static ProductImportRow row(int rowNumber, String name, String category, String type, String price,
                                        String description, String available) {
        return new ProductImportRow(rowNumber, name, category, type, price, description, available);
    }

    private static ProductImportMatch match(Long id, String name, Long categoryId, ProductType type, String price,
                                            String description, boolean available) {
        return new ProductImportMatch(id, name, categoryId, type, new BigDecimal(price), description, available);
    }

    private static Category category(long id, String name) {
        Category category = new Category();
        category.setId(id);
        category.setName(name);
        return category;
    }
}
