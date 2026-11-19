package com.huuhv.foodsndrinks.security;

import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.enums.AuthProvider;
import com.huuhv.foodsndrinks.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Google sign-in (OIDC). Google reliably verifies the account's email, so it is used (after the provider id) as the
 * account-matching key in {@code UserService.provisionOAuthUser}.
 * <p>
 * Like {@link CustomOAuth2UserService}, the principal's name is the resolved local username (a synthetic
 * {@value #NAME_ATTRIBUTE_KEY} claim), not the email: {@code Authentication#getName()} — used by audit log, MDC
 * {@code user.name} and {@code UserService.getCurrentUser(principal.getName())} — is then the same value whichever way
 * the user signed in (form, API, Google, Facebook, X).
 */
@Service
@RequiredArgsConstructor
public class CustomOidcUserService extends OidcUserService {

    static final String NAME_ATTRIBUTE_KEY = "local_username";

    private final UserService userService;

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        return toLocalUser(super.loadUser(userRequest));
    }

    /**
     * Resolves (or creates) the local account behind a Google identity and builds the principal for it.
     *
     * @throws DisabledOAuth2AccountException if the local account has been locked by an admin
     */
    OidcUser toLocalUser(OidcUser oidcUser) {
        User localUser = userService.provisionOAuthUser(
                AuthProvider.GOOGLE,
                oidcUser.getSubject(),
                oidcUser.getEmail(),
                oidcUser.getFullName(),
                oidcUser.getPicture()
        );
        DisabledOAuth2AccountException.rejectIfDisabled(localUser);

        // DefaultOidcUser merges userinfo claims with ID token claims (ID token wins), so the synthetic claim goes into
        // userinfo; Google's ID token does not carry it.
        Map<String, Object> userInfoClaims = new HashMap<>();
        if (oidcUser.getUserInfo() != null) {
            userInfoClaims.putAll(oidcUser.getUserInfo().getClaims());
        }
        userInfoClaims.put(NAME_ATTRIBUTE_KEY, localUser.getUsername());

        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(localUser.getRole().name()));
        return new DefaultOidcUser(authorities, oidcUser.getIdToken(), new OidcUserInfo(userInfoClaims), NAME_ATTRIBUTE_KEY);
    }
}
