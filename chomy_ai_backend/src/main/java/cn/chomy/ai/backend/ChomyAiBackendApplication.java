package cn.chomy.ai.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 后端服务启动入口，启用 Spring Boot 自动配置并扫描当前包及其子包中的组件。
 *
 * @author chomy
 */
@SpringBootApplication
public class ChomyAiBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChomyAiBackendApplication.class, args);
    }
}
