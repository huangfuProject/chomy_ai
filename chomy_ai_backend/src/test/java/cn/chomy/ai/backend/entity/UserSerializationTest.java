package cn.chomy.ai.backend.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证用户实体被意外用于响应或日志时，不泄露密码哈希，并保留雪花 ID 的完整精度。
 * 测试不连接数据库；测试中的哈希内容仅为占位数据，不代表有效密码。
 *
 * @author chomy
 */
class UserSerializationTest {

    /** 密码哈希即使已经显式查询并写入实体，也不得进入 JSON 或 Lombok 生成的字符串。 */
    @Test
    void excludesPasswordHashFromJsonAndToString() throws Exception {
        User user = new User();
        user.setUsername("test-user");
        user.setNickname("测试昵称");
        user.setPasswordHash("test-only-hash-placeholder");

        String json = new ObjectMapper().writeValueAsString(user);

        assertThat(json).doesNotContain("passwordHash", "test-only-hash-placeholder");
        assertThat(user.toString()).doesNotContain("passwordHash", "test-only-hash-placeholder");
        assertThat(new ObjectMapper().readTree(json).get("nickname").asText()).isEqualTo("测试昵称");
    }

    /** 大于 JavaScript 安全整数范围的用户及创建人 ID 必须以字符串输出，避免浏览器精度丢失。 */
    @Test
    void serializesSnowflakeIdsAsStrings() throws Exception {
        User user = new User();
        user.setId(9007199254740993L);
        user.setCreatedBy(9007199254740995L);

        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(user));

        assertThat(json.get("id").isTextual()).isTrue();
        assertThat(json.get("id").asText()).isEqualTo("9007199254740993");
        assertThat(json.get("createdBy").isTextual()).isTrue();
        assertThat(json.get("createdBy").asText()).isEqualTo("9007199254740995");
    }
}
