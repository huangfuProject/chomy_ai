package cn.chomy.ai.backend.service.impl;

import cn.chomy.ai.backend.common.exception.BizException;
import cn.chomy.ai.backend.dto.agent.AgentPageQuery;
import cn.chomy.ai.backend.entity.AgentChat;
import cn.chomy.ai.backend.entity.AgentChatMessage;
import cn.chomy.ai.backend.enums.AgentPermission;
import cn.chomy.ai.backend.mapper.AgentChatMapper;
import cn.chomy.ai.backend.mapper.AgentChatMessageMapper;
import cn.chomy.ai.backend.security.JwtSessionService;
import cn.chomy.ai.backend.security.UserContext;
import cn.chomy.ai.backend.service.SessionMessageService;
import cn.chomy.ai.backend.stream.ChatEventReader;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Chat 级历史读取及 SSE 订阅，不包含消息创建、Task 创建或 Agent 调度。
 * 每个连接使用独立虚拟线程与 Stream 游标，XREAD 空结果继续等待，任务终态事件不结束会话订阅。
 * @author chomy
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionMessageServiceImpl implements SessionMessageService {
    /** 通过聊天校验所属用户，不接受消息中的任意 userId。 */
    private final AgentChatMapper chatMapper;
    /** PostgreSQL 历史消息读取边界，保留全部状态。 */
    private final AgentChatMessageMapper messageMapper;
    /** 只读 Redis Streams 边界，建连及轮询不写 Redis。 */
    private final ChatEventReader eventReader;
    /** 心跳时复核会话、密码版本及账户状态，撤销登录后不继续推送。 */
    private final JwtSessionService sessions;
    /** 采用应用统一 JSON 配置序列化 Stream 中的 JSON 数据。 */
    private final ObjectMapper objectMapper;
    /** 本实例活动连接注册表，仅用于断连及关闭时清理，不共享消费游标。 */
    private final Map<UUID, Subscription> subscriptions = new ConcurrentHashMap<>();
    /** 应用关闭后拒绝建立新的后台订阅。 */
    private final AtomicBoolean stopping = new AtomicBoolean();
    /** 固定每 15 秒发送心跳并重新检查授权，使用单调时钟避免系统时间调整影响心跳间隔。 */
    private static final long HEARTBEAT_NANOS = java.time.Duration.ofSeconds(15).toNanos();

    /**
     * 分页读取当前用户聊天消息，不过滤生成中、失败或已取消消息。
     * @param chatId 聊天 ID
     * @param query 有界分页参数
     * @return 按创建时间、ID 倒序的消息分页
     */
    @Override
    public IPage<AgentChatMessage> messages(Long chatId, AgentPageQuery query) {
        Long userId = UserContext.userId();
        requireOwner(userId, chatId);
        // 服务层同样限制页码及规模，内部调用不能绕过 MVC 参数校验。
        if (query == null || query.getCurrent() == null || query.getCurrent() < 1
                || query.getCurrent() > Integer.MAX_VALUE || query.getSize() == null
                || query.getSize() < 1 || query.getSize() > 100) { throw new BizException("分页参数不合法"); }
        return messageMapper.selectPage(new Page<>(query.getCurrent(), query.getSize()),
                Wrappers.lambdaQuery(AgentChatMessage.class).eq(AgentChatMessage::getChatId, chatId)
                        .orderByDesc(AgentChatMessage::getCreatedAt).orderByDesc(AgentChatMessage::getId));
    }

    /**
     * 完成所有权、游标及 Redis 可用性检查后建立聊天级 SSE，不依赖是否存在 Task。
     * @param chatId 聊天 ID
     * @param lastEventId 重连游标，未提供时从 0-0 重放保留事件
     * @return SSE 连接，空闲时持续存活直到断连、授权失效、基础设施故障或应用关闭
     */
    @Override
    public SseEmitter start(Long chatId, String lastEventId) {
        Long userId = UserContext.userId();
        requireOwner(userId, chatId);
        String cursor = validateCursor(lastEventId);
        // 显式捕获已验签 JWT，后台线程不继承 UserContext 或 SecurityContext，防止线程身份泄漏。
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken principal)) { throw new AccessDeniedException("认证主体不合法"); }
        if (stopping.get()) { throw new IllegalStateException("服务正在关闭"); }
        // 非阻塞读取先检查 Redis 可用性，故障不能伪装成正常空流；不存在的 Stream 是合法空订阅。
        List<MapRecord<String, String, String>> initial;
        try {
            initial = eventReader.read(chatId, cursor, false);
        } catch (org.springframework.dao.DataAccessException exception) {
            // 建连前故障返回 HTTP 503，不能创建一个看似成功但永远没有事件的 SSE。
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "事件订阅服务暂不可用", exception);
        }
        SseEmitter emitter = newEmitter();
        Subscription subscription = new Subscription(userId, chatId, principal.getToken(), cursor, initial, emitter);
        // 回调和注册先于线程启动，避免快速断连或容器关闭遗漏后台线程。
        emitter.onCompletion(subscription::stop);
        emitter.onTimeout(subscription::stop);
        emitter.onError(error -> subscription.stop());
        subscriptions.put(subscription.id, subscription);
        if (stopping.get()) {
            subscriptions.remove(subscription.id);
            subscription.stop();
            emitter.complete();
        } else { subscription.worker.start(); }
        return emitter;
    }

    /**
     * 创建没有总空闲超时的 SSE，心跳负责探测客户端断开。
     * @return 新连接，超时 0 表示不因暂时无消息结束
     */
    protected SseEmitter newEmitter() { return new SseEmitter(0L); }

    /**
     * 校验查询资源归属，对不存在和他人聊天使用相同提示。
     * @param userId 当前用户 ID
     * @param chatId 聊天 ID
     */
    private void requireOwner(Long userId, Long chatId) {
        if (chatId == null || chatId <= 0) { throw new BizException("对话ID必须大于零"); }
        if (chatMapper.selectOne(Wrappers.lambdaQuery(AgentChat.class)
                .eq(AgentChat::getId, chatId).eq(AgentChat::getUserId, userId)) == null) {
            throw new BizException("对话不存在或无权访问");
        }
    }

    /**
     * 只接受 Redis 无符号 64 位数对形式的 Stream ID，拒绝 $、负数或任意命令片段。
     * @param cursor SSE Last-Event-ID
     * @return 已校验游标，未提供时使用 0-0
     */
    private String validateCursor(String cursor) {
        if (cursor == null || cursor.isEmpty()) { return "0-0"; }
        if (!cursor.matches("[0-9]{1,20}-[0-9]{1,20}")) { throw new BizException("事件游标不合法"); }
        try {
            String[] parts = cursor.split("-");
            Long.parseUnsignedLong(parts[0]); Long.parseUnsignedLong(parts[1]);
        } catch (NumberFormatException exception) { throw new BizException("事件游标不合法"); }
        return cursor;
    }

    /**
     * 持续推送保留事件和新增事件；心跳使用注释帧，不改变重连游标。
     * @param subscription 当前连接及独立游标
     */
    private void pump(Subscription subscription) {
        try {
            if (subscription.closed.get()) { return; }
            subscription.emitter.send(SseEmitter.event().name("ready").data(Map.of("chatId", subscription.chatId.toString())));
            long heartbeatAt = System.nanoTime();
            var records = subscription.initial;
            while (!subscription.closed.get()) {
                // 即使持续有事件，也定期检查会话及聊天归属；过期或撤销后停止推送。
                if (System.nanoTime() - heartbeatAt >= HEARTBEAT_NANOS) {
                    verifySession(subscription);
                    subscription.emitter.send(SseEmitter.event().comment("heartbeat"));
                    heartbeatAt = System.nanoTime();
                }
                for (var record : records) {
                    if (subscription.closed.get()) { break; }
                    sendRecord(subscription.emitter, record);
                    // 发送成功后才推进本连接游标；重连可能重复但不会因读取后断连跳过事件。
                    subscription.cursor = record.getId().getValue();
                }
                if (!subscription.closed.get()) {
                    // XREAD 最多阻塞 1 秒；空集合继续等待，不因 done/error 等任务事件退出。
                    records = eventReader.read(subscription.chatId, subscription.cursor, true);
                }
            }
        } catch (IOException exception) {
            // 写入失败通常意味着浏览器离开，直接结束本订阅，不影响后台任务或其他窗口。
        } catch (RuntimeException exception) {
            if (!subscription.closed.get()) {
                log.warn("聊天事件订阅结束，chatId={}", subscription.chatId, exception);
                try {
                    subscription.emitter.send(SseEmitter.event().name("subscription-error")
                            .data(Map.of("message", "订阅已中断，请重新连接并同步历史消息")));
                } catch (IOException | IllegalStateException ignored) { /* 连接已不可写，无需再次发送。 */ }
            }
        } finally {
            // 关闭只清理连接与读取线程，不确认、删除 Redis 事件，不修改消息或 Task。
            subscription.stop();
            subscriptions.remove(subscription.id);
            subscription.emitter.complete();
        }
    }

    /**
     * 心跳期间重新验证会话与资源归属；不向工作线程注入请求上下文。
     * @param subscription 已捕获认证快照的连接
     */
    private void verifySession(Subscription subscription) {
        if (subscription.jwt.getExpiresAt() == null || !subscription.jwt.getExpiresAt().isAfter(Instant.now())) {
            throw new AccessDeniedException("登录已过期");
        }
        var current = sessions.authenticate(subscription.jwt);
        if (current.getAuthorities().stream().noneMatch(authority ->
                AgentPermission.CHAT_SUBSCRIBE.getCode().equals(authority.getAuthority()))) {
            throw new AccessDeniedException("订阅权限已失效");
        }
        requireOwner(subscription.userId, subscription.chatId);
    }

    /**
     * 将 Redis 标准字段转为 SSE，Stream ID 作为重连 ID，任务状态事件只作为普通数据推送。
     * @param emitter 当前 SSE
     * @param record Redis 记录，event 可省略默认 message，data 必须为单个合法 JSON 值
     * @throws IOException 客户端连接不可写
     */
    private void sendRecord(SseEmitter emitter, MapRecord<String, String, String> record) throws IOException {
        String event = record.getValue().getOrDefault("event", "message");
        String data = record.getValue().get("data");
        if (!event.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,63}") || data == null) {
            throw new IllegalStateException("聊天事件格式不合法");
        }
        JsonNode payload;
        try {
            payload = objectMapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(data);
        } catch (JsonProcessingException exception) {
            // 不携带可能包含消息正文的 Jackson 异常，避免原始内容进入日志。
            throw new IllegalStateException("聊天事件 data 不是合法 JSON");
        }
        if (payload == null || payload.isMissingNode()) { throw new IllegalStateException("聊天事件 data 不能为空"); }
        emitter.send(SseEmitter.event().id(record.getId().getValue()).name(event).data(payload));
    }

    /** 应用关闭时停止全部订阅，避免虚拟线程继续等待 Redis；不取消任何 Agent 任务。 */
    @PreDestroy
    public void shutdown() {
        stopping.set(true);
        subscriptions.values().forEach(subscription -> { subscription.stop(); subscription.emitter.complete(); });
        subscriptions.clear();
    }

    /**
     * 单个 SSE 连接状态，游标仅由其虚拟线程更新，关闭信号可来自容器回调或应用关闭。
     * @author chomy
     */
    private final class Subscription {
        private final UUID id = UUID.randomUUID();
        private final Long userId;
        private final Long chatId;
        private final Jwt jwt;
        private final List<MapRecord<String, String, String>> initial;
        private final SseEmitter emitter;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Thread worker;
        private String cursor;

        /**
         * 捕获不可变认证及初始读取结果，不从后台线程读取请求 ThreadLocal。
         * @param userId 当前认证用户
         * @param chatId 已验证归属的聊天
         * @param jwt 已验签 JWT，用于周期性会话验证
         * @param cursor 本连接初始游标
         * @param initial 初始事件批次
         * @param emitter SSE 响应
         */
        private Subscription(Long userId, Long chatId, Jwt jwt, String cursor,
                List<MapRecord<String, String, String>> initial, SseEmitter emitter) {
            this.userId = userId; this.chatId = chatId; this.jwt = jwt; this.cursor = cursor;
            this.initial = initial; this.emitter = emitter;
            this.worker = Thread.ofVirtual().name("chat-sse-" + id).unstarted(() -> pump(this));
        }

        /** 幂等通知后台读取结束，当前线程退出时不自我中断，避免影响最后的容器清理。 */
        private void stop() {
            if (closed.compareAndSet(false, true) && Thread.currentThread() != worker) { worker.interrupt(); }
        }
    }
}
