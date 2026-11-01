package cn.chomy.ai.backend.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 用户角色关联持久化实体，对应 public.sys_user_role，使用下划线字段与驼峰属性自动映射。
 * 只描述单表数据，关联关系、业务取值及删除清理由应用代码维护，不依赖外键或触发器。
 * 可空属性保持 null，使通用更新只写入显式指定的属性；初始化默认值由数据库负责。
 *
 * @author chomy
 */
@Getter
@Setter
@ToString
@TableName(value = "sys_user_role", schema = "public")
public class UserRole {

    /** 关系主键，由应用使用雪花算法生成，JSON 输出为字符串以避免前端精度丢失。 */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 用户 ID，必填，逻辑关联 sys_user；用户存在性及角色分配权限由业务校验。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /** 角色 ID，必填，逻辑关联 sys_role；与 userId 组成唯一索引，禁止重复分配。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long roleId;

    /** 角色分配时间，由数据库默认值生成，普通插入和更新不由实体覆盖。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime createdAt;

    /** 角色分配操作者 ID，必填，系统操作填 0，由业务显式提供。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long createdBy;
}
