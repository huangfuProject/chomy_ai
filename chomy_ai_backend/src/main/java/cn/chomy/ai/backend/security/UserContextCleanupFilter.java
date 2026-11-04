package cn.chomy.ai.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 用户线程上下文的最外层清理保障，覆盖未进入 MVC 的认证失败、权限拒绝及过滤器异常。
 * 用户绑定仍由登录拦截器完成；此过滤器只负责入口清空及 finally 清理，不执行认证。
 *
 * @author chomy
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class UserContextCleanupFilter extends OncePerRequestFilter {
    /**
     * 包裹完整后续链，异常、提前返回及异步启动均释放当前容器线程的身份。
     * @param request 当前请求
     * @param response 当前响应
     * @param chain 后续过滤器链
     * @throws ServletException 下游 Servlet 异常，清理后继续抛出
     * @throws IOException 下游输入输出异常，清理后继续抛出
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // 即使请求在安全过滤器中被拒绝，也不能遗留复用线程上的历史身份。
        UserContext.clear();
        try {
            chain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }

    /** 异步结果再次分派时，同样需要为当前容器线程建立清理边界。 */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() { return false; }

    /** 错误分派不能继承前一次请求分派遗留的用户身份。 */
    @Override
    protected boolean shouldNotFilterErrorDispatch() { return false; }
}
