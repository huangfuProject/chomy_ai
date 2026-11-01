package cn.chomy.ai.backend.enums;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 数字编码业务枚举的公共契约，与异常错误码接口分离。
 * 编码必须与数据库约定一致，不使用 ordinal；JSON 严格接收已定义的整数编码。
 *
 * @author chomy
 */
public interface CodeEnum {
    /** @return 数据库存储和接口传输使用的稳定数字编码 */
    Integer getCode();

    /** @return 供注释、后台展示或业务提示使用的中文含义 */
    String getDescription();

    /**
     * 按稳定编码查找枚举，不将未知编码悄悄转换成默认值。
     *
     * @param type 目标枚举类型
     * @param code 数据库或业务传入的数字编码
     * @param <E> 同时实现公共契约的枚举类型
     * @return 匹配的枚举值
     * @throws IllegalArgumentException 编码未定义时拒绝转换
     */
    static <E extends Enum<E> & CodeEnum> E fromCode(Class<E> type, int code) {
        // 只根据显式编码匹配，不依赖枚举声明顺序，避免调整顺序改变已有数据含义。
        for (E value : type.getEnumConstants()) {
            if (value.getCode() == code) { return value; }
        }
        throw new IllegalArgumentException(type.getSimpleName() + "不支持该编码：" + code);
    }

    /**
     * 严格解析 JSON 整数，拒绝小数、布尔值、枚举名称和数字字符串，避免框架强制转换造成误判。
     * null 保留为 null，由 DTO 的 NotNull 或数据库必填约定决定是否允许。
     *
     * @param type 目标枚举类型
     * @param node 原始 JSON 节点
     * @param <E> 实现公共契约的枚举类型
     * @return 合法枚举值或 null
     */
    static <E extends Enum<E> & CodeEnum> E fromJson(Class<E> type, JsonNode node) {
        // 可空字段不得在反序列化时自动变为某个正常或启用状态。
        if (node == null || node.isNull()) { return null; }
        // 避免 1.5 被截断为 1，以及字符串、布尔值被隐式转换为有效状态。
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new IllegalArgumentException(type.getSimpleName() + "必须使用整数编码");
        }
        return fromCode(type, node.intValue());
    }
}
