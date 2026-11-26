package com.huuhv.foodsndrinks.repository;

import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.enums.AuthProvider;
import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.repository.projection.UserAccountStatus;
import com.huuhv.foodsndrinks.repository.projection.UsernameAccountStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
    boolean existsByUsernameOrEmail(String username, String email);
    boolean existsByPhone(String phone);
    boolean existsByUsernameAndIdNot(String username, Long id);
    boolean existsByEmailAndIdNot(String email, Long id);
    boolean existsByPhoneAndIdNot(String phone, Long id);

    Optional<User> findByUsernameOrEmail(String username, String email);
    Optional<User> findByEmail(String email);

    /** Chạy ở mỗi request của người dùng đã đăng nhập (xem {@code AccountStatusService}) — chỉ lấy 2 cột, theo index UNIQUE. */
    @Query("SELECT u.isActive AS active, u.role AS role FROM User u WHERE u.username = :username")
    Optional<UserAccountStatus> findAccountStatusByUsername(@Param("username") String username);

    /** Trạng thái của nhiều tài khoản trong một query (job kiểm tra lại các kết nối WebSocket đang mở). */
    @Query("SELECT u.username AS username, u.isActive AS active, u.role AS role FROM User u"
            + " WHERE u.username IN :usernames")
    List<UsernameAccountStatus> findAccountStatusesByUsernameIn(@Param("usernames") Collection<String> usernames);
    Optional<User> findByAuthProviderAndProviderId(AuthProvider authProvider, String providerId);

    long countByIsActiveTrue();

    @Query(value = """
            SELECT u FROM User u
            WHERE (:keyword IS NULL
                   OR LOWER(u.username) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(u.email)    LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(u.phone)    LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (:role       IS NULL OR u.role       = :role)
              AND (:isActive   IS NULL OR u.isActive   = :isActive)
            ORDER BY u.id DESC
            """,
            countQuery = """
            SELECT COUNT(u) FROM User u
            WHERE (:keyword IS NULL
                   OR LOWER(u.username) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(u.email)    LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(u.phone)    LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (:role       IS NULL OR u.role       = :role)
              AND (:isActive   IS NULL OR u.isActive   = :isActive)
            """)
    Page<User> search(@Param("keyword")  String keyword,
                      @Param("role")     Role role,
                      @Param("isActive") Boolean isActive,
                      Pageable pageable);
}
