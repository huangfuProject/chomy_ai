package cn.chomy.ai.backend.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import cn.chomy.ai.backend.enums.AgentChatTaskStatus;

/**
 * Agent 执行任务实体，对应 public.sys_agent_chat_task，与消息内容分离以跟踪任务生命周期。
 * 本实体仅提供持久化字段，不自动执行 Agent、取消或恢复任务；状态及时间由业务服务维护。
 * @author chomy
 */
@Getter
@Setter
@TableName(value = "sys_agent_chat_task", schema = "public")
public class AgentChatTask {
    /** 任务主键，应用生成雪花 ID，JSON 使用字符串。 */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 所属聊天 ID，必填；任务与触发消息必须属于同一聊天。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long chatId;

    /** 触发任务的用户消息 ID，必填；不是 AI 回复 ID，同一消息允许重试产生多个任务。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long messageId;

    /** 任务状态：0 待执行、1 执行中、2 已完成、3 失败、4 取消中、5 已取消。 */
    private AgentChatTaskStatus status;

    /** 实际开始执行时间，待执行时为空；由业务代码设置，不使用数据库触发器。 */
    private OffsetDateTime startedAt;

    /** 进入成功、失败或已取消等终态的时间；待执行、执行中或取消中时为空。 */
    private OffsetDateTime finishedAt;

    /** 脱敏后的错误摘要，可空；不保存令牌、密码或原始请求中的敏感内容。 */
    private String errorMessage;

    /** Agent 检查点标识，可空，最多 256 个字符；实际恢复还需执行引擎的持久化检查点存储。 */
    private String checkpointId;

    /** 创建时间由数据库默认值生成，普通新增和更新不覆盖。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime createdAt;
}
