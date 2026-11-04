package cn.chomy.ai.backend.service;

import cn.chomy.ai.backend.entity.AgentChat;
import cn.chomy.ai.backend.dto.agent.*;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.spring.service.IService;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * 对话服务，继承通用 CRUD；HTTP 入口必须调用带所有权校验的专用方法。
 * 通用 CRUD 仅供内部可信调用，不得直接映射为用户接口或用于接收客户端实体。
 * 当前用户身份由服务实现从 UserContext 取得，对外业务方法不接受调用方指定操作者。
 * @author chomy
 */
public interface AgentChatService extends IService<AgentChat> {
    /**
     * 根据一句话生成聊天名称，当前为文本截取占位，不创建或更新聊天。
     * @param request 最多 2000 个字符的命名文本
     * @return 名称预览结果，后续可在实现层替换为模型生成
     */
    @PreAuthorize("hasAuthority(T(cn.chomy.ai.backend.enums.AgentPermission).CHAT_GENERATE_NAME.code)")
    AgentChatNameResponse generateName(GenerateAgentChatNameRequest request);

    /**
     * 创建本人聊天，服务端推导归属类型并固定初始状态为未归档。
     * @param request 名称及可空的本人项目 ID
     * @return 持久化后的聊天，包含创建时间
     */
    @PreAuthorize("hasAuthority(T(cn.chomy.ai.backend.enums.AgentPermission).CHAT_CREATE.code)")
    AgentChat createChat(CreateAgentChatRequest request);

    /**
     * 归档本人聊天，已归档时重复操作仍返回成功，不改变项目归属。
     * @param chatId 聊天 ID
     * @return 归档成功提示，不存在或无所有权时抛出业务异常
     */
    @PreAuthorize("hasAuthority(T(cn.chomy.ai.backend.enums.AgentPermission).CHAT_ARCHIVE.code)")
    String archiveChat(Long chatId);

    /**
     * 将本人聊天恢复为未归档，重复恢复仍返回成功，不改变项目归属。
     * @param chatId 聊天 ID
     * @return 恢复成功提示，不存在或无所有权时抛出业务异常
     */
    @PreAuthorize("hasAuthority(T(cn.chomy.ai.backend.enums.AgentPermission).CHAT_RESTORE.code)")
    String restoreChat(Long chatId);

    /**
     * 移动本人聊天到本人项目，或移出为无项目聊天，保持原归档状态。
     * @param chatId 聊天 ID
     * @param request 目标项目 ID，为空表示移出；表单本身不能为空
     * @return 移动成功提示，不存在、无所有权或目标项目不合法时抛出业务异常
     */
    @PreAuthorize("hasAuthority(T(cn.chomy.ai.backend.enums.AgentPermission).CHAT_MOVE.code)")
    String moveChat(Long chatId, MoveAgentChatRequest request);

    /**
     * 内部可信业务创建对话，检查类型、项目归属与所有者，不直接暴露 HTTP 接口。
     * @param userId 由内部可信业务明确指定的用户 ID，不接受 HTTP 入参
     * @param projectId 可空项目 ID，必须与类型一致
     * @param chatName 允许重复的对话名
     * @param type 显式项目归属类型，空值必须拒绝
     * @return 创建后的未归档对话
     */
    AgentChat createChat(Long userId, Long projectId, String chatName, cn.chomy.ai.backend.enums.AgentChatType type);

    /**
     * 分页读取本人指定项目的全部对话，项目不存在或不属于本人时拒绝查询。
     * @param projectId 本人的项目 ID
     * @param query 分页参数
     * @return 创建时间、ID 倒序的分页结果
     */
    @PreAuthorize("hasAuthority(T(cn.chomy.ai.backend.enums.AgentPermission).PROJECT_CHATS.code)")
    IPage<AgentChatView> projectChats(Long projectId, AgentPageQuery query);

    /**
     * 分页读取本人无项目的全部对话。
     * @param query 分页参数
     * @return 项目名称为空的分页结果
     */
    @PreAuthorize("hasAuthority(T(cn.chomy.ai.backend.enums.AgentPermission).STANDALONE_CHATS.code)")
    IPage<AgentChatView> standaloneChats(AgentPageQuery query);

    /**
     * 分页读取本人全部归档对话，并批量补齐所属项目名称。
     * @param query 分页参数
     * @return 同时包含项目及无项目对话的分页结果
     */
    @PreAuthorize("hasAuthority(T(cn.chomy.ai.backend.enums.AgentPermission).ARCHIVED_CHATS.code)")
    IPage<AgentChatView> archivedChats(AgentPageQuery query);

    /**
     * 重命名本人对话，不限制归档状态或项目归属。
     * @param chatId 目标对话 ID
     * @param request 仅包含新名称的表单
     * @return 成功提示，不存在或无所有权时抛出业务异常
     */
    @PreAuthorize("hasAuthority(T(cn.chomy.ai.backend.enums.AgentPermission).CHAT_RENAME.code)")
    String renameChat(Long chatId, RenameAgentChatRequest request);

    /**
     * 仅删除当前用户自己的对话，不删除项目。
     * @param chatId 目标对话 ID
     * @return 成功提示，不存在或无所有权时抛出业务异常
     */
    @PreAuthorize("hasAuthority(T(cn.chomy.ai.backend.enums.AgentPermission).CHAT_DELETE.code)")
    String deleteChat(Long chatId);
}
