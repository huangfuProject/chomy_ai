package cn.chomy.ai.backend.entity;

import java.time.OffsetDateTime;
import cn.chomy.ai.backend.enums.Gender;
import cn.chomy.ai.backend.enums.UserCreateType;
import cn.chomy.ai.backend.enums.UserStatus;
import cn.chomy.ai.backend.enums.ActivationStatus;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 用户基础信息持久化实体，对应 public.sys_user，字段约束以建表脚本为准。
 * 主键由 MyBatis-Plus 生成；时间字段插入时可使用数据库默认值，更新时间由业务代码维护。
 * 密码哈希不参与普通查询、JSON 序列化或 toString，避免被无意返回前端或写入日志。
 *
 * @author chomy
 */
@Getter
@Setter
@ToString
@TableName(value = "sys_user", schema = "public")
public class User {

    /** 用户主键，应用使用雪花算法生成；JSON 输出为字符串，避免前端 JavaScript 整数精度丢失。 */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 用户名，必填且唯一，仅允许英文字母、数字和下划线，最长 64 个字符。 */
    private String username;

    /** 昵称，必填，最长 64 个字符，不允许空串或首尾空格，用于界面展示，允许重名。 */
    private String nickname;

    /** 性别：0 未知、1 男、2 女，数据库默认 0，合法值由业务校验。 */
    private Gender gender;

    /** 手机号，可为空，提供时唯一；未提供或空字符串保存为 NULL。 */
    private String phone;

    /** 邮箱，可为空，最长 254 个字符，不作为登录名或唯一身份标识。 */
    private String email;

    /**
     * 密码密文，必填，存储带盐的不可逆密码哈希，不存储明文密码。
     * 默认查询排除此字段；认证业务需要显式选择 password_hash，接口仍不会将其序列化输出。
     */
    @TableField(select = false)
    @JsonIgnore
    @ToString.Exclude
    private String passwordHash;

    /** 个性签名，可为空，最长 255 个字符。 */
    private String signature;

    /** 头像 URL，可为空，最长 2048 个字符。 */
    private String avatarUrl;

    /** 创建时间，由数据库默认值生成；普通插入和更新均不由实体覆盖。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime createdAt;

    /** 创建方式，必填：0 管理员追加，1 自注册，必须由创建业务显式指定。 */
    private UserCreateType createType;

    /** 创建人 ID，必填；管理员追加填操作者 ID，自注册填自身 ID，系统初始化允许填 0。 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long createdBy;

    /** 用户状态，必填：0 正常，1 冻结；插入时可使用数据库默认值 0，不在 Java 中回退状态。 */
    private UserStatus status;

    /** 激活标记，必填：0 未激活，1 激活；数据库默认 1，不在 Java 中自动回退为激活。 */
    private ActivationStatus actived;

    /** 最近更新时间，插入时为空可使用数据库默认值；后续更新时须由业务代码显式赋值。 */
    private OffsetDateTime updatedAt;

    /** 最近成功登录时间，可为空；由后续登录业务在认证通过后更新，不能用于记录失败登录。 */
    private OffsetDateTime lastLoginAt;
}
