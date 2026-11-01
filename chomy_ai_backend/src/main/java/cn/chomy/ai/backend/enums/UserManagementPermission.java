package cn.chomy.ai.backend.enums;

import lombok.Getter;

/**
 * 用户管理接口权限字典，创建与所有修改、删除归 update，读取归 get，各接口仍有独立权限码。
 * 与 V4 权限数据一一对应，前端按钮与后端接口共用同一个码，菜单不隐式授予操作权限。
 *
 * @author chomy
 */
@Getter
public enum UserManagementPermission {
    /** 创建用户，由对应接口权限单独授权。 */
    CREATE("user:update:create", "创建用户"),

    /** 激活用户，由对应接口权限单独授权。 */
    ACTIVATE("user:update:activate", "激活用户"),

    /** 冻结用户，由对应接口权限单独授权。 */
    FREEZE("user:update:freeze", "冻结用户"),

    /** 解冻用户，由对应接口权限单独授权。 */
    UNFREEZE("user:update:unfreeze", "解冻用户"),

    /** 重置密码，由对应接口权限单独授权。 */
    RESET_PASSWORD("user:update:password", "重置密码"),

    /** 修改用户信息，由对应接口权限单独授权。 */
    UPDATE_INFO("user:update:info", "修改用户信息"),

    /** 删除用户，由对应接口权限单独授权。 */
    DELETE("user:update:delete", "删除用户"),

    /** 查看用户详情，由对应接口权限单独授权。 */
    DETAIL("user:get:detail", "查看用户详情"),

    /** 分页查询用户，由对应接口权限单独授权。 */
    PAGE("user:get:page", "分页查询用户");
    /** 稳定权限标识，用于请求级和方法级鉴权，不映射数据库数字枚举。 */
    private final String code;
    /** 权限中文说明。 */
    private final String description;

    /**
     * 初始化接口权限标识。
     * @param code 独立接口权限码
     * @param description 操作说明
     */
    UserManagementPermission(String code, String description) {
        this.code = code;
        this.description = description;
    }
}
