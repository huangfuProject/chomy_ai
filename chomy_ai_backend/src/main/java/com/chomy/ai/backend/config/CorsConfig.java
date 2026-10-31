package com.chomy.ai.backend.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * 全局跨域放行配置，过滤器在控制器之前处理浏览器 OPTIONS 预检请求。
 * 允许任意来源、请求方法和请求头；不启用凭据，浏览器不会携带跨域 Cookie。
 *
 * @author chomy
 */
@Configuration
public class CorsConfig {

    /**
     * 注册覆盖所有 URL 的跨域过滤器，使成功及异常响应都具有跨域响应头。
     * 计时过滤器先执行，跨域过滤器紧随其后；预检请求由跨域过滤器直接返回。
     *
     * @return 配置了优先级及 URL 匹配规则的过滤器注册信息
     */
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.addAllowedOrigin("*");
        configuration.addAllowedMethod("*");
        configuration.addAllowedHeader("*");
        configuration.setAllowCredentials(false);
        // 预检结果允许浏览器缓存一小时；单位为秒，减少重复预检请求。
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
