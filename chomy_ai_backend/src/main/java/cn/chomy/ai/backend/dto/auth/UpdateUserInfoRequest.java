package cn.chomy.ai.backend.dto.auth;

import cn.chomy.ai.backend.enums.Gender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 个人资料更新白名单：不接受账号、手机号、密码、状态或角色，避免越权修改安全字段。
 * 此接口使用完整表单更新，signature、avatarUrl 为 null 或空字符串时清空对应内容。
 *
 * @param nickname 必填昵称，最多 64 个字符
 * @param gender 必填性别，0 未知、1 男、2 女
 * @param signature 个性签名，可为空，最多 255 个字符
 * @param avatarUrl 头像地址，可为空，仅允许 HTTP 或 HTTPS 地址
 * @author chomy
 */
public record UpdateUserInfoRequest(
        @NotBlank(message = "昵称不能为空") @Size(max = 64, message = "昵称长度不能超过64个字符") String nickname,
        @NotNull(message = "性别不能为空") Gender gender,
        @Size(max = 255, message = "个性签名长度不能超过255个字符") String signature,
        @Size(max = 2048, message = "头像地址过长") @Pattern(regexp = "^(https?://[^\\s]+)?$", message = "头像地址必须为HTTP或HTTPS地址") String avatarUrl) {
}
