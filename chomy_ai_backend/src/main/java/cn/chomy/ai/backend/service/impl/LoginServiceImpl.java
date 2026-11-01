package cn.chomy.ai.backend.service.impl;

import cn.chomy.ai.backend.enums.UserStatus;
import cn.chomy.ai.backend.enums.ActivationStatus;
import java.time.OffsetDateTime;
import cn.chomy.ai.backend.dto.auth.LoginRequest;
import cn.chomy.ai.backend.dto.auth.LoginResponse;
import cn.chomy.ai.backend.entity.User;
import cn.chomy.ai.backend.mapper.UserMapper;
import cn.chomy.ai.backend.security.JwtSessionService;
import cn.chomy.ai.backend.security.LoginUser;
import cn.chomy.ai.backend.service.LoginService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/**
 * 登录业务实现，集中处理密码长度限制、Spring Security 认证、审计时间和会话签发。
 * 控制器仅委托此服务，不直接查询数据库、创建认证请求或操作 Redis。
 *
 * @author chomy
 */
@Service
@RequiredArgsConstructor
public class LoginServiceImpl implements LoginService {
    /** 框架认证入口，统一执行密码和账户状态校验。 */
    private final AuthenticationManager authenticationManager;
    /** JWT 签发、Redis 授权快照缓存及退出管理。 */
    private final JwtSessionService sessions;
    /** 账户审计时间更新，写入时再次限定账户可用状态。 */
    private final UserMapper userMapper;

    /**
     * 认证成功且全部会话缓存写入成功后才返回登录结果，基础设施故障不得降级放行。
     *
     * @param request 用户名及原始密码
     * @return 可用登录会话及角色权限信息
     */
    @Override
    public LoginResponse login(LoginRequest request) {
        // BCrypt 限制密码编码后的字节数，提前拒绝超限输入并统一认证失败提示。
        if (request.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new BadCredentialsException("用户名或密码错误");
        }
        // 委托 Spring Security 验证账户状态与密码，不在控制器自行比较密文。
        var authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
        LoginUser principal = (LoginUser) authentication.getPrincipal();
        OffsetDateTime now = OffsetDateTime.now();
        // 带上账户可用状态更新登录时间，防止认证期间账户被冻结后仍成功记录登录。
        int updated = userMapper.update(Wrappers.lambdaUpdate(User.class).eq(User::getId, principal.getAccount().getId())
                .eq(User::getStatus, UserStatus.NORMAL).eq(User::getActived, ActivationStatus.ACTIVE)
                .set(User::getLastLoginAt, now).set(User::getUpdatedAt, now));
        if (updated != 1) { throw new BadCredentialsException("账户已不可用"); }
        // 同步返回资料的时间字段；所有缓存写入成功后才返回 JWT 及角色权限。
        principal.getAccount().setLastLoginAt(now);
        principal.getAccount().setUpdatedAt(now);
        return sessions.issue(principal);
    }

    /**
     * 清理当前认证会话的登录状态、角色及权限缓存。
     *
     * @param jwt 当前认证 JWT
     * @return 退出成功提示
     */
    @Override
    public String logout(Jwt jwt) {
        // 只撤销认证主体对应的当前会话，不能接受外部指定的其他用户或其他设备 Token。
        sessions.revoke(jwt);
        return "退出成功";
    }
}
