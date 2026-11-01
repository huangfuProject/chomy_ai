package cn.chomy.ai.backend.security;

import cn.chomy.ai.backend.enums.UserStatus;
import cn.chomy.ai.backend.enums.ActivationStatus;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import cn.chomy.ai.backend.entity.Permission;
import cn.chomy.ai.backend.entity.Role;
import com.fasterxml.jackson.annotation.JsonIgnoreType;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;

/**
 * 密码认证阶段的用户主体，携带角色权限快照，不直接作为接口响应。
 * 密码指纹使用 SHA-256 生成，修改数据库密码哈希后旧会话立即失效。
 *
 * @author chomy
 */
@Getter
@JsonIgnoreType
public class LoginUser extends org.springframework.security.core.userdetails.User {
    /** 认证主体序列化版本。 */
    private static final long serialVersionUID = 1L;
    /** 原始账户资料，仅用于创建明确的资料 DTO。 */
    private final cn.chomy.ai.backend.entity.User account;
    /** 登录时查询的正常角色。 */
    private final List<Role> roles;
    /** 登录时查询的正常权限。 */
    private final List<Permission> permissions;
    /** 密码哈希的 SHA-256 指纹，不保存原始密码，且不返回前端。 */
    private final String passwordFingerprint;

    /**
     * 按用户状态构造认证主体；异常或未知状态按不可用处理。
     *
     * @param account 含密码哈希的账户
     * @param roles 正常角色
     * @param permissions 正常权限
     * @param authorities 用于 Spring Security 的角色及权限标识
     */
    public LoginUser(cn.chomy.ai.backend.entity.User account, List<Role> roles, List<Permission> permissions,
                     Collection<? extends GrantedAuthority> authorities) {
        super(account.getUsername(), account.getPasswordHash(), (account.getActived() == ActivationStatus.ACTIVE),
                true, true, (account.getStatus() == UserStatus.NORMAL), authorities);
        this.account = account;
        this.roles = List.copyOf(roles);
        this.permissions = List.copyOf(permissions);
        this.passwordFingerprint = fingerprint(account.getPasswordHash());
    }

    /**
     * 计算密码哈希指纹，供 Redis 会话与数据库当前密码版本比较。
     *
     * @param hash 数据库密码哈希
     * @return SHA-256 十六进制摘要
     */
    public static String fingerprint(String hash) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(hash.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少SHA-256算法", exception);
        }
    }

    /** 认证完成后同时清除父类和账户对象中的密码哈希，只保留用于会话校验的指纹。 */
    @Override
    public void eraseCredentials() {
        super.eraseCredentials();
        account.setPasswordHash(null);
    }
}
