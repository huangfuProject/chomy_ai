package cn.chomy.ai.backend.enums;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.List;
import cn.chomy.ai.backend.entity.Permission;
import cn.chomy.ai.backend.entity.Role;
import cn.chomy.ai.backend.entity.User;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * 验证枚举在 JSON、Redis 载荷及 JDBC 边界使用稳定数字编码，防止保存枚举名称或错误状态。
 * 使用真实 Jackson 和 MyBatis-Plus 类型处理器，不连接数据库。
 *
 * @author chomy
 */
class BusinessEnumMappingTest {
    /** 全部业务枚举的 JSON 表达仍为整数编码，不改变前端或现有 Redis 数字数据的格式。 */
    @Test
    void roundTripsAllBusinessEnumsAsNumbers() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (CodeEnum value : List.of(Gender.FEMALE, UserCreateType.SELF_REGISTERED,
                UserStatus.FROZEN, ActivationStatus.ACTIVE, EnableStatus.DISABLED, PermissionType.API, AgentChatType.PROJECT, AgentChatArchiveStatus.ARCHIVED)) {
            String json = mapper.writeValueAsString(value);
            assertThat(json).isEqualTo(value.getCode().toString());
            assertThat(mapper.readValue(json, value.getClass())).isSameAs(value);
        }
    }

    /** 旧版数字缓存仍能还原成强类型实体，空状态不应被静默转换为正常或启用。 */
    @Test
    void readsExistingNumericEntityPayloads() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        User user = mapper.readValue("{\"gender\":2,\"createType\":1,\"status\":1,\"actived\":0}", User.class);
        assertThat(user.getGender()).isEqualTo(Gender.FEMALE);
        assertThat(user.getCreateType()).isEqualTo(UserCreateType.SELF_REGISTERED);
        assertThat(user.getStatus()).isEqualTo(UserStatus.FROZEN);
        assertThat(user.getActived()).isEqualTo(ActivationStatus.INACTIVE);
        assertThat(mapper.readValue("{\"status\":0}", Role.class).getStatus()).isEqualTo(EnableStatus.ENABLED);
        Permission permission = mapper.readValue("{\"permissionType\":3,\"status\":0}", Permission.class);
        assertThat(permission.getPermissionType()).isEqualTo(PermissionType.API);
        assertThat(mapper.readValue("{\"status\":null}", User.class).getStatus()).isNull();
    }

    /** 非法编码、小数、字符串、布尔及越界整数不得被截断或按 ordinal 解析为合法值。 */
    @Test
    void rejectsInvalidEnumInputs() {
        ObjectMapper mapper = new ObjectMapper();
        for (String json : List.of("99", "-1", "1.5", "\"1\"", "\"MALE\"", "true", "2147483648")) {
            assertThatThrownBy(() -> mapper.readValue(json, Gender.class))
                    .as("不应接受枚举输入：%s", json).isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        }
    }

    /** MyBatis-Plus 实际写入 SMALLINT 数字，读取数据库数字时还原枚举，而非写入名称 FEMALE。 */
    @Test
    void bindsAndReadsNumericJdbcValues() throws Exception {
        var handler = new MybatisConfiguration().getTypeHandlerRegistry().getTypeHandler(Gender.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        handler.setParameter(statement, 1, Gender.FEMALE, JdbcType.SMALLINT);
        verify(statement).setObject(1, Gender.FEMALE.getCode(), Types.SMALLINT);

        ResultSet result = mock(ResultSet.class);
        when(result.getObject("gender", Integer.class)).thenReturn(2);
        assertThat(handler.getResult(result, "gender")).isEqualTo(Gender.FEMALE);
        // 未知数据库编码不能成为正常或已激活；实体同样不能以 Java 初始值掩盖空映射。
        when(result.getObject("gender", Integer.class)).thenReturn(99);
        assertThat(handler.getResult(result, "gender")).isNull();
        assertThat(new User().getStatus()).isNull();
        assertThat(new User().getActived()).isNull();
    }
    /** 新增对话枚举使用严格数字协议，JSON 与 MyBatis 实际绑定均不得依赖 ordinal。 */
    @Test
    void agentChatEnumsRejectInvalidJsonAndBindNumericCodes() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (Class<?> type : List.of(AgentChatType.class, AgentChatArchiveStatus.class)) {
            for (String json : List.of("99", "-1", "1.5", "\"1\"", "\"PROJECT\"", "true", "2147483648")) {
                assertThatThrownBy(() -> mapper.readValue(json, type)).isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
            }
            assertThat(mapper.readValue("null", type)).isNull();
        }
        var typeHandler = new MybatisConfiguration().getTypeHandlerRegistry().getTypeHandler(AgentChatType.class);
        var archiveHandler = new MybatisConfiguration().getTypeHandlerRegistry().getTypeHandler(AgentChatArchiveStatus.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        // 验证真实类型处理器，而非仅检查注解存在。
        typeHandler.setParameter(statement, 1, AgentChatType.PROJECT, JdbcType.SMALLINT);
        archiveHandler.setParameter(statement, 2, AgentChatArchiveStatus.ARCHIVED, JdbcType.SMALLINT);
        verify(statement).setObject(1, 1, Types.SMALLINT);
        verify(statement).setObject(2, 1, Types.SMALLINT);
    }

}
