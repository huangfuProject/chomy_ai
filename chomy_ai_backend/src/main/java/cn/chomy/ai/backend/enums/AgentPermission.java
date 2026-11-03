package cn.chomy.ai.backend.enums;

import lombok.Getter;

/**
 * 项目和对话的独立接口权限，与版本 SQL 权限种子一致，不存在前缀或菜单隐式授权。
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
    /** 创建本人聊天，项目归属由表单中的项目 ID 确定。 */
    CHAT_CREATE("agent:chat:create"),
    /** 归档本人聊天。 */
    CHAT_ARCHIVE("agent:chat:archive"),
    /** 恢复本人已归档聊天。 */
    CHAT_RESTORE("agent:chat:restore"),
    /** 在本人项目间移动聊天，或移出为无项目聊天。 */
    CHAT_MOVE("agent:chat:move"),
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
