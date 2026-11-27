package com.huuhv.foodsndrinks.service;

import com.huuhv.foodsndrinks.dto.request.ProductImportCommand;
import com.huuhv.foodsndrinks.dto.request.ProductReqDto;
import com.huuhv.foodsndrinks.entity.Category;
import com.huuhv.foodsndrinks.entity.Product;
import com.huuhv.foodsndrinks.enums.ProductType;
import com.huuhv.foodsndrinks.exception.DuplicateResourceException;
import com.huuhv.foodsndrinks.exception.ResourceNotFoundException;
import com.huuhv.foodsndrinks.repository.CategoryRepository;
import com.huuhv.foodsndrinks.repository.ProductImageRepository;
import com.huuhv.foodsndrinks.repository.ProductRepository;
import com.huuhv.foodsndrinks.repository.projection.ProductExportRow;
import com.huuhv.foodsndrinks.repository.projection.ProductImportMatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test phần export/import Excel của {@link ProductService}: mỗi dòng đi qua đúng luồng thêm/sửa của form admin. Tính nguyên
 * tử (một dòng lỗi → rollback cả file) là của transaction thật — xem {@code ProductImportIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceImportTest {

    @Mock private ProductRepository productRepository;
    @Mock private ProductImageRepository productImageRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private FileStorageService fileStorageService;

    @InjectMocks private ProductService productService;

    private final Category drinks = category(10L);

    @Test
    void applyImport_createsNewProducts_withGeneratedSlug_andUpdatesExistingOnes_withoutTouchingImages() {
        Product existing = Product.builder().id(7L).name("Cơm tấm").slug("com-tam").price(new BigDecimal("40000"))
                .type(ProductType.FOOD).isAvailable(true).build();
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(drinks));
        when(productRepository.findById(7L)).thenReturn(Optional.of(existing));
        when(productImageRepository.findByProductIdOrderBySortOrderAsc(7L)).thenReturn(List.of());

        productService.applyImport(List.of(
                new ProductImportCommand(null, product("Trà đào", "35000", "Trà đào cam sả", true)),
                new ProductImportCommand(7L, product("Cơm tấm", "45000", null, false))));

        ArgumentCaptor<Product> saved = ArgumentCaptor.forClass(Product.class);
        verify(productRepository, times(2)).save(saved.capture());
        Product created = saved.getAllValues().get(0);
        assertThat(created.getId()).isNull();
        assertThat(created.getName()).isEqualTo("Trà đào");
        assertThat(created.getSlug()).isEqualTo("tra-dao");
        assertThat(created.getCategory()).isSameAs(drinks);
        assertThat(created.getPrice()).isEqualByComparingTo("35000");
        assertThat(created.getDescription()).isEqualTo("Trà đào cam sả");
        assertThat(created.getIsAvailable()).isTrue();

        assertThat(saved.getAllValues().get(1)).isSameAs(existing);
        assertThat(existing.getSlug()).isEqualTo("com-tam");
        assertThat(existing.getPrice()).isEqualByComparingTo("45000");
        assertThat(existing.getDescription()).isNull();
        assertThat(existing.getIsAvailable()).isFalse();
        assertThat(existing.getCategory()).isSameAs(drinks);
        // Import không có ảnh: không lưu/xoá file nào
        verifyNoInteractions(fileStorageService);
        verify(productImageRepository, never()).save(any());
        verify(productImageRepository, never()).delete(any());
    }

    @Test
    void applyImport_nameTakenSinceValidation_fails_soTheCallerRollsBackTheWholeFile() {
        when(productRepository.existsByName("Trà đào")).thenReturn(true);

        assertThatExceptionOfType(DuplicateResourceException.class).isThrownBy(() -> productService.applyImport(List.of(
                new ProductImportCommand(null, product("Trà đào", "35000", null, true)))));
        verify(productRepository, never()).save(any());
    }

    @Test
    void applyImport_productDeletedSinceValidation_fails() {
        when(productRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ResourceNotFoundException.class).isThrownBy(() -> productService.applyImport(List.of(
                new ProductImportCommand(7L, product("Cơm tấm", "45000", null, true)))));
    }

    @Test
    void findImportMatches_asksTheDatabaseOncePerDistinctName() {
        ProductImportMatch match = new ProductImportMatch(5L, "Trà đào", 10L, ProductType.DRINK,
                new BigDecimal("35000"), null, true);
        when(productRepository.findImportMatchesByName("Trà đào")).thenReturn(List.of(match));
        when(productRepository.findImportMatchesByName("Mới")).thenReturn(List.of());

        Map<String, List<ProductImportMatch>> matches =
                productService.findImportMatches(List.of("Trà đào", "Mới", "Trà đào"));

        assertThat(matches).containsOnlyKeys("Trà đào", "Mới");
        assertThat(matches.get("Trà đào")).containsExactly(match);
        assertThat(matches.get("Mới")).isEmpty();
        verify(productRepository, times(1)).findImportMatchesByName("Trà đào");
    }

    @Test
    void findExportRows_usesTheSameFilterParsingAsTheList_andAsksForOneRowMoreThanTheLimit() {
        List<ProductExportRow> rows = List.of(exportRow("B"), exportRow("A"));
        when(productRepository.findExportRows(any(), any(), any(), any(), any())).thenReturn(rows);

        assertThat(productService.findExportRows("  trà ", 10L, "drink", "1", 2)).isEqualTo(rows);
        // Giá trị lọc lạ/rỗng = không lọc, như trang danh sách
        productService.findExportRows(" ", null, "nước", "có", 2);

        verify(productRepository).findExportRows("trà", 10L, ProductType.DRINK, Boolean.TRUE, Limit.of(3));
        verify(productRepository).findExportRows(null, null, null, null, Limit.of(3));
    }

    @Test
    void findExportRows_moreRowsThanTheLimit_isRejected_soNoFileIsBuilt() {
        when(productRepository.findExportRows(any(), any(), any(), any(), any()))
                .thenReturn(List.of(exportRow("C"), exportRow("B"), exportRow("A")));

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> productService.findExportRows(null, null, null, null, 2))
                .withMessageContaining("vượt giới hạn 2 dòng");
    }

    private static ProductExportRow exportRow(String name) {
        return new ProductExportRow(name, "Đồ uống", ProductType.DRINK, new BigDecimal("35000"), null, true);
    }

    private static ProductReqDto product(String name, String price, String description, boolean available) {
        ProductReqDto dto = new ProductReqDto();
        dto.setName(name);
        dto.setCategoryId(10L);
        dto.setType(ProductType.DRINK);
        dto.setPrice(new BigDecimal(price));
        dto.setDescription(description);
        dto.setIsAvailable(available);
        return dto;
    }

    private static Category category(Long id) {
        Category category = new Category();
        category.setId(id);
        category.setName("Đồ uống");
        return category;
    }
}
