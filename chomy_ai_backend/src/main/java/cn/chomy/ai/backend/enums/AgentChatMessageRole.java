package cn.chomy.ai.backend.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;

/**
 * 聊天消息角色；按消息表 role 的 VARCHAR 协议使用显式字符串编码，不使用 ordinal 或枚举名称。
 * 当前仅持久化用户消息与 AI 回复，工具调用等扩展角色须后续显式增加并维护兼容性测试。
 * @author chomy
 */
@Getter
public enum AgentChatMessageRole {
    /** 前端提交的用户消息，稳定编码 user。 */
    USER("user", "用户"),
    /** Agent 生成的 AI 回复，稳定编码 assistant。 */
    ASSISTANT("assistant", "AI 回复");

    /** VARCHAR 持久化与 JSON 协议编码；角色字段遵循显式要求的字符串类型。 */
    @EnumValue @JsonValue
    private final String code;
    /** 中文角色说明。 */
    private final String description;

    /**
     * 定义角色编码，避免调整枚举名称或顺序影响已有记录。
     * @param code 稳定小写字符串编码
     * @param description 中文角色说明
     */
    AgentChatMessageRole(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 只接受协议中的字符串编码，拒绝数字、枚举名称及未知角色。
     * @param node 原始 JSON 节点
     * @return 对应角色，JSON null 保持为空
     * @throws IllegalArgumentException 角色编码不合法时抛出
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static AgentChatMessageRole fromJson(JsonNode node) {
        // 空角色不自动视为用户或 AI，是否允许为空由业务入口决定。
        if (node == null || node.isNull()) { return null; }
        if (node.isTextual()) {
            for (AgentChatMessageRole role : values()) {
                if (role.code.equals(node.textValue())) { return role; }
            }
        }
        throw new IllegalArgumentException("消息角色必须为 user 或 assistant");
    }
}
