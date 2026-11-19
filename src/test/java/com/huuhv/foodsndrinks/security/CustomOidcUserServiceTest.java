package com.huuhv.foodsndrinks.security;

import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.enums.AuthProvider;
import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Principal của đăng nhập Google có {@code getName()} là username nội bộ (không phải email), giống form login và
 * Facebook/X — audit log và MDC {@code user.name} ghi cùng một tên cho một tài khoản. Tài khoản đã bị admin khoá bị
 * từ chối như form/API.
 */
class CustomOidcUserServiceTest {

    private static final String SUBJECT = "google-sub-123";
    private static final String EMAIL = "admin01@gmail.com";

    private final UserService userService = mock(UserService.class);
    private final CustomOidcUserService service = new CustomOidcUserService(userService);

    @Test
    void principalName_isLocalUsername_notEmail() {
        givenLocalUser("admin01", Role.ROLE_ADMIN);

        OidcUser result = service.toLocalUser(googleUser(new OidcUserInfo(Map.of(
                "sub", SUBJECT, "email", EMAIL, "name", "Admin 01", "picture", "https://example.com/a.png"))));

        assertThat(result.getName()).isEqualTo("admin01");
        assertThat(result.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
        // Claim gốc của Google vẫn giữ nguyên
        assertThat(result.getEmail()).isEqualTo(EMAIL);
        assertThat(result.getSubject()).isEqualTo(SUBJECT);
        assertThat(result.getFullName()).isEqualTo("Admin 01");
        assertThat(result.getIdToken().getTokenValue()).isEqualTo("id-token");
    }

    @Test
    void withoutUserInfo_stillUsesLocalUsername() {
        givenLocalUser("google-user", Role.ROLE_USER);

        OidcUser result = service.toLocalUser(googleUser(null));

        assertThat(result.getName()).isEqualTo("google-user");
        assertThat(result.getEmail()).isEqualTo(EMAIL);
    }

    @Test
    void lockedAccount_isRejected() {
        givenLocalUser("admin01", Role.ROLE_ADMIN, false);

        assertThatThrownBy(() -> service.toLocalUser(googleUser(null)))
                .isInstanceOfSatisfying(DisabledOAuth2AccountException.class, ex -> {
                    assertThat(ex.getUsername()).isEqualTo("admin01");
                    assertThat(ex.getError().getErrorCode()).isEqualTo("access_denied");
                });
    }

    private void givenLocalUser(String username, Role role) {
        givenLocalUser(username, role, true);
    }

    private void givenLocalUser(String username, Role role, boolean active) {
        User local = User.builder().username(username).email(EMAIL).role(role).isActive(active).build();
        when(userService.provisionOAuthUser(AuthProvider.GOOGLE, SUBJECT, EMAIL,
                "Admin 01", "https://example.com/a.png")).thenReturn(local);
        when(userService.provisionOAuthUser(AuthProvider.GOOGLE, SUBJECT, EMAIL, null, null)).thenReturn(local);
    }

    private static OidcUser googleUser(OidcUserInfo userInfo) {
        Instant now = Instant.now();
        OidcIdToken idToken = OidcIdToken.withTokenValue("id-token")
                .subject(SUBJECT)
                .claim("email", EMAIL)
                .issuer("https://accounts.google.com")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .build();
        return new DefaultOidcUser(AuthorityUtils.createAuthorityList("OIDC_USER"), idToken, userInfo, "email");
    }
}
