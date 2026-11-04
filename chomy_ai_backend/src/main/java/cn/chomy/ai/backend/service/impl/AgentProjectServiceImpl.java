package cn.chomy.ai.backend.service.impl;

import cn.chomy.ai.backend.security.UserContext;
import cn.chomy.ai.backend.common.exception.BizException;
import cn.chomy.ai.backend.dto.agent.CreateAgentProjectRequest;
import cn.chomy.ai.backend.dto.agent.AgentPageQuery;
import cn.chomy.ai.backend.dto.agent.UpdateAgentProjectRequest;
import cn.chomy.ai.backend.entity.AgentChat;
import cn.chomy.ai.backend.entity.AgentProject;
import cn.chomy.ai.backend.mapper.AgentChatMapper;
import cn.chomy.ai.backend.mapper.AgentProjectMapper;
import cn.chomy.ai.backend.service.AgentProjectService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 项目业务实现；所有外部入口附加认证用户条件，同名项目允许并存。
 * 删除项目通过业务事务清理全部关联对话，不使用数据库外键或级联。
 * @author chomy
 */
@Service
@RequiredArgsConstructor
public class AgentProjectServiceImpl extends ServiceImpl<AgentProjectMapper, AgentProject> implements AgentProjectService {
    /** 项目持久化边界，复用 BaseMapper 的通用操作。 */
    private final AgentProjectMapper projectMapper;
    /** 删除项目时在同一事务清理对话，不依赖对话分页过滤条件。 */
    private final AgentChatMapper chatMapper;

    /**
     * 有界分页读取当前用户全部项目，固定排序并在查询中实施用户隔离。
     * @param query 页码从 1 开始、最大为 Integer.MAX_VALUE，每页 1 至 100 条
     * @return 项目分页；没有项目或超出末页时记录列表为空
     * @throws BizException 用户 ID 或分页参数不合法时拒绝查询
     */
    @Override
    public IPage<AgentProject> pageProjects(AgentPageQuery query) {
        // 当前操作者固定取自认证上下文，不允许 Controller 或请求参数指定。
        Long userId = UserContext.userId();
        requireId(userId, "用户不合法");
        // 内部调用也必须检查空值和分页上限，避免绕过 HTTP 校验造成无界查询。
        if (query == null || query.getCurrent() == null || query.getCurrent() < 1
                || query.getCurrent() > Integer.MAX_VALUE || query.getSize() == null
                || query.getSize() < 1 || query.getSize() > 100) {
            throw new BizException("分页参数不合法");
        }
        // 直接在 SQL 中限制所属用户；时间相同时再按 ID 倒序，保证排序确定。
        return projectMapper.selectPage(new Page<>(query.getCurrent(), query.getSize()),
                Wrappers.lambdaQuery(AgentProject.class).eq(AgentProject::getUserId, userId)
                        .orderByDesc(AgentProject::getCreatedAt).orderByDesc(AgentProject::getId));
    }

    /**
     * 创建本人项目并读取数据库生成的创建时间，名称仅作展示，不进行判重。
     * @param request 项目名和可选介绍
     * @return 已持久化的项目
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentProject createProject(CreateAgentProjectRequest request) {
        // 当前操作者固定取自认证上下文，不允许 Controller 或请求参数指定。
        Long userId = UserContext.userId();
        requireId(userId, "用户不合法");
        // 服务层再次校验，避免内部调用绕过 Controller 的参数约束。
        if (request == null || request.projectName() == null || request.projectName().isBlank()
                || request.projectName().length() > 128) { throw new BizException("项目名不能为空且不能超过128个字符"); }
        if (request.description() != null && request.description().length() > 2000) {
            throw new BizException("项目介绍不能超过2000个字符");
        }
        AgentProject project = new AgentProject();
        // 用户 ID 只使用服务端认证参数，表单不接收所有者或创建时间。
        project.setUserId(userId);
        project.setProjectName(request.projectName().strip());
        project.setDescription(request.description() == null || request.description().isEmpty() ? null : request.description());
        if (projectMapper.insert(project) != 1) { throw new BizException("创建项目失败"); }
        // 数据库负责创建时间，重新读取后返回完整结果。
        AgentProject created = projectMapper.selectById(project.getId());
        if (created == null) { throw new BizException("创建项目失败"); }
        return created;
    }

    /**
     * 通过用户与项目 ID 联合条件原子更新白名单字段，不读取后再按裸 ID 更新。
     * @param projectId 待修改项目 ID
     * @param request 必填项目名称与可空介绍，空介绍清空为 NULL
     * @return 修改成功提示
     * @throws BizException 身份、参数不合法，或项目不存在及不属于本人时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String updateProject(Long projectId, UpdateAgentProjectRequest request) {
        // 当前操作者固定取自认证上下文，不允许 Controller 或请求参数指定。
        Long userId = UserContext.userId();
        requireId(userId, "用户不合法");
        requireId(projectId, "项目不合法");
        // 服务层重复检查业务边界，防止内部调用绕过 HTTP 参数校验。
        if (request == null || request.projectName() == null || request.projectName().isBlank()
                || request.projectName().length() > 128) {
            throw new BizException("项目名不能为空且不能超过128个字符");
        }
        if (request.description() != null && request.description().length() > 2000) {
            throw new BizException("项目介绍不能超过2000个字符");
        }
        // 显式 set 可将介绍更新为 NULL，避免实体的非空更新策略使清空操作失效。
        // 更新条件始终限制所属用户，且不修改所有者、创建时间或关联对话。
        int affected = projectMapper.update(null, Wrappers.lambdaUpdate(AgentProject.class)
                .eq(AgentProject::getId, projectId).eq(AgentProject::getUserId, userId)
                .set(AgentProject::getProjectName, request.projectName().strip())
                .set(AgentProject::getDescription,
                        request.description() == null || request.description().isEmpty() ? null : request.description()));
        // 对不存在和他人项目使用相同提示，不泄漏其他用户数据是否存在。
        if (affected != 1) { throw new BizException("项目不存在或无权访问"); }
        return "项目已修改";
    }

    /**
     * 锁定当前用户项目，再删除全部项目对话和项目；任何一步失败均回滚。
     * @param projectId 待删除项目 ID
     * @return 成功提示
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String deleteProject(Long projectId) {
        // 当前操作者固定取自认证上下文，不允许 Controller 或请求参数指定。
        Long userId = UserContext.userId();
        requireId(userId, "用户不合法");
        requireId(projectId, "项目不合法");
        // 行锁串行化同一项目的删除；未来新增项目对话的写入也必须先锁定该项目并验证所有权。
        AgentProject project = projectMapper.selectOne(Wrappers.lambdaQuery(AgentProject.class)
                .eq(AgentProject::getId, projectId).eq(AgentProject::getUserId, userId).last("FOR UPDATE"));
        // 不区分记录不存在和属于他人，避免暴露其他用户的项目是否存在。
        if (project == null) { throw new BizException("项目不存在或无权访问"); }
        // 按项目 ID 删除全部对话，包含已归档记录；有效关联必须与项目属于同一用户。
        chatMapper.delete(Wrappers.lambdaQuery(AgentChat.class).eq(AgentChat::getProjectId, projectId));
        if (projectMapper.delete(Wrappers.lambdaQuery(AgentProject.class)
                .eq(AgentProject::getId, projectId).eq(AgentProject::getUserId, userId)) != 1) {
            throw new BizException("项目不存在或状态已变化");
        }
        return "项目及其全部对话已删除";
    }

    /**
     * 校验可信身份及资源 ID，拒绝空值和非正数。
     * @param id 待校验 ID
     * @param message 对应业务提示
     */
    private void requireId(Long id, String message) {
        // 服务层也保留边界检查，防止非 HTTP 调用绕过参数校验。
        if (id == null || id <= 0) { throw new BizException(message); }
    }
}
