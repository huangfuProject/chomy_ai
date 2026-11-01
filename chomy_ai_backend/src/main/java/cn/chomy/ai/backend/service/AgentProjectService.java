package cn.chomy.ai.backend.service;

import cn.chomy.ai.backend.entity.AgentProject;
import cn.chomy.ai.backend.dto.agent.*;
import com.baomidou.mybatisplus.spring.service.IService;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 项目服务，继承通用 CRUD；HTTP 入口必须调用带所有权校验的专用方法。
 * 通用 CRUD 仅供内部可信调用，不得直接映射为用户接口或用于接收客户端实体。
 * @author chomy
 */
public interface AgentProjectService extends IService<AgentProject> {
    /**
     * 创建当前用户项目，名称允许重复，所属用户由服务端确定。
     * @param userId 已验签 JWT 用户 ID
     * @param request 经过参数校验的项目表单
     * @return 创建后的项目，包含数据库创建时间
     */
    @PreAuthorize("hasAuthority(T(cn.chomy.ai.backend.enums.AgentPermission).PROJECT_CREATE.code)")
    AgentProject createProject(Long userId, CreateAgentProjectRequest request);

    /**
     * 事务内删除本人项目及其全部对话，包含归档对话。
     * @param userId 已验签 JWT 用户 ID
     * @param projectId 目标项目 ID
     * @return 成功提示，不存在或无所有权时抛出业务异常
     */
    @PreAuthorize("hasAuthority(T(cn.chomy.ai.backend.enums.AgentPermission).PROJECT_DELETE.code)")
    String deleteProject(Long userId, Long projectId);
}
