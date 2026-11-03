package cn.chomy.ai.backend.service;

import cn.chomy.ai.backend.common.exception.BizException;
import cn.chomy.ai.backend.dto.agent.*;
import cn.chomy.ai.backend.entity.*;
import cn.chomy.ai.backend.enums.*;
import cn.chomy.ai.backend.mapper.*;
import cn.chomy.ai.backend.service.impl.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 项目和对话业务回归，检查用户隔离、批量关联查询、归属校验及关联删除的调用顺序。
 * Mapper 使用受控替身，不连接真实数据库；数据库事务及行锁语义由生产事务管理器执行。
 * @author chomy
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class AgentWorkspaceServiceTest {
    /** 项目存储边界。 */
    private AgentProjectMapper projects;
    /** 对话存储边界。 */
    private AgentChatMapper chats;
    /** 真实项目业务。 */
    private AgentProjectServiceImpl projectService;
    /** 真实对话业务。 */
    private AgentChatServiceImpl chatService;

    /** 初始化真实 MyBatis 字段映射及业务服务，隔离外部数据库。 */
    @BeforeEach
    void setUp() {
        for (Class<?> type : List.of(AgentProject.class, AgentChat.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "agent-test"), type);
        }
        projects = mock(AgentProjectMapper.class);
        chats = mock(AgentChatMapper.class);
        projectService = new AgentProjectServiceImpl(projects, chats);
        chatService = new AgentChatServiceImpl(chats, projects);
    }

    /** 项目分页必须限制当前用户，保留分页元数据，并在创建时间相同时按 ID 稳定倒序。 */
    @Test
    void projectPaginationIsOwnedAndOrdered() {
        AgentProject row = project(10L, "项目");
        when(projects.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(call -> {
            Page<AgentProject> page = call.getArgument(0);
            page.setTotal(5); page.setRecords(List.of(row)); return page;
        });
        AgentPageQuery query = new AgentPageQuery(); query.setCurrent(2L); query.setSize(2L);
        var result = projectService.pageProjects(1L, query);
        assertThat(result.getCurrent()).isEqualTo(2);
        assertThat(result.getSize()).isEqualTo(2);
        assertThat(result.getTotal()).isEqualTo(5);
        assertThat(result.getRecords()).containsExactly(row);
        // 检查真正传入 Mapper 的条件，防止替身返回值掩盖缺失的用户隔离。
        ArgumentCaptor<Wrapper> capture = ArgumentCaptor.forClass(Wrapper.class);
        verify(projects).selectPage(any(Page.class), capture.capture());
        assertOwned(capture.getValue(), "user_id", 1L);
        assertThat(capture.getValue().getSqlSegment()).contains("ORDER BY created_at DESC,id DESC");
        verifyNoInteractions(chats);
    }

    /** 默认分页和空页必须正常返回，不额外查询对话或更改页码。 */
    @Test
    void projectPaginationSupportsEmptyPage() {
        when(projects.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(call -> call.getArgument(0));
        var result = projectService.pageProjects(1L, new AgentPageQuery());
        assertThat(result.getCurrent()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(20);
        assertThat(result.getTotal()).isZero();
        assertThat(result.getRecords()).isEmpty();
        verifyNoInteractions(chats);
    }

    /** 内部调用的身份、空分页参数及上下界校验均须在访问数据库前完成。 */
    @Test
    void projectPaginationRejectsInvalidParameters() {
        for (Long userId : new Long[]{null, 0L, -1L}) {
            assertThatThrownBy(() -> projectService.pageProjects(userId, new AgentPageQuery())).hasMessage("用户不合法");
        }
        assertThatThrownBy(() -> projectService.pageProjects(1L, null)).hasMessage("分页参数不合法");
        for (Long current : new Long[]{null, 0L, -1L, (long) Integer.MAX_VALUE + 1}) {
            AgentPageQuery query = new AgentPageQuery(); query.setCurrent(current);
            assertThatThrownBy(() -> projectService.pageProjects(1L, query)).hasMessage("分页参数不合法");
        }
        for (Long size : new Long[]{null, 0L, -1L, 101L}) {
            AgentPageQuery query = new AgentPageQuery(); query.setSize(size);
            assertThatThrownBy(() -> projectService.pageProjects(1L, query)).hasMessage("分页参数不合法");
        }
        verifyNoInteractions(projects, chats);
    }

    /** 修改必须同时限制项目和所有者，仅更新名称和介绍，且可显式清空介绍。 */
    @Test
    void updatesOnlyOwnedProjectFields() {
        when(projects.update(isNull(), any(Wrapper.class))).thenReturn(1);
        assertThat(projectService.updateProject(1L, 10L, new UpdateAgentProjectRequest(" 新名称 ", "")))
                .isEqualTo("项目已修改");
        ArgumentCaptor<LambdaUpdateWrapper> capture = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(projects).update(isNull(), capture.capture());
        var update = capture.getValue();
        assertOwned(update, "id", 10L); assertOwned(update, "user_id", 1L);
        // SQL SET 列表应只含两个公开字段，NULL 必须真正写入数据库。
        assertThat(update.getSqlSet()).contains("project_name=", "description=").doesNotContain("user_id", "created_at");
        assertThat(update.getParamNameValuePairs()).containsValue("新名称").containsValue(null);
        verifyNoInteractions(chats);
    }

    /** 项目修改不区分不存在与他人所有，失败时不得触碰对话。 */
    @Test
    void rejectsUnownedProjectUpdate() {
        assertThatThrownBy(() -> projectService.updateProject(1L, 10L, new UpdateAgentProjectRequest("名称", null)))
                .hasMessage("项目不存在或无权访问");
        verifyNoInteractions(chats);
    }

    /** 重命名无归档及类型过滤，只更新名称并同时限制所有者与对话 ID。 */
    @Test
    void renamesOnlyOwnedChatName() {
        when(chats.update(isNull(), any(Wrapper.class))).thenReturn(1);
        assertThat(chatService.renameChat(1L, 100L, new RenameAgentChatRequest(" 新对话 "))).isEqualTo("对话已重命名");
        ArgumentCaptor<LambdaUpdateWrapper> capture = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(chats).update(isNull(), capture.capture());
        var update = capture.getValue();
        assertOwned(update, "id", 100L); assertOwned(update, "user_id", 1L);
        assertThat(update.getSqlSegment()).doesNotContain("archived", "type", "project_id");
        assertThat(update.getSqlSet()).startsWith("chat_name=").doesNotContain(",", "project_id", "archived", "created_at", "user_id");
        assertThat(update.getParamNameValuePairs()).containsValue("新对话");
        verifyNoInteractions(projects);
    }

    /** 他人或不存在的对话不能重命名，不查询或修改项目。 */
    @Test
    void rejectsUnownedChatRename() {
        assertThatThrownBy(() -> chatService.renameChat(1L, 100L, new RenameAgentChatRequest("新对话")))
                .hasMessage("对话不存在或无权访问");
        verifyNoInteractions(projects);
    }

    /** 修改业务的服务层校验拒绝空白、超长名称和介绍以及非法身份与资源标识。 */
    @Test
    void updateServicesValidateInputsBeforeWriting() {
        assertThatThrownBy(() -> projectService.updateProject(null, 10L, new UpdateAgentProjectRequest("名称", null)))
                .hasMessage("用户不合法");
        assertThatThrownBy(() -> chatService.renameChat(1L, 0L, new RenameAgentChatRequest("名称")))
                .hasMessage("对话不合法");
        assertThatThrownBy(() -> projectService.updateProject(1L, 0L, new UpdateAgentProjectRequest("名称", null)))
                .hasMessage("项目不合法");
        assertThatThrownBy(() -> chatService.renameChat(null, 10L, new RenameAgentChatRequest("名称")))
                .hasMessage("用户不合法");
        assertThatThrownBy(() -> projectService.updateProject(1L, 10L, null)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> chatService.renameChat(1L, 10L, null)).isInstanceOf(BizException.class);
        for (String name : new String[]{null, "", "  ", "a".repeat(129)}) {
            assertThatThrownBy(() -> projectService.updateProject(1L, 10L, new UpdateAgentProjectRequest(name, null)))
                    .isInstanceOf(BizException.class);
            assertThatThrownBy(() -> chatService.renameChat(1L, 10L, new RenameAgentChatRequest(name)))
                    .isInstanceOf(BizException.class);
        }
        assertThatThrownBy(() -> projectService.updateProject(1L, 10L, new UpdateAgentProjectRequest("名称", "a".repeat(2001))))
                .hasMessage("项目介绍不能超过2000个字符");
        verifyNoInteractions(projects, chats);
    }

    /** HTTP 创建表单只决定名称及项目，归属类型与初始归档状态由服务端推导。 */
    @Test
    void createsChatFromWhitelistRequest() {
        when(chats.insert(any(AgentChat.class))).thenAnswer(call -> {
            AgentChat row = call.getArgument(0); row.setId(100L); return 1;
        });
        when(chats.selectById(100L)).thenReturn(chat(100L, null));
        chatService.createChat(1L, new CreateAgentChatRequest(" 新聊天 ", null));
        ArgumentCaptor<AgentChat> capture = ArgumentCaptor.forClass(AgentChat.class);
        verify(chats).insert(capture.capture());
        assertThat(capture.getValue().getUserId()).isEqualTo(1L);
        assertThat(capture.getValue().getChatName()).isEqualTo("新聊天");
        assertThat(capture.getValue().getProjectId()).isNull();
        assertThat(capture.getValue().getType()).isEqualTo(AgentChatType.STANDALONE);
        assertThat(capture.getValue().getArchived()).isEqualTo(AgentChatArchiveStatus.UNARCHIVED);
        verifyNoInteractions(projects);
    }

    /** 创建入口不能关联他人或不存在的项目，也不能接受空表单、非法项目 ID 或名称。 */
    @Test
    void rejectsInvalidChatCreateRequest() {
        assertThatThrownBy(() -> chatService.createChat(1L, new CreateAgentChatRequest("聊天", 50L)))
                .hasMessage("项目不存在或无权访问");
        assertThatThrownBy(() -> chatService.createChat(1L, new CreateAgentChatRequest("聊天", 0L)))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> chatService.createChat(1L, new CreateAgentChatRequest(" ", null)))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> chatService.createChat(1L, (CreateAgentChatRequest) null)).isInstanceOf(BizException.class);
        verifyNoInteractions(chats);
    }

    /** 归档和恢复只改归档字段，所有权始终受限，重复调用无需预先读取聊天。 */
    @Test
    void archiveAndRestoreOnlyChangeOwnedStatus() {
        when(chats.update(isNull(), any(Wrapper.class))).thenReturn(1);
        for (int i = 0; i < 2; i++) {
            assertThat(chatService.archiveChat(1L, 100L)).isEqualTo("对话已归档");
            assertThat(chatService.restoreChat(1L, 100L)).isEqualTo("对话已恢复");
        }
        ArgumentCaptor<LambdaUpdateWrapper> capture = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(chats, times(4)).update(isNull(), capture.capture());
        for (int i = 0; i < 4; i++) {
            var update = capture.getAllValues().get(i);
            assertOwned(update, "id", 100L); assertOwned(update, "user_id", 1L);
            assertThat(update.getSqlSet()).startsWith("archived=").doesNotContain(",");
            assertThat(update.getParamNameValuePairs()).containsValue(i % 2 == 0
                    ? AgentChatArchiveStatus.ARCHIVED : AgentChatArchiveStatus.UNARCHIVED);
        }
        verifyNoInteractions(projects);
    }

    /** 移动先锁本人目标项目再更新聊天，项目 ID 和归属类型同步修改，原归档状态保持。 */
    @Test
    void movesChatUnderTargetProjectLock() {
        when(projects.selectOne(any(Wrapper.class))).thenReturn(project(20L, "目标项目"));
        when(chats.update(isNull(), any(Wrapper.class))).thenReturn(1);
        assertThat(chatService.moveChat(1L, 100L, new MoveAgentChatRequest(20L))).isEqualTo("对话已移动");
        var order = inOrder(projects, chats);
        ArgumentCaptor<Wrapper> lock = ArgumentCaptor.forClass(Wrapper.class);
        order.verify(projects).selectOne(lock.capture());
        assertOwned(lock.getValue(), "id", 20L); assertOwned(lock.getValue(), "user_id", 1L);
        assertThat(lock.getValue().getSqlSegment()).contains("FOR UPDATE");
        ArgumentCaptor<LambdaUpdateWrapper> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        order.verify(chats).update(isNull(), update.capture());
        assertOwned(update.getValue(), "id", 100L); assertOwned(update.getValue(), "user_id", 1L);
        assertThat(update.getValue().getSqlSet()).contains("project_id=", "type=").doesNotContain("archived", "created_at", "chat_name");
        assertThat(update.getValue().getParamNameValuePairs()).containsValue(20L).containsValue(AgentChatType.PROJECT);
    }

    /** 移出项目必须显式写入 NULL 并改为无项目类型，无需查询项目表。 */
    @Test
    void movesChatToStandalone() {
        when(chats.update(isNull(), any(Wrapper.class))).thenReturn(1);
        chatService.moveChat(1L, 100L, new MoveAgentChatRequest(null));
        ArgumentCaptor<LambdaUpdateWrapper> capture = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(chats).update(isNull(), capture.capture());
        assertOwned(capture.getValue(), "id", 100L); assertOwned(capture.getValue(), "user_id", 1L);
        assertThat(capture.getValue().getSqlSet()).contains("project_id=", "type=").doesNotContain("archived");
        assertThat(capture.getValue().getParamNameValuePairs()).containsValue(null).containsValue(AgentChatType.STANDALONE);
        verifyNoInteractions(projects);
    }

    /** 目标项目不存在或不属于本人时不得更新聊天。 */
    @Test
    void rejectsForeignMoveTargetBeforeWriting() {
        assertThatThrownBy(() -> chatService.moveChat(1L, 100L, new MoveAgentChatRequest(20L)))
                .hasMessage("项目不存在或无权访问");
        verifyNoInteractions(chats);
    }

    /** 归档、恢复和移动对不存在或他人聊天统一失败，不能假报成功。 */
    @Test
    void lifecycleRejectsUnownedChats() {
        assertThatThrownBy(() -> chatService.archiveChat(1L, 100L)).hasMessage("对话不存在或无权访问");
        assertThatThrownBy(() -> chatService.restoreChat(1L, 100L)).hasMessage("对话不存在或无权访问");
        assertThatThrownBy(() -> chatService.moveChat(1L, 100L, new MoveAgentChatRequest(null)))
                .hasMessage("对话不存在或无权访问");
        verifyNoInteractions(projects);
    }

    /** 内部入口同样校验身份、聊天 ID 和目标项目边界，非法请求不得触碰存储。 */
    @Test
    void lifecycleValidatesIdsAndNullRequest() {
        assertThatThrownBy(() -> chatService.archiveChat(null, 100L)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> chatService.restoreChat(1L, 0L)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> chatService.moveChat(1L, 100L, null)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> chatService.moveChat(1L, 100L, new MoveAgentChatRequest(-1L))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> chatService.moveChat(0L, 100L, new MoveAgentChatRequest(null))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> chatService.moveChat(1L, 0L, new MoveAgentChatRequest(null))).isInstanceOf(BizException.class);
        verifyNoInteractions(projects, chats);
    }

    /** 同名项目不得判重，所有者由认证参数指定，并返回数据库创建时间。 */
    @Test
    void allowsDuplicateProjectNames() {
        when(projects.insert(any(AgentProject.class))).thenAnswer(call -> {
            AgentProject row = call.getArgument(0); row.setId(10L); return 1;
        });
        AgentProject stored = project(10L, "同名项目");
        when(projects.selectById(10L)).thenReturn(stored);
        for (int i = 0; i < 2; i++) {
            assertThat(projectService.createProject(1L, new CreateAgentProjectRequest(" 同名项目 ", "介绍"))).isSameAs(stored);
        }
        ArgumentCaptor<AgentProject> capture = ArgumentCaptor.forClass(AgentProject.class);
        verify(projects, times(2)).insert(capture.capture());
        assertThat(capture.getAllValues()).allSatisfy(row -> {
            assertThat(row.getUserId()).isEqualTo(1L);
            assertThat(row.getProjectName()).isEqualTo("同名项目");
        });
        verify(projects, never()).selectCount(any());
    }

    /** 拒绝无效身份和空白名称，服务层也不能绕过表单边界。 */
    @Test
    void rejectsInvalidProjectBeforeWriting() {
        assertThatThrownBy(() -> projectService.createProject(null, new CreateAgentProjectRequest("项目", null))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> projectService.createProject(1L, new CreateAgentProjectRequest("  ", null))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> projectService.createProject(1L, new CreateAgentProjectRequest("a".repeat(129), null))).isInstanceOf(BizException.class);
        verifyNoInteractions(projects, chats);
    }

    /** 无所有权和记录不存在使用同一错误，不应继续删除任何对话。 */
    @Test
    void cannotDeleteForeignProject() {
        when(projects.selectOne(any(Wrapper.class))).thenReturn(null);
        assertThatThrownBy(() -> projectService.deleteProject(1L, 20L)).hasMessage("项目不存在或无权访问");
        ArgumentCaptor<Wrapper> capture = ArgumentCaptor.forClass(Wrapper.class);
        verify(projects).selectOne(capture.capture());
        assertOwned(capture.getValue(), "user_id", 1L);
        assertThat(capture.getValue().getSqlSegment()).contains("FOR UPDATE", "id =");
        verifyNoInteractions(chats);
        verify(projects, never()).delete(any(Wrapper.class));
    }

    /** 项目行锁、全部对话删除、项目删除依次执行，不能遗漏归档对话。 */
    @Test
    void deletesAllProjectChatsBeforeProject() {
        when(projects.selectOne(any(Wrapper.class))).thenReturn(project(10L, "项目"));
        when(projects.delete(any(Wrapper.class))).thenReturn(1);
        assertThat(projectService.deleteProject(1L, 10L)).contains("全部对话");
        var order = inOrder(projects, chats);
        order.verify(projects).selectOne(any(Wrapper.class));
        ArgumentCaptor<Wrapper> capture = ArgumentCaptor.forClass(Wrapper.class);
        order.verify(chats).delete(capture.capture());
        // 删除条件仅按项目标识，覆盖所有归档状态和类型，清理整个关联集合。
        assertThat(capture.getValue().getSqlSegment()).contains("project_id =").doesNotContain("archived", "type");
        order.verify(projects).delete(any(Wrapper.class));
    }

    /** 对话单条删除必须同时包含用户条件，不存在或属于他人时不能报告成功。 */
    @Test
    void chatDeletionAlwaysChecksOwner() {
        when(chats.delete(any(Wrapper.class))).thenReturn(0);
        assertThatThrownBy(() -> chatService.deleteChat(1L, 99L)).hasMessage("对话不存在或无权访问");
        ArgumentCaptor<Wrapper> capture = ArgumentCaptor.forClass(Wrapper.class);
        verify(chats).delete(capture.capture());
        assertOwned(capture.getValue(), "user_id", 1L);
        assertOwned(capture.getValue(), "id", 99L);
        verifyNoInteractions(projects);
    }

    /** 归档页混合有项目和无项目对话，只查询一次项目名称且保留分页元数据。 */
    @Test
    void archivedPageIncludesNullableProjectNameAndStableOrder() {
        AgentChat first = chat(101L, 10L); AgentChat second = chat(100L, null);
        when(chats.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(call -> {
            Page<AgentChat> page = call.getArgument(0);
            page.setTotal(41); page.setRecords(List.of(first, second)); return page;
        });
        when(projects.selectList(any(Wrapper.class))).thenReturn(List.of(project(10L, "同名项目")));
        AgentPageQuery query = new AgentPageQuery(); query.setCurrent(2L); query.setSize(2L);
        var result = chatService.archivedChats(1L, query);
        assertThat(result.getTotal()).isEqualTo(41); assertThat(result.getCurrent()).isEqualTo(2);
        assertThat(result.getSize()).isEqualTo(2);
        assertThat(result.getRecords()).extracting(AgentChatView::projectName).containsExactly("同名项目", null);
        ArgumentCaptor<Wrapper> capture = ArgumentCaptor.forClass(Wrapper.class);
        verify(chats).selectPage(any(Page.class), capture.capture());
        assertOwned(capture.getValue(), "user_id", 1L);
        assertOwned(capture.getValue(), "archived", AgentChatArchiveStatus.ARCHIVED);
        assertThat(capture.getValue().getSqlSegment()).contains("ORDER BY created_at DESC,id DESC");
        verify(projects).selectList(capture.capture());
        assertOwned(capture.getValue(), "user_id", 1L);
        assertThat(capture.getValue().getSqlSegment()).contains("id IN");
    }

    /** 非项目分页必须同时要求空项目 ID 和无项目类型，空页不查询项目表。 */
    @Test
    void standalonePageUsesBothMembershipConditions() {
        when(chats.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(call -> call.getArgument(0));
        assertThat(chatService.standaloneChats(1L, new AgentPageQuery()).getRecords()).isEmpty();
        ArgumentCaptor<Wrapper> capture = ArgumentCaptor.forClass(Wrapper.class);
        verify(chats).selectPage(any(Page.class), capture.capture());
        assertOwned(capture.getValue(), "user_id", 1L);
        assertOwned(capture.getValue(), "type", AgentChatType.STANDALONE);
        assertThat(capture.getValue().getSqlSegment()).contains("project_id IS NULL").doesNotContain("archived");
        verifyNoInteractions(projects);
    }

    /** 查询项目页前必须验证项目所有者，拒绝后不得继续查询对话。 */
    @Test
    void projectPageRejectsForeignProject() {
        assertThatThrownBy(() -> chatService.projectChats(1L, 50L, new AgentPageQuery())).hasMessage("项目不存在或无权访问");
        verifyNoInteractions(chats);
    }

    /** 内部创建也必须严格遵守类型和项目 ID 的互斥规则，不能将空类型转换为默认值。 */
    @Test
    void rejectsInconsistentChatMembership() {
        assertThatThrownBy(() -> chatService.createChat(1L, null, "对话", null)).hasMessage("对话类型不能为空");
        assertThatThrownBy(() -> chatService.createChat(1L, null, "对话", AgentChatType.PROJECT)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> chatService.createChat(1L, 10L, "对话", AgentChatType.STANDALONE)).hasMessage("无项目对话不能指定项目");
        verifyNoInteractions(projects, chats);
    }

    /** 不得为他人项目创建对话，项目检查失败后不允许写入。 */
    @Test
    void cannotCreateChatInForeignProject() {
        assertThatThrownBy(() -> chatService.createChat(1L, 10L, "对话", AgentChatType.PROJECT)).hasMessage("项目不存在或无权访问");
        verifyNoInteractions(chats);
    }

    /** 同名对话允许重复写入，项目行锁必须先于插入，以便与项目删除互斥。 */
    @Test
    void createsDuplicateChatNamesUnderProjectLock() {
        when(projects.selectOne(any(Wrapper.class))).thenReturn(project(10L, "项目"));
        when(chats.insert(any(AgentChat.class))).thenAnswer(call -> {
            AgentChat row = call.getArgument(0); row.setId(100L); return 1;
        });
        when(chats.selectById(100L)).thenReturn(chat(100L, 10L));
        for (int i = 0; i < 2; i++) { chatService.createChat(1L, 10L, "同名对话", AgentChatType.PROJECT); }
        var order = inOrder(projects, chats);
        ArgumentCaptor<Wrapper> lock = ArgumentCaptor.forClass(Wrapper.class);
        order.verify(projects).selectOne(lock.capture());
        assertOwned(lock.getValue(), "user_id", 1L);
        assertThat(lock.getValue().getSqlSegment()).contains("FOR UPDATE");
        order.verify(chats).insert(any(AgentChat.class));
        ArgumentCaptor<AgentChat> inserted = ArgumentCaptor.forClass(AgentChat.class);
        verify(chats, times(2)).insert(inserted.capture());
        assertThat(inserted.getAllValues()).allSatisfy(row -> {
            assertThat(row.getChatName()).isEqualTo("同名对话");
            assertThat(row.getArchived()).isEqualTo(AgentChatArchiveStatus.UNARCHIVED);
            assertThat(row.getUserId()).isEqualTo(1L);
        });
    }

    /** 分页规模在服务内部同样受限，不能以超大页码或空规模绕过 HTTP 校验。 */
    @Test
    void rejectsInvalidPageParameters() {
        for (long size : new long[]{0, 101}) {
            AgentPageQuery query = new AgentPageQuery(); query.setSize(size);
            assertThatThrownBy(() -> chatService.archivedChats(1L, query)).hasMessage("分页参数不合法");
        }
        AgentPageQuery query = new AgentPageQuery(); query.setCurrent(null);
        assertThatThrownBy(() -> chatService.standaloneChats(1L, query)).hasMessage("分页参数不合法");
        verifyNoInteractions(projects, chats);
    }

    /**
     * 核对 SQL 字段条件和绑定值，避免测试替身无视查询条件而掩盖越权。
     * @param wrapper 实际生成的 MyBatis 条件
     * @param column 应限制的字段
     * @param value 期望绑定值
     */
    private void assertOwned(Wrapper wrapper, String column, Object value) {
        String sql = wrapper.getSqlSegment();
        assertThat(sql).contains(column + " =");
        assertThat(((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs()).containsValue(value);
    }

    /**
     * 构造固定用户的项目记录。
     * @param id 项目 ID
     * @param name 允许重复的名称
     * @return 测试项目
     */
    private AgentProject project(Long id, String name) {
        AgentProject row = new AgentProject(); row.setId(id); row.setUserId(1L); row.setProjectName(name);
        row.setCreatedAt(OffsetDateTime.parse("2026-11-01T12:00:00+08:00")); return row;
    }

    /**
     * 构造有或无项目归属的归档对话。
     * @param id 对话 ID
     * @param projectId 可空项目 ID
     * @return 归档测试对话
     */
    private AgentChat chat(Long id, Long projectId) {
        AgentChat row = new AgentChat(); row.setId(id); row.setUserId(1L); row.setProjectId(projectId);
        row.setChatName("同名对话"); row.setType(projectId == null ? AgentChatType.STANDALONE : AgentChatType.PROJECT);
        row.setArchived(AgentChatArchiveStatus.ARCHIVED); return row;
    }
}
