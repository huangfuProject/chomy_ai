package cn.chomy.ai.backend.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * PostgreSQL JSONB 与 Jackson JsonNode 的字段级映射，支持对象、数组及 JSON 标量。
 * 通过 JDBC OTHER 让 PostgreSQL 按目标 JSONB 列解析参数，避免普通 VARCHAR 与 JSONB 类型不兼容。
 * 仅在实体字段显式使用，不全局替换其他 JsonNode 或字符串的 JDBC 映射。
 * @author chomy
 */
public class JsonbTypeHandler extends BaseTypeHandler<JsonNode> {
    /** 初始化后不再修改，可安全并发复用；拒绝多个根值，避免静默丢失 JSON 尾部内容。 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * 将非 SQL 空值的 JSON 树绑定到 JSONB 列，JSON null 节点作为 JSON 字面值保存。
     * @param statement JDBC 预编译语句
     * @param index 从 1 开始的参数位置
     * @param parameter 非空 JSON 树
     * @param jdbcType 字段声明的 JDBC 类型，实际按 PostgreSQL OTHER 绑定
     * @throws SQLException 序列化或 JDBC 绑定失败时抛出，不在异常中记录消息正文
     */
    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, JsonNode parameter, JdbcType jdbcType)
            throws SQLException {
        try {
            // JDBC OTHER 避免字符串按 VARCHAR 绑定，驱动由目标列类型推断 JSONB。
            statement.setObject(index, MAPPER.writeValueAsString(parameter), Types.OTHER);
        } catch (JsonProcessingException exception) {
            throw new SQLException("JSONB 内容序列化失败");
        }
    }

    /**
     * 按列名读取 JSONB，SQL NULL 与 JSON null 分别保留为 Java null 和 NullNode。
     * @param result 查询结果集
     * @param columnName JSONB 列名
     * @return JSON 树或 Java null
     * @throws SQLException 读取或 JSON 解析失败时抛出
     */
    @Override
    public JsonNode getNullableResult(ResultSet result, String columnName) throws SQLException {
        return parse(result.getString(columnName));
    }

    /**
     * 按列序号读取 JSONB。
     * @param result 查询结果集
     * @param columnIndex 从 1 开始的列序号
     * @return JSON 树或 Java null
     * @throws SQLException 读取或 JSON 解析失败时抛出
     */
    @Override
    public JsonNode getNullableResult(ResultSet result, int columnIndex) throws SQLException {
        return parse(result.getString(columnIndex));
    }

    /**
     * 支持 JDBC 可调用语句返回值的标准映射；本项目不会因此创建数据库存储过程。
     * @param statement JDBC 可调用语句
     * @param columnIndex 从 1 开始的结果参数位置
     * @return JSON 树或 Java null
     * @throws SQLException 读取或 JSON 解析失败时抛出
     */
    @Override
    public JsonNode getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return parse(statement.getString(columnIndex));
    }

    /**
     * 将数据库 JSON 文本解析为树结构，遇到损坏内容及时失败，不静默转为空对象。
     * @param value 数据库返回的文本，SQL NULL 对应 Java null
     * @return JSON 树或 Java null
     * @throws SQLException 空文本、非法 JSON 或多个根值时抛出
     */
    private JsonNode parse(String value) throws SQLException {
        // SQL NULL 与 JSON null 有不同语义，不能把空 SQL 值包装成字符串或空对象。
        if (value == null) { return null; }
        try {
            JsonNode node = MAPPER.readTree(value);
            if (node == null || node.isMissingNode()) { throw new SQLException("JSONB 内容为空"); }
            return node;
        } catch (JsonProcessingException exception) {
            // Jackson 异常可能包含原始内容，避免消息正文通过异常堆栈进入日志。
            throw new SQLException("JSONB 内容解析失败");
        }
    }
}
