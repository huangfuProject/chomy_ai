package cn.chomy.ai.backend.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 管理端重置用户密码表单，授权管理员无需提供目标用户原密码。
 *
 * @param newPassword 新原密码，8 至 72 个字符，且 UTF-8 编码不超过 72 字节
 * @author chomy
 */
public record AdminResetPasswordRequest(
        @NotBlank(message = "新密码不能为空") @Size(min = 8, max = 72, message = "密码长度必须为8至72个字符") String newPassword) {
    /** 不在日志中输出重置密码。 */
    @Override
    public String toString() { return "AdminResetPasswordRequest[credentials=已隐藏]"; }
}
