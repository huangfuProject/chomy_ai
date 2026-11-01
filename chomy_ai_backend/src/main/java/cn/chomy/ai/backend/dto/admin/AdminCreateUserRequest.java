package cn.chomy.ai.backend.dto.admin;

import cn.chomy.ai.backend.enums.ActivationStatus;
import cn.chomy.ai.backend.enums.Gender;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 管理端创建用户表单，只允许基础资料及初始激活标记，不接收角色、创建人和创建方式。
 * 创建方式固定为管理员追加，创建人来自认证主体，账户默认正常；密码只用于生成哈希。
 *
 * @param username 必填且唯一的用户名
 * @param nickname 必填昵称，允许重名
 * @param phone 必填且唯一的手机号，采用可选国家码和数字的规范格式
 * @param password 初始原密码，8 至 72 个字符，业务层同时检查 UTF-8 字节上限
 * @param gender 性别，未提供时使用未知
 * @param actived 初始激活状态，未提供时使用激活
 * @param signature 个性签名，可为空
 * @param avatarUrl 头像 URL，可为空
 * @author chomy
 */
public record AdminCreateUserRequest(
        @NotBlank(message = "用户名不能为空") @Size(max = 64, message = "用户名不能超过64个字符") String username,
        @NotBlank(message = "昵称不能为空") @Size(max = 64, message = "昵称不能超过64个字符") String nickname,
        @NotBlank(message = "手机号不能为空") @Pattern(regexp = "^\\+?[0-9]{6,20}$", message = "手机号格式不正确") String phone,
        @NotBlank(message = "密码不能为空") @Size(min = 8, max = 72, message = "密码长度必须为8至72个字符") String password,
        Gender gender, ActivationStatus actived,
        @Size(max = 255, message = "个性签名不能超过255个字符") String signature,
        @Size(max = 2048, message = "头像地址过长") @Pattern(regexp = "^(https?://[^\\s]+)?$", message = "头像地址必须为HTTP或HTTPS地址") String avatarUrl) {
    /** 防止调试输出泄露初始密码或用户隐私信息。 */
    @Override
    public String toString() { return "AdminCreateUserRequest[credentials=已隐藏]"; }
}
