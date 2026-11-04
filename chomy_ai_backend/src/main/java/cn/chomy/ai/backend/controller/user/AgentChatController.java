package cn.chomy.ai.backend.controller.user;

import cn.chomy.ai.backend.dto.agent.*;
import cn.chomy.ai.backend.entity.AgentChat;
import cn.chomy.ai.backend.service.AgentChatService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import cn.chomy.ai.backend.security.UserContext;
import org.springframework.web.bind.annotation.*;

/**
 * 当前用户对话入口，参数中不接受所属用户；所有数据读写均由服务层实施所有权隔离。
 * @author chomy
 */
// 使用 Spring MVC 原生方法参数校验，不以类级 Validated 切换到额外的 AOP 校验路径。
@RestController
@RequestMapping("/user/agent/chats")
@RequiredArgsConstructor
public class AgentChatController {
    /** 对话名称生成、创建、分页、归档恢复、移动、重命名与删除业务。 */
    private final AgentChatService chatService;

    /**
     * 根据前端一句话生成聊天名称，当前提供占位结果，不修改聊天记录。
     * @param request 命名文本，必填且最多 2000 个字符
     * @return 包含聊天名称的结果，后续接入模型时保持结构不变
     */
    @PostMapping("/name/generate")
    public AgentChatNameResponse generateName(
            @Valid @RequestBody GenerateAgentChatNameRequest request) {
        // 当前用户来自认证用户上下文，文本校验及占位命名由 Service 完成，Controller 不拼接名称。
        return chatService.generateName(UserContext.userId(), request);
    }

    /**
     * 创建本人聊天，项目可空，初始归档状态由服务端固定。
     * @param request 名称及可空项目 ID
     * @return 新建聊天
     */
    @PostMapping
    public AgentChat create(@Valid @RequestBody CreateAgentChatRequest request) {
        // 只传递白名单字段，忽略客户端提供的所有者、类型及归档状态。
        return chatService.createChat(UserContext.userId(), request);
    }

    /**
     * 归档本人聊天，重复归档仍成功。
     * @param chatId 正数聊天 ID
     * @return 归档成功提示
     */
    @PutMapping("/{chatId}/archive")
    public String archive(
            @PathVariable @Positive(message = "对话ID必须大于零") Long chatId) {
        // 归档状态由固定业务操作决定，不接收客户端任意状态编码。
        return chatService.archiveChat(UserContext.userId(), chatId);
    }

    /**
     * 将本人聊天恢复为未归档，重复恢复仍成功。
     * @param chatId 正数聊天 ID
     * @return 恢复成功提示
     */
    @PutMapping("/{chatId}/restore")
    public String restore(
            @PathVariable @Positive(message = "对话ID必须大于零") Long chatId) {
        // 恢复独立鉴权，拥有归档权限不代表拥有恢复权限。
        return chatService.restoreChat(UserContext.userId(), chatId);
    }

    /**
     * 移动本人聊天到本人项目，或移出为无项目聊天。
     * @param chatId 正数聊天 ID
     * @param request 目标项目 ID；为空时移出，保持原归档状态
     * @return 移动成功提示
     */
    @PutMapping("/{chatId}/move")
    public String move(
            @PathVariable @Positive(message = "对话ID必须大于零") Long chatId,
            @Valid @RequestBody MoveAgentChatRequest request) {
        // 项目校验、行锁与关联字段同步更新由事务服务统一完成。
        return chatService.moveChat(UserContext.userId(), chatId, request);
    }

    /**
     * 分页读取本人无项目对话，包含归档记录。
     * @param query 有界分页参数
     * @return 创建时间、ID 倒序分页
     */
    @GetMapping("/standalone")
    public IPage<AgentChatView> standalone(@Valid @ModelAttribute AgentPageQuery query) {
        // 当前用户 ID 来自认证主体，避免客户端查询他人对话。
        return chatService.standaloneChats(UserContext.userId(), query);
    }

    /**
     * 分页读取本人全部归档对话，项目名称无归属时为空。
     * @param query 有界分页参数
     * @return 带所属项目名的归档分页
     */
    @GetMapping("/archived")
    public IPage<AgentChatView> archived(@Valid @ModelAttribute AgentPageQuery query) {
        // 项目名称由业务层查询，Controller 不进行额外数据访问。
        return chatService.archivedChats(UserContext.userId(), query);
    }

    /**
     * 重命名本人对话，适用于项目对话、无项目对话及归档对话。
     * @param chatId 正数对话 ID
     * @param request 仅包含对话名称的表单
     * @return 重命名成功提示
     */
    @PutMapping("/{chatId}/name")
    public String rename(
            @PathVariable @Positive(message = "对话ID必须大于零") Long chatId,
            @Valid @RequestBody RenameAgentChatRequest request) {
        // 所有权条件与字段白名单由 Service 处理，避免客户端借重命名移动对话或变更归档状态。
        return chatService.renameChat(UserContext.userId(), chatId, request);
    }

    /**
     * 删除本人单条对话，不删除所属项目。
     * @param chatId 正数对话 ID
     * @return 删除结果
     */
    @DeleteMapping("/{chatId}")
    public String delete(
            @PathVariable @Positive(message = "对话ID必须大于零") Long chatId) {
        // 删除条件与所有权在服务层合并执行，防止横向越权。
        return chatService.deleteChat(UserContext.userId(), chatId);
    }
}
