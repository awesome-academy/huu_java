package com.huuhv.foodsndrinks.controller.web;

import com.huuhv.foodsndrinks.dto.response.CartPayloadResDto;
import com.huuhv.foodsndrinks.dto.response.ErrorResponse;
import com.huuhv.foodsndrinks.dto.response.OrderResDto;
import com.huuhv.foodsndrinks.entity.User;
import com.huuhv.foodsndrinks.exception.ResourceNotFoundException;
import com.huuhv.foodsndrinks.service.OrderService;
import com.huuhv.foodsndrinks.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;

@Controller
@RequiredArgsConstructor
public class CartController {

    private final OrderService orderService;
    private final UserService  userService;

    @GetMapping("/cart")
    public String cartPage(Principal principal, Model model) {
        User user = userService.getCurrentUser(principal.getName());
        OrderResDto cart = orderService.getCartForUser(user.getId());
        model.addAttribute("cart", cart);
        return "web/cart";
    }

    // Add/update/remove are called via fetch() from the storefront — no page reload,
    // so they return the fresh cart state as JSON instead of a redirect.
    // Lỗi cũng là JSON {message} (main.js#postForm hiện message thành toast), bắt ngay tại đây: để lọt tới
    // WebExceptionHandler thì fetch() nhận về trang HTML. Mã lỗi theo quy ước chung: ResourceNotFoundException → 404
    // (sản phẩm / món trong giỏ không tồn tại — kể cả món của người khác, xem OrderService.requireOwnedCartItem),
    // IllegalArgumentException → 400.

    @PostMapping("/cart/add")
    @ResponseBody
    public ResponseEntity<?> addToCart(@RequestParam Long productId,
                                       @RequestParam(defaultValue = "1") int quantity,
                                       Principal principal) {
        User user = userService.getCurrentUser(principal.getName());
        try {
            orderService.addToCart(user, productId, quantity);
            return ResponseEntity.ok(buildCartPayload(user.getId()));
        } catch (ResourceNotFoundException e) {
            return error(HttpStatus.NOT_FOUND, e);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e);
        }
    }

    @PostMapping("/cart/update/{detailId}")
    @ResponseBody
    public ResponseEntity<?> updateCartItem(@PathVariable Long detailId,
                                            @RequestParam int quantity,
                                            Principal principal) {
        User user = userService.getCurrentUser(principal.getName());
        try {
            orderService.updateCartItemQuantity(user, detailId, quantity);
            return ResponseEntity.ok(buildCartPayload(user.getId()));
        } catch (ResourceNotFoundException e) {
            return error(HttpStatus.NOT_FOUND, e);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e);
        }
    }

    @PostMapping("/cart/remove/{detailId}")
    @ResponseBody
    public ResponseEntity<?> removeCartItem(@PathVariable Long detailId,
                                            Principal principal) {
        User user = userService.getCurrentUser(principal.getName());
        try {
            orderService.removeCartItem(user, detailId);
            return ResponseEntity.ok(buildCartPayload(user.getId()));
        } catch (ResourceNotFoundException e) {
            return error(HttpStatus.NOT_FOUND, e);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e);
        }
    }

    // Checkout still navigates to a new page (order confirmation / history), so it stays a normal redirect flow.
    @PostMapping("/cart/checkout")
    public String checkout(@RequestParam String shippingAddress,
                           @RequestParam(required = false) String note,
                           Principal principal,
                           RedirectAttributes ra) {
        User user = userService.getCurrentUser(principal.getName());
        try {
            orderService.checkout(user, shippingAddress, note);
            ra.addFlashAttribute("successMessage", "Đặt hàng thành công! Cảm ơn bạn đã mua hàng.");
            return "redirect:/orders";
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/cart";
        }
    }

    /** Thông điệp của hai exception nghiệp vụ này do service viết cho người dùng — không phải lỗi hệ thống. */
    private static ResponseEntity<ErrorResponse> error(HttpStatus status, RuntimeException e) {
        return ResponseEntity.status(status).body(ErrorResponse.builder().message(e.getMessage()).build());
    }

    private CartPayloadResDto buildCartPayload(Long userId) {
        OrderResDto cart = orderService.getCartForUser(userId);
        int cartItemCount = orderService.getCartItemCount(userId);
        return new CartPayloadResDto(cart, cartItemCount);
    }
}
