package cn.chomy.ai.backend.service;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;
import cn.chomy.ai.backend.common.exception.BizException;
import cn.chomy.ai.backend.dto.admin.*;
import cn.chomy.ai.backend.entity.User;
import cn.chomy.ai.backend.enums.*;
import cn.chomy.ai.backend.mapper.UserMapper;
import cn.chomy.ai.backend.mapper.UserRoleMapper;
import cn.chomy.ai.backend.security.JwtSessionService;
import cn.chomy.ai.backend.service.impl.AdminUserServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 用户管理业务回归，使用内存替身验证实体写入、密码编码、会话撤销及显式关系清理。
 * 不连接外部数据库；实际 HTTP 和方法权限由安全集成测试覆盖。
 *
 * @author chomy
 */
@SuppressWarnings("unchecked")
class AdminUserServiceTest {
    /** 模拟用户和关系 Mapper，不替换业务实现。 */
    private UserMapper users;
    private UserRoleMapper userRoles;
    /** 会话撤销边界，校验其发生在敏感写入之前。 */
    private JwtSessionService sessions;
    /** 真实 BCrypt 编码器，测试不使用明文存储。 */
    private PasswordEncoder encoder;
    /** 被测真实业务实现。 */
    private AdminUserServiceImpl service;
    /** 多用户内存表，返回独立对象，模拟数据库读取行为。 */
    private Map<Long, User> rows;

    /** 初始化两条虚构账户及 MyBatis 元数据，不访问真实账号或数据库。 */
    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "admin-test"), User.class);
        users = mock(UserMapper.class); userRoles = mock(UserRoleMapper.class); sessions = mock(JwtSessionService.class);
        encoder = new cn.chomy.ai.backend.config.SecurityConfig().passwordEncoder();
        service = new AdminUserServiceImpl(users, userRoles, encoder, sessions);
        rows = new LinkedHashMap<>(); rows.put(1L, account(1L, "operator")); rows.put(2L, account(2L, "target"));
        when(users.selectById(any())).thenAnswer(call -> copy(rows.get((Long) call.getArgument(0))));
        when(users.selectOne(any(Wrapper.class))).thenAnswer(call -> copy(rows.get(idFrom(call.getArgument(0)))));
        when(users.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(users.insert(any(User.class))).thenAnswer(call -> {
            User inserted = call.getArgument(0); rows.put(inserted.getId(), copy(inserted)); return 1;
        });
        when(users.update(any(Wrapper.class))).thenAnswer(call -> {
            LambdaUpdateWrapper<User> wrapper = call.getArgument(0);
            String set = wrapper.getSqlSet();
            User row = rows.get(idFrom(wrapper));
            if (row == null) { return 0; }
            var matcher = Pattern.compile("([a-z_]+)=#\\{ew.paramNameValuePairs.(MPGENVAL\\d+)\\}").matcher(set);
            BeanWrapperImpl bean = new BeanWrapperImpl(row);
            while (matcher.find()) {
                String[] names = matcher.group(1).split("_"); StringBuilder property = new StringBuilder(names[0]);
                for (int i = 1; i < names.length; i++) { property.append(Character.toUpperCase(names[i].charAt(0))).append(names[i].substring(1)); }
                bean.setPropertyValue(property.toString(), wrapper.getParamNameValuePairs().get(matcher.group(2)));
            }
            return 1;
        });
        when(users.deleteById(anyLong())).thenAnswer(call -> rows.remove((Long) call.getArgument(0)) == null ? 0 : 1);
        when(users.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(call -> {
            Page<User> page = call.getArgument(0);
            page.setTotal(rows.size()); page.setRecords(rows.values().stream().map(this::copy).toList()); return page;
        });
    }

    /** 创建信息由服务设置审计来源、初始状态和密码密文，不能自动授予任何角色。 */
    @Test
    void createsUserWithServerControlledAuditAndHash() {
        AdminUserView created = service.create(1L, createRequest("new-user", "13800000003", "InitialPass123!"));
        User row = rows.get(created.id());
        assertThat(row.getCreateType()).isEqualTo(UserCreateType.ADMIN_CREATED);
        assertThat(row.getCreatedBy()).isEqualTo(1L);
        assertThat(row.getStatus()).isEqualTo(UserStatus.NORMAL);
        assertThat(row.getActived()).isEqualTo(ActivationStatus.ACTIVE);
        assertThat(row.getGender()).isEqualTo(Gender.UNKNOWN);
        assertThat(encoder.matches("InitialPass123!", row.getPasswordHash())).isTrue();
        verifyNoInteractions(userRoles);
    }

    /** 预检查及数据库并发唯一键冲突都应转成业务错误，不能暴露 SQL 原文。 */
    @Test
    void rejectsDuplicatesAndConcurrentUniqueConflicts() {
        when(users.selectCount(any(Wrapper.class))).thenReturn(1L);
        assertThatThrownBy(() -> service.create(1L, createRequest("duplicate", "13800000003", "InitialPass123!")))
                .isInstanceOf(BizException.class).hasMessage("用户名或手机号已存在");
        verify(users, never()).insert(any(User.class));
        when(users.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(users.insert(any(User.class))).thenThrow(new DuplicateKeyException("test-only-internal"));
        assertThatThrownBy(() -> service.create(1L, createRequest("racing", "13800000003", "InitialPass123!")))
                .isInstanceOf(BizException.class).hasMessage("用户名或手机号已存在");
    }

    /** 激活不应解冻，冻结和解冻都应使旧会话失效，操作者不能冻结自己。 */
    @Test
    void managesActivationAndFrozenStateIndependently() {
        rows.get(2L).setStatus(UserStatus.FROZEN); rows.get(2L).setActived(ActivationStatus.INACTIVE);
        AdminUserView active = service.activate(2L);
        assertThat(active.active()).isTrue(); assertThat(active.frozen()).isTrue();
        assertThat(service.unfreeze(2L).frozen()).isFalse();
        assertThat(service.freeze(1L, 2L).frozen()).isTrue();
        verify(sessions, times(2)).revokeUserSessions(2L);
        assertThatThrownBy(() -> service.freeze(1L, 1L)).isInstanceOf(BizException.class);
    }

    /** 重置密码无需原密码，必须写入哈希并撤销原会话，超限密码不得写入。 */
    @Test
    void resetsPasswordAndRejectsInvalidPassword() {
        service.resetPassword(2L, new AdminResetPasswordRequest("ChangedPass123!"));
        assertThat(encoder.matches("ChangedPass123!", rows.get(2L).getPasswordHash())).isTrue();
        var order = inOrder(sessions, users);
        order.verify(sessions).revokeUserSessions(2L);
        order.verify(users).update(any(Wrapper.class));
        assertThatThrownBy(() -> service.resetPassword(2L, new AdminResetPasswordRequest("中".repeat(25))))
                .isInstanceOf(BizException.class);
    }

    /** 资料白名单不会改写状态、审计或密码，可空信息允许清空，身份字段变化撤销旧会话。 */
    @Test
    void updatesOnlyAllowedFields() {
        User before = copy(rows.get(2L));
        AdminUserView result = service.updateInfo(2L,
                new AdminUpdateUserRequest("renamed", "新昵称", "13800000004", Gender.FEMALE, null, ""));
        assertThat(result.nickname()).isEqualTo("新昵称"); assertThat(result.gender()).isEqualTo(Gender.FEMALE);
        assertThat(result.signature()).isNull(); assertThat(result.avatarUrl()).isNull();
        assertThat(rows.get(2L).getPasswordHash()).isEqualTo(before.getPasswordHash());
        assertThat(rows.get(2L).getCreatedBy()).isEqualTo(before.getCreatedBy());
        assertThat(rows.get(2L).getCreateType()).isEqualTo(before.getCreateType());
        assertThat(rows.get(2L).getStatus()).isEqualTo(before.getStatus());
        verify(sessions).revokeUserSessions(2L);
    }

    /** 删除只清理目标用户角色关系及账户，不触碰共享角色，撤销失败时不能进入数据删除。 */
    @Test
    void deletesAccountAndRelationsAndRejectsSelfDelete() {
        service.delete(1L, 2L);
        assertThat(rows).doesNotContainKey(2L).containsKey(1L);
        var order = inOrder(sessions, userRoles, users);
        order.verify(sessions).revokeUserSessions(2L);
        order.verify(userRoles).delete(any(Wrapper.class));
        order.verify(users).deleteById(2L);
        assertThatThrownBy(() -> service.delete(1L, 1L)).isInstanceOf(BizException.class);
    }

    /** Redis 故障会中止敏感操作，不能出现冻结后解冻仍恢复旧 Token 的降级行为。 */
    @Test
    void failsBeforeWritingWhenSessionRevocationFails() {
        doThrow(new org.springframework.data.redis.RedisConnectionFailureException("test-only"))
                .when(sessions).revokeUserSessions(2L);
        assertThatThrownBy(() -> service.delete(1L, 2L)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        verify(userRoles, never()).delete(any(Wrapper.class));
        verify(users, never()).deleteById(anyLong());
        assertThat(rows).containsKey(2L);
    }

    /** 分页条件转义 LIKE 字符，绑定枚举并固定排序，错误页大小及时间区间必须拒绝。 */
    @Test
    void pagesWithBoundFiltersAndValidatesBounds() {
        AdminUserQuery query = new AdminUserQuery(); query.setUsername("a%_");
        query.setStatus(UserStatus.FROZEN); query.setActived(ActivationStatus.ACTIVE); query.setGender(Gender.MALE);
        var result = service.page(query);
        assertThat(result.getRecords()).hasSize(2);
        ArgumentCaptor<Wrapper<User>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(users).selectPage(any(Page.class), captor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) captor.getValue();
        assertThat(wrapper.getSqlSegment()).contains("username LIKE", "status =", "actived =", "gender =", "ORDER BY created_at DESC,id DESC");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("%a\\%\\_%", UserStatus.FROZEN, ActivationStatus.ACTIVE, Gender.MALE);
        query.setSize(101L); assertThatThrownBy(() -> service.page(query)).isInstanceOf(BizException.class);
        query.setSize(20L); query.setCreatedFrom(OffsetDateTime.now()); query.setCreatedTo(query.getCreatedFrom().minusDays(1));
        assertThatThrownBy(() -> service.page(query)).isInstanceOf(BizException.class);
    }

    /** 详情及分页 DTO 不应泄露密码，即使误传入已含哈希的实体。 */
    @Test
    void viewNeverExposesPassword() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        String json = mapper.writeValueAsString(service.detail(2L));
        assertThat(json).contains("username", "nickname", "phone", "avatarUrl", "gender", "frozen", "active", "createdAt")
                .doesNotContain("password", "passwordHash");
        assertThatThrownBy(() -> service.detail(999L)).isInstanceOf(BizException.class);
    }

    /** 构造只用于测试的账户，不含真实凭据。 */
    private User account(Long id, String username) {
        User user = new User(); user.setId(id); user.setUsername(username); user.setNickname(username);
        user.setPhone("1380000000" + id); user.setStatus(UserStatus.NORMAL); user.setActived(ActivationStatus.ACTIVE);
        user.setGender(Gender.UNKNOWN); user.setCreateType(UserCreateType.ADMIN_CREATED); user.setCreatedBy(1L);
        user.setPasswordHash("test-only-not-a-real-hash"); user.setCreatedAt(OffsetDateTime.now());
        return user;
    }

    /** 创建虚构表单，避免测试代码依赖真实管理员密码。 */
    private AdminCreateUserRequest createRequest(String username, String phone, String password) {
        return new AdminCreateUserRequest(username, "测试昵称", phone, password, null, null, null, null);
    }

    /** 数据库读取返回副本，避免业务修改实体影响替身中的其他操作。 */
    private User copy(User original) {
        if (original == null) { return null; }
        User copy = new User(); BeanUtils.copyProperties(original, copy); return copy;
    }

    /** 从真实 Wrapper 的绑定参数提取目标 ID，禁止用恒定返回值掩盖错误更新范围。 */
    private Long idFrom(Wrapper<User> value) {
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) value;
        String sql = wrapper.getSqlSegment();
        var matcher = Pattern.compile("(?<![a-z_])id\\s*=\\s*#\\{ew.paramNameValuePairs\\.(MPGENVAL\\d+)\\}").matcher(sql);
        if (!matcher.find()) { throw new AssertionError("目标查询或更新缺少用户ID条件：" + sql); }
        return (Long) wrapper.getParamNameValuePairs().get(matcher.group(1));
    }
}
