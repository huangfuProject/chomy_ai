package cn.chomy.ai.backend.controller.user;

import cn.chomy.ai.backend.dto.agent.AgentPageQuery;
import cn.chomy.ai.backend.entity.AgentChatMessage;
import cn.chomy.ai.backend.service.SessionMessageService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Chat 级消息历史与 SSE 订阅入口，当前用户由服务层从已认证上下文取得。
 * start 仅订阅聊天事件，不创建消息、任务或启动 Agent；发送问题由后续 POST 接口独立负责。
 * @author chomy
 */
@RestController
@RequestMapping("/message")
@RequiredArgsConstructor
public class SessionMessageController {
    /** 历史查询与聊天级事件订阅业务，不在 Controller 操作数据库或 Redis。 */
    private final SessionMessageService sessionMessageService;

    /**
     * 分页读取本人聊天历史，包含正在生成的 Assistant 内容和其他状态的消息。
     * @param chatId 正数聊天 ID
     * @param query 有界分页参数，默认第一页、每页 20 条，最多 100 条
     * @return 按创建时间和 ID 倒序的消息分页，前端展示时可按时间正序排列
     */
    @GetMapping("/{chatId}/messages")
    public IPage<AgentChatMessage> messages(@PathVariable @Positive(message = "对话ID必须大于零") Long chatId,
            @Valid @ModelAttribute AgentPageQuery query) {
        // 所有权验证和历史读取均委托 Service，不按消息状态过滤掉生成中内容。
        return sessionMessageService.messages(chatId, query);
    }

    /**
     * 建立聊天级 SSE 连接，空闲时发送心跳，不因暂无事件或某个任务完成而结束。
     * @param chatId 正数聊天 ID
     * @param lastEventId 可选重连游标，使用 SSE 标准 Last-Event-ID 请求头
     * @return 禁止缓存及代理缓冲的 SSE 响应，不使用普通 JSON 结果包装
     */
    @GetMapping(value = "/{chatId}/start", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> start(@PathVariable @Positive(message = "对话ID必须大于零") Long chatId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        // 建连前完成认证和所有权校验；响应头允许代理及时转发心跳与事件。
        return ResponseEntity.ok().header("Cache-Control", "no-cache, no-transform")
                .header("X-Accel-Buffering", "no").body(sessionMessageService.start(chatId, lastEventId));
    }
}
