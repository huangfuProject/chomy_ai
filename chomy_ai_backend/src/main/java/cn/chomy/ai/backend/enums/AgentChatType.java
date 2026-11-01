package cn.chomy.ai.backend.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;

/**
 * 对话项目归属类型，数据库和 JSON 均采用显式整数编码，禁止按枚举序号映射。
 * 未知编码、字符串和小数由共用转换规则拒绝，空值不自动转换为有效状态。
 * @author chomy
 */
@Getter
public enum AgentChatType implements CodeEnum {
    /** 无项目归属，稳定编码 0。 */
    STANDALONE(0, "无项目归属"),
    /** 有项目归属，稳定编码 1。 */
    PROJECT(1, "有项目归属");
    /** 持久化和 JSON 使用的稳定整数编码。 */
    @EnumValue @JsonValue
    private final Integer code;
    /** 中文业务含义。 */
    private final String description;

    /**
     * 定义稳定编码，发布后不得改变已有编码的业务含义。
     * @param code 整数编码
     * @param description 中文说明
     */
    AgentChatType(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 严格解析 JSON 整数，避免默认 ordinal 转换误接受非协议值。
     * @param node 原始 JSON 节点
     * @return 对应枚举，空节点保持空值
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static AgentChatType fromJson(JsonNode node) {
        // 复用项目统一的固定业务值校验规则。
        return CodeEnum.fromJson(AgentChatType.class, node);
    }
}
