package cn.chomy.ai.backend.security;

import java.io.IOException;
import cn.chomy.ai.backend.common.api.ApiResult;
import cn.chomy.ai.backend.common.web.RequestTimingFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 将安全过滤器链中的认证和授权失败输出为项目统一 JSON，包含请求耗时。
 * 过滤器异常不会进入控制器通知，不能仅依赖全局 MVC 异常处理器。
 *
 * @author chomy
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SecurityResponseHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
    /** 与控制器共用同一个 JSON 配置。 */
    private final ObjectMapper objectMapper;

    /**
     * 未登录、Token 无效或过期返回 401；认证基础设施故障返回 503，始终拒绝请求。
     *
     * @param request 当前请求
     * @param response 当前响应
     * @param exception 认证失败原因，不直接输出其原始消息
     * @throws IOException 写出响应失败
     */
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) throws IOException {
        // 区分认证失败与基础设施故障，避免把 Redis 或数据库异常误报为密码错误。
        if (exception instanceof AuthenticationServiceException) {
            log.error("认证或权限基础设施暂不可用", exception);
            write(request, response, 503, "认证服务暂不可用，请稍后重试");
        } else {
            // 返回标准 Bearer 认证提示，不通过重定向或 HTML 页面处理 API 登录失败。
            response.setHeader("WWW-Authenticate", "Bearer");
            write(request, response, 401, "登录已失效，请重新登录");
        }
    }

    /**
     * 已登录但缺少资源权限时返回 403，不把权限配置详情暴露给调用方。
     *
     * @param request 当前请求
     * @param response 当前响应
     * @param exception 授权失败原因
     * @throws IOException 写出响应失败
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception) throws IOException {
        write(request, response, 403, "无权访问该资源");
    }

    /** 输出统一响应，已经提交的响应不再二次写出。 */
    private void write(HttpServletRequest request, HttpServletResponse response, int status, String message) throws IOException {
        // 响应已经提交时不再写入，防止异常处理产生第二份 JSON 或破坏原响应。
        if (response.isCommitted()) { return; }
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // 过滤器不经过 MVC 响应通知，需要显式补充统一结果结构和服务端处理耗时。
        objectMapper.writeValue(response.getOutputStream(), ApiResult.failure(status, message)
                .withDurationMs(RequestTimingFilter.elapsedMillis(request)));
    }
}
