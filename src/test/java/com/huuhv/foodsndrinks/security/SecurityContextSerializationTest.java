package com.huuhv.foodsndrinks.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.RememberMeAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kho session dùng chung (Spring Session JDBC) lưu attribute của session bằng Java serialization, nên mọi thứ app đặt
 * vào session phải serialize rồi đọc lại được ở instance khác: {@code SecurityContext} của CẢ BỐN kiểu đăng nhập (principal
 * dựng đúng như {@code CustomUserDetailsService} / {@code CustomOidcUserService} / {@code CustomOAuth2UserService}), token
 * CSRF và mốc xác thực. Thêm field/kiểu mới vào principal mà không serialize được thì test này fail — không phải đợi tới
 * khi request thứ hai của user rơi vào instance khác.
 */
class SecurityContextSerializationTest {

    private static final List<GrantedAuthority> USER = List.of(new SimpleGrantedAuthority("ROLE_USER"));

    @Test
    void formLogin_contextSurvivesTheSessionStore() throws Exception {
        UserDetails principal = User.withUsername("alice").password("{noop}erased-after-login").authorities(USER).build();
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(principal, null, USER);
        authentication.setDetails(new WebAuthenticationDetails(new MockHttpServletRequest()));

        Authentication restored = roundTrip(authentication);

        assertThat(restored.getName()).isEqualTo("alice");
        assertThat(restored.isAuthenticated()).isTrue();
        assertThat(authorities(restored)).containsExactly("ROLE_USER");
        assertThat(restored.getDetails()).isInstanceOf(WebAuthenticationDetails.class);
    }

    @Test
    void rememberMeLogin_contextSurvivesTheSessionStore() throws Exception {
        UserDetails principal = User.withUsername("alice").password("hash").authorities(USER).build();

        Authentication restored = roundTrip(new RememberMeAuthenticationToken("remember-me-key", principal, USER));

        assertThat(restored).isInstanceOf(RememberMeAuthenticationToken.class);
        assertThat(restored.getName()).isEqualTo("alice");
        assertThat(authorities(restored)).containsExactly("ROLE_USER");
    }

    @Test
    void googleOidcLogin_contextSurvivesTheSessionStore() throws Exception {
        Instant issuedAt = Instant.parse("2026-11-28T03:00:00Z");
        Map<String, Object> idClaims = Map.of("sub", "google-sub-1", "iss", "https://accounts.google.com",
                "email", "alice@example.com", "email_verified", true);
        OidcIdToken idToken = new OidcIdToken("id-token-value", issuedAt, issuedAt.plusSeconds(3600), idClaims);
        // CustomOidcUserService: claims của userinfo + local_username làm name attribute
        Map<String, Object> userInfo = new HashMap<>(idClaims);
        userInfo.put("name", "Alice Nguyễn");
        userInfo.put("picture", "https://lh3.googleusercontent.com/a/photo");
        userInfo.put(CustomOidcUserService.NAME_ATTRIBUTE_KEY, "alice");
        DefaultOidcUser principal =
                new DefaultOidcUser(USER, idToken, new OidcUserInfo(userInfo), CustomOidcUserService.NAME_ATTRIBUTE_KEY);

        Authentication restored = roundTrip(new OAuth2AuthenticationToken(principal, USER, "google"));

        assertThat(restored.getName()).isEqualTo("alice");
        assertThat(((OAuth2AuthenticationToken) restored).getAuthorizedClientRegistrationId()).isEqualTo("google");
        DefaultOidcUser restoredPrincipal = (DefaultOidcUser) restored.getPrincipal();
        assertThat(restoredPrincipal.getEmail()).isEqualTo("alice@example.com");
        assertThat(restoredPrincipal.getIdToken().getIssuedAt()).isEqualTo(issuedAt);
    }

    @Test
    void facebookLogin_withNestedPictureMap_contextSurvivesTheSessionStore() throws Exception {
        // Facebook trả ảnh đại diện dạng object lồng nhau: picture.data.url
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("id", "1234567890");
        attributes.put("name", "Alice Nguyễn");
        attributes.put("email", "alice@example.com");
        attributes.put("picture", Map.of("data", Map.of("url", "https://graph.facebook.com/pic.jpg", "width", 50)));
        attributes.put("local_username", "alice");

        Authentication restored = roundTrip(new OAuth2AuthenticationToken(
                new DefaultOAuth2User(USER, attributes, "local_username"), USER, "facebook"));

        assertThat(restored.getName()).isEqualTo("alice");
        OAuth2User principal = (OAuth2User) restored.getPrincipal();
        assertThat(principal.<Map<String, Map<String, Object>>>getAttribute("picture").get("data"))
                .containsEntry("url", "https://graph.facebook.com/pic.jpg");
    }

    @Test
    void twitterLogin_contextSurvivesTheSessionStore() throws Exception {
        // CustomOAuth2UserService bóc object "data" của X: các field nằm phẳng trong attributes
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("id", "44196397");
        attributes.put("username", "alice_x");
        attributes.put("name", "Alice");
        attributes.put("profile_image_url", "https://pbs.twimg.com/profile_images/1/a_normal.jpg");
        attributes.put("local_username", "alice");

        Authentication restored = roundTrip(new OAuth2AuthenticationToken(
                new DefaultOAuth2User(USER, attributes, "local_username"), USER, "twitter"));

        assertThat(restored.getName()).isEqualTo("alice");
        assertThat(((OAuth2User) restored.getPrincipal()).<String>getAttribute("username")).isEqualTo("alice_x");
    }

    @Test
    void csrfToken_andAuthenticationTime_surviveTheSessionStore() throws Exception {
        DefaultCsrfToken token = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "token-value");
        // Kiểu của attribute mốc xác thực (SessionAuthenticationTime.stamp ghi Long epoch-millis)
        Long authenticatedAt = Instant.now().toEpochMilli();

        assertThat(((DefaultCsrfToken) serializeAndRead(token)).getToken()).isEqualTo("token-value");
        assertThat(serializeAndRead(authenticatedAt)).isEqualTo(authenticatedAt);
    }

    /** Đặt vào {@code SecurityContext} như {@code HttpSessionSecurityContextRepository} rồi serialize → deserialize. */
    private static Authentication roundTrip(Authentication authentication) throws Exception {
        SecurityContext restored = (SecurityContext) serializeAndRead(new SecurityContextImpl(authentication));
        return restored.getAuthentication();
    }

    private static Object serializeAndRead(Object value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return in.readObject();
        }
    }

    private static List<String> authorities(Authentication authentication) {
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }
}
