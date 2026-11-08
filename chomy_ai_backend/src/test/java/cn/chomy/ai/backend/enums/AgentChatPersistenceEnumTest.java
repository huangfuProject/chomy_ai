package cn.chomy.ai.backend.enums;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 消息、任务状态及消息角色协议回归，验证真实 Jackson 与 MyBatis-Plus 编解码。
 * 状态显式使用整数，角色按消息表 VARCHAR 协议显式使用小写字符串。
 * @author chomy
 */
class AgentChatPersistenceEnumTest {
    /** 所有状态逐项验证数字 JSON 与 SMALLINT 绑定，避免未来修改枚举顺序改变协议。 */
    @Test
    void roundTripsEveryStatusAndBindsSmallint() throws Exception {
        var mapper = new ObjectMapper();
        var registry = new MybatisConfiguration().getTypeHandlerRegistry();
        for (Class<? extends Enum<?>> type : List.of(AgentChatMessageStatus.class, AgentChatTaskStatus.class)) {
            for (Enum<?> constant : type.getEnumConstants()) {
                CodeEnum status = (CodeEnum) constant;
                String json = mapper.writeValueAsString(constant);
                assertThat(json).isEqualTo(status.getCode().toString());
                assertThat(mapper.readValue(json, type)).isSameAs(constant);
            }
        }
        PreparedStatement statement = mock(PreparedStatement.class);
        registry.getTypeHandler(AgentChatMessageStatus.class).setParameter(statement, 1, AgentChatMessageStatus.STREAMING, JdbcType.SMALLINT);
        registry.getTypeHandler(AgentChatTaskStatus.class).setParameter(statement, 2, AgentChatTaskStatus.CANCELLING, JdbcType.SMALLINT);
        verify(statement).setObject(1, 1, Types.SMALLINT);
        verify(statement).setObject(2, 4, Types.SMALLINT);
    }

    /** 未知数字、小数、字符串、布尔及越界编码拒绝解析，null 保留为空。 */
    @Test
    void rejectsInvalidStatusInputsAndPreservesNull() throws Exception {
        var mapper = new ObjectMapper();
        for (Class<?> type : List.of(AgentChatMessageStatus.class, AgentChatTaskStatus.class)) {
            for (String json : List.of("99", "-1", "1.5", "\"1\"", "\"RUNNING\"", "true", "2147483648")) {
                assertThatThrownBy(() -> mapper.readValue(json, type)).isInstanceOf(JsonProcessingException.class);
            }
            assertThat(mapper.readValue("null", type)).isNull();
        }
    }

    /** 消息角色只接受 user/assistant，不以 ordinal、大小写转换或枚举名称形成额外协议。 */
    @Test
    void roleUsesStableVarcharProtocol() throws Exception {
        var mapper = new ObjectMapper();
        for (AgentChatMessageRole role : AgentChatMessageRole.values()) {
            String json = mapper.writeValueAsString(role);
            assertThat(json).isEqualTo("\"" + role.getCode() + "\"");
            assertThat(mapper.readValue(json, AgentChatMessageRole.class)).isSameAs(role);
        }
        for (String json : List.of("0", "\"USER\"", "\"tool\"", "\"0\"", "true", "{}")) {
            assertThatThrownBy(() -> mapper.readValue(json, AgentChatMessageRole.class)).isInstanceOf(JsonProcessingException.class);
        }
        assertThat(mapper.readValue("null", AgentChatMessageRole.class)).isNull();
        PreparedStatement statement = mock(PreparedStatement.class);
        new MybatisConfiguration().getTypeHandlerRegistry().getTypeHandler(AgentChatMessageRole.class)
                .setParameter(statement, 1, AgentChatMessageRole.ASSISTANT, JdbcType.VARCHAR);
        verify(statement).setObject(1, "assistant", Types.VARCHAR);
    }
}
