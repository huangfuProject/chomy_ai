package cn.chomy.ai.backend.service.impl;

import cn.chomy.ai.backend.dto.agent.AgentPageQuery;
import cn.chomy.ai.backend.entity.AgentChat;
import cn.chomy.ai.backend.entity.AgentChatMessage;
import cn.chomy.ai.backend.enums.AgentChatMessageStatus;
import cn.chomy.ai.backend.mapper.AgentChatMapper;
import cn.chomy.ai.backend.mapper.AgentChatMessageMapper;
import cn.chomy.ai.backend.security.JwtSessionService;
import cn.chomy.ai.backend.security.UserContext;
import cn.chomy.ai.backend.stream.ChatEventReader;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 验证 Chat 级历史和订阅边界：所有权、生成中内容、空读存活、跨任务事件及断连清理。
 * 真实虚拟线程配合受控 Redis 边界，不创建或执行任何 Agent Task。
 * @author chomy
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class SessionMessageServiceImplTest {
    private AgentChatMapper chats;
    private AgentChatMessageMapper messages;
    private ChatEventReader reader;
    private JwtSessionService sessions;
    private SessionMessageServiceImpl service;
    private MockedStatic<UserContext> user;

    /** 提供当前线程用户及已验签 JWT；后台线程必须使用捕获快照，不能读取此线程局部替身。 */
    @BeforeEach
    void setUp() {
        for (Class<?> type : List.of(AgentChat.class, AgentChatMessage.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "session-test"), type);
        }
        user = mockStatic(UserContext.class); user.when(UserContext::userId).thenReturn(1L);
        chats = mock(AgentChatMapper.class); messages = mock(AgentChatMessageMapper.class);
        reader = mock(ChatEventReader.class); sessions = mock(JwtSessionService.class);
        service = spy(new SessionMessageServiceImpl(chats, messages, reader, sessions, new ObjectMapper()));
        when(chats.selectOne(any(Wrapper.class))).thenReturn(new AgentChat());
        Jwt jwt = Jwt.withTokenValue("test-only").header("alg", "HS256").subject("1").claim("jti", "test")
                .expiresAt(java.time.Instant.now().plusSeconds(300)).build();
        var authentication = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("agent:chat:subscribe")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        when(sessions.authenticate(jwt)).thenReturn(authentication);
    }

    /** 每个用例结束时终止后台订阅并清除认证 ThreadLocal。 */
    @AfterEach
    void tearDown() { service.shutdown(); user.close(); SecurityContextHolder.clearContext(); }

    /** 历史查询不得过滤生成中的 Assistant 部分内容，且所有查询限制到本人聊天。 */
    @Test
    void historyIncludesStreamingContentAndChecksOwnership() {
        AgentChatMessage partial = new AgentChatMessage(); partial.setStatus(AgentChatMessageStatus.STREAMING);
        when(messages.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(call -> {
            Page<AgentChatMessage> page = call.getArgument(0); page.setRecords(List.of(partial)); return page;
        });
        assertThat(service.messages(10L, new AgentPageQuery()).getRecords()).containsExactly(partial);
        ArgumentCaptor<Wrapper> capture = ArgumentCaptor.forClass(Wrapper.class);
        verify(chats).selectOne(capture.capture());
        // MyBatis 条件惰性生成参数，先解析 SQL 才能核对实际绑定的用户与聊天 ID。
        assertThat(capture.getValue().getSqlSegment()).contains("id =", "user_id =");
        assertThat(((AbstractWrapper) capture.getValue()).getParamNameValuePairs()).containsValue(1L).containsValue(10L);
        verify(messages).selectPage(any(Page.class), capture.capture());
        assertThat(capture.getValue().getSqlSegment()).contains("chat_id =", "ORDER BY created_at DESC,id DESC")
                .doesNotContain("status", "role");
        verifyNoInteractions(reader, sessions);
    }

    /** 访问他人或不存在聊天时，历史与订阅均不能触碰消息表或 Redis。 */
    @Test
    void rejectsForeignChatBeforeAccessingMessagesOrRedis() {
        when(chats.selectOne(any(Wrapper.class))).thenReturn(null);
        assertThatThrownBy(() -> service.messages(10L, new AgentPageQuery())).hasMessage("对话不存在或无权访问");
        assertThatThrownBy(() -> service.start(10L, null)).hasMessage("对话不存在或无权访问");
        verifyNoInteractions(messages, reader);
    }

    /** 页码、游标及资源 ID 的业务边界在服务层同样受保护，非法输入不查询消息或 Redis。 */
    @Test
    void validatesPaginationAndResumeCursor() {
        AgentPageQuery query = new AgentPageQuery(); query.setSize(101L);
        assertThatThrownBy(() -> service.messages(10L, query)).hasMessage("分页参数不合法");
        assertThatThrownBy(() -> service.start(0L, null)).hasMessage("对话ID必须大于零");
        for (String cursor : List.of("$", "-1-0", "1", "18446744073709551616-0", "1-0\nevent:bad")) {
            assertThatThrownBy(() -> service.start(10L, cursor)).hasMessage("事件游标不合法");
        }
        verifyNoInteractions(reader, messages);
    }

    /** 建连前 Redis 故障必须返回 503，不启动后台连接或写入任何消息。 */
    @Test
    void failsBeforeConnectingWhenRedisUnavailable() {
        when(reader.read(10L, "0-0", false)).thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("test-only"));
        assertThatThrownBy(() -> service.start(10L, null))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .satisfies(error -> assertThat(((org.springframework.web.server.ResponseStatusException) error).getStatusCode().value()).isEqualTo(503));
        verify(service, never()).newEmitter();
        verifyNoInteractions(messages);
    }

    /** 空读及任务完成事件均不能结束 Chat 订阅；后续任务增量仍可推送，断连后释放读取循环。 */
    @Test
    void staysOpenAcrossEmptyReadsAndTaskCompletionAndStopsOnDisconnect() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class); doReturn(emitter).when(service).newEmitter();
        BlockingQueue<MapRecord<String, String, String>> queue = new LinkedBlockingQueue<>();
        when(reader.read(10L, "0-0", false)).thenReturn(List.of());
        CountDownLatch polled = new CountDownLatch(2);
        when(reader.read(eq(10L), anyString(), eq(true))).thenAnswer(call -> {
            polled.countDown();
            try {
                var event = queue.poll(100, TimeUnit.MILLISECONDS);
                return event == null ? List.of() : List.of(event);
            } catch (InterruptedException exception) { Thread.currentThread().interrupt(); return List.of(); }
        });
        List<String> frames = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            SseEmitter.SseEventBuilder builder = call.getArgument(0);
            frames.add(builder.build().stream().map(part -> part.getData().toString()).reduce("", String::concat));
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        service.start(10L, null);
        assertThat(polled.await(2, TimeUnit.SECONDS)).isTrue();
        verify(emitter, never()).complete();
        queue.add(StreamRecords.newRecord().in(ChatEventReader.key(10L))
                .ofMap(Map.of("event", "task.completed", "data", "{\"taskId\":\"20\"}")).withId(org.springframework.data.redis.connection.stream.RecordId.of("1-0")));
        queue.add(StreamRecords.newRecord().in(ChatEventReader.key(10L))
                .ofMap(Map.of("event", "message.delta", "data", "{\"delta\":\"下一次任务\"}")).withId(org.springframework.data.redis.connection.stream.RecordId.of("2-0")));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (frames.size() < 3 && System.nanoTime() < deadline) { Thread.sleep(10); }
        assertThat(frames).anyMatch(frame -> frame.contains("task.completed") && frame.contains("id:1-0"))
                .anyMatch(frame -> frame.contains("message.delta") && frame.contains("id:2-0"));
        verify(emitter, never()).complete();
        ArgumentCaptor<Runnable> completion = ArgumentCaptor.forClass(Runnable.class);
        verify(emitter).onCompletion(completion.capture()); completion.getValue().run();
        verify(emitter, timeout(2000)).complete();
        verifyNoInteractions(messages);
    }

    /** 长时间没有事件仍发送心跳并复核会话，不创建任务也不结束连接。 */
    @Test
    void sendsIdleHeartbeatAndRevalidatesSession() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class); doReturn(emitter).when(service).newEmitter();
        when(reader.read(10L, "7-0", false)).thenReturn(List.of());
        when(reader.read(10L, "7-0", true)).thenAnswer(call -> {
            try { Thread.sleep(100); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            return List.of();
        });
        CountDownLatch heartbeat = new CountDownLatch(1);
        doAnswer(call -> {
            SseEmitter.SseEventBuilder builder = call.getArgument(0);
            if (builder.build().stream().anyMatch(part -> part.getData().toString().contains(":heartbeat"))) { heartbeat.countDown(); }
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        service.start(10L, "7-0");
        assertThat(heartbeat.await(18, TimeUnit.SECONDS)).isTrue();
        verify(sessions).authenticate(any()); verify(emitter, never()).complete();
        verifyNoInteractions(messages);
    }
}
