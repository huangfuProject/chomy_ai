package cn.chomy.ai.backend.service;

import cn.chomy.ai.backend.dto.admin.*;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 管理端用户业务接口，方法级权限与 SQL 中每个接口权限码对应，防止被其他调用入口绕过授权。
 * 所有操作者 ID 均由服务实现从认证用户上下文取得，禁止采用表单中的创建人或角色信息。
 *
 * @author chomy
 */
public interface AdminUserService {
    /**
     * 创建管理员追加用户，设置创建人、密码哈希及默认账户状态。
     * @param request 已校验的创建表单
     * @return 用户管理视图
     */
    @PreAuthorize("hasAuthority(T(cn.chomy.ai.backend.enums.UserManagementPermission).CREATE.code)")
    AdminUserView create(AdminCreateUserRequest request);

    /**
     * @param userId 目标用户 ID
     * @return 激活后的用户资料
     */
    @PreAuthorize("hasAuthority(T(cn.chomy.ai.backend.enums.UserManagementPermission).ACTIVATE.code)")
    AdminUserView activate(Long userId);

    /**
     * 冻结用户并撤销旧会话，不允许操作者冻结自身。
     * @param userId 目标用户 ID
     * @return 冻结后的用户资料
     */
    @PreAuthorize("hasAuthority(T(cn.chomy.ai.backend.enums.UserManagementPermission).FREEZE.code)")
    AdminUserView freeze(Long userId);

    /**
     * @param userId 目标用户 ID
     * @return 解冻后的用户资料，不自动恢复已撤销会话
     */
    @PreAuthorize("hasAuthority(T(cn.chomy.ai.backend.enums.UserManagementPermission).UNFREEZE.code)")
    AdminUserView unfreeze(Long userId);

    /**
     * 管理员重置密码，不要求知道目标用户原密码。
     * @param userId 目标用户 ID
     * @param request 新密码表单
     * @return 成功提示
     */
    @PreAuthorize("hasAuthority(T(cn.chomy.ai.backend.enums.UserManagementPermission).RESET_PASSWORD.code)")
    String resetPassword(Long userId, AdminResetPasswordRequest request);

    /**
     * 修改用户资料，不能通过此入口修改角色、状态、密码或创建审计字段。
     * @param userId 目标用户 ID
     * @param request 完整资料表单
     * @return 修改后的用户资料
     */
    @PreAuthorize("hasAuthority(T(cn.chomy.ai.backend.enums.UserManagementPermission).UPDATE_INFO.code)")
    AdminUserView updateInfo(Long userId, AdminUpdateUserRequest request);

    /**
     * @param userId 目标用户 ID
     * @return 不含密码的用户详情
     */
    @PreAuthorize("hasAuthority(T(cn.chomy.ai.backend.enums.UserManagementPermission).DETAIL.code)")
    AdminUserView detail(Long userId);

    /**
     * 事务内删除用户及用户角色关系，不删除共享角色、权限和审计引用。
     * @param userId 目标用户 ID
     * @return 成功提示
     */
    @PreAuthorize("hasAuthority(T(cn.chomy.ai.backend.enums.UserManagementPermission).DELETE.code)")
    String delete(Long userId);

    /**
     * @param query 已校验的分页及筛选条件
     * @return 包含指定展示字段和创建时间的分页结果
     */
    @PreAuthorize("hasAuthority(T(cn.chomy.ai.backend.enums.UserManagementPermission).PAGE.code)")
    IPage<AdminUserView> page(AdminUserQuery query);
}
