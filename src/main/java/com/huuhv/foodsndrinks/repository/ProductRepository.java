package com.huuhv.foodsndrinks.repository;

import com.huuhv.foodsndrinks.entity.Product;
import com.huuhv.foodsndrinks.enums.ProductType;
import com.huuhv.foodsndrinks.repository.projection.ProductExportRow;
import com.huuhv.foodsndrinks.repository.projection.ProductImportMatch;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    boolean existsByName(String name);
    boolean existsByNameAndIdNot(String name, Long id);
    boolean existsBySlug(String slug);
    boolean existsBySlugAndIdNot(String slug, Long id);

    long countByIsAvailableTrue();

    /**
     * Sản phẩm trùng tên với {@code name} THEO COLLATION của cột (mặc định {@code utf8mb4_0900_ai_ci}: không phân biệt
     * hoa/thường và dấu — "Bò kho" khớp cả "bo kho" lẫn "Bơ khô"), đúng quy tắc của {@link #existsByName}. Import Excel
     * dùng để quyết định thêm mới / cập nhật: người gọi phải tự so khớp CHÍNH XÁC trước khi cập nhật.
     */
    @Query("""
            SELECT new com.huuhv.foodsndrinks.repository.projection.ProductImportMatch(
                       p.id, p.name, c.id, p.type, p.price, p.description, p.isAvailable)
            FROM Product p LEFT JOIN p.category c
            WHERE p.name = :name
            ORDER BY p.id
            """)
    List<ProductImportMatch> findImportMatchesByName(@Param("name") String name);

    @Query(value = """
            SELECT p FROM Product p LEFT JOIN FETCH p.category c
            WHERE (:name IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', :name, '%')))
              AND (:categoryId IS NULL OR (p.category IS NOT NULL AND c.id = :categoryId))
              AND (:type IS NULL OR p.type = :type)
              AND (:isAvailable IS NULL OR p.isAvailable = :isAvailable)
            ORDER BY p.id DESC
            """,
            countQuery = """
            SELECT COUNT(p) FROM Product p LEFT JOIN p.category c
            WHERE (:name IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', :name, '%')))
              AND (:categoryId IS NULL OR (p.category IS NOT NULL AND c.id = :categoryId))
              AND (:type IS NULL OR p.type = :type)
              AND (:isAvailable IS NULL OR p.isAvailable = :isAvailable)
            """)
    Page<Product> search(@Param("name") String name,
                         @Param("categoryId") Long categoryId,
                         @Param("type") ProductType type,
                         @Param("isAvailable") Boolean isAvailable,
                         Pageable pageable);

    /**
     * Các dòng của file export sản phẩm: cùng bộ lọc và thứ tự với {@link #search} (sửa WHERE của một câu thì sửa cả ba, kể
     * cả {@code countQuery}), không nạp entity. {@code limit} chặn số dòng đọc lên (người gọi xin {@code max + 1} để nhận
     * ra bộ lọc vượt giới hạn).
     */
    @Query("""
            SELECT new com.huuhv.foodsndrinks.repository.projection.ProductExportRow(
                       p.name, c.name, p.type, p.price, p.description, p.isAvailable)
            FROM Product p LEFT JOIN p.category c
            WHERE (:name IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', :name, '%')))
              AND (:categoryId IS NULL OR (p.category IS NOT NULL AND c.id = :categoryId))
              AND (:type IS NULL OR p.type = :type)
              AND (:isAvailable IS NULL OR p.isAvailable = :isAvailable)
            ORDER BY p.id DESC
            """)
    List<ProductExportRow> findExportRows(@Param("name") String name,
                                          @Param("categoryId") Long categoryId,
                                          @Param("type") ProductType type,
                                          @Param("isAvailable") Boolean isAvailable,
                                          Limit limit);

    // Note: no fetch-join on p.images here — it's a @OneToMany collection, and combining a
    // collection fetch-join with Pageable forces Hibernate to paginate the whole result set
    // in memory. Primary image URLs for the page are batch-loaded separately (see
    // ProductImageRepository.findPrimaryUrlsByProductIds), same pattern as ProductService.searchProducts.
    @Query(value = """
            SELECT p FROM Product p
            LEFT JOIN FETCH p.category c
            WHERE p.isAvailable = true
              AND (:type IS NULL OR p.type = :type)
              AND (:categoryId IS NULL OR c.id = :categoryId)
              AND (:keyword IS NULL
                   OR LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(p.description) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(c.name) LIKE LOWER(CONCAT('%', :keyword, '%')))
            """,
            countQuery = """
            SELECT COUNT(p) FROM Product p LEFT JOIN p.category c
            WHERE p.isAvailable = true
              AND (:type IS NULL OR p.type = :type)
              AND (:categoryId IS NULL OR c.id = :categoryId)
              AND (:keyword IS NULL
                   OR LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(p.description) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(c.name) LIKE LOWER(CONCAT('%', :keyword, '%')))
            """)
    Page<Product> filterProducts(@Param("type") ProductType type,
                                 @Param("categoryId") Long categoryId,
                                 @Param("keyword") String keyword,
                                 Pageable pageable);

    @Query("SELECT DISTINCT p FROM Product p LEFT JOIN FETCH p.category LEFT JOIN FETCH p.images WHERE p.slug = :slug AND p.isAvailable = true")
    Optional<Product> findBySlugAndIsAvailableTrue(@Param("slug") String slug);
}

