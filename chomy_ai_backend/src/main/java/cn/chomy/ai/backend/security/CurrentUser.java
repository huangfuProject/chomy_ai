package cn.chomy.ai.backend.security;

import cn.chomy.ai.backend.dto.auth.UserProfile;
import cn.chomy.ai.backend.entity.Permission;
import cn.chomy.ai.backend.entity.Role;
import java.util.List;

/**
 * 当前请求的登录用户快照，由已验签 JWT 对应的 Redis 会话恢复，不接受客户端指定。
 * 不携带 JWT 原文、密码哈希或密码指纹；资料与授权均为登录时快照。
 *
 * @param sessionId 当前设备会话标识，仅用于服务端注销
 * @param profile 登录时的公开用户资料，不含密码
 * @param roles 登录时的有效角色
 * @param permissions 登录时的有效权限
 * @author chomy
 */
public record CurrentUser(String sessionId, UserProfile profile, List<Role> roles, List<Permission> permissions) {
    /**
     * 固定集合结构，防止业务代码增删当前请求的角色及权限列表。
     * @throws IllegalArgumentException 会话或用户身份不完整时拒绝创建
     */
    public CurrentUser {
        // 用户身份必须由完整的服务端会话提供，不能以空用户继续执行业务。
        if (sessionId == null || sessionId.isBlank() || profile == null || profile.id() == null) {
            throw new IllegalArgumentException("登录用户信息不完整");
        }
        roles = List.copyOf(roles);
        permissions = List.copyOf(permissions);
    }

    /** @return 当前用户 ID，业务层无需接触或解析 JWT */
    public Long userId() { return profile.id(); }
}
