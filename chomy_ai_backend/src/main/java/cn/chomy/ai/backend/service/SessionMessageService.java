package cn.chomy.ai.backend.service;

import cn.chomy.ai.backend.dto.agent.AgentPageQuery;
import cn.chomy.ai.backend.entity.AgentChatMessage;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 当前用户的 Chat 级历史与订阅服务，身份从认证上下文取得，不接受客户端指定用户 ID。
 * 历史存储与实时传输独立，订阅不写消息、不创建 Task、不触发 Agent 执行。
 * @author chomy
 */
public interface SessionMessageService {
    /**
     * 查询本人聊天历史，保留全部消息状态和已落库的部分内容。
     * @param chatId 聊天 ID
     * @param query 有界分页参数
     * @return 创建时间、ID 倒序消息分页
     */
    @PreAuthorize("hasAuthority(T(cn.chomy.ai.backend.enums.AgentPermission).CHAT_MESSAGES.code)")
    IPage<AgentChatMessage> messages(Long chatId, AgentPageQuery query);

    /**
     * 订阅本人聊天的持久化事件流，每个连接维护独立游标以支持多窗口广播。
     * @param chatId 聊天 ID
     * @param lastEventId Redis Stream ID，空值表示从保留流的起点重放
     * @return 无空闲总超时的 SSE 连接，断连或应用关闭时释放后台读取线程
     */
    @PreAuthorize("hasAuthority(T(cn.chomy.ai.backend.enums.AgentPermission).CHAT_SUBSCRIBE.code)")
    SseEmitter start(Long chatId, String lastEventId);
}
