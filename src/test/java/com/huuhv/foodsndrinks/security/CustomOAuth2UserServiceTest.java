package com.huuhv.foodsndrinks.security;

import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.enums.AuthProvider;
import com.huuhv.foodsndrinks.enums.Role;
import com.huuhv.foodsndrinks.exception.OAuthEmailConflictException;
import com.huuhv.foodsndrinks.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Đăng nhập Facebook/X: principal có {@code getName()} là username nội bộ; tài khoản đã bị admin khoá bị từ chối như
 * form/API.
 */
class CustomOAuth2UserServiceTest {

    private final UserService userService = mock(UserService.class);
    private final CustomOAuth2UserService service = new CustomOAuth2UserService(userService);

    @Test
    void facebookLogin_activeAccount_principalNameIsLocalUsername() {
        givenLocalUser(AuthProvider.FACEBOOK, "fb-user", true);

        OAuth2User result = service.toLocalUser("facebook", facebookUser());

        assertThat(result.getName()).isEqualTo("fb-user");
        assertThat(result.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
    }

    @Test
    void facebookLogin_lockedAccount_isRejected() {
        givenLocalUser(AuthProvider.FACEBOOK, "fb-user", false);

        assertThatThrownBy(() -> service.toLocalUser("facebook", facebookUser()))
                .isInstanceOfSatisfying(DisabledOAuth2AccountException.class,
                        ex -> assertThat(ex.getUsername()).isEqualTo("fb-user"));
    }

    @Test
    void facebookEmailConflict_isRejectedWithEmailConflict() {
        when(userService.provisionOAuthUser(eq(AuthProvider.FACEBOOK), any(), any(), anyBoolean(), any(), any()))
                .thenThrow(new OAuthEmailConflictException("victim"));

        assertThatThrownBy(() -> service.toLocalUser("facebook", facebookUser()))
                .isInstanceOfSatisfying(OAuth2EmailConflictException.class,
                        ex -> assertThat(ex.getExistingUsername()).isEqualTo("victim"));
    }

    @Test
    void twitterLogin_lockedAccount_isRejected() {
        givenLocalUser(AuthProvider.TWITTER, "x-user", false);

        OAuth2User twitterUser = new DefaultOAuth2User(AuthorityUtils.createAuthorityList("OAUTH2_USER"),
                Map.of("data", Map.of("id", "tw-1", "name", "X User")), "data");

        assertThatThrownBy(() -> service.toLocalUser("twitter", twitterUser))
                .isInstanceOfSatisfying(DisabledOAuth2AccountException.class,
                        ex -> assertThat(ex.getUsername()).isEqualTo("x-user"));
    }

    private void givenLocalUser(AuthProvider provider, String username, boolean active) {
        User local = User.builder().username(username).role(Role.ROLE_USER).isActive(active).build();
        when(userService.provisionOAuthUser(eq(provider), any(), any(), anyBoolean(), any(), any())).thenReturn(local);
    }

    private static OAuth2User facebookUser() {
        return new DefaultOAuth2User(AuthorityUtils.createAuthorityList("OAUTH2_USER"),
                Map.of("id", "fb-1", "name", "FB User", "email", "fb@example.com"), "id");
    }
}
