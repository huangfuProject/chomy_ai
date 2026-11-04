package cn.chomy.ai.backend.service;

import cn.chomy.ai.backend.entity.AgentProject;
import cn.chomy.ai.backend.dto.agent.*;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.spring.service.IService;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 项目服务，继承通用 CRUD；HTTP 入口必须调用带所有权校验的专用方法。
 * 通用 CRUD 仅供内部可信调用，不得直接映射为用户接口或用于接收客户端实体。
 * 当前用户身份由服务实现从 UserContext 取得，对外业务方法不接受调用方指定操作者。
 * @author chomy
 */
public interface AgentProjectService extends IService<AgentProject> {
    /**
     * 分页查看本人全部项目，不读取其他用户的数据。
     * @param query 有界分页参数，服务层同样校验其合法性
     * @return 按创建时间和 ID 倒序的项目分页
     */
    @PreAuthorize("hasAuthority(T(cn.chomy.ai.backend.enums.AgentPermission).PROJECT_PAGE.code)")
    IPage<AgentProject> pageProjects(AgentPageQuery query);

    /**
     * 创建当前用户项目，名称允许重复，所属用户由服务端确定。
     * @param request 经过参数校验的项目表单
     * @return 创建后的项目，包含数据库创建时间
     */
    @PreAuthorize("hasAuthority(T(cn.chomy.ai.backend.enums.AgentPermission).PROJECT_CREATE.code)")
    AgentProject createProject(CreateAgentProjectRequest request);

    /**
     * 修改本人项目的名称和介绍，未提供介绍或空字符串表示清空介绍。
     * @param projectId 目标项目 ID
     * @param request 项目名称与介绍，不允许修改所有者或创建时间
     * @return 成功提示，不存在或无所有权时抛出业务异常
     */
    @PreAuthorize("hasAuthority(T(cn.chomy.ai.backend.enums.AgentPermission).PROJECT_UPDATE.code)")
    String updateProject(Long projectId, UpdateAgentProjectRequest request);

    /**
     * 事务内删除本人项目及其全部对话，包含归档对话。
     * @param projectId 目标项目 ID
     * @return 成功提示，不存在或无所有权时抛出业务异常
     */
    @PreAuthorize("hasAuthority(T(cn.chomy.ai.backend.enums.AgentPermission).PROJECT_DELETE.code)")
    String deleteProject(Long projectId);
}
