package cn.chomy.ai.backend.mapper;

import cn.chomy.ai.backend.entity.AgentChatMessage;
import cn.chomy.ai.backend.entity.AgentChatTask;
import cn.chomy.ai.backend.enums.AgentChatMessageRole;
import cn.chomy.ai.backend.enums.AgentChatMessageStatus;
import cn.chomy.ai.backend.enums.AgentChatTaskStatus;
import cn.chomy.ai.backend.handler.JsonbTypeHandler;
import cn.chomy.ai.backend.service.AgentChatMessageService;
import cn.chomy.ai.backend.service.AgentChatTaskService;
import cn.chomy.ai.backend.service.impl.AgentChatMessageServiceImpl;
import cn.chomy.ai.backend.service.impl.AgentChatTaskServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/**
 * 消息与任务表的持久化装配回归，检查真实 MyBatis-Plus CRUD 注入及 JSONB 字段结果映射。
 * 不加载 Nacos，不执行 SQL，不连接真实数据库；不能代替 PostgreSQL 迁移及执行引擎集成验证。
 * @author chomy
 */
class AgentChatPersistenceTest {
    /** Spring 应能装配两组基础服务，且查询及插入语句指向 V8 的对应表。 */
    @Test
    void registersCrudServicesAndJsonbMappings() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(PersistenceConfig.class)) {
            assertThat(context.getBean(AgentChatMessageService.class)).isInstanceOf(AgentChatMessageServiceImpl.class);
            assertThat(context.getBean(AgentChatTaskService.class)).isInstanceOf(AgentChatTaskServiceImpl.class);
            var configuration = context.getBean(SqlSessionFactory.class).getConfiguration();
            for (var entry : Map.of(AgentChatMessageMapper.class, "public.sys_agent_chat_message",
                    AgentChatTaskMapper.class, "public.sys_agent_chat_task").entrySet()) {
                String namespace = entry.getKey().getName();
                for (String method : new String[]{"insert", "updateById", "deleteById", "selectById"}) {
                    assertThat(configuration.hasStatement(namespace + "." + method)).isTrue();
                }
                assertThat(configuration.getMappedStatement(namespace + ".selectById").getBoundSql(1L).getSql())
                        .contains("FROM " + entry.getValue());
            }
            AgentChatMessage message = new AgentChatMessage();
            message.setId(9007199254740993L); message.setChatId(10L);
            message.setRole(AgentChatMessageRole.USER); message.setStatus(AgentChatMessageStatus.COMPLETED);
            message.setContent(new ObjectMapper().readTree("{\"text\":\"你好\"}"));
            var insert = configuration.getMappedStatement(AgentChatMessageMapper.class.getName() + ".insert")
                    .getBoundSql(message);
            assertThat(insert.getSql()).contains("content").doesNotContain("created_at");
            // 验证注入 SQL 的真实参数映射，避免只有注解存在但绑定仍误用 VARCHAR。
            var contentMapping = insert.getParameterMappings().stream()
                    .filter(mapping -> mapping.getProperty().equals("content")).findFirst().orElseThrow();
            assertThat(contentMapping.getTypeHandler()).isInstanceOf(JsonbTypeHandler.class);
            var resultMap = configuration.getMappedStatement(AgentChatMessageMapper.class.getName() + ".selectById")
                    .getResultMaps().getFirst();
            assertThat(resultMap.getResultMappings().stream().filter(mapping -> mapping.getProperty().equals("content"))
                    .findFirst().orElseThrow().getTypeHandler()).isInstanceOf(JsonbTypeHandler.class);
            AgentChatTask task = new AgentChatTask();
            task.setId(20L); task.setChatId(10L); task.setMessageId(message.getId()); task.setStatus(AgentChatTaskStatus.PENDING);
            task.setErrorMessage("脱敏错误"); task.setCheckpointId("checkpoint-1");
            assertThat(configuration.getMappedStatement(AgentChatTaskMapper.class.getName() + ".insert").getBoundSql(task).getSql())
                    .contains("message_id", "error_message", "checkpoint_id").doesNotContain("created_at");
            // 雪花 ID 在 JSON 中必须保持字符串，空状态不得由 Java 默认值变成正常状态。
            String json = new ObjectMapper().writeValueAsString(message);
            assertThat(json).contains("\"id\":\"9007199254740993\"", "\"role\":\"user\"", "\"content\":{\"text\":\"你好\"}");
            assertThat(new AgentChatMessage().getStatus()).isNull();
            assertThat(new AgentChatTask().getStatus()).isNull();
        }
    }

    /**
     * 仅装配两组表级服务，避免启动完整应用及访问配置中心。
     * @author chomy
     */
    @Configuration(proxyBeanMethods = false)
    @MapperScan("cn.chomy.ai.backend.mapper")
    @Import({AgentChatMessageServiceImpl.class, AgentChatTaskServiceImpl.class})
    static class PersistenceConfig {
        /**
         * 创建只用于 SQL 注入的会话工厂；占位地址不会被访问，不含真实凭据。
         * @return 已注册 Mapper 的会话工厂
         * @throws Exception 映射或装配失败时抛出
         */
        @Bean
        SqlSessionFactory sqlSessionFactory() throws Exception {
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setConfiguration(new MybatisConfiguration());
            factory.setDataSource(new UnpooledDataSource("org.postgresql.Driver", "jdbc:postgresql://127.0.0.1:1/not-used",
                    "test-only", "test-only"));
            return factory.getObject();
        }
    }
}
