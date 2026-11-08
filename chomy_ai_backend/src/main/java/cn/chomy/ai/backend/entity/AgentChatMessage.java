package cn.chomy.ai.backend.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import cn.chomy.ai.backend.enums.AgentChatMessageRole;
import cn.chomy.ai.backend.enums.AgentChatMessageStatus;
import cn.chomy.ai.backend.handler.JsonbTypeHandler;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.ibatis.type.JdbcType;

/**
 * 聊天消息实体，对应 public.sys_agent_chat_message，保存用户消息与 AI 回复的结构化内容。
 * 基础实体不自动校验所有权；读写前须通过所属聊天验证用户身份，消息与任务须属于同一聊天。
 * @author chomy
 */
@Getter
@Setter
@TableName(value = "sys_agent_chat_message", schema = "public", autoResultMap = true)
public class AgentChatMessage {
    /** 消息主键，应用生成雪花 ID，JSON 使用字符串。 */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 所属聊天 ID，必填；所有权由业务层通过聊天记录验证。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long chatId;

    /** 关联执行任务 ID，可空；用户消息可先保存，再在业务事务中关联后创建的任务。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long taskId;

    /** 消息角色，按 VARCHAR 存储 user 或 assistant，不保存 Java 枚举名称。 */
    private AgentChatMessageRole role;

    /** 结构化内容，保留文本、内容块等原始 JSON 结构；SQL 列必填，不预设内容协议。 */
    @TableField(typeHandler = JsonbTypeHandler.class, jdbcType = JdbcType.OTHER)
    private JsonNode content;

    /** 消息状态：0 待处理、1 生成中、2 已完成、3 失败、4 已取消；Java 空值不代表正常状态。 */
    private AgentChatMessageStatus status;

    /** 创建时间由数据库默认值生成，普通新增及更新不覆盖，历史恢复按时间和 ID 排序。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime createdAt;
}
