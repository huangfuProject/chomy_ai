package cn.chomy.ai.backend.service;

import cn.chomy.ai.backend.entity.AgentChat;
import cn.chomy.ai.backend.dto.agent.*;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.spring.service.IService;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 对话服务，继承通用 CRUD；HTTP 入口必须调用带所有权校验的专用方法。
 * 通用 CRUD 仅供内部可信调用，不得直接映射为用户接口或用于接收客户端实体。
 * @author chomy
 */
public interface AgentChatService extends IService<AgentChat> {
    /**
     * 内部可信业务创建对话，检查类型、项目归属与所有者，不直接暴露 HTTP 接口。
     * @param userId 由调用方认证上下文取得的用户 ID
     * @param projectId 可空项目 ID，必须与类型一致
     * @param chatName 允许重复的对话名
     * @param type 显式项目归属类型，空值必须拒绝
     * @return 创建后的未归档对话
     */
    AgentChat createChat(Long userId, Long projectId, String chatName, cn.chomy.ai.backend.enums.AgentChatType type);

    /**
     * 分页读取本人指定项目的全部对话，项目不存在或不属于本人时拒绝查询。
     * @param userId 已验签 JWT 用户 ID
     * @param projectId 本人的项目 ID
     * @param query 分页参数
     * @return 创建时间、ID 倒序的分页结果
     */
    @PreAuthorize("hasAuthority(T(cn.chomy.ai.backend.enums.AgentPermission).PROJECT_CHATS.code)")
    IPage<AgentChatView> projectChats(Long userId, Long projectId, AgentPageQuery query);

    /**
     * 分页读取本人无项目的全部对话。
     * @param userId 已验签 JWT 用户 ID
     * @param query 分页参数
     * @return 项目名称为空的分页结果
     */
    @PreAuthorize("hasAuthority(T(cn.chomy.ai.backend.enums.AgentPermission).STANDALONE_CHATS.code)")
    IPage<AgentChatView> standaloneChats(Long userId, AgentPageQuery query);

    /**
     * 分页读取本人全部归档对话，并批量补齐所属项目名称。
     * @param userId 已验签 JWT 用户 ID
     * @param query 分页参数
     * @return 同时包含项目及无项目对话的分页结果
     */
    @PreAuthorize("hasAuthority(T(cn.chomy.ai.backend.enums.AgentPermission).ARCHIVED_CHATS.code)")
    IPage<AgentChatView> archivedChats(Long userId, AgentPageQuery query);

    /**
     * 仅删除当前用户自己的对话，不删除项目。
     * @param userId 已验签 JWT 用户 ID
     * @param chatId 目标对话 ID
     * @return 成功提示，不存在或无所有权时抛出业务异常
     */
    @PreAuthorize("hasAuthority(T(cn.chomy.ai.backend.enums.AgentPermission).CHAT_DELETE.code)")
    String deleteChat(Long userId, Long chatId);
}
