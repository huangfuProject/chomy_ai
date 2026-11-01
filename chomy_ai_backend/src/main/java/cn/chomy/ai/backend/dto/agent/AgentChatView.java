package cn.chomy.ai.backend.dto.agent;

import cn.chomy.ai.backend.entity.AgentChat;
import cn.chomy.ai.backend.enums.AgentChatType;
import cn.chomy.ai.backend.enums.AgentChatArchiveStatus;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.OffsetDateTime;

/**
 * 对话公开视图，分页结果附带所属项目名称；无项目时项目 ID 和名称均为空。
 * @param id 对话 ID，以字符串输出
 * @param projectId 所属项目 ID，可空，以字符串输出
 * @param projectName 所属项目名称，可空
 * @param chatName 对话名称
 * @param type 项目归属类型
 * @param archived 归档状态
 * @param createdAt 创建时间
 * @author chomy
 */
public record AgentChatView(
        @JsonSerialize(using = ToStringSerializer.class) Long id,
        @JsonSerialize(using = ToStringSerializer.class) Long projectId,
        String projectName, String chatName, AgentChatType type,
        AgentChatArchiveStatus archived, OffsetDateTime createdAt) {
    /**
     * 从持久化记录构造白名单响应，不暴露内部所有者字段。
     * @param chat 对话记录
     * @param projectName 已按所有者过滤后得到的项目名称
     * @return 对话分页视图
     */
    public static AgentChatView from(AgentChat chat, String projectName) {
        // 项目名来自服务端批量查询，不信任客户端提供的关联信息。
        return new AgentChatView(chat.getId(), chat.getProjectId(), projectName, chat.getChatName(),
                chat.getType(), chat.getArchived(), chat.getCreatedAt());
    }
}
