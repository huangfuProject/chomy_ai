package cn.chomy.ai.backend.dto.admin;

import cn.chomy.ai.backend.enums.Gender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 管理端用户资料完整修改表单，不接受密码、角色、状态、激活标记或创建审计字段。
 * 可选签名和头像为空时主动清空，不通过资料接口绕过独立的状态及密码操作权限。
 *
 * @param username 必填且唯一的用户名
 * @param nickname 必填昵称
 * @param phone 必填且唯一的规范手机号
 * @param gender 必填性别枚举
 * @param signature 可空签名
 * @param avatarUrl 可空 HTTP/HTTPS 头像 URL
 * @author chomy
 */
public record AdminUpdateUserRequest(
        @NotBlank(message = "用户名不能为空") @Size(max = 64, message = "用户名不能超过64个字符") String username,
        @NotBlank(message = "昵称不能为空") @Size(max = 64, message = "昵称不能超过64个字符") String nickname,
        @NotBlank(message = "手机号不能为空") @Pattern(regexp = "^\\+?[0-9]{6,20}$", message = "手机号格式不正确") String phone,
        @NotNull(message = "性别不能为空") Gender gender,
        @Size(max = 255, message = "个性签名不能超过255个字符") String signature,
        @Size(max = 2048, message = "头像地址过长") @Pattern(regexp = "^(https?://[^\\s]+)?$", message = "头像地址必须为HTTP或HTTPS地址") String avatarUrl) { }
