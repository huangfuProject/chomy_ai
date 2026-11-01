package cn.chomy.ai.backend.service.impl;

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
 * 当前用户对话分页及删除业务，所有读取和单条删除都明确限制所属用户。
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
     * 内部创建入口，项目写入与删除共同锁定同一项目，防止删除后再写入孤立对话。
     * @param userId 认证上下文用户 ID
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
     * @param userId 当前认证用户 ID
     * @param projectId 所属项目 ID
     * @param query 分页参数
     * @return 创建时间和 ID 倒序的对话分页
     */
    @Override
    public IPage<AgentChatView> projectChats(Long userId, Long projectId, AgentPageQuery query) {
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
     * @param userId 当前认证用户 ID
     * @param query 分页参数
     * @return 无项目对话分页
     */
    @Override
    public IPage<AgentChatView> standaloneChats(Long userId, AgentPageQuery query) {
        validatePage(userId, query);
        return pageViews(userId, query, ownChats(userId).isNull(AgentChat::getProjectId)
                .eq(AgentChat::getType, AgentChatType.STANDALONE));
    }

    /**
     * 查询本人全部已归档对话，不限制项目归属。
     * @param userId 当前认证用户 ID
     * @param query 分页参数
     * @return 带可空项目名的归档分页
     */
    @Override
    public IPage<AgentChatView> archivedChats(Long userId, AgentPageQuery query) {
        validatePage(userId, query);
        return pageViews(userId, query, ownChats(userId).eq(AgentChat::getArchived, AgentChatArchiveStatus.ARCHIVED));
    }

    /**
     * 使用用户与对话 ID 联合条件删除，避免先查后删的所有权检查窗口。
     * @param userId 当前认证用户 ID
     * @param chatId 待删除对话 ID
     * @return 成功提示
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String deleteChat(Long userId, Long chatId) {
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
