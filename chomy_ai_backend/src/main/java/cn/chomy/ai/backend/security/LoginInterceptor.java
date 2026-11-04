package cn.chomy.ai.backend.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import org.springframework.web.context.request.async.WebAsyncUtils;
import java.util.concurrent.Callable;

/**
 * 控制器执行前的登录状态与用户上下文拦截器，只接受 Spring Security 已认证的 JWT 及用户快照。
 * JWT 验签、Redis 会话、账户状态和密码版本由安全过滤器提前校验，此处不重复访问缓存或数据库。
 * 此拦截器是 MVC 入口的补充检查，不能替代安全过滤器链及后续 RBAC 权限判断。
 *
 * @author chomy
 */
@Component
public class LoginInterceptor implements AsyncHandlerInterceptor {

    /** 与 Spring Security 共用的公开请求规则，同时限定路径和 HTTP 方法。 */
    private final RequestMatcher publicEndpoints;

    /** 统一输出未登录的 HTTP 401、中文提示及 durationMs，避免返回 HTML 或重定向。 */
    private final SecurityResponseHandler responses;

    /**
     * 注入共用的白名单和认证响应处理器，不额外维护第二份放行路径。
     *
     * @param publicEndpoints 登录及健康检查的公开请求匹配器
     * @param responses 现有安全链统一响应处理器
     */
    public LoginInterceptor(@Qualifier("publicEndpointMatcher") RequestMatcher publicEndpoints,
                            SecurityResponseHandler responses) {
        this.publicEndpoints = publicEndpoints;
        this.responses = responses;
    }

    /**
     * 公开请求及真实跨域预检可直接通过；其他请求须存在已经认证的 JWT 上下文。
     * 仅携带 Authorization 请求头不足以证明登录，禁止自行解析未验证的 Token 或相信用户 ID。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param handler Spring MVC 为当前请求选择的处理器
     * @return 已登录或公开请求返回 true；未登录输出统一响应并返回 false
     * @throws IOException 写出认证失败响应时发生输入输出异常
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        // 每次进入 MVC 都先删除旧映射；公开请求也必须保持无身份，不能沿用线程历史值。
        UserContext.clear();
        // 先识别公开请求和浏览器预检，复用安全链白名单，避免登录入口被自身拦截。
        if (CorsUtils.isPreFlightRequest(request) || publicEndpoints.matches(request)) {
            return true;
        }
        // 只读取安全链已验证的上下文，不将请求头中的 Token 或用户 ID 直接视为登录依据。
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        // 同时检查认证主体类型和认证标志，排除匿名主体、未认证 JWT 及其他认证方式。
        if (authentication instanceof JwtAuthenticationToken jwt && jwt.isAuthenticated()
                && jwt.getDetails() instanceof CurrentUser user
                && user.userId().toString().equals(jwt.getToken().getSubject())
                && user.sessionId().equals(jwt.getToken().getId())) {
            // 资料来自安全链核对过的 Redis 会话，不在此解析原始 Token 或重复查询用户。
            UserContext.set(user);
            // Spring MVC Callable 会切换线程，显式绑定工作线程并在任务退出时删除映射。
            WebAsyncUtils.getAsyncManager(request).registerCallableInterceptor(UserContext.class,
                    new UserCallableInterceptor(user));
            return true;
        }
        // 写出包含请求耗时的统一 401，并阻止控制器继续执行。
        responses.commence(request, response, new InsufficientAuthenticationException("请先登录"));
        return false;
    }

    /**
     * 同步请求及异步结果分派完成后释放容器线程；业务异常也会进入此回调。
     * @param request 当前请求
     * @param response 当前响应
     * @param handler 当前处理器
     * @param exception 请求处理异常，可空
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                Exception exception) {
        UserContext.clear();
    }

    /**
     * 异步开始时原容器线程即将归还线程池，不能等待异步请求整体完成后再清理。
     * @param request 当前请求
     * @param response 当前响应
     * @param handler 当前处理器
     */
    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request, HttpServletResponse response, Object handler) {
        UserContext.clear();
    }

    /**
     * 仅在 MVC 管理的 Callable 执行区间传播用户身份，不向任意线程池或 DeferredResult 生产线程隐式传播。
     * postProcess 由 MVC 在任务异常时同样执行，超时也不得由回调线程提前删除仍在执行的工作线程上下文。
     * @param user 当前请求的已认证用户
     * @author chomy
     */
    private record UserCallableInterceptor(CurrentUser user) implements CallableProcessingInterceptor {
        /** 任务开始前绑定当前工作线程，替换线程池可能残留的历史值。 */
        @Override
        public <T> void preProcess(NativeWebRequest request, Callable<T> task) {
            UserContext.clear();
            UserContext.set(user);
        }

        /** 任务结束或抛出异常后立即释放当前工作线程，不能等结果分派才清理。 */
        @Override
        public <T> void postProcess(NativeWebRequest request, Callable<T> task, Object concurrentResult) {
            UserContext.clear();
        }
    }
}
