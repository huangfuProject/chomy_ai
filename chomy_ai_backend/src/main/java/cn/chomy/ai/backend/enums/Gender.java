package cn.chomy.ai.backend.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;

/**
 * 用户性别，Java 内使用枚举，数据库、HTTP JSON 和 Redis JSON 保持原有数字编码。
 * EnumValue 指定持久化编码，JsonValue 指定输出编码，未知或非整数输入将被拒绝。
 *
 * @author chomy
 */
@Getter
public enum Gender implements CodeEnum {

    /** 未知，数据库和接口编码为 0。 */
    UNKNOWN(0, "未知"),

    /** 男，数据库和接口编码为 1。 */
    MALE(1, "男"),

    /** 女，数据库和接口编码为 2。 */
    FEMALE(2, "女");

    /** 稳定数字编码，不使用枚举名称或序号保存到数据库。 */
    @EnumValue
    @JsonValue
    private final Integer code;

    /** 编码对应的中文业务含义。 */
    private final String description;

    /**
     * 初始化显式编码及中文含义，不允许随意变更已经持久化的编码。
     * @param code 稳定数字编码
     * @param description 中文业务含义
     */
    Gender(Integer code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * HTTP 和 Redis 反序列化共用严格转换规则，避免错误输入成为合法状态。
     * @param node 原始 JSON 数字节点
     * @return 与编码对应的枚举值，JSON null 保留为 null
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static Gender fromJson(JsonNode node) {
        // 不使用 Jackson 默认的枚举 ordinal 解析，统一按显式业务编码转换。
        return CodeEnum.fromJson(Gender.class, node);
    }
}
