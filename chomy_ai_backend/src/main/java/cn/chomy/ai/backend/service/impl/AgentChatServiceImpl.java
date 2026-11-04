package cn.chomy.ai.backend.service.impl;

import cn.chomy.ai.backend.security.UserContext;
import cn.chomy.ai.backend.common.exception.BizException;
import cn.chomy.ai.backend.dto.agent.*;
import cn.chomy.ai.backend.entity.AgentChat;
import cn.chomy.ai.backend.entity.AgentProject;
import cn.chomy.ai.backend.enums.AgentChatType;
import cn.chomy.ai.backend.enums.AgentChatArchiveStatus;
import cn.chomy.ai.backend.mapper.AgentChatMapper;
import cn.chomy.ai.backend.mapper.AgentProjectMapper;
import cn.chomy.ai.backend.service.AgentChatService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 当前用户聊天业务，所有数据读写均明确限制所属用户。
 * 项目与无项目列表包含归档记录，归档列表独立筛选全部已归档对话；名称允许重复。
 * @author chomy
 */
@Service
@RequiredArgsConstructor
public class AgentChatServiceImpl extends ServiceImpl<AgentChatMapper, AgentChat> implements AgentChatService {
    /** 对话持久化边界。 */
    private final AgentChatMapper chatMapper;
    /** 项目所有权校验及当前页项目名称批量查询。 */
    private final AgentProjectMapper projectMapper;

    /**
     * 以纯文本规则提供命名占位结果，后续模型调用逻辑集中替换此处，不改变接口协议。
     * @param request 必填命名文本，最多 2000 个字符
     * @return 合并空白后最多 20 个 Unicode 码点的名称，不写入数据库
     * @throws BizException 用户身份、表单或文本不合法时抛出
     */
    @Override
    public AgentChatNameResponse generateName(GenerateAgentChatNameRequest request) {
        // 当前操作者固定取自认证上下文，不允许 Controller 或请求参数指定。
        Long userId = UserContext.userId();
        requireId(userId, "用户不合法");
        // 服务层保留边界校验，避免内部调用绕过 MVC 的表单约束。
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new BizException("命名文本不能为空");
        }
        if (request.message().length() > 2000) { throw new BizException("命名文本不能超过2000个字符"); }
        // 占位规则：把换行、制表符及 Unicode 空白合并为空格，生成适合单行展示的名称。
        String text = request.message().replaceAll("(?U)\\s+", " ").strip();
        if (text.isEmpty()) { throw new BizException("命名文本不能为空"); }
        // 按 Unicode 码点截断，避免在 emoji 等补充字符的代理对中间切断；不调用模型或存储层。
        int length = Math.min(20, text.codePointCount(0, text.length()));
        String chatName = text.substring(0, text.offsetByCodePoints(0, length)).stripTrailing();
        return new AgentChatNameResponse(chatName);
    }

    /**
     * 对外创建入口，表单仅接收名称及项目 ID，派生字段和状态由服务端确定。
     * @param request 名称及可空项目 ID
     * @return 已持久化的未归档聊天
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentChat createChat(CreateAgentChatRequest request) {
        // 当前操作者固定取自认证上下文，不允许 Controller 或请求参数指定。
        Long userId = UserContext.userId();
        requireId(userId, "用户不合法");
        // 空表单必须拒绝，不能默默创建默认名称或其他用户的聊天。
        if (request == null) { throw new BizException("创建对话参数不能为空"); }
        // 复用内部创建业务；外层事务覆盖本类方法调用，使项目行锁持续到插入完成。
        AgentChatType type = request.projectId() == null ? AgentChatType.STANDALONE : AgentChatType.PROJECT;
        return createChat(userId, request.projectId(), request.chatName(), type);
    }

    /**
     * 归档本人聊天，只修改归档字段，不限制当前状态以支持幂等调用。
     * @param chatId 聊天 ID
     * @return 归档成功提示
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String archiveChat(Long chatId) {
        // 当前操作者固定取自认证上下文，不允许 Controller 或请求参数指定。
        Long userId = UserContext.userId();
        // 目标状态使用强类型枚举，不接受客户端任意数字。
        updateArchiveStatus(userId, chatId, AgentChatArchiveStatus.ARCHIVED);
        return "对话已归档";
    }

    /**
     * 恢复本人聊天，只修改归档字段，不改变原项目、类型或创建时间。
     * @param chatId 聊天 ID
     * @return 恢复成功提示
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String restoreChat(Long chatId) {
        // 当前操作者固定取自认证上下文，不允许 Controller 或请求参数指定。
        Long userId = UserContext.userId();
        // 已未归档的聊天也允许再次恢复，保持接口幂等。
        updateArchiveStatus(userId, chatId, AgentChatArchiveStatus.UNARCHIVED);
        return "对话已恢复";
    }

    /**
     * 同一事务中校验并锁定目标项目，再原子修改聊天关联，避免与项目删除形成孤立关联。
     * @param chatId 聊天 ID
     * @param request 目标项目 ID，为空时移出为无项目聊天
     * @return 移动成功提示
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String moveChat(Long chatId, MoveAgentChatRequest request) {
        // 当前操作者固定取自认证上下文，不允许 Controller 或请求参数指定。
        Long userId = UserContext.userId();
        requireId(userId, "用户不合法");
        requireId(chatId, "对话不合法");
        if (request == null) { throw new BizException("移动对话参数不能为空"); }
        Long projectId = request.projectId();
        if (projectId != null) {
            requireId(projectId, "项目不合法");
            // 先锁项目再更新聊天，与创建及项目删除的锁顺序一致，锁持续到移动事务提交。
            AgentProject project = projectMapper.selectOne(Wrappers.lambdaQuery(AgentProject.class)
                    .eq(AgentProject::getId, projectId).eq(AgentProject::getUserId, userId).last("FOR UPDATE"));
            if (project == null) { throw new BizException("项目不存在或无权访问"); }
        }
        AgentChatType type = projectId == null ? AgentChatType.STANDALONE : AgentChatType.PROJECT;
        // 项目 ID 与类型必须同时更新；显式 set(NULL) 支持移出，不触碰名称及归档状态。
        int affected = chatMapper.update(null, Wrappers.lambdaUpdate(AgentChat.class)
                .eq(AgentChat::getId, chatId).eq(AgentChat::getUserId, userId)
                .set(AgentChat::getProjectId, projectId).set(AgentChat::getType, type));
        if (affected != 1) { throw new BizException("对话不存在或无权访问"); }
        return "对话已移动";
    }

    /**
     * 在同一次更新中校验所有权并设置归档状态，不通过先查后改暴露竞态窗口。
     * @param userId 当前认证用户 ID
     * @param chatId 聊天 ID
     * @param status 由归档或恢复业务确定的非空枚举状态
     */
    private void updateArchiveStatus(Long userId, Long chatId, AgentChatArchiveStatus status) {
        requireId(userId, "用户不合法");
        requireId(chatId, "对话不合法");
        // 所有者与资源 ID 联合匹配；PostgreSQL 对重复设置相同状态也返回匹配行数。
        int affected = chatMapper.update(null, Wrappers.lambdaUpdate(AgentChat.class)
                .eq(AgentChat::getId, chatId).eq(AgentChat::getUserId, userId).set(AgentChat::getArchived, status));
        if (affected != 1) { throw new BizException("对话不存在或无权访问"); }
    }

    /**
     * 内部创建入口，项目写入与删除共同锁定同一项目，防止删除后再写入孤立对话。
     * @param userId 内部可信业务指定的用户 ID，不接受 HTTP 入参
     * @param projectId 项目 ID，无项目时必须为空
     * @param chatName 允许重复的非空名称
     * @param type 显式归属类型
     * @return 持久化后的未归档对话
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentChat createChat(Long userId, Long projectId, String chatName, AgentChatType type) {
        requireId(userId, "用户不合法");
        // 不把空类型默认为无项目，关联状态必须由调用方明确提供。
        if (type == null) { throw new BizException("对话类型不能为空"); }
        if (chatName == null || chatName.isBlank() || chatName.length() > 128) {
            throw new BizException("对话名不能为空且不能超过128个字符");
        }
        if (type == AgentChatType.STANDALONE && projectId != null) {
            throw new BizException("无项目对话不能指定项目");
        }
        if (type == AgentChatType.PROJECT) {
            requireId(projectId, "项目对话必须指定有效项目");
            // 和项目删除采用相同行锁顺序；等待删除提交后将查不到项目，从而拒绝创建。
            AgentProject project = projectMapper.selectOne(Wrappers.lambdaQuery(AgentProject.class)
                    .eq(AgentProject::getId, projectId).eq(AgentProject::getUserId, userId).last("FOR UPDATE"));
            if (project == null) { throw new BizException("项目不存在或无权访问"); }
        }
        AgentChat chat = new AgentChat();
        chat.setUserId(userId);
        chat.setProjectId(projectId);
        chat.setChatName(chatName.strip());
        chat.setType(type);
        // 新创建对话显式设为未归档；不接受客户端提交归档状态或创建时间。
        chat.setArchived(AgentChatArchiveStatus.UNARCHIVED);
        if (chatMapper.insert(chat) != 1) { throw new BizException("创建对话失败"); }
        AgentChat created = chatMapper.selectById(chat.getId());
        if (created == null) { throw new BizException("创建对话失败"); }
        return created;
    }

    /**
     * 读取本人指定项目下的全部对话，包含归档记录。
     * @param projectId 所属项目 ID
     * @param query 分页参数
     * @return 创建时间和 ID 倒序的对话分页
     */
    @Override
    public IPage<AgentChatView> projectChats(Long projectId, AgentPageQuery query) {
        // 当前操作者固定取自认证上下文，不允许 Controller 或请求参数指定。
        Long userId = UserContext.userId();
        validatePage(userId, query);
        requireId(projectId, "项目不合法");
        // 先验证项目所有者，不能仅凭客户端提交的项目 ID 查询对话。
        AgentProject project = projectMapper.selectOne(Wrappers.lambdaQuery(AgentProject.class)
                .eq(AgentProject::getId, projectId).eq(AgentProject::getUserId, userId));
        if (project == null) { throw new BizException("项目不存在或无权访问"); }
        return pageViews(userId, query, ownChats(userId).eq(AgentChat::getProjectId, projectId)
                .eq(AgentChat::getType, AgentChatType.PROJECT));
    }

    /**
     * 查询本人无项目的全部对话，同时检查类型和空项目 ID，避免返回不一致关联。
     * @param query 分页参数
     * @return 无项目对话分页
     */
    @Override
    public IPage<AgentChatView> standaloneChats(AgentPageQuery query) {
        // 当前操作者固定取自认证上下文，不允许 Controller 或请求参数指定。
        Long userId = UserContext.userId();
        validatePage(userId, query);
        return pageViews(userId, query, ownChats(userId).isNull(AgentChat::getProjectId)
                .eq(AgentChat::getType, AgentChatType.STANDALONE));
    }

    /**
     * 查询本人全部已归档对话，不限制项目归属。
     * @param query 分页参数
     * @return 带可空项目名的归档分页
     */
    @Override
    public IPage<AgentChatView> archivedChats(AgentPageQuery query) {
        // 当前操作者固定取自认证上下文，不允许 Controller 或请求参数指定。
        Long userId = UserContext.userId();
        validatePage(userId, query);
        return pageViews(userId, query, ownChats(userId).eq(AgentChat::getArchived, AgentChatArchiveStatus.ARCHIVED));
    }

    /**
     * 仅更新当前用户对话的名称，归档对话同样允许重命名，保持原有归属及状态。
     * @param chatId 待重命名对话 ID
     * @param request 新名称，不能为空白且不得超过 128 个字符
     * @return 重命名成功提示
     * @throws BizException 身份、参数不合法，或对话不存在及不属于本人时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String renameChat(Long chatId, RenameAgentChatRequest request) {
        // 当前操作者固定取自认证上下文，不允许 Controller 或请求参数指定。
        Long userId = UserContext.userId();
        requireId(userId, "用户不合法");
        requireId(chatId, "对话不合法");
        // 非 HTTP 调用也必须遵守名称约束，名称允许重复但不能保存空白内容。
        if (request == null || request.chatName() == null || request.chatName().isBlank()
                || request.chatName().length() > 128) {
            throw new BizException("对话名不能为空且不能超过128个字符");
        }
        // 在同一次 UPDATE 中校验所有权，仅设置 chat_name，不改项目、归档状态或创建时间。
        int affected = chatMapper.update(null, Wrappers.lambdaUpdate(AgentChat.class)
                .eq(AgentChat::getId, chatId).eq(AgentChat::getUserId, userId)
                .set(AgentChat::getChatName, request.chatName().strip()));
        // 不区分记录不存在和属于他人，避免通过重命名接口探测其他用户的对话。
        if (affected != 1) { throw new BizException("对话不存在或无权访问"); }
        return "对话已重命名";
    }

    /**
     * 使用用户与对话 ID 联合条件删除，避免先查后删的所有权检查窗口。
     * @param chatId 待删除对话 ID
     * @return 成功提示
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String deleteChat(Long chatId) {
        // 当前操作者固定取自认证上下文，不允许 Controller 或请求参数指定。
        Long userId = UserContext.userId();
        requireId(userId, "用户不合法");
        requireId(chatId, "对话不合法");
        // 物理删除本人记录；他人记录和不存在记录统一返回业务错误。
        if (chatMapper.delete(ownChats(userId).eq(AgentChat::getId, chatId)) != 1) {
            throw new BizException("对话不存在或无权访问");
        }
        return "对话已删除";
    }

    /**
     * 构建所有查询共用的用户隔离条件。
     * @param userId 当前认证用户 ID
     * @return 含用户约束的查询条件
     */
    private LambdaQueryWrapper<AgentChat> ownChats(Long userId) {
        return Wrappers.lambdaQuery(AgentChat.class).eq(AgentChat::getUserId, userId);
    }

    /**
     * 执行有界分页后一次性补齐当前页项目名，避免逐条查询造成 N+1 请求。
     * @param userId 当前认证用户 ID
     * @param query 分页参数
     * @param conditions 已包含用户隔离及业务筛选的条件
     * @return 保留分页元数据的公开视图
     */
    private IPage<AgentChatView> pageViews(Long userId, AgentPageQuery query, LambdaQueryWrapper<AgentChat> conditions) {
        // 时间相同的对话再按 ID 倒序，使跨页排序稳定。
        Page<AgentChat> page = chatMapper.selectPage(new Page<>(query.getCurrent(), query.getSize()),
                conditions.orderByDesc(AgentChat::getCreatedAt).orderByDesc(AgentChat::getId));
        Set<Long> ids = page.getRecords().stream().map(AgentChat::getProjectId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            // 批量项目查询同样限制所属用户，不因错误关联泄漏其他用户项目名称。
            for (AgentProject project : projectMapper.selectList(Wrappers.lambdaQuery(AgentProject.class)
                    .eq(AgentProject::getUserId, userId).in(AgentProject::getId, ids))) {
                names.put(project.getId(), project.getProjectName());
            }
        }
        return page.convert(chat -> AgentChatView.from(chat, names.get(chat.getProjectId())));
    }

    /**
     * 服务层限制页码和每页规模，避免内部调用绕过 HTTP 注解限制。
     * @param userId 当前认证用户 ID
     * @param query 分页参数
     */
    private void validatePage(Long userId, AgentPageQuery query) {
        requireId(userId, "用户不合法");
        // 先检查空值再比较，防止空参数产生非业务空指针异常。
        if (query == null || query.getCurrent() == null || query.getCurrent() < 1
                || query.getCurrent() > Integer.MAX_VALUE || query.getSize() == null
                || query.getSize() < 1 || query.getSize() > 100) { throw new BizException("分页参数不合法"); }
    }

    /**
     * 资源及身份 ID 必须为正数。
     * @param id 目标 ID
     * @param message 业务提示
     */
    private void requireId(Long id, String message) {
        if (id == null || id <= 0) { throw new BizException(message); }
    }
}
