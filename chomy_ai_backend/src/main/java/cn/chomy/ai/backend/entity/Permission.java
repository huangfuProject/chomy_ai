package cn.chomy.ai.backend.entity;

import java.time.OffsetDateTime;
import cn.chomy.ai.backend.enums.PermissionType;
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
 * 权限持久化实体，对应 public.sys_permission，使用下划线字段与驼峰属性自动映射。
 * 只描述单表数据，关联关系、业务取值及删除清理由应用代码维护，不依赖外键或触发器。
 * 可空属性保持 null，使通用更新只写入显式指定的属性；初始化默认值由数据库负责。
 *
 * @author chomy
 */
@Getter
@Setter
@ToString
@TableName(value = "sys_permission", schema = "public")
public class Permission {

    /** 权限主键，由应用使用雪花算法生成，JSON 输出为字符串以避免前端精度丢失。 */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 父权限 ID，可为空，NULL 表示顶层；父节点有效性、自引用及循环关系由业务校验。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long parentId;

    /** 权限编码，必填且唯一，最长 128 个字符，例如 user:list、user:create。 */
    private String permissionCode;

    /** 权限中文名称，必填，最长 64 个字符。 */
    private String permissionName;

    /** 权限类型，必填：0 目录、1 菜单、2 按钮、3 接口；取值合法性由业务校验。 */
    private PermissionType permissionType;

    /** 前端路由路径，可为空，最长 255 个字符，不用于代替后端接口鉴权。 */
    private String routePath;

    /** 后端接口匹配路径，最长 255 个字符，仅接口权限需要填写，格式及模式重叠由业务校验。 */
    private String apiPath;

    /** 接口请求方法，最长 10 个字符，仅接口权限需要填写，使用显式大写 HTTP 方法。 */
    private String httpMethod;

    /** 前端图标标识，可为空，最长 128 个字符。 */
    private String icon;

    /** 权限状态：0 正常、1 禁用，数据库默认 0；禁用状态由后续鉴权查询过滤。 */
    private EnableStatus status;

    /** 同级显示顺序，数值越小越靠前，数据库默认 0；非负数规则由业务校验。 */
    private Integer sortOrder;

    /** 权限用途说明，可为空，最长 500 个字符。 */
    private String description;

    /** 创建时间，由数据库默认值生成，普通插入和更新不由实体覆盖。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime createdAt;

    /** 创建人 ID，必填，系统操作填 0，不配置数据库外键。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long createdBy;

    /** 最近更新时间，插入时可使用数据库默认值，后续更新由业务显式赋值。 */
    private OffsetDateTime updatedAt;

    /** 最近修改人 ID，可为空，系统修改填 0，由业务显式赋值。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long updatedBy;
}
