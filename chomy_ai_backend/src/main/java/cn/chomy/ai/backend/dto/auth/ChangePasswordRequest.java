package cn.chomy.ai.backend.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 当前用户修改密码请求，不允许通过请求体指定其他用户 ID。
 *
 * @param oldPassword 原密码，必须验证通过后才能修改
 * @param newPassword 新密码，8 至 72 个字符，业务层同时限制 UTF-8 编码不超过 72 字节
 * @author chomy
 */
public record ChangePasswordRequest(
        @NotBlank(message = "原密码不能为空") @Size(max = 128, message = "原密码过长") String oldPassword,
        @NotBlank(message = "新密码不能为空") @Size(min = 8, max = 72, message = "新密码长度必须为8至72个字符") String newPassword) {
    /** 密码请求不输出任何原始字段值。 */
    @Override
    public String toString() { return "ChangePasswordRequest[credentials=已隐藏]"; }
}
