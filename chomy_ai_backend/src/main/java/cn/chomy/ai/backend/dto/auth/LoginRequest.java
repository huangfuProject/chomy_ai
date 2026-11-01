package cn.chomy.ai.backend.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用户名密码登录请求，密码仅用于本次认证，不进入日志或响应。
 *
 * @param username 用户名，必填，最多 64 个字符
 * @param password 原始密码，必填，经传输层保护后由服务端验证哈希
 * @author chomy
 */
public record LoginRequest(@NotBlank(message = "用户名不能为空") @Size(max = 64, message = "用户名长度不能超过64个字符") String username,
                           @NotBlank(message = "密码不能为空") @Size(max = 128, message = "密码长度不能超过128个字符") String password) {
    /** 避免 record 默认 toString 将原始密码写入调试日志。 */
    @Override
    public String toString() { return "LoginRequest[credentials=已隐藏]"; }
}
