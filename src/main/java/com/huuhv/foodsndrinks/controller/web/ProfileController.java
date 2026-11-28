package com.huuhv.foodsndrinks.controller.web;

import com.huuhv.foodsndrinks.dto.request.ProfileUpdateReqDto;
import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.exception.DuplicateResourceException;
import com.huuhv.foodsndrinks.exception.PasswordChangeThrottledException;
import com.huuhv.foodsndrinks.security.PasswordChangeSessionHandler;
import com.huuhv.foodsndrinks.service.OrderService;
import com.huuhv.foodsndrinks.service.UserService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;

@Controller
@RequiredArgsConstructor
public class ProfileController {

    private static final int RECENT_ORDERS_SIZE = 5;
    private static final String PROFILE_EDIT_VIEW = "web/profile-edit";

    private final UserService  userService;
    private final OrderService orderService;
    private final PasswordChangeSessionHandler passwordChangeSessionHandler;

    @GetMapping("/profile")
    public String profilePage(Principal principal, Model model) {
        User user = userService.getCurrentUser(principal.getName());

        model.addAttribute("user", user);
        model.addAttribute("recentOrders", orderService.getOrderHistoryForUser(user.getId(), 0, RECENT_ORDERS_SIZE));
        model.addAttribute("cartItemCount", orderService.getCartItemCount(user.getId()));

        return "web/profile";
    }

    @GetMapping("/profile/edit")
    public String editProfileForm(Principal principal, Model model) {
        User user = userService.getCurrentUser(principal.getName());
        model.addAttribute("profileUpdateReqDto", userService.getProfileForEdit(user.getId()));
        return PROFILE_EDIT_VIEW;
    }

    @PostMapping("/profile/edit")
    public String updateProfile(@Valid @ModelAttribute("profileUpdateReqDto") ProfileUpdateReqDto dto,
                                BindingResult bindingResult,
                                Principal principal,
                                Model model,
                                RedirectAttributes ra,
                                HttpServletRequest request,
                                HttpServletResponse response) throws ServletException {
        if (bindingResult.hasErrors()) {
            return PROFILE_EDIT_VIEW;
        }

        User user = userService.getCurrentUser(principal.getName());
        try {
            // Đổi mật khẩu: mọi session khác của tài khoản bị huỷ (AccountStatusFilter); session này được giữ lại
            userService.updateProfile(user.getId(), dto).ifPresent(passwordChangedAt ->
                    passwordChangeSessionHandler.keepCurrentSession(request, response, principal.getName(),
                            passwordChangedAt));
            ra.addFlashAttribute("successMessage", "Cập nhật thông tin thành công!");
            return "redirect:/profile";
        } catch (PasswordChangeThrottledException e) {
            // Sai mật khẩu hiện tại quá nhiều lần: session có thể đã bị chiếm → đăng xuất (logout handler của Spring
            // Security: huỷ session, xoá SecurityContext và cookie remember-me) và bắt đăng nhập lại
            request.logout();
            return "redirect:/login?reauth=true";
        } catch (IllegalArgumentException | DuplicateResourceException e) {
            model.addAttribute("errorMessage", e.getMessage());
            return PROFILE_EDIT_VIEW;
        }
    }
}
