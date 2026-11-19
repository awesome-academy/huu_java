package com.huuhv.foodsndrinks.security;

import com.huuhv.foodsndrinks.repository.UserRepository;
import com.huuhv.foodsndrinks.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {
    private final UserRepository userRepository;

    /**
     * Tài khoản bị khoá được trả về với {@code enabled = false} chứ không ném exception ở đây: {@code DaoAuthenticationProvider}
     * bọc mọi exception (trừ {@code UsernameNotFoundException}) ném ra từ hàm này thành
     * {@code InternalAuthenticationServiceException} — audit sẽ ghi nhầm thành lỗi hệ thống và API trả 500. Cờ
     * {@code enabled} được kiểm tra bởi {@code preAuthenticationChecks} (form/API → {@code DisabledException}),
     * {@code userDetailsChecker} của remember-me và {@code JwtAuthenticationFilter}.
     */
    @Override
    public UserDetails loadUserByUsername(String loginId) throws UsernameNotFoundException {
        User user = userRepository.findByUsernameOrEmail(loginId, loginId)
                .orElseThrow(() -> new UsernameNotFoundException("Tài khoản không tồn tại: " + loginId));

        String encodedPassword = user.getPassword();
        if (encodedPassword == null || encodedPassword.isBlank()) {
            // OAuth accounts may not have a local password
            throw new UsernameNotFoundException("Thông tin đăng nhập không chính xác.");
        }

        return org.springframework.security.core.userdetails.User.withUsername(user.getUsername())
                .password(encodedPassword)
                .authorities(Collections.singletonList(new SimpleGrantedAuthority(user.getRole().name())))
                .disabled(Boolean.FALSE.equals(user.getIsActive()))
                .build();
    }
}
