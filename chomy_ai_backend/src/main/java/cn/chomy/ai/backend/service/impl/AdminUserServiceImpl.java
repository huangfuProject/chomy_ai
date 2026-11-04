package cn.chomy.ai.backend.service.impl;

import cn.chomy.ai.backend.security.UserContext;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Objects;
import cn.chomy.ai.backend.common.exception.BizException;
import cn.chomy.ai.backend.dto.admin.*;
import cn.chomy.ai.backend.entity.User;
import cn.chomy.ai.backend.entity.UserRole;
import cn.chomy.ai.backend.enums.*;
import cn.chomy.ai.backend.mapper.UserMapper;
import cn.chomy.ai.backend.mapper.UserRoleMapper;
import cn.chomy.ai.backend.security.JwtSessionService;
import cn.chomy.ai.backend.service.AdminUserService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 管理端用户业务，使用 MyBatis-Plus 显式维护资料、状态、密码和用户角色关系。
 * 行锁串行化同一目标的管理写入；唯一索引兜底并发重名；关联删除在数据库事务内完成。
 * 会话撤销发生在敏感写入前，Redis 故障则不写数据库；事务失败时允许保守地要求旧会话重新登录。
 *
 * @author chomy
 */
@Service
@RequiredArgsConstructor
public class AdminUserServiceImpl implements AdminUserService {
    /** 用户表基础操作。 */
    private final UserMapper userMapper;
    /** 删除用户时显式清理其角色关联，不依赖外键级联。 */
    private final UserRoleMapper userRoleMapper;
    /** 新密码统一进行 BCrypt 哈希，不保存原密码。 */
    private final PasswordEncoder passwordEncoder;
    /** 敏感账户修改前撤销已有登录会话。 */
    private final JwtSessionService sessions;

    /**
     * 创建管理员追加用户，固定审计来源，不自动授予任何角色。
     * @param request 已校验表单
     * @return 数据库持久化后的公开资料
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminUserView create(AdminCreateUserRequest request) {
        // 当前操作者固定取自认证上下文，不允许 Controller 或请求参数指定。
        Long operatorId = UserContext.userId();
        // 身份与创建方式只能由服务端设置，客户端不能冒充创建人或将来源改为自注册。
        if (operatorId == null || operatorId <= 0) { throw new BizException("操作者不合法"); }
        String username = requireUsername(request.username());
        // 可选手机号统一存为 NULL，避免多个空号码触发唯一索引冲突。
        String phone = emptyToNull(request.phone());
        ensureUnique(null, username, phone);
        User user = new User();
        user.setId(IdWorker.getId()); user.setUsername(username); user.setNickname(request.nickname().strip());
        user.setPhone(phone); user.setEmail(emptyToNull(request.email())); user.setGender(request.gender() == null ? Gender.UNKNOWN : request.gender());
        user.setSignature(emptyToNull(request.signature())); user.setAvatarUrl(emptyToNull(request.avatarUrl()));
        user.setCreateType(UserCreateType.ADMIN_CREATED); user.setCreatedBy(operatorId);
        user.setStatus(UserStatus.NORMAL);
        user.setActived(request.actived() == null ? ActivationStatus.ACTIVE : request.actived());
        user.setPasswordHash(encodePassword(request.password())); user.setUpdatedAt(OffsetDateTime.now());
        try {
            // 查询判重仅提供友好提示，最终仍由数据库唯一索引阻止并发重复写入。
            if (userMapper.insert(user) != 1) { throw new BizException("创建用户失败"); }
        } catch (DuplicateKeyException exception) { throw duplicateUser(exception); }
        return detailInternal(user.getId());
    }

    /** 激活只改变激活标记，不会同时解冻账户。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminUserView activate(Long userId) {
        lockUser(userId);
        // 激活只更新激活标记，不修改冻结状态；保持更新时间由代码维护。
        requireChanged(userMapper.update(Wrappers.lambdaUpdate(User.class).eq(User::getId, userId)
                .set(User::getActived, ActivationStatus.ACTIVE).set(User::getUpdatedAt, OffsetDateTime.now())));
        return detailInternal(userId);
    }

    /** 冻结前撤销旧会话，禁止操作者冻结自身。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminUserView freeze(Long userId) {
        // 当前操作者固定取自认证上下文，不允许 Controller 或请求参数指定。
        Long operatorId = UserContext.userId();
        // 先阻止当前管理账户自我冻结，再进入目标状态切换流程。
        rejectSelf(operatorId, userId, "不能冻结当前登录账户");
        return changeStatus(userId, UserStatus.FROZEN);
    }

    /** 解冻前仍撤销旧会话，冻结前签发的 Token 不会因解冻而重新可用。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminUserView unfreeze(Long userId) { return changeStatus(userId, UserStatus.NORMAL); }

    /** 管理员重置密码，新的随机盐哈希同时使旧 JWT 密码指纹失效。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String resetPassword(Long userId, AdminResetPasswordRequest request) {
        lockUser(userId);
        String hash = encodePassword(request.newPassword());
        // 先撤销所有设备会话；缓存不可用时抛出异常，不继续持久化密码。
        sessions.revokeUserSessions(userId);
        int changed = userMapper.update(Wrappers.lambdaUpdate(User.class).eq(User::getId, userId)
                .set(User::getPasswordHash, hash).set(User::getUpdatedAt, OffsetDateTime.now()));
        requireChanged(changed);
        return "密码修改成功，用户需重新登录";
    }

    /** 资料修改只写白名单，用户名、手机号变更时撤销旧认证会话。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AdminUserView updateInfo(Long userId, AdminUpdateUserRequest request) {
        // 锁定目标账户后再判重和写入，避免相同用户的并发管理操作相互覆盖。
        User current = lockUser(userId);
        String username = requireUsername(request.username());
        // 可选手机号统一存为 NULL，避免多个空号码触发唯一索引冲突。
        String phone = emptyToNull(request.phone());
        ensureUnique(userId, username, phone);
        // 身份标识变化后要求重新登录，单纯昵称或签名变化不打断正常会话。
        if (!Objects.equals(current.getUsername(), username) || !Objects.equals(current.getPhone(), phone)) {
            sessions.revokeUserSessions(userId);
        }
        try {
            // 不把请求直接转换为实体，避免额外 JSON 字段改变状态、创建方式、审计信息或角色。
            int changed = userMapper.update(Wrappers.lambdaUpdate(User.class).eq(User::getId, userId)
                    .set(User::getUsername, username).set(User::getNickname, request.nickname().strip())
                    .set(User::getPhone, phone).set(User::getEmail, emptyToNull(request.email())).set(User::getGender, request.gender())
                    .set(User::getSignature, emptyToNull(request.signature())).set(User::getAvatarUrl, emptyToNull(request.avatarUrl()))
                    .set(User::getUpdatedAt, OffsetDateTime.now()));
            requireChanged(changed);
        } catch (DuplicateKeyException exception) { throw duplicateUser(exception); }
        return detailInternal(userId);
    }

    /** 查询用户详情，明确使用不含密码的展示 DTO。 */
    @Override
    public AdminUserView detail(Long userId) { return detailInternal(userId); }

    /** 物理删除用户及其角色关系，保留共享角色、权限及历史审计操作者 ID。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String delete(Long userId) {
        // 当前操作者固定取自认证上下文，不允许 Controller 或请求参数指定。
        Long operatorId = UserContext.userId();
        rejectSelf(operatorId, userId, "不能删除当前登录账户");
        lockUser(userId);
        // 删除关联和用户处于同一个数据库事务；任一步失败均回滚，不能遗留本次操作造成的孤立关系。
        sessions.revokeUserSessions(userId);
        userRoleMapper.delete(Wrappers.lambdaQuery(UserRole.class).eq(UserRole::getUserId, userId));
        requireChanged(userMapper.deleteById(userId));
        return "用户删除成功";
    }

    /** 分页读取用户，文本查询参数仅作为绑定值，排序使用固定列，不接受客户端 SQL。 */
    @Override
    public IPage<AdminUserView> page(AdminUserQuery query) {
        // 服务层也校验分页边界，其他代码直接调用服务时不能绕过 Controller 的 Bean Validation。
        if (query.getCurrent() == null || query.getCurrent() < 1 || query.getCurrent() > Integer.MAX_VALUE || query.getSize() == null || query.getSize() < 1 || query.getSize() > 100) {
            throw new BizException("分页参数不正确，每页最多100条");
        }
        // 时间范围使用带时区时间直接比较，防止反向区间生成无意义查询。
        if (query.getCreatedFrom() != null && query.getCreatedTo() != null && query.getCreatedFrom().isAfter(query.getCreatedTo())) {
            throw new BizException("创建时间下限不能晚于上限");
        }
        String username = likeKeyword(query.getUsername()), nickname = likeKeyword(query.getNickname()), phone = likeKeyword(query.getPhone());
        // 百分号及下划线按字面量转义，枚举仍按稳定数字编码绑定到数据库。
        var wrapper = Wrappers.lambdaQuery(User.class)
                .like(StringUtils.hasText(username), User::getUsername, username)
                .like(StringUtils.hasText(nickname), User::getNickname, nickname)
                .like(StringUtils.hasText(phone), User::getPhone, phone)
                .eq(query.getGender() != null, User::getGender, query.getGender())
                .eq(query.getStatus() != null, User::getStatus, query.getStatus())
                .eq(query.getActived() != null, User::getActived, query.getActived())
                .ge(query.getCreatedFrom() != null, User::getCreatedAt, query.getCreatedFrom())
                .le(query.getCreatedTo() != null, User::getCreatedAt, query.getCreatedTo())
                .orderByDesc(User::getCreatedAt, User::getId);
        return userMapper.selectPage(new Page<>(query.getCurrent(), query.getSize()), wrapper).convert(AdminUserView::from);
    }

    /** 串行化同一目标的写入；FOR UPDATE 为固定 SQL 片段，绝不拼入外部参数。 */
    private User lockUser(Long userId) {
        requireId(userId);
        User user = userMapper.selectOne(Wrappers.lambdaQuery(User.class).eq(User::getId, userId).last("FOR UPDATE"));
        if (user == null) { throw new BizException("用户不存在"); }
        return user;
    }

    /** 普通详情查询不读取密码，审计字段仅在管理 DTO 中明确公开。 */
    private AdminUserView detailInternal(Long userId) {
        requireId(userId);
        User user = userMapper.selectById(userId);
        if (user == null) { throw new BizException("用户不存在"); }
        return AdminUserView.from(user);
    }

    /** 在同一事务及行锁下切换状态，显式更新审计时间。 */
    private AdminUserView changeStatus(Long userId, UserStatus status) {
        lockUser(userId);
        sessions.revokeUserSessions(userId);
        requireChanged(userMapper.update(Wrappers.lambdaUpdate(User.class).eq(User::getId, userId)
                .set(User::getStatus, status).set(User::getUpdatedAt, OffsetDateTime.now())));
        return detailInternal(userId);
    }

    /** 排除当前用户后检查用户名与手机号，更新同一用户自己的原值不会误判重复。 */
    private void ensureUnique(Long userId, String username, String phone) {
        // 将用户名或手机号重复条件括在同一个 AND 分组，更新时排除自身，避免 OR 绕过排除条件。
        Long count = userMapper.selectCount(Wrappers.lambdaQuery(User.class).ne(userId != null, User::getId, userId)
                .and(condition -> {
                    // 空号码不参与判重，用户名仍始终检查；数据库唯一索引允许多个 NULL。
                    condition.eq(User::getUsername, username);
                    if (phone != null) { condition.or().eq(User::getPhone, phone); }
                }));
        if (count != null && count > 0) { throw new BizException("用户名或手机号已存在"); }
    }

    /** 密码策略在业务层再校验，调用服务时也不能绕过 BCrypt 的 UTF-8 字节上限。 */
    private String encodePassword(String raw) {
        // 按 UTF-8 字节而非仅按字符数限制 BCrypt 输入，多字节中文密码也必须满足上限。
        if (!StringUtils.hasText(raw) || raw.length() < 8 || raw.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new BizException("密码至少8个字符且UTF-8编码不能超过72字节");
        }
        return passwordEncoder.encode(raw);
    }

    /** 将并发唯一键冲突转为可公开提示，不将数据库异常原文返回前端。 */
    private BizException duplicateUser(DuplicateKeyException exception) {
        return new BizException(400, "用户名或手机号已存在", exception);
    }

    /** 防止误操作当前管理账户；操作者来自认证 JWT。 */
    private void rejectSelf(Long operatorId, Long userId, String message) {
        if (operatorId == null || operatorId <= 0) { throw new BizException("操作者不合法"); }
        requireId(userId);
        if (operatorId.equals(userId)) { throw new BizException(message); }
    }

    /** 目标 ID 必须为正数。 */
    private void requireId(Long userId) { if (userId == null || userId <= 0) { throw new BizException("用户ID不合法"); } }

    /** 预期单行变更必须成功，避免静默返回已不存在的用户。 */
    private void requireChanged(int changed) { if (changed != 1) { throw new BizException("用户不存在或状态已变化"); } }

    /** 用户名按原始值严格校验，不能先去空格后将非法输入转换成合法账号。 */
    private String requireUsername(String value) {
        // 服务层防止内部调用绕过表单校验，所有非 ASCII 字母、数字、下划线均拒绝。
        if (value == null || value.length() > 64 || !value.matches("[A-Za-z0-9_]+")) {
            throw new BizException("非合法用户名");
        }
        return value;
    }

    /** 空手机号、邮箱、签名或头像规范为数据库 NULL。 */
    private String emptyToNull(String value) { return value == null || value.isEmpty() ? null : value; }

    /** 将 LIKE 特殊字符转义，查询只具有字面量子串含义。 */
    private String likeKeyword(String value) {
        // 先转义反斜线再转义通配符，避免新增转义符在后续步骤被再次转义。
        if (value == null) { return null; }
        return value.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
