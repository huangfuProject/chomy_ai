package cn.chomy.ai.backend.common.web;

import cn.chomy.ai.backend.enums.CodeEnum;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;

/**
 * 将 URL 查询参数中的数字编码转成业务枚举，与 JSON 的数字协议保持一致。
 * 只注册到 CodeEnum，不改变其他框架枚举或一般字符串参数的解析方式。
 *
 * @author chomy
 */
public class CodeEnumConverterFactory implements ConverterFactory<String, CodeEnum> {
    /**
     * 为具体业务枚举创建严格转换器，不接受枚举名称、小数或未定义数字。
     * @param targetType 属性声明的业务枚举类型
     * @param <T> 业务枚举契约类型
     * @return 数字字符串转枚举的转换器
     */
    @Override
    public <T extends CodeEnum> Converter<String, T> getConverter(Class<T> targetType) {
        if (!targetType.isEnum()) { throw new IllegalArgumentException("目标类型必须为业务枚举"); }
        return source -> {
            // URL 参数天然为字符串；空字符串视作未提供，其他输入必须精确匹配已定义编码。
            if (source == null || source.isBlank()) { return null; }
            for (T value : targetType.getEnumConstants()) {
                if (value.getCode().toString().equals(source)) { return value; }
            }
            throw new IllegalArgumentException("业务枚举编码不合法");
        };
    }
}
