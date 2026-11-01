package cn.chomy.ai.backend.dto.auth;

import java.util.List;
import cn.chomy.ai.backend.entity.Permission;
import cn.chomy.ai.backend.entity.Role;

/**
 * 登录结果，角色与权限在登录时查询，写入 Redis 后与 JWT 一起返回前端。
 *
 * @param accessToken JWT 访问令牌，仅通过 Authorization 请求头传递
 * @param tokenType 固定为 Bearer
 * @param expiresIn 剩余有效期，单位为秒
 * @param user 用户个人资料，不含密码
 * @param roles 正常角色列表，不含禁用角色
 * @param permissions 正常角色的正常权限并集
 * @author chomy
 */
public record LoginResponse(String accessToken, String tokenType, long expiresIn, UserProfile user,
                            List<Role> roles, List<Permission> permissions) {
    /** 避免日志打印包含 JWT 的整个登录响应。 */
    @Override
    public String toString() { return "LoginResponse[token=已隐藏]"; }
}
