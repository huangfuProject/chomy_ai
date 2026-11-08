package cn.chomy.ai.backend.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;

/**
 * 聊天消息持久化状态，数据库 SMALLINT 与 JSON 均采用稳定整数编码，不使用 ordinal。
 * 空值保持为空，不默认转换为待处理或成功；状态流转由后续业务服务显式校验。
 * @author chomy
 */
@Getter
public enum AgentChatMessageStatus implements CodeEnum {
    /** 待处理，稳定编码 0。 */
    PENDING(0, "待处理"),
    /** 生成中，稳定编码 1。 */
    STREAMING(1, "生成中"),
    /** 已完成，稳定编码 2。 */
    COMPLETED(2, "已完成"),
    /** 失败，稳定编码 3。 */
    FAILED(3, "失败"),
    /** 已取消，稳定编码 4。 */
    CANCELLED(4, "已取消");

    /** 数据库与 JSON 使用的稳定整数编码。 */
    @EnumValue @JsonValue
    private final Integer code;
    /** 面向维护者的中文状态说明。 */
    private final String description;

    /**
     * 定义状态协议，发布后不能改变既有编码含义。
     * @param code 稳定整数编码
     * @param description 中文状态说明
     */
    AgentChatMessageStatus(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 严格解析 JSON 状态，拒绝未知编码、小数、布尔及数字字符串。
     * @param node 原始 JSON 节点
     * @return 对应状态或 null
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static AgentChatMessageStatus fromJson(JsonNode node) {
        // 复用公共校验，防止 Jackson 将字符串或小数隐式转换为正常状态。
        return CodeEnum.fromJson(AgentChatMessageStatus.class, node);
    }
}
