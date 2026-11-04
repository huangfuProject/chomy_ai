package cn.chomy.ai.backend.service.impl;

import cn.chomy.ai.backend.security.UserContext;
import cn.chomy.ai.backend.enums.UserStatus;
import cn.chomy.ai.backend.enums.ActivationStatus;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import cn.chomy.ai.backend.common.exception.BizException;
import cn.chomy.ai.backend.dto.auth.*;
import cn.chomy.ai.backend.entity.User;
import cn.chomy.ai.backend.mapper.UserMapper;
import cn.chomy.ai.backend.service.UserService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 用户资料及密码业务，使用 MyBatis-Plus 白名单更新，禁止直接将客户端对象映射为用户实体更新。
 * 不依赖数据库触发器，由代码明确更新 updated_at；密码修改使用旧哈希条件避免并发覆盖。
 *
 * @author chomy
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {
    /** 密码生成及比对统一使用 Spring Security 编码器。 */
    private final PasswordEncoder passwordEncoder;

    /** 根据认证用户 ID 读取资料，缺失用户不返回空对象。 */
    @Override
    public UserProfile getProfile() {
        // 当前操作者固定取自认证上下文，不允许 Controller 或请求参数指定。
        Long userId = UserContext.userId();
        // 通过普通查询获取资料，沿用实体 select=false 策略，不读取密码哈希。
        User user = getById(userId);
        if (user == null) { throw new BizException("用户不存在"); }
        // 显式构造公开字段 DTO，防止实体后续新增内部字段时自动暴露给前端。
        return UserProfile.from(user);
    }

    /**
     * 只写入昵称、性别、邮箱、签名、头像和更新时间；显式 set 允许将可空资料清空。
     * @param request 已经过参数校验的完整资料表单
     * @return 更新后的资料
     */
    @Override
    public UserProfile updateProfile(UpdateUserInfoRequest request) {
        // 当前操作者固定取自认证上下文，不允许 Controller 或请求参数指定。
        Long userId = UserContext.userId();
        // 限定当前可用账户，仅更新允许用户自行维护的字段；邮箱等可选资料通过显式 set 支持清空。
        int updated = baseMapper.update(Wrappers.lambdaUpdate(User.class).eq(User::getId, userId)
                .eq(User::getStatus, UserStatus.NORMAL).eq(User::getActived, ActivationStatus.ACTIVE)
                .set(User::getNickname, request.nickname().strip()).set(User::getGender, request.gender())
                .set(User::getEmail, emptyToNull(request.email()))
                .set(User::getSignature, emptyToNull(request.signature())).set(User::getAvatarUrl, emptyToNull(request.avatarUrl()))
                .set(User::getUpdatedAt, OffsetDateTime.now()));
        if (updated != 1) { throw new BizException("用户不存在或当前不可用"); }
        // 从数据库重新读取结果，确保响应与持久化后的资料一致。
        return getProfile();
    }

    /**
     * 验证原密码，拒绝与旧密码相同或超过 BCrypt 字节上限的新密码。
     * 更新条件包含旧哈希和可用状态，并发修改失败时不覆盖他人已更新的密码。
     * @param request 原密码及新密码
     * @return 密码修改完成及重新登录提示
     */
    @Override
    public String changePassword(ChangePasswordRequest request) {
        // 当前操作者固定取自认证上下文，不允许 Controller 或请求参数指定。
        Long userId = UserContext.userId();
        // 修改密码必须显式读取旧哈希，用于原密码验证和后续并发更新条件。
        User user = baseMapper.selectOne(Wrappers.lambdaQuery(User.class).select(User.class, field -> true)
                .eq(User::getId, userId));
        // 再次检查账户状态，防止认证完成后账户变化而继续写入。
        if (user == null || user.getStatus() != UserStatus.NORMAL || user.getActived() != ActivationStatus.ACTIVE) {
            throw new BizException("用户不存在或当前不可用");
        }
        // 原密码先检查 BCrypt 字节限制再比对哈希，统一返回业务提示，不暴露密文。
        if (request.oldPassword().getBytes(StandardCharsets.UTF_8).length > 72
                || !passwordEncoder.matches(request.oldPassword(), user.getPasswordHash())) { throw new BizException("原密码不正确"); }
        // 新密码还需满足编码字节上限，且不能与现有密码相同。
        if (request.newPassword().getBytes(StandardCharsets.UTF_8).length > 72) { throw new BizException("新密码UTF-8编码不能超过72字节"); }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) { throw new BizException("新密码不能与原密码相同"); }
        // 使用旧哈希作为比较并更新条件，避免并发修改覆盖新密码；落库的始终是新哈希。
        int updated = baseMapper.update(Wrappers.lambdaUpdate(User.class).eq(User::getId, userId)
                .eq(User::getPasswordHash, user.getPasswordHash()).eq(User::getStatus, UserStatus.NORMAL).eq(User::getActived, ActivationStatus.ACTIVE)
                .set(User::getPasswordHash, passwordEncoder.encode(request.newPassword()))
                .set(User::getUpdatedAt, OffsetDateTime.now()));
        // 更新失败意味着账户或密码已变化，要求重新认证，不能忽略失败继续返回成功。
        if (updated != 1) { throw new BizException("账户信息已变化，请重新登录后重试"); }
        // 业务服务确认修改成功后给出后续动作提示，控制器无需拼装业务结果。
        return "密码修改成功，请重新登录";
    }

    /** 将可空文本的空字符串规范为 NULL，不修改非空内容。 */
    private static String emptyToNull(String value) { return value == null || value.isEmpty() ? null : value; }
}
