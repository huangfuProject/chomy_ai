package cn.chomy.ai.backend.security;

import cn.chomy.ai.backend.enums.UserStatus;
import cn.chomy.ai.backend.enums.ActivationStatus;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import cn.chomy.ai.backend.dto.auth.LoginResponse;
import cn.chomy.ai.backend.dto.auth.UserProfile;
import cn.chomy.ai.backend.entity.Permission;
import cn.chomy.ai.backend.entity.Role;
import cn.chomy.ai.backend.entity.User;
import cn.chomy.ai.backend.mapper.UserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

/**
 * JWT 签名及 Redis 会话管理：JWT 负责防篡改，Redis 控制会话存活并缓存角色权限。
 * 每次请求额外检查数据库用户状态及密码指纹，使冻结、未激活和密码修改立即阻断旧会话。
 *
 * @author chomy
 */
@Service
public class JwtSessionService {
    /** 与签名验证器保持一致的签发者标识。 */
    public static final String ISSUER = "chomy-ai-backend";
    /** 会话按 jti 独立存储，Redis 中不存放原始 JWT 或密码哈希。 */
    private static final String PREFIX = "chomy:auth:session:";
    /** JWT 签名器，不自行实现加密算法。 */
    private final JwtEncoder encoder;
    /** 登录状态、角色及权限分别写入不同 Redis Key。 */
    private final StringRedisTemplate redis;
    /** 统一 JSON 序列化器，支持实体中的带时区时间。 */
    private final ObjectMapper objectMapper;
    /** 读取账户最新状态及密码版本。 */
    private final UserMapper userMapper;
    /** Token 和 Redis 会话共用有效期，单位为秒，默认两小时。 */
    private final long ttlSeconds;

    /**
     * 注入签名器、缓存和账户数据访问组件，并拒绝非法有效期。
     *
     * @param encoder JWT 编码器
     * @param redis Redis 字符串模板
     * @param objectMapper 包含时间模块的统一 JSON 序列化器
     * @param userMapper 用户 Mapper
     * @param ttlSeconds 有效期秒数，必须大于零
     */
    public JwtSessionService(JwtEncoder encoder, StringRedisTemplate redis, ObjectMapper objectMapper, UserMapper userMapper, @Value("${security.jwt.ttl-seconds:7200}") long ttlSeconds) {
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("JWT有效期必须大于零");
        }
        this.encoder = encoder;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.userMapper = userMapper;
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * 先构建 Token，再成功写入角色权限缓存，最后返回前端；缓存失败不发放可用登录结果。
     *
     * @param principal 已通过密码认证的主体
     * @return JWT、有效期、用户资料、角色和权限
     */
    public LoginResponse issue(LoginUser principal) {
        // 每次登录生成独立 jti，使不同设备会话可以单独退出且不会互相覆盖。
        Instant now = Instant.now();
        String jti = UUID.randomUUID().toString();
        // JWT 只携带身份和时间等必要声明，完整角色权限保存在 Redis 中。
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(ISSUER).subject(principal.getAccount().getId().toString())
                .id(jti).issuedAt(now).expiresAt(now.plusSeconds(ttlSeconds)).build();
        // 固定使用 HS256 签名，密钥由配置提供，禁止自行拼装或跳过标准签名验证。
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        CachedSession session = new CachedSession(principal.getAccount().getId(), principal.getAccount().getUsername(),
                principal.getPasswordFingerprint(), UserProfile.from(principal.getAccount()));
        try {
            // 角色、权限及会话使用相同 TTL；只在三项全部写入后向调用方发放令牌。
            Duration ttl = Duration.ofSeconds(ttlSeconds);
            redis.opsForValue().set("chomy:auth:roles:" + jti, objectMapper.writeValueAsString(principal.getRoles()), ttl);
            redis.opsForValue().set("chomy:auth:permissions:" + jti, objectMapper.writeValueAsString(principal.getPermissions()), ttl);
            // 会话标记最后写入，只有三份缓存均成功写入后才返回 JWT。
            redis.opsForValue().set(PREFIX + jti, objectMapper.writeValueAsString(session), ttl);
        } catch (IOException | DataAccessException exception) {
            // 写入或序列化失败时尽力删除残留缓存，并拒绝登录，不能降级为只校验 JWT。
            try {
                deleteSession(jti);
            } catch (DataAccessException ignored) {
                /* Redis 故障时残留内容依靠 TTL 清理，不签发登录结果。 */
            }
            throw new AuthenticationServiceException("登录缓存暂不可用", exception);
        }
        return new LoginResponse(token, "Bearer", ttlSeconds, UserProfile.from(principal.getAccount()),
                principal.getRoles(), principal.getPermissions());
    }

    /**
     * 签名验证成功后读取会话缓存，核对主体、用户状态及当前密码版本。
     * 角色和权限使用登录缓存，不再每次查询角色关系表；变更授权后应调用 revokeUserSessions。
     *
     * @param jwt 已由 Spring Security 完成签名、签发者及时间校验的 JWT
     * @return 带数据库角色及权限标识的认证结果
     */
    public JwtAuthenticationToken authenticate(Jwt jwt) {
        // 先确认 JWT 包含可关联 Redis 会话的必要标识，再读取服务端登录状态。
        if (jwt.getId() == null || jwt.getSubject() == null) { throw invalidSession(); }
        try {
            // 签名有效不等于仍处于登录状态；退出、过期或缓存缺失都必须拒绝。
            String json = redis.opsForValue().get(PREFIX + jwt.getId());
            if (json == null) { throw invalidSession(); }
            // 绑定会话用户与 JWT 主体，禁止用其他会话的角色权限替换当前身份。
            CachedSession session = objectMapper.readValue(json, CachedSession.class);
            if (session.userId() == null || !session.userId().toString().equals(jwt.getSubject())
                    || session.profile() == null || !session.userId().equals(session.profile().id())
                    || !java.util.Objects.equals(session.username(), session.profile().username())) {
                // 旧版或不完整会话不能提供可信的用户资料上下文，要求重新登录补齐缓存。
                throw invalidSession();
            }
            // 分别恢复登录时的角色和权限快照，任一缓存缺失均不允许带不完整权限继续访问。
            String roleJson = redis.opsForValue().get("chomy:auth:roles:" + jwt.getId());
            String permissionJson = redis.opsForValue().get("chomy:auth:permissions:" + jwt.getId());
            if (roleJson == null || permissionJson == null) { throw invalidSession(); }
            List<Role> roles = objectMapper.readValue(roleJson, objectMapper.getTypeFactory().constructCollectionType(List.class, Role.class));
            List<Permission> permissions = objectMapper.readValue(permissionJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Permission.class));
            if (roles == null || permissions == null) { throw invalidSession(); }
            // 实时读取账户状态及密码版本，使冻结、取消激活和改密无需等待 JWT 到期。
            User account = userMapper.selectOne(Wrappers.lambdaQuery(User.class)
                    .select(User::getId, User::getUsername, User::getPasswordHash, User::getStatus, User::getActived)
                    .eq(User::getId, session.userId()));
            if (account == null || account.getStatus() != UserStatus.NORMAL
                    || account.getActived() != ActivationStatus.ACTIVE || account.getPasswordHash() == null
                    || !LoginUser.fingerprint(account.getPasswordHash()).equals(session.passwordFingerprint())) {
                // 失效账户或密码版本不匹配时主动清理会话，不保留可再次使用的授权快照。
                deleteSession(jwt.getId());
                throw invalidSession();
            }
            // 读取授权撤销时间戳，拒绝该时间点及此前签发的所有会话，旧权限缓存不能继续生效。
            String version = redis.opsForValue().get("chomy:auth:revoked-after:" + session.userId());
            if (version != null && (jwt.getIssuedAt() == null
                    || jwt.getIssuedAt().getEpochSecond() <= Long.parseLong(version))) { throw invalidSession(); }
            // 只有全部状态检查通过后，才把缓存中的角色权限转换为已认证的安全主体。
            JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt,
                    RbacUserDetailsService.authorities(roles, permissions), account.getUsername());
            // 将已校验的 Redis 用户快照附于认证结果，MVC 拦截器直接使用，不重复读取缓存。
            authentication.setDetails(new CurrentUser(jwt.getId(), session.profile(), roles, permissions));
            return authentication;
        } catch (IOException | DataAccessException | NumberFormatException exception) {
            // 缓存损坏或认证基础设施异常时返回服务不可用，绝不把异常当作校验通过。
            throw new AuthenticationServiceException("认证服务暂不可用", exception);
        }
    }

    /**
     * 注销当前 JWT，会话不存在时也视为已注销，不修改其他设备的登录状态。
     *
     * @param sessionId 当前用户上下文中的已认证会话标识
     */
    public void revoke(String sessionId) { deleteSession(sessionId); }

    /** 同时清理指定会话的状态、角色和权限，避免退出后残留授权快照。 */
    private void deleteSession(String jti) {
        // 同一会话的三份缓存一起清理，避免退出后仍留下可复用的角色或权限数据。
        redis.delete(List.of(PREFIX + jti, "chomy:auth:roles:" + jti, "chomy:auth:permissions:" + jti));
    }

    /**
     * 标记指定用户当前及此前签发的 JWT 全部失效，供后续后台授权变更业务调用。
     * 同一秒内新登录也会被拒绝，客户端在下一秒重新登录即可，采取保守的权限失效策略。
     *
     * @param userId 需要重新登录的用户 ID
     */
    public void revokeUserSessions(Long userId) {
        // 撤销标记至少保留一个 Token 有效期，覆盖此前签发但尚未自然过期的会话。
        redis.opsForValue().set("chomy:auth:revoked-after:" + userId,
                Long.toString(Instant.now().getEpochSecond()), Duration.ofSeconds(ttlSeconds));
    }

    /** @return 不泄露用户是否存在或具体失效原因的认证异常 */
    private static OAuth2AuthenticationException invalidSession() {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_token"), "登录已失效，请重新登录");
    }

    /**
     * Redis 缓存载荷，不存储密码哈希，仅保存密码版本摘要与不含密码的用户资料；角色权限单独缓存。
     *
     * @param userId 用户 ID
     * @param username 登录用户名
     * @param passwordFingerprint 密码哈希摘要，用于修改密码后废止旧 JWT
     * @param profile 登录时用户资料，不包含密码哈希
     * @author chomy
     */
    public record CachedSession(Long userId, String username, String passwordFingerprint, UserProfile profile) { }
}
