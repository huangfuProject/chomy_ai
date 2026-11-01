package cn.chomy.ai.backend.config;

import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import cn.chomy.ai.backend.security.*;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * 使用 JWT 和 Redis 建立无 Session 的认证链，启用请求级 RBAC 和 @PreAuthorize 方法鉴权。
 * 仅通过 Authorization Bearer 头认证，不使用 Cookie、HTTP Basic 或表单登录。
 *
 * @author chomy
 */
@Configuration
@EnableMethodSecurity
@Slf4j
public class SecurityConfig {

    /**
     * 新密码使用带算法标识的 BCrypt；兼容已有不带 {bcrypt} 前缀的 BCrypt 哈希，不支持明文密码。
     *
     * @return 统一密码编码和匹配器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        // 仅注册 BCrypt 并兼容无算法前缀的旧哈希，不开放明文或弱算法密码匹配。
        BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
        DelegatingPasswordEncoder encoder = new DelegatingPasswordEncoder("bcrypt", java.util.Map.of("bcrypt", bcrypt));
        encoder.setDefaultPasswordEncoderForMatches(bcrypt);
        return encoder;
    }

    /**
     * 使用数据库账户认证；不启用默认内存用户，用户名或密码错误统一处理。
     *
     * @param users 数据库用户及 RBAC 快照加载器
     * @param encoder 密码匹配器
     * @return 使用 DaoAuthenticationProvider 的认证管理器
     */
    @Bean
    public AuthenticationManager authenticationManager(RbacUserDetailsService users, PasswordEncoder encoder) {
        // 将数据库账户和授权加载器接入框架，账户状态及密码校验统一由认证提供者处理。
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    /**
     * 构造 HS256 密钥。生产必须提供至少 32 字节的 Base64 密钥，开发缺省时生成临时随机密钥。
     * 临时密钥不落盘，不输出日志；应用重启后此前 JWT 失效，多实例须配置相同密钥。
     *
     * @param secretBase64 部署环境提供的 Base64 编码密钥
     * @param environment 当前环境，用于禁止生产使用临时密钥
     * @return JWT 签名及验证共用的密钥
     */
    @Bean
    public SecretKey jwtSecretKey(@Value("${security.jwt.secret-base64:}") String secretBase64, Environment environment) {
        byte[] key;
        // 生产必须使用固定部署密钥，开发缺省才允许生成进程内临时随机密钥。
        if (secretBase64.isBlank()) {
            if (environment.acceptsProfiles(Profiles.of("prod"))) {
                throw new IllegalStateException("生产环境必须配置JWT_SECRET_BASE64");
            }
            key = new byte[32];
            new SecureRandom().nextBytes(key);
            log.warn("开发环境使用临时JWT密钥，应用重启后需重新登录；多实例请配置统一密钥");
        } else {
            // 配置值按 Base64 解码后检查实际字节长度，保证 HS256 密钥强度。
            key = Base64.getDecoder().decode(secretBase64);
            if (key.length < 32) { throw new IllegalArgumentException("JWT签名密钥至少需要32字节"); }
        }
        return new SecretKeySpec(key, "HmacSHA256");
    }

    /**
     * 通过 Nimbus 实现 JWT 签名，实际签发请求显式指定 HS256 算法。
     *
     * @param key 对称签名密钥
     * @return JWT 编码器
     */
    @Bean
    public JwtEncoder jwtEncoder(SecretKey key) { return new NimbusJwtEncoder(new ImmutableSecret<>(key)); }

    /**
     * 验证固定算法、签发者、有效期，避免接受其他签发者或任意算法的 Token。
     *
     * @param key 对称验证密钥
     * @return 具有标准时间及签发者校验的解码器
     */
    @Bean
    public JwtDecoder jwtDecoder(SecretKey key) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        // 签名之外还必须校验签发者和时间声明，不能接收其他系统或已过期的 Token。
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(JwtSessionService.ISSUER));
        return decoder;
    }

    /**
     * 定义安全过滤器和 MVC 登录拦截器共用的公开请求规则，不创建第二份白名单。
     * 仅 POST 登录与 GET 健康检查公开，同一路径的其他方法仍受登录及权限校验。
     *
     * @return 同时匹配 HTTP 方法和路径的公开请求集合
     */
    @Bean
    public RequestMatcher publicEndpointMatcher() {
        var paths = PathPatternRequestMatcher.withDefaults();
        return new OrRequestMatcher(paths.matcher(HttpMethod.POST, "/user/login"),
                paths.matcher(HttpMethod.GET, "/health/health"),
                paths.matcher(HttpMethod.GET, "/actuator/health"),
                paths.matcher(HttpMethod.GET, "/actuator/health/**"));
    }

    /**
     * 注册无状态安全过滤器链，个人接口仅允许操作当前用户，其他业务接口必须命中数据库 RBAC。
     * 保留已有高优先级 CORS 过滤器；不使用浏览器自动附带的认证凭据，因此关闭 CSRF。
     *
     * @param http 安全构建器
     * @param sessions Redis 会话校验及权限转换组件
     * @param authorization 请求级 RBAC 管理器
     * @param responses 统一 401、403、503 输出器
     * @param publicEndpoints 与 MVC 登录拦截器共用的公开请求规则
     * @return 安全过滤器链
     * @throws Exception 安全链初始化失败
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtSessionService sessions,
                                                   RbacAuthorizationManager authorization,
                                                   SecurityResponseHandler responses,
                                                   @Qualifier("publicEndpointMatcher") RequestMatcher publicEndpoints) throws Exception {
        // 当前只通过 Authorization: Bearer 认证，不依赖浏览器自动携带的 Cookie，因此关闭 CSRF。
        return http.csrf(AbstractHttpConfigurer::disable)
                // CORS 已由高优先级 CorsFilter 统一处理，此处不重复注册，预检请求仍由前置过滤器放行。
                .cors(AbstractHttpConfigurer::disable)
                // 不提供默认 HTML 登录页，用户名密码登录交给 LoginController 委托 LoginService 处理。
                .formLogin(AbstractHttpConfigurer::disable)
                // 禁止 HTTP Basic，避免浏览器弹出账号密码窗口或引入第二种认证方式。
                .httpBasic(AbstractHttpConfigurer::disable)
                // 不使用默认 /logout，退出操作通过业务服务清理当前 JWT 对应的三份 Redis 缓存。
                .logout(AbstractHttpConfigurer::disable)
                // API 未登录时直接返回 JSON，不保存原请求，也不在登录后自动跳转。
                .requestCache(AbstractHttpConfigurer::disable)
                // 不创建或使用 HTTP Session 保存认证，每次请求都必须验证 JWT 和 Redis 会话。
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 过滤器异常不经过 MVC 异常通知：由共用处理器输出包含 durationMs 的 401、403 或 503。
                .exceptionHandling(errors -> errors.authenticationEntryPoint(responses).accessDeniedHandler(responses))
                .authorizeHttpRequests(requests -> requests
                        // 白名单与 MVC 登录拦截器共用，并同时限定 HTTP 方法和路径，避免两处规则不一致。
                        .requestMatchers(publicEndpoints).permitAll()
                        // 当前用户退出及资料接口要求已登录，但不要求管理权限；用户 ID 只从认证 JWT 取得。
                        .requestMatchers(HttpMethod.POST, "/user/logout").authenticated()
                        .requestMatchers(HttpMethod.GET, "/user/info").authenticated()
                        .requestMatchers(HttpMethod.PUT, "/user/info", "/user/password").authenticated()
                        // 兜底规则必须放在最后：其他接口按路径、方法及权限码执行 RBAC，未配置资源默认拒绝。
                        .anyRequest().access(authorization))
                // 使用框架原生 Bearer/JWT 认证过滤器，由 JwtDecoder 验证签名、算法、签发者和有效期。
                .oauth2ResourceServer(resource -> resource.authenticationEntryPoint(responses)
                        .accessDeniedHandler(responses)
                        // 默认失败处理器会重新抛出认证服务异常；显式接管后，Redis/数据库故障可统一返回 503。
                        .withObjectPostProcessor(new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                            @Override
                            public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                                // 将所有认证失败交给统一处理器，不因基础设施故障而降级为仅验证签名。
                                filter.setAuthenticationFailureHandler(responses::commence);
                                return filter;
                            }
                        })
                        // 此转换只在 JWT 基础验证通过后执行：继续核对 Redis、账户状态和密码版本并恢复角色权限。
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(sessions::authenticate)))
                // 完成过滤器链构建，后续请求先认证、再检查请求级权限，最后进入 MVC 登录拦截器和控制器。
                .build();
    }
}
