package com.huuhv.foodsndrinks.dto.request;

import com.huuhv.foodsndrinks.validation.NotCompromisedPassword;
import com.huuhv.foodsndrinks.validation.PasswordBreachCheck;
import com.huuhv.foodsndrinks.validation.ValidPassword;
import jakarta.validation.GroupSequence;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
// Kiểm tra mật khẩu bị lộ (gọi dịch vụ ngoài) chỉ chạy khi mọi ràng buộc khác đã đạt
@GroupSequence({ProfileUpdateReqDto.class, PasswordBreachCheck.class})
public class ProfileUpdateReqDto {

    @NotBlank(message = "Họ và tên không được để trống.")
    @Size(min = 2, max = 100, message = "Họ và tên phải từ 2 đến 100 ký tự.")
    private String fullName;

    @NotBlank(message = "Email không được để trống.")
    @Email(message = "Định dạng email không hợp lệ.")
    private String email;

    @NotBlank(message = "Số điện thoại không được để trống.")
    @Pattern(regexp = "^(0|\\+84)[35789][0-9]{8}$", message = "Số điện thoại không hợp lệ.")
    private String phone;

    /** Bắt buộc nếu muốn đổi mật khẩu */
    private String currentPassword;

    /** Để trống = không đổi mật khẩu. @ValidPassword coi chuỗi rỗng "" là hợp lệ (khác @Size, vốn chặn luôn trường
     *  hợp bỏ trống); kiểm tra mật khẩu hiện tại/xác nhận vẫn nằm ở UserService.updateProfile. */
    @ValidPassword
    @NotCompromisedPassword(groups = PasswordBreachCheck.class)
    private String newPassword;

    private String confirmNewPassword;
}
