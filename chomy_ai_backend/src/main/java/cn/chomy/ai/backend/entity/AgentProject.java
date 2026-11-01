package cn.chomy.ai.backend.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户项目持久化实体，对应 public.sys_agent_project。
 * 所属用户与关联关系由业务层校验，不使用外键、CHECK 或数据库级联删除。
 * @author chomy
 */
@Getter
@Setter
@TableName(value = "sys_agent_project", schema = "public")
public class AgentProject {
    /** 主键，由应用生成雪花 ID，JSON 使用字符串避免前端精度丢失。 */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 项目名，必填且允许重复，最长 128 个字符。 */
    private String projectName;

    /** 项目介绍，可空，最长 2000 个字符。 */
    private String description;

    /** 所属用户 ID，只能由已验签的 JWT 提供。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /** 创建时间由数据库默认值生成，普通插入和更新不覆盖。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime createdAt;
}
