package cn.chomy.ai.backend.controller.user;

import cn.chomy.ai.backend.dto.agent.*;
import cn.chomy.ai.backend.entity.AgentProject;
import cn.chomy.ai.backend.service.AgentProjectService;
import cn.chomy.ai.backend.service.AgentChatService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/**
 * 当前用户项目入口，仅接收参数并委托服务；用户身份固定来自经过验签的 JWT。
 * 项目与对话通过 ID 关联，名称允许重复；独立接口权限由请求级及服务级 RBAC 校验。
 * @author chomy
 */
// 使用 Spring MVC 原生方法参数校验，不以类级 Validated 切换到额外的 AOP 校验路径。
@RestController
@RequestMapping("/user/agent/projects")
@RequiredArgsConstructor
public class AgentProjectController {
    /** 项目分页、创建、修改与事务删除业务。 */
    private final AgentProjectService projectService;
    /** 项目对话分页业务。 */
    private final AgentChatService chatService;

    /**
     * 分页读取当前登录用户的全部项目，按创建时间和 ID 倒序。
     * @param jwt 已验签的认证主体，不接受客户端指定所有者
     * @param query 分页参数，默认第一页、每页 20 条，最多 100 条
     * @return 包含项目记录、总数及页码信息的分页结果
     */
    @GetMapping
    public IPage<AgentProject> page(@AuthenticationPrincipal Jwt jwt, @Valid @ModelAttribute AgentPageQuery query) {
        // 用户身份固定来自 JWT；所有权过滤、分页边界检查和排序由服务层统一处理。
        return projectService.pageProjects(Long.valueOf(jwt.getSubject()), query);
    }

    /**
     * 创建当前用户项目。
     * @param jwt 已验签的认证主体
     * @param request 项目名及介绍表单
     * @return 新建项目
     */
    @PostMapping
    public AgentProject create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateAgentProjectRequest request) {
        // 所有者由服务端获取，不接受客户端指定的 userId。
        return projectService.createProject(Long.valueOf(jwt.getSubject()), request);
    }

    /**
     * 分页读取当前用户项目下的全部对话，按创建时间倒序。
     * @param jwt 已验签的认证主体
     * @param projectId 正数项目 ID
     * @param query 分页参数，默认第一页、每页 20 条
     * @return 对话分页
     */
    @GetMapping("/{projectId}/chats")
    public IPage<AgentChatView> chats(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive(message = "项目ID必须大于零") Long projectId, @Valid @ModelAttribute AgentPageQuery query) {
        // 项目所有权检查和排序统一交给业务服务。
        return chatService.projectChats(Long.valueOf(jwt.getSubject()), projectId, query);
    }

    /**
     * 修改本人项目名称和介绍，介绍为空时清空原值。
     * @param jwt 已验签的认证主体
     * @param projectId 正数项目 ID
     * @param request 项目名称与可空介绍
     * @return 修改成功提示
     */
    @PutMapping("/{projectId}")
    public String update(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive(message = "项目ID必须大于零") Long projectId,
            @Valid @RequestBody UpdateAgentProjectRequest request) {
        // 仅透传白名单表单，用户身份由 JWT 确定；服务层统一校验所有权并更新。
        return projectService.updateProject(Long.valueOf(jwt.getSubject()), projectId, request);
    }

    /**
     * 物理删除本人项目及其全部对话，包括已归档对话。
     * @param jwt 已验签的认证主体
     * @param projectId 正数项目 ID
     * @return 删除结果
     */
    @DeleteMapping("/{projectId}")
    public String delete(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @Positive(message = "项目ID必须大于零") Long projectId) {
        // 多表清理由 Service 事务保证，Controller 不直接操作 Mapper。
        return projectService.deleteProject(Long.valueOf(jwt.getSubject()), projectId);
    }
}
