package cn.chomy.ai.backend.security;

import cn.chomy.ai.backend.enums.EnableStatus;
import java.util.List;
import java.util.stream.Stream;
import cn.chomy.ai.backend.entity.*;
import cn.chomy.ai.backend.mapper.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 登录时用 MyBatis-Plus 查询账户、正常角色及正常权限，不从客户端接收授权信息。
 * 空关系及时返回空列表，避免 IN 空集合被框架省略后意外查出全部角色或权限。
 *
 * @author chomy
 */
@Service
@RequiredArgsConstructor
public class RbacUserDetailsService implements UserDetailsService {
    /** 账户查询，登录时需显式选择默认被排除的密码哈希。 */
    private final UserMapper userMapper;
    /** 用户角色关系查询。 */
    private final UserRoleMapper userRoleMapper;
    /** 角色状态查询。 */
    private final RoleMapper roleMapper;
    /** 角色权限关系查询。 */
    private final RolePermissionMapper rolePermissionMapper;
    /** 权限状态及内容查询。 */
    private final PermissionMapper permissionMapper;

    /**
     * 根据用户名加载认证数据，用户不存在时使用框架的统一认证失败处理。
     *
     * @param username 用户名，不支持通过同一字段猜测手机号，避免不同账户歧义
     * @return 带角色和权限快照的认证主体
     */
    @Override
    public UserDetails loadUserByUsername(String username) {
        // 登录认证需要显式选取密码哈希，普通用户查询仍保持排除该敏感字段。
        User account = userMapper.selectOne(Wrappers.lambdaQuery(User.class)
                .select(User.class, field -> true).eq(User::getUsername, username));
        if (account == null) { throw new UsernameNotFoundException("用户名或密码错误"); }
        // 先按当前账户查询角色关系并去重，禁止使用客户端提供的角色信息。
        List<Long> roleIds = userRoleMapper.selectList(Wrappers.lambdaQuery(UserRole.class)
                .eq(UserRole::getUserId, account.getId())).stream().map(UserRole::getRoleId).distinct().toList();
        // 没有角色时不执行 IN 查询；只接受正常角色，防止空条件或禁用角色带来额外授权。
        List<Role> roles = roleIds.isEmpty() ? List.of() : roleMapper.selectList(Wrappers.lambdaQuery(Role.class)
                .in(Role::getId, roleIds).eq(Role::getStatus, EnableStatus.ENABLED).orderByAsc(Role::getSortOrder, Role::getId));
        // 只从正常角色继续查权限关联，多个角色的权限取并集。
        List<Long> activeRoleIds = roles.stream().map(Role::getId).toList();
        List<Long> permissionIds = activeRoleIds.isEmpty() ? List.of() : rolePermissionMapper.selectList(
                Wrappers.lambdaQuery(RolePermission.class).in(RolePermission::getRoleId, activeRoleIds))
                .stream().map(RolePermission::getPermissionId).distinct().toList();
        // 权限列表为空时直接保留空集合；其余情况只加载正常权限并保持展示顺序。
        List<Permission> permissions = permissionIds.isEmpty() ? List.of() : permissionMapper.selectList(
                Wrappers.lambdaQuery(Permission.class).in(Permission::getId, permissionIds)
                        .eq(Permission::getStatus, EnableStatus.ENABLED).orderByAsc(Permission::getSortOrder, Permission::getId));
        // 将账户状态和授权快照交给认证框架，密码验证成功后才允许创建登录会话。
        return new LoginUser(account, roles, permissions, authorities(roles, permissions));
    }

    /**
     * 角色增加 ROLE_ 前缀，权限直接使用权限码；去重后供请求及方法级鉴权使用。
     *
     * @param roles 正常角色快照
     * @param permissions 正常权限快照
     * @return 不含重复值的授权列表
     */
    public static List<SimpleGrantedAuthority> authorities(List<Role> roles, List<Permission> permissions) {
        // ROLE_ 仅供角色使用，拒绝权限码冒充角色标识，避免 hasRole 与 hasAuthority 语义混淆。
        if (roles.stream().anyMatch(role -> role.getRoleCode() == null || role.getRoleCode().isBlank())
                || permissions.stream().anyMatch(permission -> permission.getPermissionCode() == null
                || permission.getPermissionCode().isBlank() || permission.getPermissionCode().startsWith("ROLE_"))) {
            throw new AuthenticationServiceException("角色或权限编码配置不合法");
        }
        // 角色使用 ROLE_ 命名空间，权限使用原业务码，合并去重后交给 Spring Security。
        return Stream.concat(roles.stream().map(role -> "ROLE_" + role.getRoleCode()),
                permissions.stream().map(Permission::getPermissionCode)).distinct()
                .map(SimpleGrantedAuthority::new).toList();
    }
}
