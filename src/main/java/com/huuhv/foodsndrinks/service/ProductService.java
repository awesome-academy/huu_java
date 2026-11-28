package com.huuhv.foodsndrinks.service;

import com.huuhv.foodsndrinks.dto.request.ProductImportCommand;
import com.huuhv.foodsndrinks.dto.request.ProductReqDto;
import com.huuhv.foodsndrinks.dto.response.ProductResDto;
import com.huuhv.foodsndrinks.entity.Category;
import com.huuhv.foodsndrinks.entity.Product;
import com.huuhv.foodsndrinks.entity.ProductImage;
import com.huuhv.foodsndrinks.enums.ProductType;
import com.huuhv.foodsndrinks.exception.DuplicateResourceException;
import com.huuhv.foodsndrinks.exception.ImportNameConflictException;
import com.huuhv.foodsndrinks.exception.ResourceNotFoundException;
import com.huuhv.foodsndrinks.repository.CategoryRepository;
import com.huuhv.foodsndrinks.repository.ProductImageRepository;
import com.huuhv.foodsndrinks.repository.ProductRepository;
import com.huuhv.foodsndrinks.repository.projection.ProductExportRow;
import com.huuhv.foodsndrinks.repository.projection.ProductImportMatch;
import com.huuhv.foodsndrinks.utils.SlugUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductService {

    private static final String IMAGE_FOLDER = "products";
    /** Độ dài cột {@code products.slug}. */
    static final int SLUG_MAX_LENGTH = 255;

    private final ProductRepository      productRepository;
    private final ProductImageRepository productImageRepository;
    private final CategoryRepository     categoryRepository;
    private final FileStorageService     fileStorageService;

    // -------------------------------------------------------
    // Read
    // -------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<ProductResDto> searchProducts(String name, Long categoryId, String type,
                                              String isAvailableStr, int page, int size) {
        ProductType productType = parseEnum(ProductType.class, type);
        Boolean isAvailable = parseBoolean(isAvailableStr);
        String nameParam = blank(name) ? null : name.trim();

        Page<Product> productPage = productRepository.search(
                nameParam, categoryId, productType, isAvailable,
                PageRequest.of(Math.max(page, 0), size));

        // Batch-load primary image URLs in 1 query — avoids N+1
        List<Long> ids = productPage.getContent().stream()
                .map(Product::getId).collect(Collectors.toList());

        Map<Long, String> primaryUrls = ids.isEmpty() ? Map.of() :
                productImageRepository.findPrimaryUrlsByProductIds(ids)
                        .stream()
                        .collect(Collectors.toMap(
                                row -> (Long) row[0],
                                row -> (String) row[1],
                                (a, b) -> a   // keep first if somehow duplicate
                        ));

        return productPage.map(p -> ProductResDto.forList(p, primaryUrls.get(p.getId())));
    }

    @Transactional(readOnly = true)
    public ProductReqDto getProductForEdit(Long id) {
        Product p = findById(id);
        ProductReqDto dto = new ProductReqDto();
        dto.setId(p.getId());
        dto.setName(p.getName());
        dto.setCategoryId(p.getCategory() != null ? p.getCategory().getId() : null);
        dto.setType(p.getType());
        dto.setPrice(p.getPrice());
        dto.setDescription(p.getDescription());
        dto.setIsAvailable(p.getIsAvailable());
        return dto;
    }

    @Transactional(readOnly = true)
    public ProductResDto getProductDetail(Long id) {
        Product p = findById(id);
        List<ProductResDto.ImageDto> images = productImageRepository
                .findByProductIdOrderBySortOrderAsc(id)
                .stream().map(ProductResDto.ImageDto::from)
                .collect(Collectors.toList());
        return ProductResDto.forDetail(p, images);
    }

    // -------------------------------------------------------
    // Write
    // -------------------------------------------------------

    @Transactional
    public void createProduct(ProductReqDto dto,
                              List<MultipartFile> newImages,
                              int primaryIndex) {
        String normalizedName = normalizeName(dto.getName());
        if (productRepository.existsByName(normalizedName)) {
            throw new DuplicateResourceException("Tên sản phẩm đã tồn tại!");
        }

        Category category = findCategory(dto.getCategoryId());

        Product product = new Product();
        product.setName(normalizedName);
        product.setSlug(uniqueSlug(normalizedName, null));
        product.setCategory(category);
        product.setType(dto.getType());
        product.setPrice(dto.getPrice());
        product.setDescription(dto.getDescription());
        product.setIsAvailable(dto.getIsAvailable() != null && dto.getIsAvailable());
        productRepository.save(product);

        saveImages(product, newImages, primaryIndex, new ArrayList<>());
    }

    @Transactional
    public void updateProduct(Long id,
                              ProductReqDto dto,
                              List<MultipartFile> newImages,
                              List<Long> deleteImageIds,
                              Long primaryImageId) {
        Product product = findById(id);
        String normalizedName = normalizeName(dto.getName());

        if (productRepository.existsByNameAndIdNot(normalizedName, id)) {
            throw new DuplicateResourceException("Tên sản phẩm đã tồn tại!");
        }

        // Delete marked images
        if (deleteImageIds != null) {
            for (Long imgId : deleteImageIds) {
                productImageRepository.findById(imgId).ifPresent(img -> {
                    if (!img.getProduct().getId().equals(id)) return;
                    fileStorageService.delete(img.getImageUrl());
                    productImageRepository.delete(img);
                });
            }
        }

        // Re-load remaining images and update primary flag
        List<ProductImage> remaining = productImageRepository.findByProductIdOrderBySortOrderAsc(id);
        if (primaryImageId != null) {
            remaining.forEach(img -> img.setIsPrimary(img.getId().equals(primaryImageId)));
        }

        if (remaining.stream().noneMatch(ProductImage::getIsPrimary) && !remaining.isEmpty()) {
            remaining.getFirst().setIsPrimary(true);
        }
        productImageRepository.saveAll(remaining);

        // Upload new images
        saveImages(product, newImages, -1, remaining.isEmpty() ? new ArrayList<>() : remaining);

        // Slug chỉ đổi khi tên đổi: link /products/{slug} đã chia sẻ không được tự hỏng khi sửa giá/mô tả (vd. import lại
        // file export — slug gốc của tên này có thể vừa được trả lại vì sản phẩm khác bị xoá)
        if (!normalizedName.equals(product.getName()) || product.getSlug() == null) {
            product.setSlug(uniqueSlug(normalizedName, id));
        }
        product.setName(normalizedName);
        product.setCategory(findCategory(dto.getCategoryId()));
        product.setType(dto.getType());
        product.setPrice(dto.getPrice());
        product.setDescription(dto.getDescription());
        product.setIsAvailable(dto.getIsAvailable() != null && dto.getIsAvailable());
        productRepository.save(product);
    }

    // -------------------------------------------------------
    // Export / import Excel (service/excel)
    // -------------------------------------------------------

    /**
     * Các dòng của file export: đúng các sản phẩm mà {@link #searchProducts} liệt kê với cùng bộ lọc (mọi trang), mới
     * nhất trước.
     *
     * @param maxRows số dòng tối đa của một file
     * @throws IllegalArgumentException khi bộ lọc khớp nhiều hơn {@code maxRows} sản phẩm
     */
    @Transactional(readOnly = true)
    public List<ProductExportRow> findExportRows(String name, Long categoryId, String type, String isAvailableStr,
                                                 int maxRows) {
        // Xin thừa 1 dòng: biết bộ lọc vượt giới hạn mà không cần đếm riêng
        List<ProductExportRow> rows = productRepository.findExportRows(blank(name) ? null : name.trim(), categoryId,
                parseEnum(ProductType.class, type), parseBoolean(isAvailableStr), Limit.of(maxRows + 1));
        if (rows.size() > maxRows) {
            throw new IllegalArgumentException("Số sản phẩm khớp bộ lọc vượt giới hạn " + maxRows
                    + " dòng của một file Excel. Hãy thu hẹp bộ lọc!");
        }
        return rows;
    }

    /**
     * Với mỗi tên trong {@code names}: các sản phẩm đã có mà MySQL coi là trùng tên (theo collation của cột — xem
     * {@link ProductRepository#findImportMatchesByName}). Tra từng tên một (đi trên index {@code idx_products_name}) trong
     * MỘT transaction read-only: chỉ MySQL biết chính xác hai tên có "bằng nhau" theo collation hay không, gộp thành một
     * câu {@code IN} thì phía Java không ghép lại được dòng nào khớp sản phẩm nào.
     *
     * @return map theo đúng chuỗi tên đã truyền vào; tên không trùng sản phẩm nào → danh sách rỗng
     */
    @Transactional(readOnly = true)
    public Map<String, List<ProductImportMatch>> findImportMatches(Collection<String> names) {
        Map<String, List<ProductImportMatch>> matches = new HashMap<>();
        for (String name : names) {
            matches.computeIfAbsent(name, productRepository::findImportMatchesByName);
        }
        return matches;
    }

    /**
     * Ghi toàn bộ một file import trong MỘT transaction: hoặc mọi dòng được ghi, hoặc không dòng nào. Mỗi dòng đi qua
     * đúng {@link #createProduct} / {@link #updateProduct} của form admin (cùng kiểm tra trùng tên, sinh slug) — không ghi
     * thẳng repository. Ảnh của sản phẩm không bị đụng tới.
     *
     * @throws ImportNameConflictException hai dòng thêm mới của CÙNG file mà MySQL coi là cùng một tên — lỗi của file,
     *                                     ghi lại cũng trùng như vậy
     * @throws DuplicateResourceException  hoặc {@link ResourceNotFoundException} khi dữ liệu đã đổi kể từ lúc validate
     *                                     (sản phẩm cùng tên vừa được tạo ở nơi khác, sản phẩm/danh mục vừa bị xoá) →
     *                                     rollback cả file; validate lại trên dữ liệu mới sẽ ra kết quả khác
     */
    @Transactional
    public void applyImport(List<ProductImportCommand> commands) {
        // Tên đã lưu (đúng chuỗi) của các sản phẩm vừa tạo trong lần import này → dòng đã tạo ra nó
        Map<String, Integer> createdRowByName = new HashMap<>();
        for (ProductImportCommand command : commands) {
            if (!command.isCreate()) {
                updateProduct(command.productId(), command.product(), null, null, null);
                continue;
            }
            try {
                createProduct(command.product(), null, 0);
            } catch (DuplicateResourceException duplicate) {
                Integer earlierRow = rowCreatedWithSameName(command, createdRowByName);
                if (earlierRow == null) {
                    throw duplicate; // trùng một sản phẩm có từ trước: dữ liệu vừa đổi
                }
                throw new ImportNameConflictException(command.row(), earlierRow, duplicate);
            }
            createdRowByName.put(normalizeName(command.product().getName()), command.row());
        }
    }

    /**
     * Sản phẩm mà {@link #createProduct} coi là trùng tên có phải do chính lần import này vừa tạo không. Hỏi lại MySQL
     * (cùng collation với kiểm tra trùng tên) thay vì so trong Java: bước kiểm tra trùng trong file của validator chỉ xấp
     * xỉ collation nên mới lọt tới đây.
     *
     * @return dòng của file đã tạo sản phẩm đó, {@code null} nếu là sản phẩm có từ trước
     */
    private Integer rowCreatedWithSameName(ProductImportCommand command, Map<String, Integer> createdRowByName) {
        for (ProductImportMatch match : productRepository.findImportMatchesByName(
                normalizeName(command.product().getName()))) {
            Integer row = createdRowByName.get(match.name());
            if (row != null) {
                return row;
            }
        }
        return null;
    }

    @Transactional
    public void deleteProduct(Long id) {
        Product product = findById(id);
        List<String> imageUrls = productImageRepository.findByProductIdOrderBySortOrderAsc(id)
                .stream()
                .map(ProductImage::getImageUrl)
                .toList();
        try {
            productRepository.delete(product);
            productRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Không thể xóa sản phẩm này vì đang được tham chiếu!");
        }

        // Delete images from storage
        imageUrls.forEach(fileStorageService::delete);
    }

    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------

    private void saveImages(Product product, List<MultipartFile> files, int primaryIndex,
                            List<ProductImage> existingImages) {
        if (files == null || files.isEmpty()) return;

        boolean hasPrimary = existingImages.stream().anyMatch(ProductImage::getIsPrimary);
        int order = existingImages.stream().mapToInt(ProductImage::getSortOrder).max().orElse(-1) + 1;

        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            if (file == null || file.isEmpty()) continue;

            String url = fileStorageService.store(file, IMAGE_FOLDER);
            boolean isPrimary = !hasPrimary && (primaryIndex < 0 ? i == 0 : i == primaryIndex);
            if (isPrimary) hasPrimary = true;

            ProductImage img = new ProductImage();
            img.setProduct(product);
            img.setImageUrl(url);
            img.setIsPrimary(isPrimary);
            img.setSortOrder(order++);
            productImageRepository.save(img);
        }
    }

    private Product findById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm!"));
    }

    private Category findCategory(Long categoryId) {
        if (categoryId == null) return null;
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy danh mục!"));
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tên sản phẩm không được để trống!");
        }
        return name.trim();
    }

    /**
     * Slug chưa sản phẩm nào khác dùng, dài tối đa {@link #SLUG_MAX_LENGTH} (cột {@code products.slug}): tên dài tới 255
     * ký tự cho slug gốc dài ngang vậy, nên slug gốc bị cắt bớt để chừa chỗ cho hậu tố {@code -N} — không thì MySQL báo
     * "Data too long" (lỗi hệ thống thay vì một slug hợp lệ).
     */
    private String uniqueSlug(String name, Long currentId) {
        String base = SlugUtils.toSlug(name);
        if (base.isBlank()) throw new IllegalArgumentException("Tên sản phẩm không hợp lệ để tạo slug!");
        String candidate = truncateSlug(base, SLUG_MAX_LENGTH);
        int i = 1;
        while (currentId == null ? productRepository.existsBySlug(candidate)
                                 : productRepository.existsBySlugAndIdNot(candidate, currentId)) {
            String suffix = "-" + i++;
            candidate = truncateSlug(base, SLUG_MAX_LENGTH - suffix.length()) + suffix;
        }
        return candidate;
    }

    /** Cắt slug về tối đa {@code maxLength} ký tự, không để dấu {@code -} treo ở cuối. */
    private static String truncateSlug(String slug, int maxLength) {
        if (slug.length() <= maxLength) {
            return slug;
        }
        String cut = slug.substring(0, maxLength);
        int end = cut.length();
        while (end > 1 && cut.charAt(end - 1) == '-') {
            end--;
        }
        return cut.substring(0, end);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (blank(value)) return null;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Boolean parseBoolean(String value) {
        if (blank(value)) return null;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1" -> Boolean.TRUE;
            case "false", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    // Filter products for user
    @Transactional(readOnly = true)
    public Page<Product> filterProductsForUser(ProductType type, Long categoryId, String keyword, Pageable pageable) {
        String keywordParam = blank(keyword) ? null : keyword.trim();
        return productRepository.filterProducts(type, categoryId, keywordParam, pageable);
    }

    /** Batch-load primary image URLs for a page of products — avoids N+1 when rendering the list. */
    @Transactional(readOnly = true)
    public Map<Long, String> getPrimaryImageUrls(List<Long> productIds) {
        if (productIds.isEmpty()) return Map.of();
        return productImageRepository.findPrimaryUrlsByProductIds(productIds)
                .stream()
                .collect(Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (String) row[1],
                        (a, b) -> a
                ));
    }

    // Get product by slug for user
    public Product getProductBySlug(String slug) {
        return productRepository.findBySlugAndIsAvailableTrue(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Sản phẩm không tồn tại hoặc đã ngừng kinh doanh!"));
    }
}



