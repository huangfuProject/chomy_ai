package cn.chomy.ai.backend.common.web;

import java.beans.PropertyEditorSupport;
import cn.chomy.ai.backend.enums.CodeEnum;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.InitBinder;

/**
 * 为表单及 ModelAttribute 中的业务枚举绑定严格数字编辑器。
 * Spring 的通用类型转换失败后可能回退到 Enum.valueOf；显式编辑器阻止枚举名称绕过数字协议。
 * 只处理实际声明为 CodeEnum 的属性，不影响其他枚举、普通字段或 JSON 创建器。
 *
 * @author chomy
 */
@ControllerAdvice(basePackages = "cn.chomy.ai.backend")
public class CodeEnumBindingAdvice {
    /**
     * 根据当前绑定对象的属性类型动态注册编辑器，新增业务枚举不需要维护额外类型清单。
     * @param binder 当前 MVC 参数绑定器
     */
    @InitBinder
    public void bindCodeEnums(WebDataBinder binder) {
        // Spring MVC 可能在构造表单对象之前调用 InitBinder，此时通过声明类型识别字段。
        Class<?> targetType = binder.getTarget() != null ? binder.getTarget().getClass()
                : binder.getTargetType() == null ? null : binder.getTargetType().resolve();
        if (targetType == null) { return; }
        var factory = new CodeEnumConverterFactory();
        for (var property : BeanUtils.getPropertyDescriptors(targetType)) {
            Class<?> type = property.getPropertyType();
            if (type == null || !type.isEnum() || !CodeEnum.class.isAssignableFrom(type)) { continue; }
            var converter = factory.getConverter(type.asSubclass(CodeEnum.class));
            // 属性编辑器优先于默认 Enum.valueOf 回退，非法名称、小数及未知数字均成为参数绑定错误。
            binder.registerCustomEditor(type, new PropertyEditorSupport() {
                @Override
                public void setAsText(String text) { setValue(converter.convert(text)); }
            });
        }
    }
}
