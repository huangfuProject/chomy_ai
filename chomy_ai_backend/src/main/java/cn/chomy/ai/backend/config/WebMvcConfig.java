package cn.chomy.ai.backend.config;

import cn.chomy.ai.backend.security.LoginInterceptor;
import cn.chomy.ai.backend.common.web.CodeEnumConverterFactory;
import org.springframework.format.FormatterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册全局 MVC 登录拦截器，保持 Spring Boot 的默认消息转换、资源处理及参数解析配置。
 * 不使用 EnableWebMvc 接管自动配置，避免影响现有统一响应、参数校验和安全参数解析。
 *
 * @author chomy
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    /** 已认证状态检查组件，公开请求在其内部使用统一规则识别。 */
    private final LoginInterceptor loginInterceptor;

    /**
     * 查询参数中的状态、性别和激活标记使用与 JSON 相同的数字编码。
     * @param registry MVC 类型转换注册器
     */
    @Override
    public void addFormatters(FormatterRegistry registry) {
        // 只转换 CodeEnum 类型，普通框架枚举仍按原有规则处理。
        registry.addConverterFactory(new CodeEnumConverterFactory());
    }

    /**
     * 覆盖所有 MVC 路径，公开路径也交由方法敏感的共用白名单判断。
     * 不以 excludePathPatterns 排除整个登录 URL，避免无意放行其他 HTTP 方法。
     *
     * @param registry Spring MVC 拦截器注册器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor).addPathPatterns("/**").order(0);
    }
}
