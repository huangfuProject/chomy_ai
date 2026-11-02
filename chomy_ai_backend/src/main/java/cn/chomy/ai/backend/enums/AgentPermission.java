package cn.chomy.ai.backend.enums;

import lombok.Getter;

/**
 * 项目和对话的独立接口权限，与 V2 权限种子一致，不存在前缀或菜单隐式授权。
 * @author chomy
 */
@Getter
public enum AgentPermission {
    /** 分页查看本人全部项目。 */
    PROJECT_PAGE("agent:project:page"),
    /** 创建本人项目。 */
    PROJECT_CREATE("agent:project:create"),
    /** 修改本人项目名称和介绍。 */
    PROJECT_UPDATE("agent:project:update"),
    /** 重命名本人对话。 */
    CHAT_RENAME("agent:chat:rename"),
    /** 删除本人项目及全部对话。 */
    PROJECT_DELETE("agent:project:delete"),
    /** 分页查看本人项目对话。 */
    PROJECT_CHATS("agent:chat:project"),
    /** 分页查看本人无项目对话。 */
    STANDALONE_CHATS("agent:chat:standalone"),
    /** 分页查看本人全部归档对话。 */
    ARCHIVED_CHATS("agent:chat:archived"),
    /** 删除本人对话。 */
    CHAT_DELETE("agent:chat:delete");
    /** 权限协议使用的稳定字符串。 */
    private final String code;
    /**
     * 定义权限编码。
     * @param code 与权限种子和前端操作共用的编码
     */
    AgentPermission(String code) { this.code = code; }
}
