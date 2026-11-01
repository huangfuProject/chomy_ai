package cn.chomy.ai.backend.entity;

import cn.chomy.ai.backend.enums.AgentChatType;
import cn.chomy.ai.backend.enums.AgentChatArchiveStatus;
import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户对话持久化实体，对应 public.sys_agent_chat。
 * 所属用户与关联关系由业务层校验，不使用外键、CHECK 或数据库级联删除。
 * @author chomy
 */
@Getter
@Setter
@TableName(value = "sys_agent_chat", schema = "public")
public class AgentChat {
    /** 主键，由应用生成雪花 ID，JSON 使用字符串避免前端精度丢失。 */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 所属项目 ID，可空；项目类型必须填写，同一用户的项目才可关联。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long projectId;

    /** 对话名，必填，最长 128 个字符。 */
    private String chatName;

    /** 归属类型：0 无项目，1 有项目；与 projectId 的一致性由业务代码维护。 */
    private AgentChatType type;

    /** 所属用户 ID，查询和删除必须附加该用户条件。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /** 归档状态：0 未归档，1 已归档，空值不得视为未归档。 */
    private AgentChatArchiveStatus archived;

    /** 创建时间由数据库默认值生成，普通插入和更新不覆盖。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime createdAt;
}
