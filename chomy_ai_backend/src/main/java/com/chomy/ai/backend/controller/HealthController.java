package com.chomy.ai.backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP 服务存活检查，返回值由全局响应拦截器包装，不执行数据库或 Redis 访问。
 *
 * @author chomy
 */
@RestController
@RequestMapping("/health")
public class HealthController {

    /**
     * 检查控制器能否正常响应请求，保持已有 /health/health 路由。
     *
     * @return 表示 HTTP 服务可用的字符串
     */
    @GetMapping("/health")
    public String health() {
        return "ok";
    }
}
