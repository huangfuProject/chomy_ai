package cn.chomy.ai.backend.dto.auth;

import java.time.OffsetDateTime;
import cn.chomy.ai.backend.enums.Gender;
import cn.chomy.ai.backend.entity.User;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/**
 * 对外公开的个人资料，不包含密码哈希、创建人及内部认证信息。
 *
 * @param id 用户 ID，使用字符串输出以保留雪花 ID 精度
 * @param username 用户名
 * @param nickname 昵称
 * @param phone 手机号，可为空
 * @param email 邮箱，可为空
 * @param gender 性别，0 未知、1 男、2 女
 * @param signature 个性签名
 * @param avatarUrl 头像地址
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 * @param lastLoginAt 最近成功登录时间
 * @author chomy
 */
public record UserProfile(@JsonSerialize(using = ToStringSerializer.class) Long id, String username,
                          String nickname, String phone, String email, Gender gender, String signature, String avatarUrl,
                          OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime lastLoginAt) {
    /**
     * 只拷贝明确允许公开的资料字段，防止实体增加安全字段后被自动返回。
     *
     * @param user 当前用户实体
     * @return 不含密码的资料响应
     */
    public static UserProfile from(User user) {
        return new UserProfile(user.getId(), user.getUsername(), user.getNickname(), user.getPhone(), user.getEmail(),
                user.getGender(), user.getSignature(), user.getAvatarUrl(), user.getCreatedAt(),
                user.getUpdatedAt(), user.getLastLoginAt());
    }
}
