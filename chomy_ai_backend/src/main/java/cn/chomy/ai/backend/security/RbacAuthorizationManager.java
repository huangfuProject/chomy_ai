package cn.chomy.ai.backend.security;

import cn.chomy.ai.backend.enums.EnableStatus;
import cn.chomy.ai.backend.enums.PermissionType;
import java.util.Comparator;
import java.util.function.Supplier;
import cn.chomy.ai.backend.entity.Permission;
import cn.chomy.ai.backend.mapper.PermissionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.server.PathContainer;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * 按 HTTP 方法及权限表接口路径匹配请求，未配置接口权限时拒绝访问。
 * 重叠模式使用最具体路径；多个同等具体模式须全部授权，避免宽路径授权绕过精确资源权限。
 *
 * @author chomy
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RbacAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {
    /** 只读取接口资源定义；用户拥有的权限来自已认证的 Redis 会话。 */
    private final PermissionMapper permissionMapper;

    /**
     * 所有接口资源均参与路径匹配，包括禁用资源，防止禁用后退回更宽泛的权限规则。
     *
     * @param authentication 当前认证结果供应器
     * @param context HTTP 请求上下文
     * @return 是否可以访问当前业务接口
     */
    @Override
    public AuthorizationDecision check(Supplier<Authentication> authentication, RequestAuthorizationContext context) {
        // 未登录、匿名或未完成认证的请求直接拒绝，避免查询权限表后误判为已登录。
        Authentication current = authentication.get();
        if (current == null || !current.isAuthenticated()
                || current instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
            return new AuthorizationDecision(false);
        }
        var request = context.getRequest();
        // 去掉部署上下文前缀，使数据库中的接口路径与应用内部路由保持一致。
        String path = request.getRequestURI().substring(request.getContextPath().length());
        try {
            // 按方法和路径匹配所有接口资源，保留禁用定义，避免禁用后退回宽泛授权。
            var matches = permissionMapper.selectList(Wrappers.lambdaQuery(Permission.class)
                            .eq(Permission::getPermissionType, PermissionType.API)).stream()
                    .filter(permission -> request.getMethod().equals(permission.getHttpMethod()))
                    .map(permission -> new MatchedPermission(permission,
                            PathPatternParser.defaultInstance.parse(permission.getApiPath())))
                    .filter(match -> match.pattern().matches(PathContainer.parsePath(path)))
                    .sorted(Comparator.comparing(MatchedPermission::pattern, PathPattern.SPECIFICITY_COMPARATOR)).toList();
            // 未配置的业务接口默认拒绝，不因用户已登录而自动开放。
            if (matches.isEmpty()) { return new AuthorizationDecision(false); }
            // 已按路径具体程度排序；同等具体规则全部满足，防止宽路径绕过精确权限。
            PathPattern best = matches.getFirst().pattern();
            boolean granted = matches.stream().filter(match -> PathPattern.SPECIFICITY_COMPARATOR.compare(best, match.pattern()) == 0)
                    .allMatch(match -> (match.permission().getStatus() == EnableStatus.ENABLED)
                            && current.getAuthorities().stream().anyMatch(authority ->
                            authority.getAuthority().equals(match.permission().getPermissionCode())));
            return new AuthorizationDecision(granted);
        } catch (DataAccessException exception) {
            // 权限数据源故障时拒绝请求，交给统一认证异常处理输出 503。
            throw new AuthenticationServiceException("权限服务暂不可用", exception);
        } catch (IllegalArgumentException | NullPointerException exception) {
            // 错误路径配置不能作为放行理由，记录服务端异常后返回拒绝结果。
            log.error("接口权限路径配置不合法，已拒绝访问", exception);
            return new AuthorizationDecision(false);
        }
    }

    /**
     * 保存数据库资源及解析后的路径模式，避免排序和匹配时重复解析。
     *
     * @param permission 接口权限定义
     * @param pattern 解析后的 Spring 路径模式
     * @author chomy
     */
    private record MatchedPermission(Permission permission, PathPattern pattern) { }
}
