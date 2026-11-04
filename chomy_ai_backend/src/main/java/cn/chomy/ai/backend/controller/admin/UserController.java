package cn.chomy.ai.backend.controller.admin;

import cn.chomy.ai.backend.dto.admin.*;
import cn.chomy.ai.backend.service.AdminUserService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 后台用户管理接口，路径和方法由请求级 RBAC 校验，Service 同时按独立权限码执行方法鉴权。
 * Controller 只做路由、声明式校验、认证主体提取及委托，不进行数据库和缓存操作。
 *
 * @author chomy
 */
@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class UserController {
    /** 用户管理业务入口，包含权限、资料、密码及关联清理规则。 */
    private final AdminUserService adminUserService;

    /**
     * @param request 用户创建表单
     * @return 新建用户
     */
    @PostMapping
    public AdminUserView create(@Valid @RequestBody AdminCreateUserRequest request) {
        // 创建人由服务层从认证上下文提取，不允许表单指定；其余流程全部委托 Service。
        return adminUserService.create(request);
    }

    /**
     * @param id 目标用户 ID
     * @return 激活后的用户
     */
    @PutMapping("/{id}/activate")
    public AdminUserView activate(@PathVariable @Positive(message = "用户ID必须为正数") Long id) {
        // 激活不等于解冻，状态规则由业务层明确处理。
        return adminUserService.activate(id);
    }

    /**
     * @param id 目标用户 ID
     * @return 冻结后的用户
     */
    @PutMapping("/{id}/freeze")
    public AdminUserView freeze(@PathVariable @Positive(message = "用户ID必须为正数") Long id) {
        // 操作者由服务层从认证上下文取得，由业务层判断是否为自己并撤销旧会话。
        return adminUserService.freeze(id);
    }

    /**
     * @param id 目标用户 ID
     * @return 解冻后的用户
     */
    @PutMapping("/{id}/unfreeze")
    public AdminUserView unfreeze(@PathVariable @Positive(message = "用户ID必须为正数") Long id) {
        // 仅委托业务服务，不在控制器中直接更新状态。
        return adminUserService.unfreeze(id);
    }

    /**
     * @param id 目标用户 ID
     * @param request 新密码
     * @return 修改提示
     */
    @PutMapping("/{id}/password")
    public String resetPassword(@PathVariable @Positive(message = "用户ID必须为正数") Long id,
                                @Valid @RequestBody AdminResetPasswordRequest request) {
        // 管理员通过独立权限重置目标用户密码，不能经资料接口绕过此校验。
        return adminUserService.resetPassword(id, request);
    }

    /**
     * @param id 目标用户 ID
     * @param request 完整资料
     * @return 修改后的用户
     */
    @PutMapping("/{id}")
    public AdminUserView update(@PathVariable @Positive(message = "用户ID必须为正数") Long id,
                                @Valid @RequestBody AdminUpdateUserRequest request) {
        // 只传递资料白名单，不构造可被任意赋值的用户实体。
        return adminUserService.updateInfo(id, request);
    }

    /**
     * @param id 目标用户 ID
     * @return 用户详情
     */
    @GetMapping("/{id}")
    public AdminUserView detail(@PathVariable @Positive(message = "用户ID必须为正数") Long id) {
        // 不在控制器拼装返回字段，服务统一生成不含密码的 DTO。
        return adminUserService.detail(id);
    }

    /**
     * @param id 目标用户 ID
     * @return 删除提示
     */
    @DeleteMapping("/{id}")
    public String delete(@PathVariable @Positive(message = "用户ID必须为正数") Long id) {
        // 删除及关系清理在同一业务事务中完成，删除也要求 update 组权限。
        return adminUserService.delete(id);
    }

    /**
     * @param query 分页和筛选条件
     * @return 用户分页结果
     */
    @GetMapping
    public IPage<AdminUserView> page(@Valid @ModelAttribute AdminUserQuery query) {
        // GET 查询参数经校验和枚举转换后交由 Service，禁止客户端控制 SQL 排序片段。
        return adminUserService.page(query);
    }
}
