package cn.chomy.ai.backend.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 验证 JSONB 在 JDBC 边界按 OTHER 绑定，结构化内容和 SQL/JSON 两种空值语义不会丢失。
 * 使用真实类型处理器及 Jackson，JDBC 替身不访问外部数据库。
 * @author chomy
 */
class JsonbTypeHandlerTest {
    /** JSON 对象、数组及标量均保持原结构，不能按普通 VARCHAR 或双重编码的 JSON 字符串写入。 */
    @Test
    void bindsJsonbAsOtherAndReadsOriginalStructure() throws Exception {
        var handler = new JsonbTypeHandler();
        var mapper = new ObjectMapper();
        for (String json : new String[]{"{\"text\":\"你好😀\",\"blocks\":[{\"type\":\"text\"}]}", "[1,\"文本\"]", "\"文本\"", "42", "true"}) {
            var content = mapper.readTree(json);
            PreparedStatement statement = mock(PreparedStatement.class);
            handler.setParameter(statement, 1, content, JdbcType.OTHER);
            verify(statement).setObject(1, content.toString(), Types.OTHER);
            // JDBC 读出的 JSONB 文本必须还原成树，而不是 JSON 字符串节点。
            ResultSet result = mock(ResultSet.class);
            when(result.getString("content")).thenReturn(content.toString());
            when(result.getString(1)).thenReturn(content.toString());
            assertThat(handler.getResult(result, "content")).isEqualTo(content);
            assertThat(handler.getResult(result, 1)).isEqualTo(content);
            CallableStatement callable = mock(CallableStatement.class);
            when(callable.getString(1)).thenReturn(content.toString());
            assertThat(handler.getResult(callable, 1)).isEqualTo(content);
        }
    }

    /** SQL NULL 与 JSON null 的写入和读取语义必须区分，不将缺失内容静默转换为空对象。 */
    @Test
    void preservesSqlNullAndJsonNullSeparately() throws Exception {
        var handler = new JsonbTypeHandler();
        PreparedStatement statement = mock(PreparedStatement.class);
        handler.setParameter(statement, 1, null, JdbcType.OTHER);
        verify(statement).setNull(1, Types.OTHER);
        handler.setParameter(statement, 2, new ObjectMapper().nullNode(), JdbcType.OTHER);
        verify(statement).setObject(2, "null", Types.OTHER);
        ResultSet result = mock(ResultSet.class);
        assertThat(handler.getResult(result, "content")).isNull();
        when(result.getString("content")).thenReturn("null");
        assertThat(handler.getResult(result, "content").isNull()).isTrue();
    }

    /** 损坏内容和多个根值必须失败，公开异常不得携带消息正文。 */
    @Test
    void rejectsMalformedJsonWithoutExposingContent() throws Exception {
        var handler = new JsonbTypeHandler();
        ResultSet result = mock(ResultSet.class);
        for (String value : new String[]{"", " ", "{private-content", "{} {}"}) {
            when(result.getString("content")).thenReturn(value);
            assertThatThrownBy(() -> handler.getNullableResult(result, "content"))
                    .isInstanceOf(SQLException.class).hasMessageStartingWith("JSONB 内容").hasNoCause();
        }
    }
}
