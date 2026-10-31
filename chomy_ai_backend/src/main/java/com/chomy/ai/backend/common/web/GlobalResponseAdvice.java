package com.chomy.ai.backend.common.web;

import com.chomy.ai.backend.common.api.ApiResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 在业务控制器结果序列化前统一包装响应并填入请求耗时。
 * 限定业务包范围，保留 Actuator 等框架端点的原始协议。
 *
 * @author chomy
 */
@RestControllerAdvice(basePackages = "com.chomy.ai.backend")
public class GlobalResponseAdvice implements ResponseBodyAdvice<Object> {

    /** 复用 Spring Boot 的 JSON 配置，确保字符串结果与普通对象采用一致的序列化规则。 */
    private final ObjectMapper objectMapper;

    /**
     * 注入框架统一管理的 JSON 序列化器。
     *
     * @param objectMapper 已应用项目序列化配置的 Jackson 实例
     */
    public GlobalResponseAdvice(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 仅处理 JSON 和字符串响应，文件资源、字节数组及其他专用转换器保持原样。
     *
     * @param returnType 当前响应方法的返回类型信息
     * @param converterType Spring MVC 已选择的响应转换器类型
     * @return 是否需要进入统一响应处理
     */
    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return MappingJackson2HttpMessageConverter.class.isAssignableFrom(converterType)
                || StringHttpMessageConverter.class.isAssignableFrom(converterType);
    }

    /**
     * 包装业务数据或补充现有结果的耗时；保留 HTTP 状态、响应头以及不允许带响应体的状态。
     * 字符串转换器已在此方法之前选定，因此须提前序列化，避免把 ApiResult 强转为 String。
     *
     * @param body 控制器或异常处理器返回的响应体
     * @param returnType 当前响应方法的类型信息
     * @param selectedContentType 协商后的媒体类型
     * @param selectedConverterType 已选定的消息转换器
     * @param request 当前服务端请求，用于获取计时属性
     * @param response 当前服务端响应，用于保留状态并设置 JSON 类型
     * @return 统一结果，或无需包装的原始响应体
     */
    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (MediaType.TEXT_EVENT_STREAM.isCompatibleWith(selectedContentType)) {
            return body;
        }
        int status = 200;
        if (response instanceof ServletServerHttpResponse servletResponse) {
            status = servletResponse.getServletResponse().getStatus();
            if (status < 200 || status == 204 || status == 205 || status == 304) {
                return body;
            }
        }
        long durationMs = request instanceof ServletServerHttpRequest servletRequest
                ? RequestTimingFilter.elapsedMillis(servletRequest.getServletRequest()) : 0;
        // 控制器主动返回 ResponseEntity 错误状态时同步结果码，避免出现 HTTP 失败但业务码成功的矛盾。
        ApiResult<?> result = body instanceof ApiResult<?> existing ? existing
                : status >= 400 ? new ApiResult<>(status, "请求处理失败", body, 0) : ApiResult.success(body);
        result = result.withDurationMs(durationMs);
        if (StringHttpMessageConverter.class.isAssignableFrom(selectedConverterType)) {
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            try {
                return objectMapper.writeValueAsString(result);
            } catch (JsonProcessingException exception) {
                throw new HttpMessageNotWritableException("统一响应序列化失败", exception);
            }
        }
        return result;
    }
}
