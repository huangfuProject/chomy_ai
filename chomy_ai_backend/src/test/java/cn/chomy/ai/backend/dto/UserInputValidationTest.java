package cn.chomy.ai.backend.dto;

import cn.chomy.ai.backend.dto.admin.AdminCreateUserRequest;
import cn.chomy.ai.backend.dto.admin.AdminUpdateUserRequest;
import cn.chomy.ai.backend.dto.auth.LoginRequest;
import cn.chomy.ai.backend.enums.Gender;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户输入约束回归，覆盖用户名字符白名单及可选联系方式，避免仅修改数据库而遗漏接口约束。
 *
 * @author chomy
 */
class UserInputValidationTest {
    /** 非法字符在创建、修改和登录入口统一拒绝，并给出约定的中文提示。 */
    @Test
    void rejectsIllegalUsernamesAcrossEntryPoints() {
        // 使用真实 Bean Validation，验证 Controller 实际采用的注解，而非复制正则进行断言。
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (String name : new String[]{"中文", "bad-name", "a.b", "user name", " admin", "é"}) {
                Object[] requests = {
                        new AdminCreateUserRequest(name, "昵称", null, null, "TestPass123!", null, null, null, null),
                        new AdminUpdateUserRequest(name, "昵称", null, null, Gender.UNKNOWN, null, null),
                        new LoginRequest(name, "TestPass123!")};
                for (Object request : requests) {
                    assertThat(validator.validate(request)).anyMatch(error -> error.getMessage().equals("非合法用户名"));
                }
            }
        }
    }

    /** 联系方式可省略或留空，填写后仍必须满足手机号和邮箱的格式要求。 */
    @Test
    void allowsAbsentContactsAndValidatesProvidedValues() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            // 数字、英文字母及下划线可以混用；空手机号与空邮箱不能成为必填约束。
            for (String contact : new String[]{null, ""}) {
                assertThat(validator.validate(new AdminCreateUserRequest("User_123", "昵称", contact, contact,
                        "TestPass123!", null, null, null, null))).isEmpty();
                assertThat(validator.validate(new AdminUpdateUserRequest("User_123", "昵称", contact, contact,
                        Gender.MALE, null, null))).isEmpty();
            }
            assertThat(validator.validate(new AdminCreateUserRequest("User_123", "昵称", "wrong", "invalid-mail",
                    "TestPass123!", null, null, null, null))).hasSize(2);
        }
    }
}
