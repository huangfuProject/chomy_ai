package cn.chomy.ai.backend.dto.admin;

import java.time.OffsetDateTime;
import cn.chomy.ai.backend.entity.User;
import cn.chomy.ai.backend.enums.ActivationStatus;
import cn.chomy.ai.backend.enums.Gender;
import cn.chomy.ai.backend.enums.UserCreateType;
import cn.chomy.ai.backend.enums.UserStatus;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

/**
 * 管理端用户详情及分页行 DTO，字段白名单中永远不包含密码哈希或认证会话内容。
 *
 * @param id 用户 ID，按字符串输出以保留精度
 * @param username 用户名
 * @param nickname 昵称
 * @param phone 手机号，可为空
 * @param email 邮箱，可为空
 * @param avatarUrl 头像 URL
 * @param gender 性别数字编码
 * @param status 冻结状态数字编码
 * @param actived 激活状态数字编码
 * @param frozen 是否冻结，方便管理端直接展示
 * @param active 是否激活，方便管理端直接展示
 * @param signature 个性签名
 * @param createType 创建方式
 * @param createdBy 创建人 ID
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 * @param lastLoginAt 最近成功登录时间
 * @author chomy
 */
public record AdminUserView(@JsonSerialize(using = ToStringSerializer.class) Long id, String username, String nickname,
                            String phone, String email, String avatarUrl, Gender gender, UserStatus status, ActivationStatus actived,
                            boolean frozen, boolean active, String signature, UserCreateType createType,
                            @JsonSerialize(using = ToStringSerializer.class) Long createdBy,
                            OffsetDateTime createdAt, OffsetDateTime updatedAt, OffsetDateTime lastLoginAt) {
    /**
     * 只映射明确公开的管理字段，防止查询实体中包含密码时意外返回。
     * @param user 目标用户实体
     * @return 不含密码的管理视图
     */
    public static AdminUserView from(User user) {
        // 布尔展示字段由枚举推导，不在数据库中维护重复状态。
        return new AdminUserView(user.getId(), user.getUsername(), user.getNickname(), user.getPhone(), user.getEmail(), user.getAvatarUrl(),
                user.getGender(), user.getStatus(), user.getActived(), user.getStatus() == UserStatus.FROZEN,
                user.getActived() == ActivationStatus.ACTIVE, user.getSignature(), user.getCreateType(),
                user.getCreatedBy(), user.getCreatedAt(), user.getUpdatedAt(), user.getLastLoginAt());
    }
}
