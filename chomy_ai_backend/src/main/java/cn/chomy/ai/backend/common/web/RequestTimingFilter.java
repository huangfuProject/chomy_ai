package cn.chomy.ai.backend.common.web;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 在请求入口记录单调时钟时间，用请求属性隔离并发请求的计时数据。
 * 不使用 ThreadLocal，便于异步请求在不同线程完成时仍可读取同一个开始时间。
 *
 * @author chomy
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestTimingFilter extends OncePerRequestFilter {

    /** 使用类名限定属性名称，避免与业务代码设置的请求属性冲突。 */
    private static final String START_NANOS = RequestTimingFilter.class.getName() + ".startNanos";

    /**
     * 在其他过滤器和控制器执行前记录开始时间；已有时间时保留原值，避免重复计时。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param filterChain 后续过滤器链
     * @throws ServletException 后续请求处理发生 Servlet 异常
     * @throws IOException 后续请求处理发生输入输出异常
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (request.getAttribute(START_NANOS) == null) {
            request.setAttribute(START_NANOS, System.nanoTime());
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 计算截至调用时的服务端处理耗时，不包含响应序列化、网络传输或客户端接收时间。
     * 单调时钟不受系统校时影响；没有经过计时过滤器的请求返回 0。
     *
     * @param request 保存了开始时间的 HTTP 请求
     * @return 已处理的毫秒数，低于一毫秒时为 0
     */
    public static long elapsedMillis(HttpServletRequest request) {
        Object start = request.getAttribute(START_NANOS);
        return start instanceof Long nanos ? TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nanos) : 0;
    }
}
