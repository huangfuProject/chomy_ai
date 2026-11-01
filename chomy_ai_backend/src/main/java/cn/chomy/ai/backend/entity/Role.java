package cn.chomy.ai.backend.entity;

import java.time.OffsetDateTime;
import cn.chomy.ai.backend.enums.EnableStatus;

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
 * 角色持久化实体，对应 public.sys_role，使用下划线字段与驼峰属性自动映射。
 * 只描述单表数据，关联关系、业务取值及删除清理由应用代码维护，不依赖外键或触发器。
 * 可空属性保持 null，使通用更新只写入显式指定的属性；初始化默认值由数据库负责。
 *
 * @author chomy
 */
@Getter
@Setter
@ToString
@TableName(value = "sys_role", schema = "public")
public class Role {

    /** 角色主键，由应用使用雪花算法生成，JSON 输出为字符串以避免前端精度丢失。 */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 角色编码，必填且唯一，最长 64 个字符，区分大小写，例如 admin、user。 */
    private String roleCode;

    /** 角色中文名称，必填，最长 64 个字符，允许重名。 */
    private String roleName;

    /** 角色用途说明，可为空，最长 500 个字符。 */
    private String description;

    /** 角色状态：0 正常、1 禁用，数据库默认 0；未赋值时插入使用数据库默认值。 */
    private EnableStatus status;

    /** 显示顺序，数值越小越靠前，数据库默认 0；非负数规则由业务校验。 */
    private Integer sortOrder;

    /** 创建时间，由数据库默认值生成，普通插入和更新不由实体覆盖。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime createdAt;

    /** 创建人 ID，必填，系统操作填 0；真实性及操作权限由业务校验。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long createdBy;

    /** 最近更新时间，插入时可使用数据库默认值，后续更新由业务显式赋值。 */
    private OffsetDateTime updatedAt;

    /** 最近修改人 ID，可为空，系统修改填 0，由业务显式赋值。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long updatedBy;
}
